package io.github.rdilipkumar.livewallpapermaker.engine.creator

/**
 * Motion photos (Google "MP" / Samsung motion photos) are a normal JPEG or HEIC with a short MP4 appended.
 * This finds where that MP4 starts, by looking for an MP4 "ftyp" box after the start of the file.
 */
object MotionPhoto {
  private val BRANDS = setOf("mp41", "mp42", "isom", "iso2", "iso4", "iso5", "iso6", "avc1", "M4V ", "3gp4", "3gp5", "qt  ")

  /** Byte offset of the embedded video, or -1 when the photo has none. */
  fun findVideoOffset(bytes: ByteArray): Int {
    // HEIC files start with their own ftyp box, so skip the first few bytes.
    var i = 16
    while (i + 8 <= bytes.size) {
      if (bytes[i] == 'f'.code.toByte() && bytes[i + 1] == 't'.code.toByte() &&
        bytes[i + 2] == 'y'.code.toByte() && bytes[i + 3] == 'p'.code.toByte()
      ) {
        val start = i - 4
        val size = ((bytes[start].toInt() and 0xFF) shl 24) or ((bytes[start + 1].toInt() and 0xFF) shl 16) or
          ((bytes[start + 2].toInt() and 0xFF) shl 8) or (bytes[start + 3].toInt() and 0xFF)
        val brand = String(bytes, i + 4, 4, Charsets.ISO_8859_1)
        if (size in 12..256 && brand in BRANDS && bytes.size - start > 1024) return start
      }
      i++
    }
    return -1
  }
}
