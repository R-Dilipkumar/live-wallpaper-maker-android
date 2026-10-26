package io.github.rdilipkumar.livewallpapermaker.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.Matrix
import android.os.Handler
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Surface
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Gl
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextFont
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextSpec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Something that ends up as a GL texture: an image, a playing video or rendered text. */
internal interface FrameSource {
  val texture: Int
  val oes: Boolean
  val texMatrix: FloatArray
  val width: Int
  val height: Int
  /** Goes up every time the picture changes (used to refresh cached blurs). */
  val frameCounter: Long
  val ready: Boolean
  fun release()
}

internal class ImageSource(file: File, maxDim: Int) : FrameSource {
  override val oes = false
  override val texMatrix: FloatArray = Gl.FLIP_V
  override var texture = 0; private set
  override var width = 1; private set
  override var height = 1; private set
  override val frameCounter = 1L
  override var ready = false; private set

  init {
    try {
      val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val s = min(1f, maxDim.toFloat() / max(w, h))
        if (s < 1f) decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
      width = bmp.width
      height = bmp.height
      texture = Gl.uploadBitmap(bmp)
      bmp.recycle()
      ready = true
    } catch (t: Throwable) {
      Log.w("LwmImage", "Could not load ${file.name}", t)
    }
  }

  override fun release() {
    Gl.deleteTexture(texture)
    texture = 0
  }
}

/**
 * Plays an MP4 forever with the hardware decoder into an OES texture.
 * Looping is gapless: at the end of the file the extractor jumps back to the start and timestamps keep
 * counting up, so the decoder never has to be flushed.
 * All methods run on the render thread.
 */
