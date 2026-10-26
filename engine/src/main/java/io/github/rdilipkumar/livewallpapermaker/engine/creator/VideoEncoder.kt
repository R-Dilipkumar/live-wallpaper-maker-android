package io.github.rdilipkumar.livewallpapermaker.engine.creator

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** H.264 encoder fed through a Surface, written to an MP4 with no audio. */
internal class VideoEncoder(private val outFile: File, val width: Int, val height: Int, fps: Int, bitrate: Int) {
  private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
  val inputSurface: Surface
  private val muxer: MediaMuxer
  private var track = -1
  private var started = false
  private val info = MediaCodec.BufferInfo()
  var framesWritten = 0; private set

  init {
    val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      inputSurface = codec.createInputSurface()
      codec.start()
      outFile.parentFile?.mkdirs()
      muxer = MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } catch (t: Throwable) {
      runCatching { codec.release() }
      throw t
    }
  }

  /** Moves finished frames into the file. With [endOfStream], waits for the very last one. */
  fun drain(endOfStream: Boolean) {
    if (endOfStream) codec.signalEndOfInputStream()
    var tries = 0
    while (true) {
      val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
      when {
        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if (!endOfStream || ++tries > 500) return
        }
        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          check(!started) { "Encoder format changed twice" }
          track = muxer.addTrack(codec.outputFormat)
          muxer.start()
          started = true
        }
        idx >= 0 -> {
          val buf = codec.getOutputBuffer(idx)
          val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
          if (buf != null && info.size > 0 && started && !isConfig) {
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            muxer.writeSampleData(track, buf, info)
            framesWritten++
          }
          codec.releaseOutputBuffer(idx, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
      }
    }
  }

  fun finish() {
    try {
      drain(true)
    } finally {
      runCatching { codec.stop() }
      runCatching { codec.release() }
      runCatching { inputSurface.release() }
      if (started) runCatching { muxer.stop() }
      runCatching { muxer.release() }
    }
  }

  fun cancel() {
    runCatching { codec.stop() }
    runCatching { codec.release() }
    runCatching { inputSurface.release() }
    if (started) runCatching { muxer.stop() }
    runCatching { muxer.release() }
    outFile.delete()
  }

  companion object {
    const val MIME = "video/avc"

    /**
     * Output size: same shape as the source, short side at most [maxShortSide], long side at most 2560,
     * multiples of 16, and something the phone's H.264 encoder accepts.
     */
    fun pickSize(srcW: Int, srcH: Int, maxShortSide: Int): Pair<Int, Int> {
      var s = min(1f, maxShortSide.toFloat() / max(1, min(srcW, srcH)))
      s = min(s, 2560f / max(1, max(srcW, srcH)))
      val caps = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
          .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } }
          .mapNotNull { runCatching { it.getCapabilitiesForType(MIME).videoCapabilities }.getOrNull() }
      }.getOrDefault(emptyList())
      repeat(12) {
        val w = round16(srcW * s)
        val h = round16(srcH * s)
        if (caps.isEmpty() || caps.any { c -> runCatching { c.isSizeSupported(w, h) }.getOrDefault(false) }) return w to h
        s *= 0.85f
      }
      return round16(srcW * s) to round16(srcH * s)
    }

    private fun round16(v: Float): Int = max(16, (v / 16f).roundToInt() * 16)

    fun bitrateFor(w: Int, h: Int, fps: Int): Int = (w.toLong() * h * fps * 0.12).toLong().coerceIn(1_500_000L, 14_000_000L).toInt()
  }
}
