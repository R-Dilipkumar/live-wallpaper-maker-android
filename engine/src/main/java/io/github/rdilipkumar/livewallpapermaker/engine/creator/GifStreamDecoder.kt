package io.github.rdilipkumar.livewallpapermaker.engine.creator

/**
 * Streaming GIF87a/89a decoder: one fully composited frame at a time into a reused IntArray (ARGB).
 * Pure Kotlin so it can be unit-tested on the JVM.
 *
 * Based on the decoder in the original design document, with its bugs fixed:
 * the 6-byte "GIF89a" header is skipped, short delays follow browser rules (<= 10 ms -> 100 ms),
 * disposal 2 clears to transparent, tiny interlaced frames are handled, and frames are not all kept in memory.
 */
class GifStreamDecoder(private val data: ByteArray) {
  var width = 0; private set
  var height = 0; private set
  private var gct: IntArray? = null
  private var bgIndex = 0
  private var firstBlock = 0

  private var pos = 0
  private val canvas: IntArray
  private var backup: IntArray? = null
  private var pendingDisposal = 0
  private var lastRect = IntArray(4)

  // Graphic control state for the next image.
  private var delayMs = 100
  private var transparentIndex = -1
  private var disposal = 0

  // LZW work buffers, reused across frames.
  private val prefix = ShortArray(4096)
  private val suffix = ByteArray(4096)
  private val pixelStack = ByteArray(4097)
  private var indices = ByteArray(0)

  init {
    require(data.size >= 13) { "Not a GIF (too short)" }
    val sig = String(data, 0, 6, Charsets.US_ASCII)
    require(sig == "GIF87a" || sig == "GIF89a") { "Not a GIF" }
    pos = 6
    width = u16()
    height = u16()
    require(width in 1..8192 && height in 1..8192) { "Bad GIF size ${width}x$height" }
    val packed = u8()
    bgIndex = u8()
    u8() // pixel aspect ratio
    if (packed and 0x80 != 0) gct = readColorTable(1 shl ((packed and 0x07) + 1))
    firstBlock = pos
    canvas = IntArray(width * height)
  }

  /** Delays (ms) of every frame, without decoding pixels. */
  fun scanDelays(): List<Int> {
    val saved = pos
    pos = firstBlock
    val out = ArrayList<Int>()
    var delay = 100
    loop@ while (pos < data.size) {
      when (u8()) {
        0x21 -> {
          val label = u8()
          if (label == 0xF9 && pos + 5 < data.size) {
            u8()
            u8()
            delay = normalizeDelay(u16())
            u8()
            u8()
          } else skipSubBlocks()
        }
        0x2C -> {
          pos += 8
          val p = u8()
          if (p and 0x80 != 0) pos += 3 * (1 shl ((p and 0x07) + 1))
          u8() // LZW min code size
          skipSubBlocks()
          out += delay
          delay = 100
        }
        else -> break@loop
      }
    }
    pos = saved
    return out
  }

  fun rewind() {
    pos = firstBlock
    canvas.fill(0)
    backup = null
    pendingDisposal = 0
    delayMs = 100
    transparentIndex = -1
    disposal = 0
  }

  /**
   * Decodes the next frame into [out] (size width*height, ARGB, transparent = 0).
   * Returns the frame's delay in ms, or -1 when there are no more frames.
   */
  fun nextFrame(out: IntArray): Int {
    applyPendingDisposal()
    while (pos < data.size) {
      when (u8()) {
        0x21 -> {
          val label = u8()
          if (label == 0xF9 && pos + 5 < data.size) {
            u8() // block size (4)
            val p = u8()
            disposal = (p shr 2) and 0x07
            val hasTransparency = p and 0x01 != 0
            delayMs = normalizeDelay(u16())
            val t = u8()
            transparentIndex = if (hasTransparency) t else -1
            u8() // terminator
          } else skipSubBlocks()
        }
        0x2C -> {
          val delay = readImage()
          canvas.copyInto(out)
          return delay
        }
        0x3B -> return -1
        else -> return -1
      }
    }
    return -1
  }

  private fun readImage(): Int {
    val left = u16()
    val top = u16()
    val fw = u16()
    val fh = u16()
    val p = u8()
    val interlaced = p and 0x40 != 0
    val palette = if (p and 0x80 != 0) readColorTable(1 shl ((p and 0x07) + 1)) else (gct ?: GREYS)
    if (disposal == 3) backup = canvas.copyOf()
    val count = fw * fh
    if (indices.size < count) indices = ByteArray(count)
    val decoded = decodeLzw(count)
    composite(left, top, fw, fh, interlaced, palette, decoded)
    pendingDisposal = disposal
    lastRect[0] = left; lastRect[1] = top; lastRect[2] = fw; lastRect[3] = fh
    val delay = delayMs
    delayMs = 100
    transparentIndex = -1
    disposal = 0
    return delay
  }

