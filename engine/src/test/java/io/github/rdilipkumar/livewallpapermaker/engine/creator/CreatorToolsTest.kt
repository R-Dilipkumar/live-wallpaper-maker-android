package io.github.rdilipkumar.livewallpapermaker.engine.creator

import io.github.rdilipkumar.livewallpapermaker.engine.model.Conditions
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.SceneLogic
import io.github.rdilipkumar.livewallpapermaker.engine.model.ScreenFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.random.Random

class CreatorToolsTest {

  @Test
  fun motionPhotoVideoIsFound() {
    val jpeg = ByteArray(5000) { (it * 31).toByte() }
    // MP4 starts with a box: size, "ftyp", brand.
    val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray() + ByteArray(3000) { 1 }
    val combined = jpeg + mp4
    assertEquals(jpeg.size, MotionPhoto.findVideoOffset(combined))
    assertEquals(-1, MotionPhoto.findVideoOffset(jpeg))
    // A HEIC photo starts with its own ftyp box: that one must be ignored.
    val heic = byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(4000)
    assertEquals(-1, MotionPhoto.findVideoOffset(heic))
    assertEquals(heic.size, MotionPhoto.findVideoOffset(heic + mp4))
    // "ftyp" text with a silly size is not a video.
    val fake = jpeg + byteArrayOf(0x7F, 0, 0, 0) + "ftypmp42".toByteArray() + ByteArray(3000)
    assertEquals(-1, MotionPhoto.findVideoOffset(fake))
  }

  @Test
  fun stickerSheetKeepsFramesAndTransparency() {
    val pal = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
    val w = TestGifWriter(10, 8, pal)
    for (i in 0 until 5) w.frame(IntArray(80) { if (it % 10 < 5) i % 3 else 3 }, delayCs = 10, transparent = 3, disposal = 2)
    val gif = w.bytes()
    assertTrue(StickerSheet.hasTransparency(gif))
    val sheet = StickerSheet.build(gif)
    assertEquals(5, sheet.info.delays.size)
    assertEquals(3, sheet.info.cols)
    assertEquals(2, sheet.info.rows)
    assertEquals(10, sheet.info.frameW)
    assertEquals(3 * (10 + 2) + 2, sheet.width)
    // First cell: left half red, right half transparent.
    val p0 = sheet.pixels[2 * sheet.width + 2]
    val p1 = sheet.pixels[2 * sheet.width + 2 + 7]
    assertEquals(0xFFFF0000.toInt(), p0)
    assertEquals(0, p1 ushr 24)
    // Opaque GIF has no transparency.
    val opaque = TestGifWriter(4, 4, pal).frame(IntArray(16) { 1 }).bytes()
    assertFalse(StickerSheet.hasTransparency(opaque))
  }

  @Test
  fun longStickersAreThinnedButKeepTheirSpeed() {
    val pal = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
    val w = TestGifWriter(4, 4, pal)
    repeat(250) { w.frame(IntArray(16) { it % 4 }, delayCs = 4, transparent = 3) }
    val sheet = StickerSheet.build(w.bytes(), maxFrames = 120)
    assertTrue(sheet.info.delays.size <= 120)
    assertEquals(250 * 40, sheet.info.delays.sum())
  }

  @Test
  fun bigStickersShrinkToFitTheSheet() {
    val pal = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
    val w = TestGifWriter(600, 400, pal)
    repeat(16) { w.frame(IntArray(600 * 400) { i -> i % 2 }, realLzw = false) }
    val sheet = StickerSheet.build(w.bytes(), maxSide = 2048)
    assertTrue(sheet.width <= 2048 && sheet.height <= 2048)
    assertEquals(600f / 400f, sheet.info.frameW.toFloat() / sheet.info.frameH, 0.02f)
  }

