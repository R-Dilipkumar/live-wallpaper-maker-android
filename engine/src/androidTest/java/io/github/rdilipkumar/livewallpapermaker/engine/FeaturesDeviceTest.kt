package io.github.rdilipkumar.livewallpapermaker.engine

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rdilipkumar.livewallpapermaker.engine.creator.BakeOptions
import io.github.rdilipkumar.livewallpapermaker.engine.creator.ExportOptions
import io.github.rdilipkumar.livewallpapermaker.engine.creator.GifStreamDecoder
import io.github.rdilipkumar.livewallpapermaker.engine.creator.LoopBaker
import io.github.rdilipkumar.livewallpapermaker.engine.creator.SceneExporter
import io.github.rdilipkumar.livewallpapermaker.engine.creator.StickerSheet
import io.github.rdilipkumar.livewallpapermaker.engine.model.Asset
import io.github.rdilipkumar.livewallpapermaker.engine.model.AssetKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.Fit
import io.github.rdilipkumar.livewallpapermaker.engine.model.GradientSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import io.github.rdilipkumar.livewallpapermaker.engine.model.LoopStyle
import io.github.rdilipkumar.livewallpapermaker.engine.model.MaskShape
import io.github.rdilipkumar.livewallpapermaker.engine.model.ParticleKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.ParticleSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.PlaybackSettings
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.ScreenFilter
import io.github.rdilipkumar.livewallpapermaker.engine.model.StorageLayout
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.TouchEffect
import io.github.rdilipkumar.livewallpapermaker.engine.model.TouchKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.Transform
import io.github.rdilipkumar.livewallpapermaker.engine.model.Transition
import io.github.rdilipkumar.livewallpapermaker.engine.model.TransitionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class FeaturesDeviceTest {
  private val w = 270
  private val h = 480
  private val root = TestFiles.dir("features-test")
  private val storage = StorageLayout(root)
  private val readerThread = HandlerThread("reader2").apply { start() }
  private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
  private val frames = AtomicInteger()
  @Volatile private var lastPixels: IntArray? = null
  private val renderer = WallpaperRenderer(TestFiles.target, storage)

  /** Sample points: centre, 4 corners, and a point 1/8 in from the left edge at mid height. */
  private val points = listOf(w / 2 to h / 2, 3 to 3, w - 4 to 3, 3 to h - 4, w - 4 to h - 4, w / 8 to h / 2)

  init {
    reader.setOnImageAvailableListener({ r ->
      r.acquireLatestImage()?.use { img ->
        val plane = img.planes[0]
        val buf = plane.buffer
        lastPixels = IntArray(points.size) { i ->
          val (x, y) = points[i]
          val o = y * plane.rowStride + x * plane.pixelStride
          (0xFF shl 24) or ((buf.get(o).toInt() and 0xFF) shl 16) or ((buf.get(o + 1).toInt() and 0xFF) shl 8) or (buf.get(o + 2).toInt() and 0xFF)
        }
        frames.incrementAndGet()
      }
    }, Handler(readerThread.looper))
  }

  @After
  fun tearDown() {
    renderer.release()
    reader.close()
    readerThread.quitSafely()
  }

  private fun start(project: Project) {
    renderer.setSurface(reader.surface, w, h)
    renderer.setVisible(true)
    renderer.setProject(project)
  }

  private fun waitFrames(n: Int, timeoutMs: Long = 5000): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      if (frames.get() >= n) return true
      Thread.sleep(20)
    }
    return false
  }

  private fun r(c: Int) = c shr 16 and 0xFF
  private fun g(c: Int) = c shr 8 and 0xFF
  private fun b(c: Int) = c and 0xFF
  private fun solid(color: Long, mask: MaskShape = MaskShape.NONE) =
    Layer("g", type = LayerType.GRADIENT, gradient = GradientSpec(listOf(color.toInt(), color.toInt())), mask = mask, maskFeather = 0.02f)

  @Test
  fun circleAndHeartMasksCutTheLayer() {
    for (mask in listOf(MaskShape.CIRCLE, MaskShape.HEART, MaskShape.STAR, MaskShape.ROUNDED, MaskShape.SOFT_EDGES)) {
      frames.set(0)
      start(Project("m-$mask", scenes = listOf(Scene("s", background = 0xFF0000FF.toInt(), layers = listOf(solid(0xFFFF0000, mask))))))
      assertTrue(waitFrames(1))
      Thread.sleep(200)
      val px = lastPixels!!
      assertTrue("$mask centre ${Integer.toHexString(px[0])}", r(px[0]) > 200 && b(px[0]) < 60)
      // Hard shapes leave the corner fully blue; soft edges fade it most of the way.
      val cornerBlue = if (mask == MaskShape.SOFT_EDGES) b(px[1]) > 120 && r(px[1]) < 140 else b(px[1]) > 200 && r(px[1]) < 60
      assertTrue("$mask corner ${Integer.toHexString(px[1])}", cornerBlue)
      renderer.setProject(null)
      Thread.sleep(100)
    }
  }

  @Test
  fun tapEffectsAnimateThenStop() {
    for (kind in listOf(TouchKind.RIPPLE, TouchKind.SPARKLES, TouchKind.GLOW)) {
      // A still scene with stripes, so the ripple's bending shows.
      val stripes = Layer("t", type = LayerType.GRADIENT, gradient = GradientSpec(listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()), angleDeg = 0f))
      frames.set(0)
      start(Project("t-$kind", scenes = listOf(Scene("s", layers = listOf(stripes))), settings = PlaybackSettings(touch = TouchEffect(kind, 0xFFFF00FF.toInt(), 1.5f))))
      assertTrue(waitFrames(1))
      Thread.sleep(300)
      val idle = frames.get()
      val before = lastPixels!!.clone()
      renderer.touch(w / 8f + 10f, h / 2f)
      Thread.sleep(250)
      val during = frames.get() - idle
      assertTrue("$kind drew only $during frames", during >= 3)
      // The glow lights the spot it was tapped (ripples and sparks are checked by eye in the emulator).
      if (kind == TouchKind.GLOW) {
        val changed = before.indices.any { i -> abs(r(before[i]) - r(lastPixels!![i])) + abs(b(before[i]) - b(lastPixels!![i])) > 20 }
        assertTrue("$kind changed nothing", changed)
      }
      // It ends by itself and the scene goes back to idle.
      Thread.sleep(2200)
      val after = frames.get()
      Thread.sleep(600)
      assertTrue("$kind kept drawing", frames.get() - after <= 1)
      renderer.setProject(null)
      Thread.sleep(100)
    }
  }

  @Test
  fun lockScreenSceneAndDayTint() {
    val lock = Scene("lock", layers = listOf(solid(0xFF00FF00)), showOn = ScreenFilter.LOCK_SCREEN, transitionIn = Transition(TransitionType.CUT))
    val home = Scene("home", layers = listOf(solid(0xFFFFFFFF)), showOn = ScreenFilter.HOME_SCREEN, transitionIn = Transition(TransitionType.CUT))
    start(Project("lh", scenes = listOf(home, lock), settings = PlaybackSettings(dayCycle = 1f)))
    renderer.setSimulatedLock(false)
    renderer.setTimeOverride(12 * 60)
    assertTrue(waitFrames(1))
    Thread.sleep(300)
    assertEquals("home", renderer.currentSceneId)
    val noon = lastPixels!![0]
    renderer.setSimulatedLock(true)
    Thread.sleep(400)
    assertEquals("lock", renderer.currentSceneId)
    renderer.trigger(TriggerEvent.UNLOCK)
    Thread.sleep(400)
    assertEquals("home", renderer.currentSceneId)
    // At 2 am the white scene turns dim and blue.
    renderer.setTimeOverride(2 * 60)
    Thread.sleep(400)
    val night = lastPixels!![0]
    assertTrue("noon ${Integer.toHexString(noon)} night ${Integer.toHexString(night)}", b(night) > r(night) + 30 && r(night) < r(noon) - 40)
  }

  @Test
  fun stickerAnimatesWithTransparency() {
    val gif = File(root, "t.gif").also { f -> TestFiles.asset("transparent.gif", root).copyTo(f, overwrite = true) }.readBytes()
    val sheet = StickerSheet.build(gif)
    storage.assetsDir.mkdirs()
    val bmp = Bitmap.createBitmap(sheet.pixels, sheet.width, sheet.height, Bitmap.Config.ARGB_8888)
    File(storage.assetsDir, "s.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val asset = Asset("s", AssetKind.STICKER, "s.png", sheet.info.frameW, sheet.info.frameH, sheet.info.totalMs.toLong(), hasAlpha = true, sticker = sheet.info)
    val layer = Layer("l", assetId = "s", fit = Fit.CONTAIN)
    start(Project("st", assets = listOf(asset), scenes = listOf(Scene("s", background = 0xFF0000FF.toInt(), layers = listOf(layer)))))
    assertTrue(waitFrames(5, 4000))
    // Corners stay the blue background (transparent sticker), and the sticker keeps animating.
    val px = lastPixels!!
    assertTrue("corner ${Integer.toHexString(px[1])}", b(px[1]) > 200 && r(px[1]) < 40)
    val n = frames.get()
    Thread.sleep(800)
    assertTrue("sticker not animating", frames.get() - n > 10)
  }

  @Test
  fun exportVideoAndGif() {
    val dir = storage.assetsDir.apply { mkdirs() }
    val res = LoopBaker.bake(TestFiles.asset("portrait_rot90.mp4", root), false, BakeOptions(loop = LoopStyle.NONE), File(dir, "v.mp4"), root)
    val asset = Asset("v", AssetKind.VIDEO, "v.mp4", res.width, res.height, res.durationMs)
    val project = Project(
      "ex", assets = listOf(asset),
      scenes = listOf(
        Scene(
          "s",
          layers = listOf(
            Layer("v", assetId = "v"),
            Layer("p", type = LayerType.PARTICLES, particles = ParticleSpec(ParticleKind.SNOW, 100)),
            Layer("t", type = LayerType.TEXT, text = TextSpec("{time}"), transform = Transform(y = -0.3f)),
          ),
        ),
      ),
    )
    val mp4 = File(root, "out.mp4")
    var last = 0f
    SceneExporter.export(TestFiles.target, storage, project, "s", ExportOptions(seconds = 3f, width = 360, screenAspect = 0.5f), mp4) { last = it }
    assertEquals(1f, last)
    val info = TestFiles.inspect(mp4)
    assertEquals(368, info.width) // 360 rounded to 16
    assertEquals(736, info.height)
    assertTrue("frames ${info.frames}", info.frames in 88..91)
    // The video layer really moves in the export.
    val a = TestFiles.frameAt(mp4, 300_000)
    val b = TestFiles.frameAt(mp4, 1_500_000)
    assertTrue(TestFiles.diff(a, b) > 3.0)

    val gifFile = File(root, "out.gif")
    SceneExporter.export(TestFiles.target, storage, project, "s", ExportOptions(seconds = 2f, width = 200, screenAspect = 0.5f, gif = true), gifFile)
    val d = GifStreamDecoder(gifFile.readBytes())
    assertEquals(208, d.width)
    assertEquals(30, d.scanDelays().size) // 2 s at 15 fps
  }
}
