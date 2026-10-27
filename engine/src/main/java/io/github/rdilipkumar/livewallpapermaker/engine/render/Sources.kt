package io.github.rdilipkumar.livewallpapermaker.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Surface
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Gl
import io.github.rdilipkumar.livewallpapermaker.engine.model.StickerInfo
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

  @Volatile private var pendingLatch = false
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

  /**
   * Offline use (export): decodes until the picture for [localUs] is in the texture. The frame-available
   * callback must arrive on another thread. Returns false if the decoder fails or takes too long.
   */
  fun advanceTo(localUs: Long, timeoutMs: Long = 3000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (failed || codec == null) return false
      val released = tick(localUs)
      if (released) {
        val wait = System.currentTimeMillis() + 300
        while (!pendingLatch && System.currentTimeMillis() < wait) Thread.sleep(1)
        latch()
      }
      // Caught up once the next decoded frame belongs to the future.
      if (heldIndex >= 0 && heldPts > localUs - skew) return true
      if (!released) Thread.sleep(1)
    }
    return frameCounter > 0
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

/** An animated transparent sticker: one sheet texture, the visible frame chosen by time. */
internal class StickerSource(file: File, private val info: StickerInfo) : FrameSource {
  override val oes = false
  override val texMatrix: FloatArray = FloatArray(16)
  override var texture = 0; private set
  override val width = info.frameW
  override val height = info.frameH
  override var frameCounter = 0L; private set
  override var ready = false; private set
  private var sheetW = 1
  private var sheetH = 1
  private var frame = -1

  init {
    try {
      val bmp = BitmapFactory.decodeFile(file.path) ?: throw IllegalStateException("bad sheet")
      sheetW = bmp.width
      sheetH = bmp.height
      texture = Gl.createTexture()
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
      GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
      bmp.recycle()
      ready = true
      setTime(0)
    } catch (t: Throwable) {
      Log.w("LwmSticker", "Could not load ${file.name}", t)
    }
  }

  /** Picks the frame showing at [ms]; returns true if it changed. */
  fun setTime(ms: Long): Boolean {
    val delays = info.delays.ifEmpty { listOf(100) }
    var t = Math.floorMod(ms, info.totalMs.toLong()).toInt()
    var index = 0
    while (index < delays.size - 1 && t >= delays[index]) {
      t -= delays[index]
      index++
    }
    if (index == frame) return false
    frame = index
    val col = index % info.cols
    val row = index / info.cols
    val x0 = info.pad + col * (info.frameW + info.pad)
    val y0 = info.pad + row * (info.frameH + info.pad)
    Matrix.setIdentityM(texMatrix, 0)
    texMatrix[0] = info.frameW.toFloat() / sheetW
    texMatrix[5] = -info.frameH.toFloat() / sheetH
    texMatrix[12] = x0.toFloat() / sheetW
    texMatrix[13] = (y0 + info.frameH).toFloat() / sheetH
    frameCounter++
    return true
  }

  override fun release() {
    Gl.deleteTexture(texture)
    texture = 0
  }
}

