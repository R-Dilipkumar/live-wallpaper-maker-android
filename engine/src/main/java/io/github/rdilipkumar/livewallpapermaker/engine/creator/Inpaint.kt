package io.github.rdilipkumar.livewallpapermaker.engine.creator

import kotlin.math.max
import kotlin.math.min

/**
 * Fills the hole left after cutting a subject out of a photo, so the background behind it can show
 * when the subject moves (3D photo). "Push-pull": average the known pixels into smaller and smaller
 * images, then pull the averages back up into the hole. Smooth, fast, no AI needed.
 */
object Inpaint {

  /** Grows [mask] (true = hole) by [radius] pixels, so the fill also covers the subject's soft edge. */
  fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
    if (radius <= 0) return mask.copyOf()
    // Two 1-D passes (horizontal then vertical) make a square dilation.
    val tmp = BooleanArray(mask.size)
    for (y in 0 until h) {
      var last = -1_000_000
      val row = y * w
      for (x in 0 until w) {
        if (mask[row + x]) last = x
        if (x - last <= radius) tmp[row + x] = true
      }
      last = 1_000_000
      for (x in w - 1 downTo 0) {
        if (mask[row + x]) last = x
        if (last - x <= radius) tmp[row + x] = true
      }
    }
    val out = BooleanArray(mask.size)
    for (x in 0 until w) {
      var last = -1_000_000
      for (y in 0 until h) {
        if (tmp[y * w + x]) last = y
        if (y - last <= radius) out[y * w + x] = true
      }
      last = 1_000_000
      for (y in h - 1 downTo 0) {
        if (tmp[y * w + x]) last = y
        if (last - y <= radius) out[y * w + x] = true
      }
    }
    return out
  }

  /** Fills pixels where [hole] is true from their surroundings. [pixels] (ARGB) is changed in place. */
  fun fill(pixels: IntArray, w: Int, h: Int, hole: BooleanArray) {
    if (hole.none { it }) return
    // Level 0: colour sums and weights (1 where known).
    val levels = ArrayList<FloatArray>() // r,g,b,weight interleaved
    val sizes = ArrayList<Pair<Int, Int>>()
    val base = FloatArray(w * h * 4)
    for (i in 0 until w * h) {
      if (!hole[i]) {
        val p = pixels[i]
        base[i * 4] = ((p shr 16) and 0xFF).toFloat()
        base[i * 4 + 1] = ((p shr 8) and 0xFF).toFloat()
        base[i * 4 + 2] = (p and 0xFF).toFloat()
        base[i * 4 + 3] = 1f
      }
    }
    levels += base
    sizes += w to h
    // Push: halve until tiny, summing weighted colours.
    while (true) {
      val (pw, ph) = sizes.last()
      if (pw <= 1 && ph <= 1) break
      val nw = max(1, (pw + 1) / 2)
      val nh = max(1, (ph + 1) / 2)
      val prev = levels.last()
      val next = FloatArray(nw * nh * 4)
      for (y in 0 until ph) for (x in 0 until pw) {
        val si = (y * pw + x) * 4
        val di = ((y / 2) * nw + x / 2) * 4
        val wgt = prev[si + 3]
        if (wgt <= 0f) continue
        next[di] += prev[si] * wgt
        next[di + 1] += prev[si + 1] * wgt
        next[di + 2] += prev[si + 2] * wgt
        next[di + 3] += wgt
      }
      for (i in 0 until nw * nh) {
        val wgt = next[i * 4 + 3]
        if (wgt > 0f) {
          next[i * 4] /= wgt
          next[i * 4 + 1] /= wgt
          next[i * 4 + 2] /= wgt
          next[i * 4 + 3] = min(1f, wgt)
        }
      }
      levels += next
      sizes += nw to nh
    }
    // Pull: fill missing weight in each finer level from the coarser one (bilinear-ish via the parent).
    for (l in levels.size - 2 downTo 0) {
      val (fw, fh) = sizes[l]
      val (cw, ch) = sizes[l + 1]
      val fine = levels[l]
      val coarse = levels[l + 1]
      for (y in 0 until fh) for (x in 0 until fw) {
        val fi = (y * fw + x) * 4
        val wgt = fine[fi + 3]
        if (wgt >= 1f) continue
        // Sample the coarse level at this position with bilinear weights.
        val cx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (cw - 1).toFloat())
        val cy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (ch - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(cw - 1, x0 + 1)
        val y1 = min(ch - 1, y0 + 1)
        val fx = cx - x0
        val fy = cy - y0
        for (c in 0 until 3) {
          val v = (coarse[(y0 * cw + x0) * 4 + c] * (1 - fx) + coarse[(y0 * cw + x1) * 4 + c] * fx) * (1 - fy) +
            (coarse[(y1 * cw + x0) * 4 + c] * (1 - fx) + coarse[(y1 * cw + x1) * 4 + c] * fx) * fy
          fine[fi + c] = fine[fi + c] * wgt + v * (1 - wgt)
        }
        fine[fi + 3] = 1f
      }
    }
    val top = levels[0]
    for (i in 0 until w * h) {
      if (!hole[i]) continue
      val r = top[i * 4].toInt().coerceIn(0, 255)
      val g = top[i * 4 + 1].toInt().coerceIn(0, 255)
      val b = top[i * 4 + 2].toInt().coerceIn(0, 255)
      pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
  }
}
