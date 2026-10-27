package io.github.rdilipkumar.livewallpapermaker.engine.creator

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Animated GIF writer for "Export as GIF". The LZW compressor is the one from the original design
 * document (it was correct). Unlike that document, the palette is built from several frames and
 * pixels use ordered (Bayer) dithering, which stays still from frame to frame instead of shimmering.
 */
class GifWriter(private val out: OutputStream, private val width: Int, private val height: Int, private val palette: IntArray) {
  private val cache = IntArray(32768) { -1 }
  private val indices = ByteArray(width * height)

  init {
    require(palette.size == 256) { "Palette must have 256 colours" }
    out.write("GIF89a".toByteArray(Charsets.US_ASCII))
    short(width)
    short(height)
    out.write(0xF7) // global colour table, 8 bits, 256 entries
    out.write(0)
    out.write(0)
    for (c in palette) {
      out.write((c shr 16) and 0xFF)
      out.write((c shr 8) and 0xFF)
      out.write(c and 0xFF)
    }
    // NETSCAPE2.0: loop forever.
    out.write(0x21); out.write(0xFF); out.write(11)
    out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
    out.write(3); out.write(1); short(0); out.write(0)
  }

  /** Adds one frame ([argb] is width*height, opaque) shown for [delayMs]. */
  fun frame(argb: IntArray, delayMs: Int) {
    out.write(0x21); out.write(0xF9); out.write(4)
    out.write(0x04)
    short((delayMs / 10).coerceAtLeast(2))
    out.write(0); out.write(0)
    out.write(0x2C)
    short(0); short(0); short(width); short(height)
    out.write(0)
    for (y in 0 until height) {
      val row = y * width
      for (x in 0 until width) {
        val p = argb[row + x]
        val t = BAYER[(y and 3) * 4 + (x and 3)]
        val r = (((p shr 16) and 0xFF) + t).coerceIn(0, 255)
        val g = (((p shr 8) and 0xFF) + t).coerceIn(0, 255)
        val b = ((p and 0xFF) + t).coerceIn(0, 255)
        indices[row + x] = nearest(r, g, b).toByte()
      }
    }
    lzw(indices)
  }

  fun finish() {
    out.write(0x3B)
    out.flush()
  }

  private fun nearest(r: Int, g: Int, b: Int): Int {
    val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
    val cached = cache[key]
    if (cached >= 0) return cached
    var best = 0
    var bestDist = Int.MAX_VALUE
    for (i in palette.indices) {
      val c = palette[i]
      val dr = r - ((c shr 16) and 0xFF)
      val dg = g - ((c shr 8) and 0xFF)
      val db = b - (c and 0xFF)
      val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
      if (d < bestDist) {
        bestDist = d
        best = i
        if (d == 0) break
      }
    }
    cache[key] = best
    return best
  }

