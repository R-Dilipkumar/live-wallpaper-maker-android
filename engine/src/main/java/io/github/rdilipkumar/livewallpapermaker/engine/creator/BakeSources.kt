package io.github.rdilipkumar.livewallpapermaker.engine.creator

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.view.Surface
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Gl
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Frames read in order while baking. Times are microseconds from the trim start. */
internal interface BakeSource {
  val width: Int
  val height: Int
  val durationUs: Long
  /** Native frame rate, if known. */
  val fps: Float
  val texture: Int
  val oes: Boolean
  val texMatrix: FloatArray
  /** Moves to the next frame and returns its time, or -1 at the end. */
  fun next(): Long
  fun release()
}

/** Decodes a video file (trimmed) frame by frame into an OES texture. Render-thread only. */
internal class VideoBakeSource(
  file: File,
  private val trimStartUs: Long,
  trimEndUs: Long,
  callbackHandler: Handler,
) : BakeSource {
  override var width = 0; private set
  override var height = 0; private set
  override val durationUs: Long
  override var fps = 30f; private set
  override val texture = Gl.createOesTexture()
  override val oes = true
  override val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

  private val lock = Object()
  private var frameReady = false
  private val surfaceTexture = SurfaceTexture(texture)
  private val surface: Surface
  private val extractor = MediaExtractor()
  private var codec: MediaCodec
  private val info = MediaCodec.BufferInfo()
  private val endUs: Long
  private var inputDone = false
  private var outputDone = false
  private val format: MediaFormat
  private val candidates: ArrayDeque<String>
  private var framesOut = 0

  init {
    surfaceTexture.setOnFrameAvailableListener({
      synchronized(lock) {
        frameReady = true
        lock.notifyAll()
      }
    }, callbackHandler)
    surface = Surface(surfaceTexture)
    extractor.setDataSource(file.path)
    var track = -1
    for (i in 0 until extractor.trackCount) {
      if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
        track = i
        break
      }
    }
    if (track < 0) {
      release()
      throw IOException("No video track")
    }
    extractor.selectTrack(track)
    val format = extractor.getTrackFormat(track)
    val w = format.getInteger(MediaFormat.KEY_WIDTH)
    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
    val rot = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
    if (rot == 90 || rot == 270) { width = h; height = w } else { width = w; height = h }
    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
      fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
        .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30f) }
        .let { if (it in 1f..240f) it else 30f }
    }
    val fileDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE
    endUs = min(trimEndUs, fileDuration)
    durationUs = max(1L, endUs - trimStartUs)
    // HDR clips (most new phones record HDR): ask the decoder for normal SDR colours.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    }
    this.format = format
    candidates = decoderCandidates(format)
    codec = startNextDecoder()
    extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
  }

  /** The decoder Android suggests for this exact format first, then every other one for the type. */
  private fun decoderCandidates(format: MediaFormat): ArrayDeque<String> {
    val mime = format.getString(MediaFormat.KEY_MIME)!!
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val names = LinkedHashSet<String>()
    val probe = MediaFormat(format).apply { removeKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) }
    runCatching { list.findDecoderForFormat(probe) }.getOrNull()?.let { names += it }
    list.codecInfos
      .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
      .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
      .forEach { names += it.name }
    return ArrayDeque(names)
  }

  /** Starts the next candidate decoder. Throws a readable error when none is left. */
  private fun startNextDecoder(): MediaCodec {
    val mime = format.getString(MediaFormat.KEY_MIME) ?: "?"
    var lastError: Throwable? = null
    while (candidates.isNotEmpty()) {
      val name = candidates.removeFirst()
      for (attempt in 0..1) {
        // Some decoders reject the HDR tone-mapping request; the second try goes without it.
        val f = MediaFormat(format)
        if (attempt == 1) f.removeKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
        val c = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: break
        try {
          c.configure(f, surface, null, 0)
          c.start()
          return c
        } catch (e: Exception) {
          runCatching { c.release() }
          lastError = e
        }
      }
    }
    releaseResources()
    throw IOException("This phone can't decode this video format ($mime)", lastError)
  }

  /** A decoder that dies before giving any picture is swapped for the next one (e.g. 10-bit HDR on some phones). */
  private fun failOver(e: Exception): Boolean {
    if (framesOut > 0 || candidates.isEmpty()) return false
    runCatching { codec.release() }
    codec = try {
      startNextDecoder()
    } catch (io: IOException) {
      throw IOException(io.message, e)
    }
    extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    inputDone = false
    outputDone = false
    return true
  }

  override fun next(): Long {
    while (true) {
      try {
        val t = nextFrame()
        if (t >= 0) framesOut++
        return t
      } catch (e: IllegalStateException) {
        if (!failOver(e)) throw IOException("The video decoder stopped working on this file", e)
      }
    }
  }

  private fun nextFrame(): Long {
    if (outputDone) return -1
    var idle = 0
    while (true) {
      if (!inputDone) {
        val inIdx = codec.dequeueInputBuffer(5_000)
        if (inIdx >= 0) {
          val buf = codec.getInputBuffer(inIdx)!!
          val size = extractor.readSampleData(buf, 0)
          val t = extractor.sampleTime
          if (size < 0 || t > endUs + 1_000_000L) {
            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
          } else {
            codec.queueInputBuffer(inIdx, 0, size, t, 0)
            extractor.advance()
          }
        }
      }
      val outIdx = codec.dequeueOutputBuffer(info, 5_000)
      if (outIdx >= 0) {
        idle = 0
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
          codec.releaseOutputBuffer(outIdx, false)
          outputDone = true
          return -1
        }
        val pts = info.presentationTimeUs
        if (pts < trimStartUs) {
          codec.releaseOutputBuffer(outIdx, false)
          continue
        }
        if (pts > endUs) {
          codec.releaseOutputBuffer(outIdx, false)
          outputDone = true
          return -1
        }
        synchronized(lock) { frameReady = false }
        codec.releaseOutputBuffer(outIdx, true)
        synchronized(lock) {
          val deadline = System.currentTimeMillis() + 2500
          while (!frameReady) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw IOException("Timed out waiting for a decoded frame")
            lock.wait(left)
          }
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        return pts - trimStartUs
      } else if (++idle > 2000) {
        throw IOException("Decoder stopped producing frames")
      }
    }
  }

  override fun release() {
    runCatching { codec.stop() }
    runCatching { codec.release() }
    releaseResources()
  }

  private fun releaseResources() {
    runCatching { extractor.release() }
    runCatching { surface.release() }
    runCatching { surfaceTexture.release() }
    Gl.deleteTexture(texture)
  }
}

