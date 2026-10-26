package io.github.rdilipkumar.livewallpapermaker.engine

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rdilipkumar.livewallpapermaker.engine.creator.BakeOptions
import io.github.rdilipkumar.livewallpapermaker.engine.creator.LoopBaker
import io.github.rdilipkumar.livewallpapermaker.engine.model.Asset
import io.github.rdilipkumar.livewallpapermaker.engine.model.AssetKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.BatteryBehavior
import io.github.rdilipkumar.livewallpapermaker.engine.model.Effects
import io.github.rdilipkumar.livewallpapermaker.engine.model.Fit
import io.github.rdilipkumar.livewallpapermaker.engine.model.GradientSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import io.github.rdilipkumar.livewallpapermaker.engine.model.LoopStyle
import io.github.rdilipkumar.livewallpapermaker.engine.model.ParticleSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.PlaybackSettings
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.SceneSwitching
import io.github.rdilipkumar.livewallpapermaker.engine.model.StorageLayout
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextSpec
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
class RendererDeviceTest {
  private val w = 270
  private val h = 480
  private val root = TestFiles.dir("render-test")
  private val storage = StorageLayout(root)
  private val readerThread = HandlerThread("reader").apply { start() }
  private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
  private val frames = AtomicInteger()
  @Volatile private var lastPixels: IntArray? = null
  private val renderer = WallpaperRenderer(TestFiles.target, storage)