  @Test
  fun inpaintFillsTheHoleFromItsSurroundings() {
    val w = 64
    val h = 48
    // Left half dark blue, right half orange; a hole in the middle.
    val px = IntArray(w * h) { i -> if (i % w < w / 2) 0xFF102040.toInt() else 0xFFFF8000.toInt() }
    val hole = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; x in 20..44 && y in 10..38 }
    for (i in px.indices) if (hole[i]) px[i] = 0xFFFF00FF.toInt() // garbage
    Inpaint.fill(px, w, h, hole)
    // No magenta left; left side of the hole leans blue, right side leans orange.
    assertTrue(px.none { it == 0xFFFF00FF.toInt() })
    val left = px[24 * w + 21]
    val right = px[24 * w + 43]
    assertTrue((left and 0xFF) > (right and 0xFF)) // more blue on the left
    assertTrue(((right shr 16) and 0xFF) > ((left shr 16) and 0xFF)) // more red on the right
    // Known pixels are untouched.
    assertEquals(0xFF102040.toInt(), px[5 * w + 2])
    val grown = Inpaint.dilate(hole, w, h, 3)
    assertTrue(grown[24 * w + 17])
    assertFalse(grown[24 * w + 15])
  }

  @Test
  fun gifWriterOutputDecodesBack() {
    val w = 40
    val h = 30
    val rnd = Random(3)
    val frames = List(4) { f -> IntArray(w * h) { i -> 0xFF000000.toInt() or ((i % w * 6) shl 16) or ((i / w * 8) shl 8) or ((f * 60 + rnd.nextInt(10)) and 0xFF) } }
    val palette = GifWriter.medianCut(frames.flatMap { it.asList() }.toIntArray())
    val out = ByteArrayOutputStream()
    val writer = GifWriter(out, w, h, palette)
    frames.forEach { writer.frame(it, 80) }
    writer.finish()
    val d = GifStreamDecoder(out.toByteArray())
    assertEquals(w, d.width)
    val back = IntArray(w * h)
    var n = 0
    var error = 0.0
    while (d.nextFrame(back) >= 0) {
      for (i in back.indices) {
        val a = frames[n][i]
        val b = back[i]
        error += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
      }
      n++
    }
    assertEquals(4, n)
    assertTrue("average error ${error / (n * w * h * 3)}", error / (n * w * h * 3) < 14.0)
    assertEquals(listOf(80, 80, 80, 80), d.scanDelays())
  }

  @Test
  fun lockAndBatteryRules() {
    fun s(id: String, on: ScreenFilter = ScreenFilter.ANY, battery: Int = 0) =
      Scene(id, showOn = on, batteryBelow = battery, layers = listOf(Layer("l$id", type = LayerType.GRADIENT)))
    val p = Project("p", scenes = listOf(s("home", ScreenFilter.HOME_SCREEN), s("lock", ScreenFilter.LOCK_SCREEN), s("low", battery = 15)))
    assertEquals(listOf("lock"), SceneLogic.eligibleScenes(p, Conditions(locked = true)).map { it.id })
    assertEquals(listOf("home"), SceneLogic.eligibleScenes(p, Conditions(locked = false)).map { it.id })
    assertEquals(listOf("home", "low"), SceneLogic.eligibleScenes(p, Conditions(battery = 10)).map { it.id })
    // Only a lock-screen scene and we're on the home screen: fall back instead of showing nothing.
    val onlyLock = Project("q", scenes = listOf(s("lock", ScreenFilter.LOCK_SCREEN)))
    assertEquals(listOf("lock"), SceneLogic.eligibleScenes(onlyLock, Conditions(locked = false)).map { it.id })
  }

  @Test
  fun dayTintFollowsTheClock() {
    val noon = SceneLogic.dayTint(12 * 60, 1f)
    val night = SceneLogic.dayTint(2 * 60, 1f)
    val evening = SceneLogic.dayTint(17 * 60 + 30, 1f)
    assertTrue(noon.amount < 0.1f)
    assertTrue(night.b > night.r && night.amount > 0.3f && night.dim > 0.2f)
    assertTrue(evening.r > evening.b)
    assertEquals(0f, SceneLogic.dayTint(2 * 60, 0f).amount, 0f)
    // Smooth: one minute apart barely changes.
    val a = SceneLogic.dayTint(18 * 60, 1f)
    val b = SceneLogic.dayTint(18 * 60 + 1, 1f)
    assertTrue(abs(a.amount - b.amount) < 0.01f)
    assertFalse(SceneLogic.tintChanged(a, b))
  }
}
