package io.github.rdilipkumar.livewallpapermaker.engine.model

import io.github.rdilipkumar.livewallpapermaker.engine.model.SceneLogic.contains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeometryTest {
  private val eps = 0.001f

  @Test
  fun coverAndContainFit() {
    val (cw, ch) = Geometry.fitSize(Fit.COVER, 1920f, 1080f, 1080f, 2400f)
    assertEquals(2400f, ch, eps)
    assertEquals(4266.667f, cw, 0.01f)
    val (w2, h2) = Geometry.fitSize(Fit.CONTAIN, 1920f, 1080f, 1080f, 2400f)
    assertEquals(1080f, w2, eps)
    assertEquals(607.5f, h2, eps)
    assertEquals(1080f to 2400f, Geometry.fitSize(Fit.STRETCH, 10f, 10f, 1080f, 2400f))
    assertEquals(1080f to 2400f, Geometry.fitSize(Fit.COVER, 0f, 0f, 1080f, 2400f))
  }

  @Test
  fun swipeMovesOppositeToPageAndOverscanHidesEdges() {
    val layer = Layer(id = "a", motion = Motion(swipeDepth = 0.1f))
    val left = Geometry.pose(layer, 1080f, 2400f, 1080f, 2400f, MotionInput(swipe = 0f))
    val right = Geometry.pose(layer, 1080f, 2400f, 1080f, 2400f, MotionInput(swipe = 1f))
    assertTrue(left.cx > right.cx)
    assertEquals(108f, left.cx - right.cx, eps)
    // Edges never show: half the width minus the shift still covers half the screen.
    assertTrue(left.width / 2f - (left.cx - 540f) >= 540f - eps)
  }

  @Test
  fun tiltMovesLayer() {
    val layer = Layer(id = "a", motion = Motion(tiltDepth = 0.05f))
    val p = Geometry.pose(layer, 1080f, 2400f, 1080f, 2400f, MotionInput(tiltX = 1f, tiltY = -1f))
    assertEquals(540f + 54f, p.cx, eps)
    assertEquals(1200f - 120f, p.cy, eps)
    assertTrue(p.width >= 1080f * 1.1f - eps)
  }

  @Test
  fun keyframesLoopPingPongAndOnce() {
    val keys = listOf(Keyframe(0f, 0f), Keyframe(2f, 10f))
    val loop = KeyframeTrack(AnimProperty.ROTATION, keys, LoopMode.LOOP, Easing.LINEAR)
    assertEquals(5f, Geometry.evaluate(loop, 1f)!!, eps)
    assertEquals(5f, Geometry.evaluate(loop, 3f)!!, eps)
    val pp = loop.copy(loop = LoopMode.PING_PONG)
    assertEquals(5f, Geometry.evaluate(pp, 3f)!!, eps)
    assertEquals(2.5f, Geometry.evaluate(pp, 3.5f)!!, eps)
    val once = loop.copy(loop = LoopMode.ONCE)
    assertEquals(10f, Geometry.evaluate(once, 50f)!!, eps)
    assertEquals(0f, Geometry.evaluate(once, -5f)!!, eps)
    assertNull(Geometry.evaluate(loop.copy(keys = emptyList()), 1f))
    assertEquals(3f, Geometry.evaluate(loop.copy(keys = listOf(Keyframe(1f, 3f))), 9f)!!, eps)
    // Unsorted keys still work.
    assertEquals(5f, Geometry.evaluate(loop.copy(keys = keys.reversed()), 1f)!!, eps)
  }

  @Test
  fun presetsAreNeutralAtTimeZeroWhereExpected() {
    val pulse = Geometry.presetOffsets(MotionPreset.PULSE, 1f, 4f, 0f)
    assertEquals(1f, pulse.scale, eps)
    val kb = Geometry.presetOffsets(MotionPreset.KEN_BURNS, 1f, 10f, 0f)
    assertEquals(1f, kb.scale, eps)
    val breathe = Geometry.presetOffsets(MotionPreset.BREATHE, 1f, 4f, 2f)
    assertEquals(0.3f, breathe.opacity, eps)
  }

  @Test
  fun hitTestRespectsRotation() {
    val pose = Pose(100f, 100f, 200f, 20f, 90f, 1f)
    assertTrue(Geometry.hitTest(pose, 100f, 180f))
    assertFalse(Geometry.hitTest(pose, 180f, 100f))
  }
}

class SceneLogicTest {
  private fun scene(id: String, window: TimeWindow? = null, intro: Boolean = false, charging: Boolean = false) =
    Scene(id = id, timeWindow = window, isIntro = intro, onlyWhileCharging = charging)

  @Test
  fun timeWindowsWrapPastMidnight() {
    val night = TimeWindow(18 * 60, 6 * 60)
    assertTrue(night.contains(23 * 60))
    assertTrue(night.contains(2 * 60))
    assertFalse(night.contains(12 * 60))
    assertTrue(TimeWindow(600, 600).contains(5))
    assertTrue(TimeWindow(6 * 60, 18 * 60).contains(6 * 60))
    assertFalse(TimeWindow(6 * 60, 18 * 60).contains(18 * 60))
  }

