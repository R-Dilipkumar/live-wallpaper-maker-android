package io.github.rdilipkumar.livewallpapermaker.engine.creator

import android.content.Context
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import io.github.rdilipkumar.livewallpapermaker.engine.gl.EglCore
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Fbo
import io.github.rdilipkumar.livewallpapermaker.engine.model.MotionInput
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.SceneLogic
import io.github.rdilipkumar.livewallpapermaker.engine.model.StorageLayout
import io.github.rdilipkumar.livewallpapermaker.engine.render.Painter
import io.github.rdilipkumar.livewallpapermaker.engine.render.SceneDrawer
import io.github.rdilipkumar.livewallpapermaker.engine.render.SourceStore
import io.github.rdilipkumar.livewallpapermaker.engine.render.Target
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

data class ExportOptions(
  val seconds: Float = 8f,
  /** Output width; height follows the screen's shape. */
  val width: Int = 720,
  val screenAspect: Float = 9f / 19.5f,
  val gif: Boolean = false,
  /** Sway the phone tilt gently so parallax layers show their depth. */
  val tiltSway: Boolean = true,
)

/**
 * Renders one scene offline into an MP4 (hardware encoder) or an animated GIF, using the same drawing code as
 * the wallpaper. Blocking; run it on a worker thread.
 */
object SceneExporter {

  fun export(
    context: Context,
    storage: StorageLayout,
    project: Project,
    sceneId: String,
    options: ExportOptions,
    out: File,
    isCancelled: () -> Boolean = { false },
    onProgress: (Float) -> Unit = {},
  ) {
    val scene = project.scene(sceneId) ?: throw IllegalArgumentException("No such scene")
    val fps = if (options.gif) 15 else 30
    val width = round16(if (options.gif) minOf(options.width, 480) else options.width)
    val height = round16(width / options.screenAspect)
    val frames = max(1, (options.seconds * fps).roundToInt())

    val cbThread = HandlerThread("lwm-export-frames").apply { start() }
    val cbHandler = Handler(cbThread.looper)
    val egl = EglCore(recordable = true)
    val pbuffer = egl.createPbuffer(1, 1)
    egl.makeCurrent(pbuffer)
    val painter = Painter()
    var sources: SourceStore? = null
    var drawer: SceneDrawer? = null
    var encoder: VideoEncoder? = null
    var fbo: Fbo? = null
    try {
      painter.setScreenWidth(width)
      painter.dayTint = if (project.settings.dayCycle > 0f) {
        val c = Calendar.getInstance()
        SceneLogic.dayTint(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), project.settings.dayCycle)
      } else null

      fun freshSources(): Pair<SourceStore, SceneDrawer> {
        drawer?.release()
        sources?.releaseAll()
        val s = SourceStore(context, storage, cbHandler) {}
        s.screenW = width
        s.screenH = height
        s.prepare(scene, project)
        val d = SceneDrawer(painter, s)
        sources = s
        drawer = d
        return s to d
      }

      fun motion(t: Float) = MotionInput(
        timeSec = t,
        swipe = 0.5f,
        tiltX = if (options.tiltSway) 0.7f * sin(2.0 * PI * t / 6.0).toFloat() else 0f,
        tiltY = if (options.tiltSway) 0.35f * sin(2.0 * PI * t / 9.0).toFloat() else 0f,
      )

      /** Draws frame [i] into the current target. */
      fun render(i: Int, s: SourceStore, d: SceneDrawer, target: Target) {
        val t = i.toFloat() / fps
        for ((key, v) in s.allVideos()) v.advanceTo((t * 1_000_000L * s.speedOf(key)).toLong())
        s.refreshTexts(System.currentTimeMillis(), setOf(scene.id))
        painter.use(target)
        d.draw(scene, project, motion(t), width, height)
      }

      if (!options.gif) {
        val enc = VideoEncoder(out, width, height, fps, VideoEncoder.bitrateFor(width, height, fps))
        encoder = enc
        val surface = egl.createWindowSurface(enc.inputSurface)
        egl.makeCurrent(surface)
        val (s, d) = freshSources()
        for (i in 0 until frames) {
          if (isCancelled()) throw CancellationException("Cancelled")
          egl.makeCurrent(surface)
          render(i, s, d, Target(null, width, height))
          egl.setPresentationTime(surface, i * 1_000_000_000L / fps)
          egl.swap(surface)
          enc.drain(false)
          onProgress((i + 1f) / frames * 0.98f)
        }
        egl.makeCurrent(pbuffer)
        egl.releaseSurface(surface)
        enc.finish()
        encoder = null
      } else {
        val f = Fbo(width, height).also { fbo = it }
        val pixels = IntArray(width * height)
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        fun readFrame() {
          buf.rewind()
          GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
          buf.rewind()
          // RGBA bytes, bottom row first -> ARGB ints, top row first.
          for (y in 0 until height) {
            val srcRow = (height - 1 - y) * width * 4
            for (x in 0 until width) {
              val o = srcRow + x * 4
              val r = buf.get(o).toInt() and 0xFF
              val g = buf.get(o + 1).toInt() and 0xFF
              val b = buf.get(o + 2).toInt() and 0xFF
              pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
          }
        }
        // Pass 1: sample colours from frames spread over the clip for one shared palette.
        val samples = ArrayList<Int>()
        var (s, d) = freshSources()
        val every = max(1, frames / 10)
        for (i in 0 until frames) {
          if (isCancelled()) throw CancellationException("Cancelled")
          render(i, s, d, Target(f, width, height))
          if (i % every == 0) {
            readFrame()
            var k = (i * 7) % 5
            while (k < pixels.size) {
              samples += pixels[k]
              k += 5
            }
          }
          onProgress((i + 1f) / frames * 0.35f)
        }
        val palette = GifWriter.medianCut(samples.toIntArray())
        // Pass 2: render again from the start and write the GIF.
        val fresh = freshSources()
        s = fresh.first
        d = fresh.second
        out.outputStream().buffered().use { stream ->
          val writer = GifWriter(stream, width, height, palette)
          for (i in 0 until frames) {
            if (isCancelled()) throw CancellationException("Cancelled")
            render(i, s, d, Target(f, width, height))
            readFrame()
            writer.frame(pixels, 1000 / fps)
            onProgress(0.35f + (i + 1f) / frames * 0.63f)
          }
          writer.finish()
        }
      }
      onProgress(1f)
    } catch (t: Throwable) {
      encoder?.cancel()
      out.delete()
      throw t
    } finally {
      runCatching { egl.makeCurrent(pbuffer) }
      drawer?.release()
      sources?.releaseAll()
      fbo?.release()
      painter.release()
      egl.releaseSurface(pbuffer)
      egl.release()
      cbThread.quitSafely()
    }
  }

  private fun round16(v: Float): Int = max(16, (v / 16f).roundToInt() * 16)
  private fun round16(v: Int): Int = round16(v.toFloat())
}