internal class VideoSource(
  private val file: File,
  glHandler: Handler,
  private val onFrameAvailable: () -> Unit,
) : FrameSource {
  override val oes = true
  override val texture: Int = Gl.createOesTexture()
  override val texMatrix: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
  override var width = 1; private set
  override var height = 1; private set
  override var frameCounter = 0L; private set
  override val ready: Boolean get() = frameCounter > 0 || failed

  var durationUs = 0L; private set
  var failed = false; private set
  val running: Boolean get() = codec != null

  private var pendingLatch = false
  private val surfaceTexture = SurfaceTexture(texture).apply {
    setOnFrameAvailableListener({
      pendingLatch = true
      onFrameAvailable()
    }, glHandler)
  }
  private val surface = Surface(surfaceTexture)

  private var extractor: MediaExtractor? = null
  private var codec: MediaCodec? = null
  private val info = MediaCodec.BufferInfo()
  private var firstPts = 0L
  private var maxPts = 0L
  private var lastPts = -1L
  private var loopOffset = 0L
  private var frameDur = 33_333L
  private var heldIndex = -1
  private var heldPts = 0L
  private var skew = 0L

  init {
    runCatching {
      val ex = MediaExtractor()
      ex.setDataSource(file.path)
      val track = findVideoTrack(ex)
      if (track >= 0) {
        val f = ex.getTrackFormat(track)
        val w = f.getInteger(MediaFormat.KEY_WIDTH)
        val h = f.getInteger(MediaFormat.KEY_HEIGHT)
        val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
        if (rot == 90 || rot == 270) { width = h; height = w } else { width = w; height = h }
        if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = f.getLong(MediaFormat.KEY_DURATION)
      } else failed = true
      ex.release()
    }.onFailure { failed = true }
  }

  private fun findVideoTrack(ex: MediaExtractor): Int {
    for (i in 0 until ex.trackCount) {
      if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return i
    }
    return -1
  }

  /** Starts (or restarts) decoding from the beginning. */
  fun start() {
    if (codec != null || failed) return
    try {
      val ex = MediaExtractor()
      ex.setDataSource(file.path)
      val track = findVideoTrack(ex)
      ex.selectTrack(track)
      val format = ex.getTrackFormat(track)
      if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
        val fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
          ?: runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt() }.getOrDefault(30)
        if (fps in 1..240) frameDur = 1_000_000L / fps
      }
      val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
      c.configure(format, surface, null, 0)
      c.start()
      extractor = ex
      codec = c
      firstPts = max(0L, ex.sampleTime)
      maxPts = firstPts
      lastPts = -1L
      loopOffset = 0L
      heldIndex = -1
      skew = 0L
    } catch (t: Throwable) {
      Log.w("LwmVideo", "Decoder start failed for ${file.name}", t)
      failed = true
      stop()
    }
  }

  /**
   * Feeds the decoder and shows the frame due at [localUs] (time since this video started).
   * Returns true when a new frame was sent to the texture.
   */
  fun tick(localUs: Long): Boolean {
    val c = codec ?: return false
    try {
      feed(c)
      var shown = false
      var now = localUs - skew
      while (true) {
        if (heldIndex < 0) {
          val idx = c.dequeueOutputBuffer(info, 0)
          if (idx < 0) break
          heldIndex = idx
          heldPts = info.presentationTimeUs
        }
        if (heldPts > now) break
        val lateBy = now - heldPts
        if (lateBy > 600_000L) { // fell far behind (e.g. after a pause): jump the clock instead of racing
          skew += lateBy
          now -= lateBy
        }
        val render = lateBy <= 2 * frameDur || lateBy > 600_000L
        c.releaseOutputBuffer(heldIndex, render)
        if (render) shown = true
        heldIndex = -1
      }
      return shown
    } catch (t: Throwable) {
      Log.w("LwmVideo", "Decoder error for ${file.name}", t)
      failed = true
      stop()
      return false
    }
  }

  private fun feed(c: MediaCodec) {
    val ex = extractor ?: return
    repeat(4) {
      val inIdx = c.dequeueInputBuffer(0)
      if (inIdx < 0) return
      val buf = c.getInputBuffer(inIdx) ?: return
      var size = ex.readSampleData(buf, 0)
      if (size < 0) {
        loopOffset += (maxPts - firstPts) + frameDur
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        size = ex.readSampleData(buf, 0)
        if (size < 0) {
          c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          return
        }
      }
      val pts = ex.sampleTime
      if (pts > maxPts) maxPts = pts
      if (lastPts >= 0 && pts > lastPts && loopOffset == 0L) frameDur = min(frameDur, max(4_000L, pts - lastPts))
      lastPts = pts
      c.queueInputBuffer(inIdx, 0, size, pts - firstPts + loopOffset, 0)
      ex.advance()
    }
  }

  /** Moves the newest decoded picture into the texture. */
  fun latch(): Boolean {
    if (!pendingLatch) return false
    pendingLatch = false
    return try {
      surfaceTexture.updateTexImage()
      surfaceTexture.getTransformMatrix(texMatrix)
      frameCounter++
      true
    } catch (t: Throwable) {
      false
    }
  }

  /** Frees the decoder but keeps the last picture in the texture. */
  fun stop() {
    runCatching { codec?.stop() }
    runCatching { codec?.release() }
    runCatching { extractor?.release() }
    codec = null
    extractor = null
    heldIndex = -1
  }

  override fun release() {
    stop()
    surface.release()
    surfaceTexture.release()
    Gl.deleteTexture(texture)
  }
}

