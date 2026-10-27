package io.github.rdilipkumar.livewallpapermaker.engine.creator

import io.github.rdilipkumar.livewallpapermaker.engine.model.StickerInfo
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a transparent animated GIF into one sheet image (all frames in a grid) that the wallpaper
 * can animate cheaply while keeping transparency. Pure Kotlin; the caller saves [pixels] as a PNG.
 */
class StickerSheet(val pixels: IntArray, val width: Int, val height: Int, val info: StickerInfo) {

  companion object {
    /** True when any frame of the GIF has transparent pixels. */
    fun hasTransparency(gif: ByteArray, maxFramesToCheck: Int = 30): Boolean {
      val d = GifStreamDecoder(gif)
      val frame = IntArray(d.width * d.height)
      var n = 0
      while (n < maxFramesToCheck && d.nextFrame(frame) >= 0) {
        // Sample the frame; a transparent background usually covers a lot of pixels.
        val step = max(1, frame.size / 4000)
        var i = 0
        while (i < frame.size) {
          if (frame[i] ushr 24 == 0) return true
          i += step
        }
        n++
      }
      return false
    }

    /**
     * Builds the sheet. The sheet stays within [maxSide] px; frames shrink if needed, and very long GIFs
     * keep every k-th frame (their delays are added up so the speed is unchanged).
     */
    fun build(gif: ByteArray, maxSide: Int = 2048, maxFrames: Int = 120, pad: Int = 2): StickerSheet {
      val d = GifStreamDecoder(gif)
      val delays = d.scanDelays().ifEmpty { listOf(100) }
      val step = ceil(delays.size / maxFrames.toDouble()).toInt().coerceAtLeast(1)
      val kept = (delays.indices step step).toList()
      val keptDelays = kept.map { start -> (start until min(delays.size, start + step)).sumOf { delays[it] } }
      val n = kept.size
      val cols = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
      val rows = ceil(n / cols.toDouble()).toInt().coerceAtLeast(1)
      // Largest cell that fits: cols*(w+pad)+pad <= maxSide, same for height, keeping the aspect ratio.
      val scale = min(
        1.0,
        min((maxSide - pad).toDouble() / cols - pad, (maxSide - pad).toDouble() / rows - pad) / max(d.width, d.height),
      )
      // Round down, so the grid never grows past maxSide.
      val cw = max(1, kotlin.math.floor(d.width * scale).toInt())
      val ch = max(1, kotlin.math.floor(d.height * scale).toInt())
      val sw = cols * (cw + pad) + pad
      val sh = rows * (ch + pad) + pad
      val sheet = IntArray(sw * sh)
      val frame = IntArray(d.width * d.height)
      var index = 0
      var cell = 0
      while (cell < n) {
        if (d.nextFrame(frame) < 0) break
        if (index == kept[cell]) {
          val x0 = pad + (cell % cols) * (cw + pad)
          val y0 = pad + (cell / cols) * (ch + pad)
          blit(frame, d.width, d.height, sheet, sw, x0, y0, cw, ch)
          cell++
        }
        index++
      }
      return StickerSheet(sheet, sw, sh, StickerInfo(cols, rows, cw, ch, keptDelays.take(max(1, cell)), pad))
    }

    /** Area-averaged resize of [src] into a cell of [sheet], weighting colour by alpha so edges don't go dark. */
    private fun blit(src: IntArray, w: Int, h: Int, sheet: IntArray, sw: Int, x0: Int, y0: Int, cw: Int, ch: Int) {
      if (cw == w && ch == h) {
        for (y in 0 until h) System.arraycopy(src, y * w, sheet, (y0 + y) * sw + x0, w)
        return
      }
      for (y in 0 until ch) {
        val sy0 = y * h / ch
        val sy1 = max(sy0 + 1, (y + 1) * h / ch)
        for (x in 0 until cw) {
          val sx0 = x * w / cw
          val sx1 = max(sx0 + 1, (x + 1) * w / cw)
          var a = 0L; var r = 0L; var g = 0L; var b = 0L; var count = 0
          for (sy in sy0 until min(h, sy1)) for (sx in sx0 until min(w, sx1)) {
            val p = src[sy * w + sx]
            val pa = (p ushr 24).toLong()
            a += pa
            r += ((p shr 16) and 0xFF) * pa
            g += ((p shr 8) and 0xFF) * pa
            b += (p and 0xFF) * pa
            count++
          }
          val out = if (a == 0L || count == 0) 0 else {
            val aa = (a / count).toInt()
            ((aa shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt())
          }
          sheet[(y0 + y) * sw + x0 + x] = out
        }
      }
    }
  }
}
