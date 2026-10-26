package io.github.rdilipkumar.livewallpapermaker.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs

object TestFiles {
  val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context

  fun dir(name: String): File = File(target.cacheDir, name).apply { deleteRecursively(); mkdirs() }

  /** Copies a test asset into [dir]. */
  fun asset(name: String, dir: File): File {
    val out = File(dir, name)
    testContext.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
    return out
  }

  data class Mp4Info(val width: Int, val height: Int, val durationMs: Long, val frames: Int, val rotation: Int)

  fun inspect(file: File): Mp4Info {
    val ex = MediaExtractor()
    ex.setDataSource(file.path)
    val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
    ex.selectTrack(track)
    val f = ex.getTrackFormat(track)
    var frames = 0
    var last = 0L
    while (ex.sampleTime >= 0) {
      frames++
      last = maxOf(last, ex.sampleTime)
      ex.advance()
    }
    ex.release()
    val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
    return Mp4Info(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT), last / 1000, frames, rot)
  }

  fun frameAt(file: File, timeUs: Long): Bitmap {
    val r = MediaMetadataRetriever()
    try {
      r.setDataSource(file.path)
      return r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
    } finally {
      r.release()
    }
  }

  /** Mean absolute difference (0..255) between two pictures, compared at 32x32. */
  fun diff(a: Bitmap, b: Bitmap): Double {
    val sa = Bitmap.createScaledBitmap(a, 32, 32, true)
    val sb = Bitmap.createScaledBitmap(b, 32, 32, true)
    var sum = 0L
    for (y in 0 until 32) for (x in 0 until 32) {
      val p = sa.getPixel(x, y)
      val q = sb.getPixel(x, y)
      sum += abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
    }
    return sum / (32.0 * 32 * 3)
  }
}