/** Text drawn once into a texture; re-drawn when a {time}/{date} token changes. */
internal class TextSource(
  private val context: Context,
  private var spec: TextSpec,
  private var screenH: Int,
  private val fontFile: (String) -> File?,
) : FrameSource {
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
    val bmp = TextRenderer.render(spec, screenH, text, spec.fontAsset?.let(fontFile))
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
  val TOKENS = listOf("{time}", "{date}", "{day}", "{month}", "{year}", "{hour}", "{minute}", "{battery}", "{quote}", "{countdown:")

  private val COUNTDOWN = Regex("\\{countdown:(\\d{4})-(\\d{2})-(\\d{2})\\}")

  /** Short, timeless sayings; one per day. */
  val QUOTES = listOf(
    "Well begun is half done.",
    "Every day is a fresh start.",
    "Small steps, every day.",
    "Stay curious.",
    "Breathe. You're doing fine.",
    "Make today count.",
    "Be kind to yourself.",
    "Slow progress is still progress.",
    "Fortune favours the bold.",
    "Dream big, start small.",
    "Do one thing at a time.",
    "Rest is part of the work.",
    "Keep going.",
    "Begin anywhere.",
    "Less, but better.",
    "Little by little, a little becomes a lot.",
    "The best time to start is now.",
    "Be here now.",
    "Done is better than perfect.",
    "Look up at the stars.",
    "Choose joy.",
    "Light follows the dark.",
    "Make it simple.",
    "Trust the process.",
    "You've got this.",
    "Water wears down stone.",
    "Leave it better than you found it.",
    "Enjoy the little things.",
    "One day or day one.",
    "Grow through what you go through.",
  )

  /** The quote for the given day (same all day, different tomorrow). */
  fun quoteFor(nowMs: Long): String {
    val day = Math.floorDiv(nowMs + java.util.TimeZone.getDefault().getOffset(nowMs), 86_400_000L)
    return QUOTES[Math.floorMod(day, QUOTES.size.toLong()).toInt()]
  }

  /** "12 days", "1 day", "Today", or "3 days ago" until the date. */
  fun countdown(nowMs: Long, year: Int, month: Int, day: Int): String {
    val today = java.util.Calendar.getInstance().apply {
      timeInMillis = nowMs
      set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val target = java.util.Calendar.getInstance().apply {
      clear()
      set(year, month - 1, day, 12, 0, 0)
    }
    val days = Math.round((target.timeInMillis - today.timeInMillis) / 86_400_000.0).toInt()
    return when {
      days == 0 -> "Today"
      days == 1 -> "1 day"
      days > 1 -> "$days days"
      days == -1 -> "1 day ago"
      else -> "${-days} days ago"
    }
  }

  fun typeface(font: TextFont, bold: Boolean, fontFile: File? = null): Typeface {
    if (fontFile != null && fontFile.exists()) {
      runCatching { Typeface.createFromFile(fontFile) }.getOrNull()?.let { tf ->
        return if (bold) Typeface.create(tf, Typeface.BOLD) else tf
      }
    }
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
    var out = template
      .replace("{time}", f(if (is24) "HH:mm" else "h:mm"))
      .replace("{date}", android.text.format.DateFormat.getMediumDateFormat(context).format(date))
      .replace("{day}", f("EEEE"))
      .replace("{month}", f("MMMM"))
      .replace("{year}", f("yyyy"))
      .replace("{hour}", f(if (is24) "HH" else "h"))
      .replace("{minute}", f("mm"))
      .replace("{quote}", quoteFor(nowMs))
    if (out.contains("{battery}")) {
      val level = runCatching {
        context.getSystemService(android.os.BatteryManager::class.java)
          ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
      }.getOrNull()
      out = out.replace("{battery}", if (level != null && level in 0..100) "$level%" else "")
    }
    out = COUNTDOWN.replace(out) { m ->
      val (y, mo, d) = m.destructured
      runCatching { countdown(nowMs, y.toInt(), mo.toInt(), d.toInt()) }.getOrDefault(m.value)
    }
    return out
  }

  private fun layout(spec: TextSpec, screenH: Int, text: String, fontFile: File?): Triple<StaticLayout, Int, TextPaint> {
    val px = max(8f, spec.size * screenH)
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
      color = spec.color
      textSize = px
      typeface = typeface(spec.font, spec.bold, fontFile)
      if (spec.shadow) setShadowLayer(px * 0.08f, 0f, px * 0.03f, 0x99000000.toInt())
    }
    val shown = text.ifEmpty { " " }
    val outlinePx = spec.outline.coerceIn(0f, 0.4f) * px
    val maxW = shown.split('\n').maxOf { ceil(paint.measureText(it)).toInt() } + 2 + ceil(outlinePx).toInt()
    val layout = StaticLayout.Builder.obtain(shown, 0, shown.length, paint, max(1, maxW))
      .setAlignment(Layout.Alignment.ALIGN_CENTER)
      .setIncludePad(true)
      .build()
    return Triple(layout, (px * 0.2f + outlinePx).roundToInt(), paint)
  }

  /** Size in pixels of the rendered text bitmap. */
  fun measure(context: Context, spec: TextSpec, screenH: Int, nowMs: Long = System.currentTimeMillis(), fontFile: File? = null): Pair<Int, Int> {
    val (layout, pad) = layout(spec, screenH, format(context, spec.text, nowMs), fontFile)
    return min(4096, layout.width + pad * 2) to min(4096, layout.height + pad * 2)
  }

  fun render(spec: TextSpec, screenH: Int, text: String, fontFile: File? = null): Bitmap {
    val (layout, pad, paint) = layout(spec, screenH, text, fontFile)
    val w = min(4096, layout.width + pad * 2)
    val h = min(4096, layout.height + pad * 2)
    val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.translate(pad.toFloat(), pad.toFloat())
    if (spec.outline > 0f) {
      // Outline first (the layout keeps a reference to the paint, so switch its style around the draws).
      val fillColor = paint.color
      paint.style = android.graphics.Paint.Style.STROKE
      paint.strokeJoin = android.graphics.Paint.Join.ROUND
      paint.strokeWidth = spec.outline.coerceIn(0f, 0.4f) * paint.textSize
      paint.color = spec.outlineColor
      layout.draw(canvas)
      paint.style = android.graphics.Paint.Style.FILL
      paint.color = fillColor
      paint.clearShadowLayer()
    }
    layout.draw(canvas)
    return bmp
  }
}
