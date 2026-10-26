package io.github.rdilipkumar.livewallpapermaker.engine.creator

import java.io.ByteArrayOutputStream

/** Builds small GIF files for tests, with either real LZW compression or "literal only" codes. */
class TestGifWriter(private val width: Int, private val height: Int, private val gct: IntArray?) {
  private val out = ByteArrayOutputStream()

  init {
    out.write("GIF89a".toByteArray(Charsets.US_ASCII))
    short(width)
    short(height)
    if (gct != null) {
      val bits = bitsFor(gct.size)
      out.write(0x80 or (7 shl 4) or (bits - 1))
    } else out.write(0)
    out.write(0)
    out.write(0)
    gct?.let { table(it, bitsFor(it.size)) }
  }

  fun frame(
    indices: IntArray,
    left: Int = 0,
    top: Int = 0,
    fw: Int = width,
    fh: Int = height,
    delayCs: Int = 10,
    disposal: Int = 0,
    transparent: Int = -1,
    interlaced: Boolean = false,
    localPalette: IntArray? = null,
    realLzw: Boolean = true,
  ): TestGifWriter {
    out.write(0x21); out.write(0xF9); out.write(4)
    out.write((disposal shl 2) or (if (transparent >= 0) 1 else 0))
    short(delayCs)
    out.write(if (transparent >= 0) transparent else 0)
    out.write(0)
    out.write(0x2C)
    short(left); short(top); short(fw); short(fh)
    var packed = if (interlaced) 0x40 else 0
    if (localPalette != null) packed = packed or 0x80 or (bitsFor(localPalette.size) - 1)
    out.write(packed)
    localPalette?.let { table(it, bitsFor(it.size)) }
    val ordered = if (interlaced) interlace(indices, fw, fh) else indices
    val paletteSize = (localPalette ?: gct)!!.size
    val minCode = maxOf(2, bitsFor(paletteSize))
    if (realLzw) lzw(ordered, minCode) else literals(ordered, minCode)
    return this
  }

  fun bytes(trailer: Boolean = true): ByteArray {
    if (trailer) out.write(0x3B)
    return out.toByteArray()
  }

  private fun interlace(src: IntArray, w: Int, h: Int): IntArray {
    val rows = ArrayList<Int>()
    for (y in 0 until h step 8) rows += y
    for (y in 4 until h step 8) rows += y
    for (y in 2 until h step 4) rows += y
    for (y in 1 until h step 2) rows += y
    val res = IntArray(src.size)
    rows.forEachIndexed { i, y -> System.arraycopy(src, y * w, res, i * w, w) }
    return res
  }

  private fun bitsFor(n: Int): Int {
    var b = 1
    while ((1 shl b) < n) b++
    return b
  }

  private fun table(colors: IntArray, bits: Int) {
    for (i in 0 until (1 shl bits)) {
      val c = colors.getOrElse(i) { 0 }
      out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF)
    }
  }

  private fun short(v: Int) {
    out.write(v and 0xFF)
    out.write((v shr 8) and 0xFF)
  }

  private class BitWriter(private val sink: ByteArrayOutputStream) {
    private val block = ByteArrayOutputStream()
    private var acc = 0
    private var n = 0
    fun write(code: Int, size: Int) {
      acc = acc or (code shl n)
      n += size
      while (n >= 8) {
        byte(acc and 0xFF)
        acc = acc ushr 8
        n -= 8
      }
    }
    private fun byte(b: Int) {
      block.write(b)
      if (block.size() == 255) flushBlock()
    }
    private fun flushBlock() {
      if (block.size() == 0) return
      sink.write(block.size())
      sink.write(block.toByteArray())
      block.reset()
    }
    fun finish() {
      if (n > 0) byte(acc and 0xFF)
      flushBlock()
      sink.write(0)
    }
  }

  /** Every pixel as its own code, with a clear code before the table would grow. */
  private fun literals(px: IntArray, minCode: Int) {
    out.write(minCode)
    val clear = 1 shl minCode
    val size = minCode + 1
    val chunk = clear - 2
    val bw = BitWriter(out)
    var i = 0
    while (i < px.size) {
      bw.write(clear, size)
      val end = minOf(px.size, i + chunk)
      while (i < end) bw.write(px[i++], size)
    }
    bw.write(clear + 1, size)
    bw.finish()
  }

  /** Standard GIF LZW (same scheme as the encoder in the original design document). */
  private fun lzw(px: IntArray, minCode: Int) {
    out.write(minCode)
    val clear = 1 shl minCode
    val eoi = clear + 1
    val dict = HashMap<Int, Int>()
    var codeSize = minCode + 1
    var next = eoi + 1
    val bw = BitWriter(out)
    bw.write(clear, codeSize)
    if (px.isEmpty()) {
      bw.write(eoi, codeSize); bw.finish(); return
    }
    var prefix = px[0]
    for (i in 1 until px.size) {
      val k = px[i]
      val key = (prefix shl 8) or k
      val found = dict[key]
      if (found != null) {
        prefix = found
        continue
      }
      bw.write(prefix, codeSize)
      if (next < 4096) {
        dict[key] = next
        if (next > (1 shl codeSize) - 1 && codeSize < 12) codeSize++
        next++
      } else {
        bw.write(clear, codeSize)
        dict.clear()
        codeSize = minCode + 1
        next = eoi + 1
      }
      prefix = k
    }
    bw.write(prefix, codeSize)
    bw.write(eoi, codeSize)
    bw.finish()
  }
}