  @Test
  fun picksAllowedScenesAndFallsBack() {
    val p = Project(
      id = "p",
      scenes = listOf(
        scene("day", TimeWindow(360, 1080)),
        scene("night", TimeWindow(1080, 360)),
        scene("intro", intro = true),
      ),
    )
    assertEquals("day", SceneLogic.pickScene(p, 12 * 60, false, null)!!.id)
    assertEquals("night", SceneLogic.pickScene(p, 22 * 60, false, null)!!.id)
    // Only-charging scene with nothing else allowed → falls back to non-intro scenes.
    val q = Project(id = "q", scenes = listOf(scene("c", charging = true), scene("i", intro = true)))
    assertEquals("c", SceneLogic.pickScene(q, 0, false, null)!!.id)
    assertEquals("c", SceneLogic.pickScene(q, 0, true, null)!!.id)
    // A project made only of an intro still shows something.
    val r = Project(id = "r", scenes = listOf(scene("i", intro = true)))
    assertNotNull(SceneLogic.pickScene(r, 0, false, null))
  }

  @Test
  fun nextSceneCyclesAndShuffleNeverRepeats() {
    val p = Project(id = "p", scenes = listOf(scene("a"), scene("b"), scene("c")))
    assertEquals("b", SceneLogic.nextScene(p, "a", 0, false)!!.id)
    assertEquals("a", SceneLogic.nextScene(p, "c", 0, false)!!.id)
    assertEquals("a", SceneLogic.nextScene(p, "missing", 0, false)!!.id)
    val shuffled = p.copy(switching = SceneSwitching(shuffle = true))
    val rnd = Random(1)
    repeat(50) { assertTrue(SceneLogic.nextScene(shuffled, "b", 0, false, rnd)!!.id != "b") }
    val single = Project(id = "s", scenes = listOf(scene("only")))
    assertEquals("only", SceneLogic.nextScene(single, "only", 0, false)!!.id)
  }

  @Test
  fun emptyScenesAreSkipped() {
    val img = Asset("i", AssetKind.IMAGE, "i.jpg", 10, 10)
    val full = Scene("full", layers = listOf(Layer("1", assetId = "i")))
    val empty = Scene("empty", layers = listOf(Layer("2", assetId = null)))
    val gradientOnly = Scene("grad", layers = listOf(Layer("3", type = LayerType.GRADIENT)))
    val p = Project("p", assets = listOf(img), scenes = listOf(full, empty, gradientOnly))
    assertEquals(listOf("full", "grad"), SceneLogic.eligibleScenes(p, 0, false).map { it.id })
    assertEquals("grad", SceneLogic.nextScene(p, "full", 0, false)!!.id)
    assertEquals("full", SceneLogic.nextScene(p, "grad", 0, false)!!.id)
    // A brand-new template with every slot empty still shows its first scene (the editor needs it).
    val blank = Project("b", scenes = listOf(empty, empty.copy(id = "e2")))
    assertEquals("empty", SceneLogic.pickScene(blank, 0, false, null)!!.id)
    // An empty intro never plays.
    val withIntro = p.copy(scenes = p.scenes + Scene("intro", isIntro = true, layers = listOf(Layer("4", assetId = null))))
    assertNull(SceneLogic.introScene(withIntro))
  }

  @Test
  fun animationDetection() {
    val asset = Asset("v", AssetKind.VIDEO, "v.mp4", 10, 10, 4000)
    val img = Asset("i", AssetKind.IMAGE, "i.jpg", 10, 10)
    val p = Project(id = "p", assets = listOf(asset, img))
    assertTrue(SceneLogic.layerIsAnimated(Layer("1", assetId = "v"), p))
    assertFalse(SceneLogic.layerIsAnimated(Layer("2", assetId = "i"), p))
    assertTrue(SceneLogic.layerIsAnimated(Layer("3", assetId = "i", motion = Motion(preset = MotionPreset.FLOAT)), p))
    assertFalse(SceneLogic.layerIsAnimated(Layer("4", assetId = "v", visible = false), p))
    assertTrue(SceneLogic.layerIsAnimated(Layer("5", type = LayerType.PARTICLES), p))
    assertFalse(SceneLogic.layerIsAnimated(Layer("6", type = LayerType.GRADIENT, gradient = GradientSpec()), p))
    assertEquals(4000L, SceneLogic.introDurationMs(Scene("s", layers = listOf(Layer("1", assetId = "v"))), p))
    assertEquals(3000L, SceneLogic.introDurationMs(Scene("s"), p))
  }
}

class ProjectJsonTest {
  @Test
  fun roundTripAndUnknownFields() {
    val p = Project(
      id = "p1",
      name = "Test",
      assets = listOf(Asset("a", AssetKind.VIDEO, "a.mp4", 1080, 1920, 5000, prep = VideoPrep(sourceFile = "a-src.mp4"))),
      scenes = listOf(
        Scene(
          id = "s",
          layers = listOf(
            Layer("l1", assetId = "a", motion = Motion(keyframes = listOf(KeyframeTrack(AnimProperty.X, listOf(Keyframe(0f, 0f), Keyframe(1f, 0.1f)))))),
            Layer("l2", type = LayerType.TEXT, text = TextSpec("{time}")),
          ),
          timeWindow = TimeWindow(60, 120),
        ),
      ),
    )
    val text = ProjectJson.encode(p)
    assertEquals(p, ProjectJson.decode(text))
    // Files from newer versions with extra fields and unknown enum values still load.
    val newer = text.replace("\"name\":\"Test\"", "\"name\":\"Test\",\"futureThing\":42")
      .replace("\"fit\":\"COVER\"", "\"fit\":\"SOMETHING_NEW\"")
    val loaded = ProjectJson.decode(newer)
    assertEquals("Test", loaded.name)
    assertEquals(Fit.COVER, loaded.scenes[0].layers[0].fit)
  }

  @Test
  fun minimalFileUsesDefaults() {
    val p = ProjectJson.decode("""{"id":"x"}""")
    assertEquals("Untitled", p.name)
    assertEquals(1, p.scenes.size)
    assertEquals(30, p.settings.fpsCap)
  }
}