  private fun applyPendingDisposal() {
    when (pendingDisposal) {
      2 -> {
        val (l, t, w, h) = lastRect.toList()
        for (y in maxOf(0, t) until minOf(height, t + h)) {
          val row = y * width
          for (x in maxOf(0, l) until minOf(width, l + w)) canvas[row + x] = 0
        }
      }
      3 -> backup?.copyInto(canvas)
    }
    pendingDisposal = 0
  }

  private fun composite(left: Int, top: Int, fw: Int, fh: Int, interlaced: Boolean, palette: IntArray, decoded: Int) {
    var pass = 1
    var inc = 8
    var iline = 0
    for (y in 0 until fh) {
      var dy = y
      if (interlaced) {
        while (iline >= fh && pass < 4) {
          pass++
          when (pass) {
            2 -> iline = 4
            3 -> { iline = 2; inc = 4 }
            4 -> { iline = 1; inc = 2 }
          }
        }
        dy = iline
        iline += inc
      }
      val destY = top + dy
      if (destY < 0 || destY >= height) continue
      val srcRow = y * fw
      if (srcRow >= decoded) break
      val destRow = destY * width
      for (x in 0 until fw) {
        val destX = left + x
        if (destX < 0 || destX >= width) continue
        val si = srcRow + x
        if (si >= decoded) break
        val ci = indices[si].toInt() and 0xFF
        if (ci != transparentIndex && ci < palette.size) canvas[destRow + destX] = palette[ci]
      }
    }
  }

  /** Decodes one image's LZW data into [indices]; returns how many pixels were produced. */
  private fun decodeLzw(pixelCount: Int): Int {
    val minCodeSize = u8()
    if (minCodeSize !in 1..11) {
      skipSubBlocks()
      return 0
    }
    val clearCode = 1 shl minCodeSize
    val endCode = clearCode + 1
    var codeSize = minCodeSize + 1
    var codeMask = (1 shl codeSize) - 1
    var available = clearCode + 2
    var oldCode = -1
    var first = 0
    for (code in 0 until clearCode) {
      prefix[code] = 0
      suffix[code] = code.toByte()
    }
    var datum = 0
    var bits = 0
    var blockLeft = 0
    var top = 0
    var out = 0
    var ended = false

    while (out < pixelCount) {
      if (top == 0) {
        if (bits < codeSize) {
          if (blockLeft == 0) {
            if (pos >= data.size) break
            blockLeft = u8()
            if (blockLeft == 0) { ended = true; break }
          }
          if (pos >= data.size) break
          datum += u8() shl bits
          bits += 8
          blockLeft--
          continue
        }
        var code = datum and codeMask
        datum = datum ushr codeSize
        bits -= codeSize
        if (code == endCode || code > available) break
        if (code == clearCode) {
          codeSize = minCodeSize + 1
          codeMask = (1 shl codeSize) - 1
          available = clearCode + 2
          oldCode = -1
          continue
        }
        if (oldCode == -1) {
          pixelStack[top++] = suffix[code]
          oldCode = code
          first = code
          continue
        }
        val inCode = code
        if (code == available) {
          pixelStack[top++] = first.toByte()
          code = oldCode
        }
        while (code > clearCode) {
          pixelStack[top++] = suffix[code]
          code = prefix[code].toInt()
        }
        first = suffix[code].toInt() and 0xFF
        pixelStack[top++] = first.toByte()
        if (available < 4096) {
          prefix[available] = oldCode.toShort()
          suffix[available] = first.toByte()
          available++
          if (available and codeMask == 0 && available < 4096) {
            codeSize++
            codeMask += available
          }
        }
        oldCode = inCode
      }
      top--
      indices[out++] = pixelStack[top]
    }
    // Skip whatever is left of this image's data blocks.
    if (!ended) {
      if (blockLeft > 0) pos = minOf(data.size, pos + blockLeft)
      skipSubBlocks()
    }
    return out
  }

  private fun readColorTable(size: Int): IntArray {
    val table = IntArray(size)
    for (i in 0 until size) {
      if (pos + 2 >= data.size) break
      val r = u8()
      val g = u8()
      val b = u8()
      table[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return table
  }

  private fun skipSubBlocks() {
    while (pos < data.size) {
      val n = u8()
      if (n == 0) break
      pos = minOf(data.size, pos + n)
    }
  }

  private fun u8(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else 0
  private fun u16(): Int = u8() or (u8() shl 8)

  companion object {
    /** Browsers show frames with a delay of 0 or 10 ms at 100 ms; matching that keeps GIFs at their intended speed. */
    fun normalizeDelay(delayCs: Int): Int = if (delayCs <= 1) 100 else delayCs * 10

    private val GREYS = IntArray(256) { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }
  }
}
