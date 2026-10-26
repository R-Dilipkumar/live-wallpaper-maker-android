package io.github.rdilipkumar.livewallpapermaker.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rdilipkumar.livewallpapermaker.engine.creator.BakeOptions
import io.github.rdilipkumar.livewallpapermaker.engine.creator.LoopBaker
import io.github.rdilipkumar.livewallpapermaker.engine.creator.Remuxer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LoopStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class BakerDeviceTest {
  private val dir = TestFiles.dir("bake-test")
  private val cache = TestFiles.dir("bake-cache")

  private fun bake(name: String, options: BakeOptions, gif: Boolean = false, out: String = "out.mp4"): Pair<File, TestFiles.Mp4Info> {
    val src = TestFiles.asset(name, dir)
    val file = File(dir, out)
    var lastProgress = 0f
    val res = LoopBaker.bake(src, gif, options, file, cache, onProgress = { lastProgress = it })
    assertEquals(1f, lastProgress)
    val info = TestFiles.inspect(file)
    assertEquals(res.width, info.width)
    assertEquals(res.height, info.height)
    return file to info
  }

  @Test
  fun rotatedPhoneVideoComesOutPortrait() {
    val (_, info) = bake("portrait_rot90.mp4", BakeOptions(loop = LoopStyle.NONE))
    assertTrue("expected portrait, got ${info.width}x${info.height}", info.height > info.width)
    assertEquals(0, info.rotation)
    assertEquals(368, info.width) // 360 rounded to a multiple of 16
    assertEquals(640, info.height)
  }

  @Test
  fun sixtyFpsIsReducedToThirtyWithoutSpeedingUp() {
    // The original code played long clips up to 2x too fast. Duration must match the trim.
    val (_, info) = bake("fps60_7s.mp4", BakeOptions(loop = LoopStyle.NONE, trimStartMs = 1000, trimEndMs = 6000))
    assertTrue("duration ${info.durationMs}", info.durationMs in 4800..5050)
    assertTrue("frames ${info.frames}", info.frames in 145..152)
  }

  @Test
  fun crossfadeLoopIsSeamless() {
    val (cross, ci) = bake("fps60_7s.mp4", BakeOptions(loop = LoopStyle.CROSSFADE, loopWindowMs = 600), out = "cross.mp4")
    val (plain, pi) = bake("fps60_7s.mp4", BakeOptions(loop = LoopStyle.NONE), out = "plain.mp4")
    // The crossfade skips its first window, so it is shorter.
    assertTrue("${ci.durationMs} vs ${pi.durationMs}", ci.durationMs in (pi.durationMs - 700)..(pi.durationMs - 500))
    val step = 33_333L
    val crossSeam = TestFiles.diff(TestFiles.frameAt(cross, ci.durationMs * 1000), TestFiles.frameAt(cross, 0))
    val plainSeam = TestFiles.diff(TestFiles.frameAt(plain, pi.durationMs * 1000), TestFiles.frameAt(plain, 0))
    val normalStep = TestFiles.diff(TestFiles.frameAt(plain, 3_000_000), TestFiles.frameAt(plain, 3_000_000 + step))
    assertTrue("cross seam $crossSeam vs plain seam $plainSeam (normal step $normalStep)", crossSeam < plainSeam * 0.6)
    assertTrue("cross seam $crossSeam vs normal step $normalStep", crossSeam < normalStep * 3 + 4)
  }

  @Test
  fun everyLoopStyleProducesAPlayableFile() {
    for (style in LoopStyle.entries) {
      val (_, info) = bake("portrait_rot90.mp4", BakeOptions(loop = style), out = "style-${style.name}.mp4")
      assertTrue("$style frames ${info.frames}", info.frames > 20)
      if (style == LoopStyle.PING_PONG) assertTrue("ping-pong ${info.durationMs}", info.durationMs in 3700..4100)
    }
  }

  @Test
  fun gifBecomesThirtyFpsVideo() {
    val (_, info) = bake("anim.gif", BakeOptions(loop = LoopStyle.NONE), gif = true)
    assertEquals(160, info.width)
    assertEquals(128, info.height) // 120 rounded to 16
    assertTrue("duration ${info.durationMs}", info.durationMs in 2900..3000)
    assertTrue("frames ${info.frames}", info.frames in 88..91)
    val (_, smooth) = bake("anim.gif", BakeOptions(loop = LoopStyle.CROSSFADE, smoothGif = true), gif = true, out = "smooth.mp4")
    assertTrue(smooth.frames > 60)
  }

  @Test
  fun transparentGifAndHdrAndTinyClips() {
    bake("transparent.gif", BakeOptions(loop = LoopStyle.NONE), gif = true, out = "t.mp4")
    // 10-bit HDR: either some decoder on this device handles it, or it fails with a readable message.
    try {
      val (_, hdr) = bake("hdr_hlg.mp4", BakeOptions(loop = LoopStyle.NONE), out = "hdr.mp4")
      assertTrue(hdr.frames > 30)
    } catch (e: java.io.IOException) {
      assertTrue(e.message!!, e.message!!.contains("can't decode") || e.message!!.contains("stopped working"))
      assertFalse(File(dir, "hdr.mp4").exists())
    }
    // Half a second is too short for a crossfade: it falls back to a plain loop instead of failing.
    val (_, tiny) = bake("tiny.mp4", BakeOptions(loop = LoopStyle.CROSSFADE, loopWindowMs = 2000), out = "tiny-out.mp4")
    assertTrue(tiny.frames in 10..16)
  }

  @Test
  fun brokenFilesFailCleanly() {
    for ((name, gif) in listOf("corrupt.mp4" to false, "fake.gif" to true, "photo.jpg" to false)) {
      val out = File(dir, "broken-$name.mp4")
      try {
        LoopBaker.bake(TestFiles.asset(name, dir), gif, BakeOptions(), out, cache)
        fail("$name should not bake")
      } catch (e: Exception) {
        assertFalse("$name left a partial file", out.exists())
      }
    }
  }

  @Test
  fun cancelStopsAndCleansUp() {
    val out = File(dir, "cancel.mp4")
    var calls = 0
    try {
      LoopBaker.bake(TestFiles.asset("fps60_7s.mp4", dir), false, BakeOptions(), out, cache, isCancelled = { ++calls > 20 })
      fail("should cancel")
    } catch (e: CancellationException) {
      assertFalse(out.exists())
    }
  }

  @Test
  fun remuxKeepsRotationAndTrims() {
    val src = TestFiles.asset("fps60_7s.mp4", dir)
    val out = File(dir, "src-copy.mp4")
    val offset = Remuxer.copyVideo(TestFiles.target, android.net.Uri.fromFile(src), 2000, 4000, out)
    assertTrue("offset $offset", offset in 0..2000)
    val info = TestFiles.inspect(out)
    assertTrue("copy ${info.durationMs}", info.durationMs in 2000..4600)
    val rotated = File(dir, "rot-copy.mp4")
    Remuxer.copyVideo(TestFiles.target, android.net.Uri.fromFile(TestFiles.asset("portrait_rot90.mp4", dir)), 0, 0, rotated)
    assertEquals(90, TestFiles.inspect(rotated).rotation)
    val probe = Remuxer.probe(TestFiles.target, android.net.Uri.fromFile(rotated))
    assertTrue(probe.height > probe.width)
  }
}