/** Text drawn once into a texture; re-drawn when a {time}/{date} token changes. */
internal class TextSource(private val context: Context, private var spec: TextSpec, private var screenH: Int) :
  FrameSource {
  override val oes = false
  override val texMatrix: FloatArray = Gl.FLIP_V
  override var texture = 0; private set
  override var width = 1; private set
  override var height = 1; private set
  override var frameCounter = 0L; private set
  override val ready get() = texture != 0
  private var rendered: String? = null

  val hasClockTokens: Boolean get() = TOKENS.any { spec.text.contains(it) }

  fun update(newSpec: TextSpec, newScreenH: Int) {
    if (newSpec != spec || newScreenH != screenH) {
      spec = newSpec
      screenH = newScreenH
      rendered = null
    }
  }

  /** Re-renders if the visible text changed. Returns true when the texture changed. */
  fun refresh(nowMs: Long): Boolean {
    val text = TextRenderer.format(context, spec.text, nowMs)
    if (text == rendered) return false
    rendered = text
    val bmp = TextRenderer.render(spec, screenH, text)
    texture = Gl.uploadBitmap(bmp, texture)
    width = bmp.width
    height = bmp.height
    bmp.recycle()
    frameCounter++
    return true
  }

  override fun release() {
    Gl.deleteTexture(texture)
    texture = 0
  }

  companion object {
    val TOKENS = TextRenderer.TOKENS
  }
}

/** Draws text layers. Shared by the wallpaper and the editor (which needs the same size for its selection box). */
object TextRenderer {
  val TOKENS = listOf("{time}", "{date}", "{day}", "{month}", "{year}", "{hour}", "{minute}")

  fun typeface(font: TextFont, bold: Boolean): Typeface {
    val style = if (bold) Typeface.BOLD else Typeface.NORMAL
    return when (font) {
      TextFont.SANS -> Typeface.create(Typeface.SANS_SERIF, style)
      TextFont.SERIF -> Typeface.create(Typeface.SERIF, style)
      TextFont.MONO -> Typeface.create(Typeface.MONOSPACE, style)
      TextFont.CONDENSED -> Typeface.create("sans-serif-condensed", style)
      TextFont.LIGHT -> Typeface.create(if (bold) "sans-serif-medium" else "sans-serif-light", Typeface.NORMAL)
    }
  }

  fun format(context: Context, template: String, nowMs: Long): String {
    if (!template.contains('{')) return template
    val date = Date(nowMs)
    val locale = Locale.getDefault()
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    fun f(pattern: String) = SimpleDateFormat(pattern, locale).format(date)
    return template
      .replace("{time}", f(if (is24) "HH:mm" else "h:mm"))
      .replace("{date}", android.text.format.DateFormat.getMediumDateFormat(context).format(date))
      .replace("{day}", f("EEEE"))
      .replace("{month}", f("MMMM"))
      .replace("{year}", f("yyyy"))
      .replace("{hour}", f(if (is24) "HH" else "h"))
      .replace("{minute}", f("mm"))
  }

  private fun layout(spec: TextSpec, screenH: Int, text: String): Pair<StaticLayout, Int> {
    val px = max(8f, spec.size * screenH)
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
      color = spec.color
      textSize = px
      typeface = typeface(spec.font, spec.bold)
      if (spec.shadow) setShadowLayer(px * 0.08f, 0f, px * 0.03f, 0x99000000.toInt())
    }
    val shown = text.ifEmpty { " " }
    val maxW = shown.split('\n').maxOf { ceil(paint.measureText(it)).toInt() } + 2
    val layout = StaticLayout.Builder.obtain(shown, 0, shown.length, paint, max(1, maxW))
      .setAlignment(Layout.Alignment.ALIGN_CENTER)
      .setIncludePad(true)
      .build()
    return layout to (px * 0.2f).roundToInt()
  }

  /** Size in pixels of the rendered text bitmap. */
  fun measure(context: Context, spec: TextSpec, screenH: Int, nowMs: Long = System.currentTimeMillis()): Pair<Int, Int> {
    val (layout, pad) = layout(spec, screenH, format(context, spec.text, nowMs))
    return min(4096, layout.width + pad * 2) to min(4096, layout.height + pad * 2)
  }

  fun render(spec: TextSpec, screenH: Int, text: String): Bitmap {
    val (layout, pad) = layout(spec, screenH, text)
    val w = min(4096, layout.width + pad * 2)
    val h = min(4096, layout.height + pad * 2)
    val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.translate(pad.toFloat(), pad.toFloat())
    layout.draw(canvas)
    return bmp
  }
}