  init {
    reader.setOnImageAvailableListener({ r ->
      r.acquireLatestImage()?.use { img ->
        val plane = img.planes[0]
        val buf = plane.buffer
        val px = IntArray(5)
        val points = listOf(w / 2 to h / 2, 5 to 5, w - 5 to 5, 5 to h - 5, w - 5 to h - 5)
        points.forEachIndexed { i, (x, y) ->
          val o = y * plane.rowStride + x * plane.pixelStride
          px[i] = (0xFF shl 24) or ((buf.get(o).toInt() and 0xFF) shl 16) or ((buf.get(o + 1).toInt() and 0xFF) shl 8) or (buf.get(o + 2).toInt() and 0xFF)
        }
        lastPixels = px
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

  private fun red(c: Int) = c shr 16 and 0xFF
  private fun green(c: Int) = c shr 8 and 0xFF
  private fun blue(c: Int) = c and 0xFF

  private fun solid(color: Long) = Layer("g", type = LayerType.GRADIENT, gradient = GradientSpec(listOf(color.toInt(), color.toInt())))

  @Test
  fun drawsGradientAndThenIdles() {
    start(Project("p", scenes = listOf(Scene("s", layers = listOf(solid(0xFFFF0000))))))
    assertTrue("no first frame", waitFrames(1))
    Thread.sleep(300)
    val c = lastPixels!![0]
    assertTrue("centre ${Integer.toHexString(c)}", red(c) > 240 && green(c) < 15 && blue(c) < 15)
    // A still scene must not keep drawing.
    val before = frames.get()
    Thread.sleep(1200)
    assertTrue("still scene drew ${frames.get() - before} extra frames", frames.get() - before <= 1)
    // Hidden: nothing is drawn even when the project changes.
    renderer.setVisible(false)
    Thread.sleep(200)
    val hidden = frames.get()
    renderer.setProject(Project("p", scenes = listOf(Scene("s", layers = listOf(solid(0xFF0000FF))))))
    Thread.sleep(500)
    assertEquals(hidden, frames.get())
    renderer.setVisible(true)
    assertTrue(waitFrames(hidden + 1))
    Thread.sleep(200)
    assertTrue(blue(lastPixels!![0]) > 240)
  }

  @Test
  fun animatedSceneKeepsDrawingAtTheFpsCap() {
    val layer = Layer("p", type = LayerType.PARTICLES, particles = ParticleSpec(count = 200))
    start(Project("p", scenes = listOf(Scene("s", layers = listOf(layer))), settings = PlaybackSettings(fpsCap = 20)))
    assertTrue(waitFrames(1))
    val before = frames.get()
    Thread.sleep(2000)
    val drawn = frames.get() - before
    assertTrue("drew $drawn frames in 2 s at a 20 fps cap", drawn in 25..46)
    // Battery saver set to pause → freezes.
    renderer.setProject(Project("p", scenes = listOf(Scene("s", layers = listOf(layer))), settings = PlaybackSettings(fpsCap = 20, batterySaver = BatteryBehavior.PAUSE)))
    renderer.setPowerState(true, 0)
    Thread.sleep(400)
    val frozen = frames.get()
    Thread.sleep(1000)
    assertTrue("frozen but drew ${frames.get() - frozen}", frames.get() - frozen <= 1)
  }

  @Test
  fun videoLayerPlaysAndCoversScreen() {
    val dir = storage.assetsDir.apply { mkdirs() }
    val out = File(dir, "v.mp4")
    val res = LoopBaker.bake(TestFiles.asset("portrait_rot90.mp4", root), false, BakeOptions(loop = LoopStyle.CROSSFADE), out, root)
    val asset = Asset("v", AssetKind.VIDEO, "v.mp4", res.width, res.height, res.durationMs)
    val project = Project(
      "p", assets = listOf(asset),
      scenes = listOf(Scene("s", layers = listOf(Layer("l", assetId = "v", fit = Fit.COVER, effects = Effects(blur = 0.3f, vignette = 0.5f))))),
    )
    start(project)
    assertTrue("no video frames", waitFrames(20, 8000))
    val a = lastPixels!!.clone()
    Thread.sleep(700)
    val b = lastPixels!!
    assertTrue("video did not change", a.indices.any { abs(red(a[it]) - red(b[it])) + abs(green(a[it]) - green(b[it])) > 20 })
    // Loops past the end of the clip without stalling.
    val before = frames.get()
    Thread.sleep(res.durationMs + 1500)
    assertTrue("stalled after loop", frames.get() - before > 30)
  }

  @Test
  fun scenesSwitchWithEveryTransition() {
    for (type in TransitionType.entries) {
      val scenes = listOf(
        Scene("a", layers = listOf(solid(0xFFFF0000)), transitionIn = Transition(type, 300)),
        Scene("b", layers = listOf(solid(0xFF00FF00)), transitionIn = Transition(type, 300)),
      )
      frames.set(0)
      start(Project("p-$type", scenes = scenes, switching = SceneSwitching(timerSec = 1)))
      assertTrue(waitFrames(1))
      val end = System.currentTimeMillis() + 4000
      while (renderer.currentSceneId != "b" && System.currentTimeMillis() < end) Thread.sleep(50)
      assertEquals("transition $type did not finish", "b", renderer.currentSceneId)
      Thread.sleep(250)
      val c = lastPixels!![0]
      assertTrue("$type centre ${Integer.toHexString(c)}", green(c) > 200 && red(c) < 60)
      renderer.setProject(null)
      Thread.sleep(100)
    }
  }

  @Test
  fun unlockIntroPlaysThenReturnsAndDoubleTapSwitches() {
    val scenes = listOf(
      Scene("main", layers = listOf(solid(0xFFFF0000)), transitionIn = Transition(TransitionType.CROSSFADE, 200)),
      Scene("other", layers = listOf(solid(0xFF0000FF)), transitionIn = Transition(TransitionType.CUT)),
      Scene("intro", layers = listOf(solid(0xFF00FF00)), transitionIn = Transition(TransitionType.CIRCLE, 200), isIntro = true, introDurationSec = 1f),
    )
    start(Project("p", scenes = scenes, switching = SceneSwitching(onDoubleTap = true)))
    assertTrue(waitFrames(1))
    Thread.sleep(200)
    assertEquals("main", renderer.currentSceneId)
    renderer.trigger(TriggerEvent.UNLOCK)
    val end = System.currentTimeMillis() + 2000
    while (renderer.currentSceneId != "intro" && System.currentTimeMillis() < end) Thread.sleep(20)
    assertEquals("intro", renderer.currentSceneId)
    Thread.sleep(150)
    assertTrue("intro colour", green(lastPixels!![0]) > 200)
    // After its 1 s the intro hands back to the scene that was showing (even though the scene is still).
    val back = System.currentTimeMillis() + 3000
    while (renderer.currentSceneId != "main" && System.currentTimeMillis() < back) Thread.sleep(20)
    assertEquals("main", renderer.currentSceneId)
    // Double-tap moves on, skipping the intro scene.
    renderer.trigger(TriggerEvent.DOUBLE_TAP)
    val tap = System.currentTimeMillis() + 2000
    while (renderer.currentSceneId != "other" && System.currentTimeMillis() < tap) Thread.sleep(20)
    assertEquals("other", renderer.currentSceneId)
  }

  @Test
  fun textImageAndMissingAssetsDoNotCrash() {
    val dir = storage.assetsDir.apply { mkdirs() }
    TestFiles.asset("cutout.png", dir)
    val project = Project(
      "p",
      assets = listOf(
        Asset("png", AssetKind.IMAGE, "cutout.png", 200, 250, hasAlpha = true),
        Asset("gone", AssetKind.VIDEO, "missing.mp4", 100, 100),
      ),
      scenes = listOf(
        Scene(
          "s",
          layers = listOf(
            solid(0xFF000080),
            Layer("img", assetId = "png", fit = Fit.CONTAIN),
            Layer("gone", assetId = "gone"),
            Layer("slot", assetId = null),
            Layer("t", type = LayerType.TEXT, text = TextSpec("{time}\n{date}")),
          ),
        ),
      ),
    )
    start(project)
    assertTrue(waitFrames(1))
    Thread.sleep(300)
    // Corners show the navy background; the centre is covered by the blue PNG disc or the text.
    val px = lastPixels!!
    assertTrue("corner ${Integer.toHexString(px[1])}", blue(px[1]) in 100..160 && red(px[1]) < 20)
    assertTrue("centre ${Integer.toHexString(px[0])}", px[0] != px[1])
  }
}
