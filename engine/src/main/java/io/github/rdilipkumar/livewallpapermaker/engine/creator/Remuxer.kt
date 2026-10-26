package io.github.rdilipkumar.livewallpapermaker.engine.creator

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Basic facts about a picked video. */
data class VideoInfo(val width: Int, val height: Int, val durationMs: Long, val rotation: Int, val mime: String)

object Remuxer {

  fun probe(context: Context, uri: Uri): VideoInfo {
    val ex = MediaExtractor()
    try {
      try {
        ex.setDataSource(context, uri, null)
      } catch (e: Exception) {
        throw IOException("This video can't be read. The file may be damaged or in an unusual format.", e)
      }
      val track = videoTrack(ex)
      if (track < 0) throw IOException("This video can't be read. The file may be damaged or have no picture.")
      val f = ex.getTrackFormat(track)
      val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
      val w = f.getInteger(MediaFormat.KEY_WIDTH)
      val h = f.getInteger(MediaFormat.KEY_HEIGHT)
      val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
      val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
      return VideoInfo(dw, dh, dur, rot, f.getString(MediaFormat.KEY_MIME) ?: "")
    } finally {
      ex.release()
    }
  }

  /**
   * Copies the video track from the keyframe before [startMs] up to [endMs] without re-encoding,
   * so the clip can be prepared again later. Returns how far into the new file [startMs] lands (ms).
   */
  fun copyVideo(context: Context, uri: Uri, startMs: Long, endMs: Long, out: File): Long {
    val ex = MediaExtractor()
    var muxer: MediaMuxer? = null
    var started = false
    try {
      ex.setDataSource(context, uri, null)
      val track = videoTrack(ex)
      if (track < 0) throw IOException("This file has no video")
      ex.selectTrack(track)
      val format = ex.getTrackFormat(track)
      val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
      val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
      val buffer = ByteBuffer.allocateDirect(maxOf(maxSize, 4 * 1024 * 1024))
      out.parentFile?.mkdirs()
      val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      muxer = m
      if (rotation != 0) m.setOrientationHint(rotation)
      val outTrack = m.addTrack(format)
      m.start()
      started = true
      ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      val base = maxOf(0L, ex.sampleTime)
      val info = MediaCodec.BufferInfo()
      // Keep a little extra after the end so reordered frames near the cut are not lost.
      val stopUs = if (endMs > 0) endMs * 1000 + 500_000L else Long.MAX_VALUE
      var count = 0
      while (true) {
        val size = ex.readSampleData(buffer, 0)
        if (size < 0) break
        val t = ex.sampleTime
        if (t > stopUs) break
        val pts = t - base
        if (pts >= 0) {
          info.set(0, size, pts, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
          m.writeSampleData(outTrack, buffer, info)
          count++
        }
        ex.advance()
      }
      if (count == 0) throw IOException("No video frames in the chosen range")
      return (startMs * 1000 - base) / 1000
    } catch (t: Throwable) {
      out.delete()
      throw t
    } finally {
      ex.release()
      if (started) runCatching { muxer?.stop() }
      runCatching { muxer?.release() }
    }
  }

  private fun videoTrack(ex: MediaExtractor): Int {
    for (i in 0 until ex.trackCount) {
      if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return i
    }
    return -1
  }
}