  private fun lzw(pixels: ByteArray) {
    val minCodeSize = 8
    out.write(minCodeSize)
    val clearCode = 1 shl minCodeSize
    val eoiCode = clearCode + 1
    val hashSize = 5003
    val hashKeys = IntArray(hashSize) { -1 }
    val hashValues = IntArray(hashSize)
    var codeSize = minCodeSize + 1
    var maxCode = (1 shl codeSize) - 1
    var nextCode = eoiCode + 1
    val block = ByteArrayOutputStream(256)
    var bitBuffer = 0
    var bitCount = 0

    fun emit(code: Int) {
      bitBuffer = bitBuffer or (code shl bitCount)
      bitCount += codeSize
      while (bitCount >= 8) {
        block.write(bitBuffer and 0xFF)
        bitBuffer = bitBuffer ushr 8
        bitCount -= 8
        if (block.size() == 255) {
          out.write(255)
          out.write(block.toByteArray())
          block.reset()
        }
      }
    }

    emit(clearCode)
    var prefix = pixels[0].toInt() and 0xFF
    for (i in 1 until pixels.size) {
      val k = pixels[i].toInt() and 0xFF
      val key = (prefix shl 8) or k
      var h = ((k shl 4) xor prefix) % hashSize
      if (h < 0) h += hashSize
      var found = false
      while (hashKeys[h] != -1) {
        if (hashKeys[h] == key) {
          prefix = hashValues[h]
          found = true
          break
        }
        h = (h + 1) % hashSize
      }
      if (found) continue
      emit(prefix)
      if (nextCode < 4096) {
        hashKeys[h] = key
        hashValues[h] = nextCode
        if (nextCode > maxCode && codeSize < 12) {
          codeSize++
          maxCode = (1 shl codeSize) - 1
        }
        nextCode++
      } else {
        emit(clearCode)
        hashKeys.fill(-1)
        codeSize = minCodeSize + 1
        maxCode = (1 shl codeSize) - 1
        nextCode = eoiCode + 1
      }
      prefix = k
    }
    emit(prefix)
    emit(eoiCode)
    if (bitCount > 0) block.write(bitBuffer and 0xFF)
    if (block.size() > 0) {
      out.write(block.size())
      out.write(block.toByteArray())
    }
    out.write(0)
  }

  private fun short(v: Int) {
    out.write(v and 0xFF)
    out.write((v shr 8) and 0xFF)
  }

  companion object {
    // 4x4 Bayer matrix scaled to about ±12 levels.
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5).map { (it - 7.5f) * 1.6f }.map { it.toInt() }.toIntArray()

    /**
     * Median-cut palette of 256 colours from [samples] (ARGB). Boxes are split along their widest channel
     * at the median, using a counting sort (no boxing), until there are 256 boxes.
     */
    fun medianCut(samples: IntArray, colors: Int = 256): IntArray {
      val px = samples.copyOf()
      class Box(val start: Int, val end: Int) {
        var range = 0
        var channel = 0
        init {
          var rMin = 255; var rMax = 0; var gMin = 255; var gMax = 0; var bMin = 255; var bMax = 0
          for (i in start until end) {
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (r < rMin) rMin = r; if (r > rMax) rMax = r
            if (g < gMin) gMin = g; if (g > gMax) gMax = g
            if (b < bMin) bMin = b; if (b > bMax) bMax = b
          }
          val rs = rMax - rMin; val gs = gMax - gMin; val bs = bMax - bMin
          range = maxOf(rs, gs, bs)
          channel = when (range) { gs -> 8; rs -> 16; else -> 0 }
        }
      }
      val boxes = ArrayList<Box>()
      if (px.isNotEmpty()) boxes += Box(0, px.size)
      val counts = IntArray(256)
      val tmp = IntArray(px.size)
      while (boxes.size < colors) {
        val box = boxes.filter { it.end - it.start > 1 && it.range > 0 }.maxByOrNull { it.range } ?: break
        boxes.remove(box)
        // Counting sort of the box by its widest channel.
        counts.fill(0)
        for (i in box.start until box.end) counts[(px[i] shr box.channel) and 0xFF]++
        var acc = 0
        for (v in 0 until 256) { val c = counts[v]; counts[v] = acc; acc += c }
        for (i in box.start until box.end) {
          val v = (px[i] shr box.channel) and 0xFF
          tmp[counts[v]++] = px[i]
        }
        System.arraycopy(tmp, 0, px, box.start, box.end - box.start)
        val mid = (box.start + box.end) ushr 1
        boxes += Box(box.start, mid)
        boxes += Box(mid, box.end)
      }
      val palette = IntArray(colors)
      for (i in 0 until colors) {
        val b = boxes.getOrNull(i)
        palette[i] = if (b == null) 0 else {
          var r = 0L; var g = 0L; var bl = 0L
          for (j in b.start until b.end) {
            val c = px[j]
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF
          }
          val n = (b.end - b.start).coerceAtLeast(1)
          ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
        }
      }
      return palette
    }
  }
}
