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
import io.github.rdilipkumar.livewallpapermaker.engine.creator.MotionPhoto
import io.github.rdilipkumar.livewallpapermaker.engine.creator.Remuxer
import io.github.rdilipkumar.livewallpapermaker.engine.creator.StickerSheet
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

  /** [stillUri] is set for motion photos: the video was pulled out of a photo, which can still be used as-is. */
  data class Video(
    override val uri: Uri,
    override val label: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val stillUri: Uri? = null,
  ) : PickedMedia

  data class Gif(
    override val uri: Uri,
    override val label: String,
    val durationMs: Long,
    val frames: Int,
    val width: Int,
    val height: Int,
    val transparent: Boolean = false,
  ) : PickedMedia
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
        else PickedMedia.Gif(
          uri, label, delays.sum().toLong(), delays.size, decoder.width, decoder.height,
          transparent = runCatching { StickerSheet.hasTransparency(bytes) }.getOrDefault(false),
        )
      }
      mime.startsWith("video/") -> {
        val info = Remuxer.probe(context, uri)
        if (info.durationMs <= 0) throw IOException("This video has no length")
        PickedMedia.Video(uri, label, info.durationMs, info.width, info.height)
      }
      else -> motionPhoto(uri, label) ?: PickedMedia.Image(uri, label)
    }
  }

  /** Pixel and Samsung "motion photos" hide a short video after the picture. Returns it if there is one. */
  private fun motionPhoto(uri: Uri, label: String): PickedMedia.Video? {
    val bytes = runCatching { readLimited(uri, MAX_MOTION_PHOTO_BYTES) }.getOrNull() ?: return null
    val offset = MotionPhoto.findVideoOffset(bytes)
    if (offset < 0) return null
    val dir = File(context.cacheDir, "motion").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "motion-${System.nanoTime()}.mp4")
    file.outputStream().use { it.write(bytes, offset, bytes.size - offset) }
    return runCatching {
      val info = Remuxer.probe(context, Uri.fromFile(file))
      if (info.durationMs <= 0) null
      else PickedMedia.Video(Uri.fromFile(file), "Motion photo", info.durationMs, info.width, info.height, stillUri = uri)
    }.getOrNull().also { if (it == null) file.delete() }
  }

  /** Keeps a transparent GIF transparent: all frames go into one sheet image the wallpaper animates. */
  suspend fun importSticker(media: PickedMedia.Gif): Asset = withContext(Dispatchers.Default) {
    val bytes = readLimited(media.uri, MAX_GIF_BYTES)
    val sheet = try {
      StickerSheet.build(bytes)
    } catch (e: Exception) {
      throw IOException("This GIF could not be read", e)
    }
    val id = Ids.new("a")
    val bmp = Bitmap.createBitmap(sheet.width, sheet.height, Bitmap.Config.ARGB_8888)
    bmp.setPixels(sheet.pixels, 0, sheet.width, 0, 0, sheet.width, sheet.height)
    storage.assetsDir.mkdirs()
    File(storage.assetsDir, "$id.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val info = sheet.info
    val first = Bitmap.createBitmap(bmp, info.pad, info.pad, info.frameW, info.frameH)
    writeThumb(id, first)
    if (first !== bmp) first.recycle()
    bmp.recycle()
    Asset(
      id, AssetKind.STICKER, "$id.png", info.frameW, info.frameH, info.totalMs.toLong(),
      hasAlpha = true, label = media.label, sticker = info,
    )
  }

  /** A TTF/OTF font for text layers. */
  suspend fun importFont(uri: Uri): Asset = withContext(Dispatchers.IO) {
    val label = displayName(uri)
    val ext = if (label.endsWith(".otf", true)) "otf" else "ttf"
    val id = Ids.new("a")
    storage.assetsDir.mkdirs()
    val file = File(storage.assetsDir, "$id.$ext")
    file.writeBytes(readLimited(uri, 30_000_000L))
    val ok = runCatching { android.graphics.Typeface.createFromFile(file) }.isSuccess
    if (!ok) {
      file.delete()
      throw IOException("This isn't a font file Android can use (try a .ttf or .otf)")
    }
    Asset(id, AssetKind.FONT, file.name, 0, 0, label = label.substringBeforeLast('.').ifEmpty { "Font" })
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
    val w = max(1, (bmp.width * s).roundToInt())
    val h = max(1, (bmp.height * s).roundToInt())
    // Thumbnails are JPEGs; put see-through pictures on the app's dark grey instead of black.
    val thumb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(thumb).apply {
      drawColor(0xFF2A2E3A.toInt())
      drawBitmap(bmp, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    }
    thumbFile(id).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    thumb.recycle()
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
    const val MAX_MOTION_PHOTO_BYTES = 80_000_000L
    const val MAX_CLIP_MS = 60_000L
  }
}
