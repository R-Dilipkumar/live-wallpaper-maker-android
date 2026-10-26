package io.github.rdilipkumar.livewallpapermaker.engine.creator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GifStreamDecoderTest {
  private val red = 0xFFFF0000.toInt()
  private val green = 0xFF00FF00.toInt()
  private val blue = 0xFF0000FF.toInt()
  private val white = 0xFFFFFFFF.toInt()
  private val pal4 = intArrayOf(red, green, blue, white)

  private fun colors(indices: IntArray, pal: IntArray) = IntArray(indices.size) { pal[indices[it]] }

  @Test
  fun readsSizeFromAfterTheHeader() {
    // The original decoder read "GI"/"F8" as the size (18759 x 14406). Make sure that is gone.
    val gif = TestGifWriter(7, 5, pal4).frame(IntArray(35)).bytes()
    val d = GifStreamDecoder(gif)
    assertEquals(7, d.width)
    assertEquals(5, d.height)
  }

  @Test
  fun decodesPixelsAndDelays() {
    val f1 = IntArray(16) { it % 4 }
    val f2 = IntArray(16) { 3 - it % 4 }
    val gif = TestGifWriter(4, 4, pal4).frame(f1, delayCs = 0).frame(f2, delayCs = 5).bytes()
    val d = GifStreamDecoder(gif)
    val out = IntArray(16)
    assertEquals(100, d.nextFrame(out)) // 0 delay -> 100 ms, like browsers
    assertArrayEquals(colors(f1, pal4), out)
    assertEquals(50, d.nextFrame(out))
    assertArrayEquals(colors(f2, pal4), out)
    assertEquals(-1, d.nextFrame(out))
    assertEquals(listOf(100, 50), d.scanDelays())
  }

  @Test
  fun delayRules() {
    assertEquals(100, GifStreamDecoder.normalizeDelay(0))
    assertEquals(100, GifStreamDecoder.normalizeDelay(1))
    assertEquals(20, GifStreamDecoder.normalizeDelay(2))
    assertEquals(70, GifStreamDecoder.normalizeDelay(7))
  }

  @Test
  fun literalOnlyStreamsDecode() {
    val px = IntArray(200) { (it * 7) % 4 }
    val gif = TestGifWriter(20, 10, pal4).frame(px, realLzw = false).bytes()
    val out = IntArray(200)
    GifStreamDecoder(gif).nextFrame(out)
    assertArrayEquals(colors(px, pal4), out)
  }

  @Test
  fun bigPaletteWithDictionaryResets() {
    // 256 colours and noisy pixels push the code size to 12 bits and force table resets.
    val pal = IntArray(256) { (0xFF shl 24) or (it shl 16) or ((255 - it) shl 8) or (it * 3 and 0xFF) }
    val rnd = Random(42)
    val px = IntArray(160 * 120) { if (it % 7 == 0) rnd.nextInt(256) else (it / 13) % 256 }
    val gif = TestGifWriter(160, 120, pal).frame(px).bytes()
    val out = IntArray(px.size)
    GifStreamDecoder(gif).nextFrame(out)
    assertArrayEquals(colors(px, pal), out)
  }

  @Test
  fun transparencyKeepsWhatIsUnderneath() {
    val base = IntArray(4) { 0 } // all red
    val over = intArrayOf(3, 1, 3, 1) // index 3 is transparent in frame 2
    val gif = TestGifWriter(2, 2, pal4).frame(base, disposal = 1).frame(over, transparent = 3).bytes()
    val d = GifStreamDecoder(gif)
    val out = IntArray(4)
    d.nextFrame(out)
    d.nextFrame(out)
    assertArrayEquals(intArrayOf(red, green, red, green), out)
  }

  @Test
  fun disposalTwoClearsToTransparent() {
    val gif = TestGifWriter(2, 2, pal4)
      .frame(intArrayOf(0, 0, 0, 0), disposal = 1)
      .frame(intArrayOf(1), left = 0, top = 0, fw = 1, fh = 1, disposal = 2)
      .frame(intArrayOf(2), left = 1, top = 1, fw = 1, fh = 1)
      .bytes()
    val d = GifStreamDecoder(gif)
    val out = IntArray(4)
    d.nextFrame(out)
    d.nextFrame(out)
    assertArrayEquals(intArrayOf(green, red, red, red), out)
    d.nextFrame(out)
    assertArrayEquals(intArrayOf(0, red, red, blue), out)
  }

  @Test
  fun disposalThreeRestoresPrevious() {
    val gif = TestGifWriter(2, 1, pal4)
      .frame(intArrayOf(0, 0), disposal = 1)
      .frame(intArrayOf(2, 2), disposal = 3)
      .frame(intArrayOf(1), left = 1, top = 0, fw = 1, fh = 1)
      .bytes()
    val d = GifStreamDecoder(gif)
    val out = IntArray(2)
    d.nextFrame(out)
    d.nextFrame(out)
    assertArrayEquals(intArrayOf(blue, blue), out)
    d.nextFrame(out)
    assertArrayEquals(intArrayOf(red, green), out)
  }

  @Test
  fun interlacedFramesOfManyHeights() {
    for (h in listOf(1, 2, 3, 5, 8, 9, 17)) {
      val px = IntArray(3 * h) { (it / 3) % 4 } // each row its own colour
      val gif = TestGifWriter(3, h, pal4).frame(px, interlaced = true).bytes()
      val out = IntArray(px.size)
      GifStreamDecoder(gif).nextFrame(out)
      assertArrayEquals("height $h", colors(px, pal4), out)
    }
  }

  @Test
  fun localPaletteIsUsed() {
    val local = intArrayOf(white, blue)
    val gif = TestGifWriter(2, 1, pal4).frame(intArrayOf(1, 0), localPalette = local).bytes()
    val out = IntArray(2)
    GifStreamDecoder(gif).nextFrame(out)
    assertArrayEquals(intArrayOf(blue, white), out)
  }

  @Test
  fun rewindStartsOver() {
    val gif = TestGifWriter(1, 1, pal4).frame(intArrayOf(1)).frame(intArrayOf(2)).bytes()
    val d = GifStreamDecoder(gif)
    val out = IntArray(1)
    d.nextFrame(out); d.nextFrame(out)
    assertEquals(-1, d.nextFrame(out))
    d.rewind()
    d.nextFrame(out)
    assertEquals(green, out[0])
  }

  @Test
  fun truncatedFileDoesNotCrash() {
    val full = TestGifWriter(16, 16, pal4).frame(IntArray(256) { it % 4 }).frame(IntArray(256) { 3 }).bytes()
    for (cut in listOf(14, 30, 40, full.size / 2, full.size - 3)) {
      val d = GifStreamDecoder(full.copyOf(cut))
      val out = IntArray(256)
      var frames = 0
      while (d.nextFrame(out) >= 0 && frames < 10) frames++
      assertTrue(frames <= 2)
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsNonGif() {
    GifStreamDecoder("this is not a gif at all".toByteArray())
  }

  @Test
  fun noGlobalPaletteFallsBackToGreys() {
    val gif = TestGifWriter(2, 1, null).frame(intArrayOf(0, 1), localPalette = intArrayOf(red, green)).bytes()
    val out = IntArray(2)
    GifStreamDecoder(gif).nextFrame(out)
    assertArrayEquals(intArrayOf(red, green), out)
  }
}
