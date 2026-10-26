package io.github.rdilipkumar.livewallpapermaker.engine.creator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import io.github.rdilipkumar.livewallpapermaker.engine.gl.EglCore
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Fbo
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Gl
import io.github.rdilipkumar.livewallpapermaker.engine.model.BlendMode
import io.github.rdilipkumar.livewallpapermaker.engine.model.LoopStyle
import io.github.rdilipkumar.livewallpapermaker.engine.model.Pose
import io.github.rdilipkumar.livewallpapermaker.engine.render.Painter
import io.github.rdilipkumar.livewallpapermaker.engine.render.Target
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class BakeOptions(
  val trimStartMs: Long = 0,
  /** 0 = to the end. */
  val trimEndMs: Long = 0,
  val loop: LoopStyle = LoopStyle.CROSSFADE,
  val loopWindowMs: Int = 500,
  val maxShortSide: Int = 1080,
  val maxFps: Int = 30,
  val smoothGif: Boolean = false,
)

data class BakeResult(val width: Int, val height: Int, val durationMs: Long, val frames: Int)

/**
 * Turns a video or GIF into a short MP4 that loops seamlessly, using the GPU and the phone's encoder.
 * The loop style is baked in, so the wallpaper only has to play the file. Blocking; call from a worker thread.
 *
 * Crossfade/zoom: the clip starts after its first [BakeOptions.loopWindowMs]; its last window is blended
 * with that skipped opening (read by a second decoder), so the last frame flows into the first.
 */
object LoopBaker {
  /** Ping-pong keeps every frame on disk for the way back, so it is limited to this length. */
  const val PING_PONG_MAX_MS = 15_000L