/**
 * Plays a GIF on a fixed 30 fps grid. Transparent pixels become black.
 * With [smooth], in-between grid frames blend the current GIF frame into the next one.
 */
internal class GifBakeSource(
  bytes: ByteArray,
  private val trimStartMs: Long,
  trimEndMs: Long,
  private val smooth: Boolean,
) : BakeSource {
  private val decoder = GifStreamDecoder(bytes)
  private val delays = decoder.scanDelays().ifEmpty { listOf(100) }
  private val totalMs = delays.sum().toLong()
  private val endMs = min(if (trimEndMs <= 0) totalMs else trimEndMs, totalMs)
  override val width = decoder.width
  override val height = decoder.height
  override val durationUs = max(1L, (endMs - trimStartMs) * 1000)
  override val fps = 30f
  override val texture: Int = Gl.createTexture()
  override val oes = false
  override val texMatrix: FloatArray = Gl.FLIP_V

  private var cur = IntArray(width * height)
  private var nxt = IntArray(width * height)
  private val mix = IntArray(width * height)
  private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  private var curIndex = 0
  private var curStart = 0L
  private var gridIndex = 0
  private var uploaded = false

  init {
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    decodeInto(cur)
    decodeInto(nxt)
  }

  val frameCount: Int get() = delays.size

  private fun decodeInto(target: IntArray) {
    if (decoder.nextFrame(target) < 0) {
      // Past the last frame: wrap to the first one (the GIF loops).
      decoder.rewind()
      if (decoder.nextFrame(target) < 0) target.fill(0)
    }
  }

  override fun next(): Long {
    val tMs = trimStartMs + gridIndex * 1000L / 30
    if (tMs >= endMs) return -1
    var advanced = false
    while (tMs >= curStart + delays[curIndex % delays.size]) {
      curStart += delays[curIndex % delays.size]
      curIndex++
      val tmp = cur
      cur = nxt
      nxt = tmp
      decodeInto(nxt)
      advanced = true
    }
    val delay = delays[curIndex % delays.size]
    val frac = ((tMs - curStart).toFloat() / delay).coerceIn(0f, 1f)
    if (smooth && frac > 0.02f) {
      blend(cur, nxt, frac, mix)
      upload(mix)
    } else if (advanced || !uploaded || smooth) {
      upload(cur)
    }
    gridIndex++
    return (tMs - trimStartMs) * 1000
  }

  private fun blend(a: IntArray, b: IntArray, f: Float, out: IntArray) {
    val fb = (f * 256).toInt().coerceIn(0, 256)
    val fa = 256 - fb
    for (i in a.indices) {
      val pa = opaque(a[i])
      val pb = opaque(b[i])
      val r = (((pa shr 16) and 0xFF) * fa + ((pb shr 16) and 0xFF) * fb) shr 8
      val g = (((pa shr 8) and 0xFF) * fa + ((pb shr 8) and 0xFF) * fb) shr 8
      val bl = ((pa and 0xFF) * fa + (pb and 0xFF) * fb) shr 8
      out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
  }

  private fun opaque(p: Int): Int = if (p ushr 24 == 0) 0xFF000000.toInt() else p or 0xFF000000.toInt()

  private fun upload(px: IntArray) {
    for (i in px.indices) mix[i] = opaque(px[i])
    bitmap.setPixels(mix, 0, width, 0, 0, width, height)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
    uploaded = true
  }

  override fun release() {
    bitmap.recycle()
    Gl.deleteTexture(texture)
  }
}

/** Saved JPEG frames played back (used for the reverse half of ping-pong loops). */
internal class StillTexture {
  val texture: Int = Gl.createTexture()

  fun load(bitmap: Bitmap) {
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
  }

  fun release() = Gl.deleteTexture(texture)
}
