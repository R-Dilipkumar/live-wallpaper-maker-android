package io.github.rdilipkumar.livewallpapermaker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import io.github.rdilipkumar.livewallpapermaker.engine.creator.BakeOptions
import io.github.rdilipkumar.livewallpapermaker.engine.creator.GifStreamDecoder
import io.github.rdilipkumar.livewallpapermaker.engine.creator.LoopBaker
import io.github.rdilipkumar.livewallpapermaker.engine.creator.Remuxer
import io.github.rdilipkumar.livewallpapermaker.engine.model.Asset
import io.github.rdilipkumar.livewallpapermaker.engine.model.AssetKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.VideoPrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What was picked, before any preparing. */
sealed interface PickedMedia {
  val uri: Uri
  val label: String

  data class Image(override val uri: Uri, override val label: String) : PickedMedia
  data class Video(override val uri: Uri, override val label: String, val durationMs: Long, val width: Int, val height: Int) : PickedMedia
  data class Gif(override val uri: Uri, override val label: String, val durationMs: Long, val frames: Int, val width: Int, val height: Int) : PickedMedia
}

/** Turns picked photos, GIFs and videos into assets in the shared asset folder. */
class MediaImporter(private val context: Context, private val repo: ProjectRepository) {
  private val storage = repo.storage
  /** GL work stays on one thread (EGL contexts belong to a thread). */
  private val bakeDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "lwm-bake") }.asCoroutineDispatcher()

  suspend fun inspect(uri: Uri): PickedMedia = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: ""
    val label = displayName(uri)
    when {
      mime == "image/gif" || label.endsWith(".gif", true) -> {
        val bytes = readLimited(uri, MAX_GIF_BYTES)
        val decoder = try {
          GifStreamDecoder(bytes)
        } catch (e: IllegalArgumentException) {
          throw IOException("This GIF could not be read")
        }
        val delays = decoder.scanDelays()
        if (delays.size <= 1) PickedMedia.Image(uri, label)
        else PickedMedia.Gif(uri, label, delays.sum().toLong(), delays.size, decoder.width, decoder.height)
      }
      mime.startsWith("video/") -> {
        val info = Remuxer.probe(context, uri)
        if (info.durationMs <= 0) throw IOException("This video has no length")
        PickedMedia.Video(uri, label, info.durationMs, info.width, info.height)
      }
      else -> PickedMedia.Image(uri, label)
    }
  }

  suspend fun importImage(uri: Uri, label: String): Asset = withContext(Dispatchers.IO) {
    val id = Ids.new("a")
    val bmp = try {
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val s = min(1f, MAX_IMAGE_SIDE.toFloat() / max(w, h))
        if (s < 1f) decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    } catch (e: Exception) {
      throw IOException("This image could not be opened", e)
    }
    val alpha = bmp.hasAlpha() && hasTransparentPixels(bmp)
    val name = if (alpha) "$id.png" else "$id.jpg"
    storage.assetsDir.mkdirs()
    File(storage.assetsDir, name).outputStream().use {
      bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (alpha) 100 else 92, it)
    }
    writeThumb(id, bmp)
    val asset = Asset(id, AssetKind.IMAGE, name, bmp.width, bmp.height, hasAlpha = alpha, label = label)
    bmp.recycle()
    asset
  }

  /**
   * Copies the picked clip (trimmed, no re-encode) so it can be prepared again later, then bakes
   * the loop-ready MP4.
   */
  suspend fun importClip(media: PickedMedia, options: BakeOptions, onProgress: (Float) -> Unit): Asset {
    val id = Ids.new("a")
    storage.assetsDir.mkdirs()
    val isGif = media is PickedMedia.Gif
    val srcName = if (isGif) "$id-src.gif" else "$id-src.mp4"
    val srcFile = File(storage.assetsDir, srcName)
    var bakeOptions = options
    withContext(Dispatchers.IO) {
      if (isGif) {
        srcFile.writeBytes(readLimited(media.uri, MAX_GIF_BYTES))
      } else {
        val offset = try {
          Remuxer.copyVideo(context, media.uri, options.trimStartMs, options.trimEndMs, srcFile)
        } catch (e: Exception) {
          throw IOException("This video could not be read", e)
        }
        val length = options.trimEndMs - options.trimStartMs
        bakeOptions = options.copy(trimStartMs = offset, trimEndMs = if (options.trimEndMs > 0) offset + length else 0)
      }
    }
    try {
      return bake(id, srcName, isGif, bakeOptions, media.label, onProgress)
    } catch (t: Throwable) {
      srcFile.delete()
      throw t
    }
  }

  /** Prepares an existing clip again with new loop/trim settings. Makes a new asset; the old one stays for the live wallpaper. */
  suspend fun reprepare(asset: Asset, options: BakeOptions, onProgress: (Float) -> Unit): Asset {
    val prep = asset.prep ?: throw IOException("This clip can't be prepared again")
    val src = prep.sourceFile ?: throw IOException("The original clip is no longer stored")
    if (!File(storage.assetsDir, src).exists()) throw IOException("The original clip is no longer stored")
    return bake(Ids.new("a"), src, prep.fromGif, options, asset.label, onProgress)
  }

  private suspend fun bake(id: String, srcName: String, isGif: Boolean, options: BakeOptions, label: String, onProgress: (Float) -> Unit): Asset {
    val out = File(storage.assetsDir, "$id.mp4")
    val ctx = currentCoroutineContext()
    val result = withContext(bakeDispatcher) {
      try {
        LoopBaker.bake(
          File(storage.assetsDir, srcName), isGif, options, out, context.cacheDir,
          isCancelled = { !ctx.isActive },
          onProgress = onProgress,
        )
      } catch (e: java.util.concurrent.CancellationException) {
        ctx.ensureActive()
        throw e
      }
    }
    withContext(Dispatchers.IO) { videoThumb(id, out) }
    return Asset(
      id, AssetKind.VIDEO, out.name, result.width, result.height, result.durationMs, label = label,
      prep = VideoPrep(
        sourceFile = srcName,
        trimStartMs = options.trimStartMs,
        trimEndMs = options.trimEndMs,
        loop = options.loop,
        loopWindowMs = options.loopWindowMs,
        fromGif = isGif,
        smoothGif = options.smoothGif,
      ),
    )
  }

  fun thumbFile(assetId: String): File = File(storage.assetsDir, ProjectRepository.thumbName(assetId))

  private fun writeThumb(id: String, bmp: Bitmap) {
    val s = 240f / max(bmp.width, bmp.height)
    val small = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
    thumbFile(id).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    if (small !== bmp) small.recycle()
  }

  private fun videoThumb(id: String, file: File) {
    val r = MediaMetadataRetriever()
    try {
      r.setDataSource(file.path)
      r.getFrameAtTime(0)?.let { writeThumb(id, it); it.recycle() }
    } catch (_: Exception) {
    } finally {
      r.release()
    }
  }

  private fun hasTransparentPixels(bmp: Bitmap): Boolean {
    val step = max(1, max(bmp.width, bmp.height) / 64)
    var y = 0
    while (y < bmp.height) {
      var x = 0
      while (x < bmp.width) {
        if (bmp.getPixel(x, y) ushr 24 < 250) return true
        x += step
      }
      y += step
    }
    return false
  }

  /** File name for labels. The photo picker often hands out bare numbers ("32.mp4"); those get a plain name instead. */
  private fun displayName(uri: Uri): String {
    val name = runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }
    }.getOrNull() ?: uri.lastPathSegment ?: ""
    val base = name.substringBeforeLast('.')
    if (base.isNotEmpty() && !base.all { it.isDigit() }) return name
    val mime = context.contentResolver.getType(uri) ?: ""
    return when {
      mime == "image/gif" -> "GIF"
      mime.startsWith("video/") -> "Video"
      else -> "Photo"
    }
  }

  private fun readLimited(uri: Uri, limit: Long): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open this file")
    input.use {
      val out = java.io.ByteArrayOutputStream()
      val buf = ByteArray(64 * 1024)
      var total = 0L
      while (true) {
        val n = it.read(buf)
        if (n < 0) break
        total += n
        if (total > limit) throw IOException("This GIF is too large (max ${limit / 1_000_000} MB)")
        out.write(buf, 0, n)
      }
      return out.toByteArray()
    }
  }

  companion object {
    const val MAX_IMAGE_SIDE = 2560
    const val MAX_GIF_BYTES = 60_000_000L
    const val MAX_CLIP_MS = 60_000L
  }
}