  fun bake(
    source: File,
    isGif: Boolean,
    options: BakeOptions,
    out: File,
    cacheDir: File,
    isCancelled: () -> Boolean = { false },
    onProgress: (Float) -> Unit = {},
  ): BakeResult {
    val cbThread = HandlerThread("lwm-bake-frames").apply { start() }
    val cbHandler = Handler(cbThread.looper)
    val egl = EglCore(recordable = true)
    val pbuffer = egl.createPbuffer(1, 1)
    egl.makeCurrent(pbuffer)
    val painter = Painter()
    val gifBytes = if (isGif) source.readBytes() else null
    val opened = ArrayList<BakeSource>()
    var encoder: VideoEncoder? = null
    var fbo: Fbo? = null
    var still: StillTexture? = null
    val tempDir = File(cacheDir, "pingpong-" + System.nanoTime())

    fun open(): BakeSource {
      val endMs = if (options.trimEndMs > 0) options.trimEndMs else Long.MAX_VALUE / 1000
      val s = if (gifBytes != null) {
        GifBakeSource(gifBytes, options.trimStartMs, if (options.trimEndMs > 0) options.trimEndMs else 0, options.smoothGif)
      } else {
        VideoBakeSource(source, options.trimStartMs * 1000, endMs * 1000, cbHandler)
      }
      opened += s
      return s
    }

    try {
      val main = open()
      var durationUs = main.durationUs
      var loop = options.loop
      if (loop == LoopStyle.PING_PONG && durationUs > PING_PONG_MAX_MS * 1000) durationUs = PING_PONG_MAX_MS * 1000
      val fps = min(options.maxFps.toFloat(), if (main.fps > 0) main.fps else 30f).roundToInt().coerceIn(5, 60)
      val step = 1_000_000L / fps
      var windowUs = options.loopWindowMs.toLong() * 1000
      if (loop == LoopStyle.CROSSFADE || loop == LoopStyle.ZOOM_DISSOLVE || loop == LoopStyle.FADE_BLACK) {
        windowUs = windowUs.coerceIn(2 * step, durationUs / 3)
        if (durationUs < 6 * step) loop = LoopStyle.NONE
      }

      val (outW, outH) = VideoEncoder.pickSize(main.width, main.height, options.maxShortSide)
      val enc = VideoEncoder(out, outW, outH, fps, VideoEncoder.bitrateFor(outW, outH, fps))
      encoder = enc
      val encSurface = egl.createWindowSurface(enc.inputSurface)
      val screen = Target(null, outW, outH)
      val full = Pose(outW / 2f, outH / 2f, outW.toFloat(), outH.toFloat(), 0f, 1f)

      var lastK = -1L
      var written = 0
      val expected = max(1L, durationUs / step) * (if (loop == LoopStyle.PING_PONG) 2 else 1)

      fun checkCancel() {
        if (isCancelled()) throw CancellationException("Cancelled")
      }

      /** Draws via [drawBody] and sends the frame to the encoder at grid slot [k]. */
      fun emit(k: Long, drawBody: () -> Unit) {
        egl.makeCurrent(encSurface)
        painter.use(screen)
        painter.clear(0xFF000000.toInt())
        drawBody()
        egl.setPresentationTime(encSurface, k * step * 1000)
        egl.swap(encSurface)
        enc.drain(false)
        written++
        if (written % 4 == 0) onProgress((written.toFloat() / expected).coerceAtMost(0.99f))
      }

      fun drawSource(s: BakeSource, zoom: Float = 1f, alpha: Float = 1f) {
        val pose = full.copy(width = full.width * zoom, height = full.height * zoom, opacity = alpha)
        painter.drawTexture(s.texture, s.oes, s.texMatrix, pose, null, 0f, BlendMode.NORMAL)
      }

      when (loop) {
        LoopStyle.NONE, LoopStyle.FADE_BLACK -> {
          val half = windowUs / 2
          while (true) {
            checkCancel()
            val t = main.next()
            if (t < 0 || t >= durationUs) break
            val k = (t.toDouble() / step).roundToLong()
            if (k <= lastK || k * step >= durationUs) continue
            lastK = k
            val alpha = if (loop == LoopStyle.FADE_BLACK) {
              min(((t + step).toFloat() / half).coerceIn(0f, 1f), ((durationUs - t - step).toFloat() / half).coerceIn(0f, 1f))
            } else 1f
            emit(k) { drawSource(main, alpha = alpha) }
          }
        }
        LoopStyle.CROSSFADE, LoopStyle.ZOOM_DISSOLVE -> {
          val head = open()
          var headT = head.next()
          val tailStart = durationUs - windowUs
          while (true) {
            checkCancel()
            val t = main.next()
            if (t < 0 || t >= durationUs) break
            if (t < windowUs) continue
            val k = ((t - windowUs).toDouble() / step).roundToLong()
            if (k <= lastK || k * step >= durationUs - windowUs) continue
            lastK = k
            if (t >= tailStart) {
              val tau = t - tailStart
              while (headT >= 0 && headT < tau - step / 2) headT = head.next()
              val a = (tau.toFloat() / windowUs).coerceIn(0f, 1f)
              emit(k) {
                if (loop == LoopStyle.ZOOM_DISSOLVE) {
                  drawSource(main, zoom = 1f + 0.12f * a)
                  drawSource(head, zoom = 1.08f - 0.08f * a, alpha = a)
                } else {
                  drawSource(main)
                  drawSource(head, alpha = a)
                }
              }
            } else {
              emit(k) { drawSource(main) }
            }
          }
        }
        LoopStyle.PING_PONG -> {
          tempDir.mkdirs()
          val f = Fbo(outW, outH).also { fbo = it }
          val saved = ArrayList<File>()
          var first = true
          while (true) {
            checkCancel()
            val t = main.next()
            if (t < 0 || t >= durationUs) break
            val k = (t.toDouble() / step).roundToLong()
            if (k <= lastK || k * step >= durationUs) continue
            lastK = k
            // Draw into a buffer first so the frame can be saved for the way back.
            egl.makeCurrent(encSurface)
            painter.use(Target(f, outW, outH))
            painter.clear(0xFF000000.toInt())
            drawSource(main)
            if (!first) {
              val bmp = Gl.readPixels(outW, outH)
              val file = File(tempDir, "f${saved.size}.jpg")
              FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
              bmp.recycle()
              saved += file
            }
            first = false
            emit(k) { painter.copyFull(f.texture, false, Gl.IDENTITY) }
          }
          // The newest saved frame is the last one played forward; skip it so the turn does not stutter.
          if (saved.isNotEmpty()) saved.removeAt(saved.size - 1).delete()
          val tex = StillTexture().also { still = it }
          for (i in saved.indices.reversed()) {
            checkCancel()
            val bmp = BitmapFactory.decodeFile(saved[i].path) ?: continue
            egl.makeCurrent(encSurface)
            tex.load(bmp)
            bmp.recycle()
            saved[i].delete()
            lastK++
            emit(lastK) {
              painter.drawTexture(tex.texture, false, Gl.FLIP_V, full, null, 0f, BlendMode.NORMAL)
            }
          }
        }
      }
      egl.makeCurrent(pbuffer)
      egl.releaseSurface(encSurface)
      enc.finish()
      encoder = null
      check(enc.framesWritten > 0) { "No frames could be read from this file" }
      onProgress(1f)
      return BakeResult(outW, outH, (lastK + 1) * step / 1000, written)
    } catch (t: Throwable) {
      encoder?.cancel()
      out.delete()
      throw t
    } finally {
      runCatching { egl.makeCurrent(pbuffer) }
      opened.forEach { runCatching { it.release() } }
      fbo?.release()
      still?.release()
      painter.release()
      egl.releaseSurface(pbuffer)
      egl.release()
      cbThread.quitSafely()
      tempDir.deleteRecursively()
    }
  }
}
