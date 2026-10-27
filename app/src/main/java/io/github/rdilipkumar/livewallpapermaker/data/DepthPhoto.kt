package io.github.rdilipkumar.livewallpapermaker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import io.github.rdilipkumar.livewallpapermaker.engine.creator.Inpaint
import io.github.rdilipkumar.livewallpapermaker.engine.model.Asset
import io.github.rdilipkumar.livewallpapermaker.engine.model.AssetKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.Effects
import io.github.rdilipkumar.livewallpapermaker.engine.model.Geometry
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import io.github.rdilipkumar.livewallpapermaker.engine.model.Motion
import io.github.rdilipkumar.livewallpapermaker.engine.model.MotionPreset
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextFont
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The cut-out tool can't run here (no Google Play services, or its model couldn't be downloaded). */
class CutoutUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The photo has no clear subject to cut out. */
class NoSubjectFound(message: String) : IOException(message)

/** The two pictures a 3D photo is made of: the filled-in background and the cut-out subject (same size). */
data class DepthPhoto(val background: Asset, val subject: Asset)

/**
 * One-tap 3D photo: finds the main subject with ML Kit (on the phone; the model comes from Google Play
 * services), cuts it out, fills the background behind it, and saves both as images.
 */
class DepthPhotoMaker(private val context: Context, private val repo: ProjectRepository) {

  suspend fun make(uri: Uri, label: String, onStatus: (String, Float) -> Unit): DepthPhoto {
    val options = SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
    val segmenter = SubjectSegmentation.getClient(options)
    try {
      onStatus("Getting the cut-out tool", 0.02f)
      ensureModule(segmenter, onStatus)
      onStatus("Finding the subject", 0.35f)
      val photo = withContext(Dispatchers.IO) { decode(uri) }
      val w = photo.width
      val h = photo.height
      val result = segmenter.process(InputImage.fromBitmap(photo, 0)).await()
      val maskBuffer = result.foregroundConfidenceMask ?: throw NoSubjectFound("No subject was found in this photo")
      val conf = FloatArray(w * h)
      maskBuffer.rewind()
      maskBuffer.get(conf, 0, min(conf.size, maskBuffer.remaining()))
      val covered = conf.count { it > 0.5f }.toFloat() / conf.size
      if (covered < 0.01f) throw NoSubjectFound("No clear subject was found in this photo")
      if (covered > 0.95f) throw NoSubjectFound("The subject fills the whole photo")

      onStatus("Cutting out", 0.6f)
      val assets = withContext(Dispatchers.Default) { build(photo, conf, label) }
      onStatus("Done", 1f)
      return assets
    } finally {
      runCatching { segmenter.close() }
    }
  }

  private suspend fun ensureModule(segmenter: com.google.android.gms.common.api.OptionalModuleApi, onStatus: (String, Float) -> Unit) {
    val client = ModuleInstall.getClient(context)
    val available = runCatching { client.areModulesAvailable(segmenter).await().areModulesAvailable() }.getOrElse {
      throw CutoutUnavailable("This phone doesn't have Google Play services, which the cut-out tool needs", it)
    }
    if (available) return
    try {
      waitForInstall(client, segmenter, onStatus)
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
      throw CutoutUnavailable("The cut-out tool took too long to download", e)
    }
  }

  private suspend fun waitForInstall(
    client: com.google.android.gms.common.moduleinstall.ModuleInstallClient,
    segmenter: com.google.android.gms.common.api.OptionalModuleApi,
    onStatus: (String, Float) -> Unit,
  ) {
    withTimeout(120_000L) {
      suspendCancellableCoroutine { cont ->
        lateinit var listener: InstallStatusListener
        listener = InstallStatusListener { update ->
          val p = update.progressInfo
          if (p != null && p.totalBytesToDownload > 0) {
            onStatus("Downloading the cut-out tool", 0.02f + 0.3f * p.bytesDownloaded / p.totalBytesToDownload)
          }
          when (update.installState) {
            ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
              client.unregisterListener(listener)
              if (cont.isActive) cont.resume(Unit)
            }
            ModuleInstallStatusUpdate.InstallState.STATE_FAILED, ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
              client.unregisterListener(listener)
              if (cont.isActive) cont.resumeWithException(CutoutUnavailable("The cut-out tool couldn't be downloaded"))
            }
            else -> Unit
          }
        }
        val request = ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build()
        client.installModules(request)
          .addOnSuccessListener { response ->
            if (response.areModulesAlreadyInstalled()) {
              client.unregisterListener(listener)
              if (cont.isActive) cont.resume(Unit)
            }
          }
          .addOnFailureListener { e ->
            client.unregisterListener(listener)
            if (cont.isActive) cont.resumeWithException(CutoutUnavailable("The cut-out tool couldn't be downloaded", e))
          }
        cont.invokeOnCancellation { client.unregisterListener(listener) }
      }
    }
  }

  private fun decode(uri: Uri): Bitmap = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
      val s = min(1f, MAX_SIDE.toFloat() / max(info.size.width, info.size.height))
      if (s < 1f) decoder.setTargetSize(max(1, (info.size.width * s).roundToInt()), max(1, (info.size.height * s).roundToInt()))
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      decoder.isMutableRequired = true
    }
  } catch (e: Exception) {
    throw IOException("This image could not be opened", e)
  }

  /** For tests: runs the cut-out and fill steps on a given mask (the real mask comes from ML Kit). */
  @androidx.annotation.VisibleForTesting
  internal fun buildForTest(photo: Bitmap, conf: FloatArray, label: String): DepthPhoto = build(photo, conf, label)

  private fun build(photo: Bitmap, conf: FloatArray, label: String): DepthPhoto {
    val w = photo.width
    val h = photo.height
    val pixels = IntArray(w * h)
    photo.getPixels(pixels, 0, w, 0, 0, w, h)

    // Subject: the photo with the confidence as a soft alpha edge.
    val subjectPx = IntArray(w * h)
    for (i in pixels.indices) {
      val c = conf[i]
      val a = ((c - 0.35f) / 0.3f).coerceIn(0f, 1f)
      val alpha = (a * a * (3f - 2f * a) * 255f).roundToInt()
      subjectPx[i] = (alpha shl 24) or (pixels[i] and 0x00FFFFFF)
    }

    // Background: fill where the subject was. Done on a small copy (fast, low memory), then scaled up into the hole.
    val small = SMALL_SIDE.toFloat() / max(w, h)
    val sw = max(8, (w * small).roundToInt())
    val sh = max(8, (h * small).roundToInt())
    val smallBmp = Bitmap.createScaledBitmap(photo, sw, sh, true)
    val smallPx = IntArray(sw * sh)
    smallBmp.getPixels(smallPx, 0, sw, 0, 0, sw, sh)
    val smallHole = BooleanArray(sw * sh) { i ->
      val x = (i % sw) * w / sw
      val y = (i / sw) * h / sh
      conf[min(h - 1, y) * w + min(w - 1, x)] > 0.15f
    }
    Inpaint.fill(smallPx, sw, sh, Inpaint.dilate(smallHole, sw, sh, max(2, sw / 60)))
    smallBmp.setPixels(smallPx, 0, sw, 0, 0, sw, sh)
    val filled = Bitmap.createScaledBitmap(smallBmp, w, h, true)
    val filledPx = IntArray(w * h)
    filled.getPixels(filledPx, 0, w, 0, 0, w, h)
    val hole = Inpaint.dilate(BooleanArray(w * h) { conf[it] > 0.15f }, w, h, max(3, w / 80))
    val bgPx = IntArray(w * h) { i -> if (hole[i]) filledPx[i] else pixels[i] }

    val storage = repo.storage
    storage.assetsDir.mkdirs()
    val bgId = Ids.new("a")
    val subjectId = Ids.new("a")
    val bgBmp = Bitmap.createBitmap(bgPx, w, h, Bitmap.Config.ARGB_8888)
    File(storage.assetsDir, "$bgId.jpg").outputStream().use { bgBmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    val subjectBmp = Bitmap.createBitmap(subjectPx, w, h, Bitmap.Config.ARGB_8888)
    File(storage.assetsDir, "$subjectId.png").outputStream().use { subjectBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    thumb(bgId, bgBmp)
    thumb(subjectId, subjectBmp)
    listOf(bgBmp, subjectBmp, smallBmp, filled, photo).forEach { it.recycle() }

    val name = label.substringBeforeLast('.').take(20)
    return DepthPhoto(
      Asset(bgId, AssetKind.IMAGE, "$bgId.jpg", w, h, label = "$name background"),
      Asset(subjectId, AssetKind.IMAGE, "$subjectId.png", w, h, hasAlpha = true, label = "$name subject"),
    )
  }

  private fun thumb(id: String, bmp: Bitmap) {
    val s = 240f / max(bmp.width, bmp.height)
    val t = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
    File(repo.storage.assetsDir, ProjectRepository.thumbName(id)).outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    t.recycle()
  }

  companion object {
    const val MAX_SIDE = 2048
    const val SMALL_SIDE = 400

    /**
     * Layers for a 3D photo, back to front: background, optional clock (it sits *behind* the subject),
     * subject. The subject moves more with tilt/swipe; its size is matched so both line up at rest.
     */
    fun layers(photo: DepthPhoto, withClock: Boolean): List<Layer> {
      val ken = Motion(preset = MotionPreset.KEN_BURNS, presetAmount = 0.22f, presetPeriodSec = 30f)
      val bg = Layer(
        id = Ids.new("l"), name = "Background", assetId = photo.background.id,
        motion = ken.copy(swipeDepth = 0.03f, tiltDepth = 0.025f), effects = Effects(brightness = -0.04f),
      )
      val subject0 = Layer(
        id = Ids.new("l"), name = "Subject", assetId = photo.subject.id,
        motion = ken.copy(swipeDepth = 0.07f, tiltDepth = 0.075f),
      )
      val subject = subject0.copy(transform = Transform(scale = Geometry.overscan(bg) / Geometry.overscan(subject0)))
      val clock = Layer(
        id = Ids.new("l"), name = "Clock", type = LayerType.TEXT,
        transform = Transform(y = -0.30f), motion = Motion(tiltDepth = 0.045f, swipeDepth = 0.05f),
        text = TextSpec(text = "{time}", size = 0.14f, font = TextFont.LIGHT, shadow = true),
      )
      return if (withClock) listOf(bg, clock, subject) else listOf(bg, subject)
    }

    /**
     * Works on any phone: the photo as a rounded card floating over a blurred copy of itself,
     * leaning with tilt. Used when the subject can't be cut out.
     */
    fun cardLayers(photo: Asset, withClock: Boolean): List<Layer> {
      val bg = Layer(
        id = Ids.new("l"), name = "Blurred background", assetId = photo.id,
        effects = Effects(blur = 0.55f, brightness = -0.2f, saturation = 1.15f),
        motion = Motion(swipeDepth = 0.03f, tiltDepth = 0.02f),
      )
      val card = Layer(
        id = Ids.new("l"), name = "Photo card", assetId = photo.id, fit = io.github.rdilipkumar.livewallpapermaker.engine.model.Fit.CONTAIN,
        mask = io.github.rdilipkumar.livewallpapermaker.engine.model.MaskShape.ROUNDED, maskFeather = 0.04f,
        transform = Transform(scale = 0.86f, y = 0.04f),
        motion = Motion(swipeDepth = 0.06f, tiltDepth = 0.07f, preset = MotionPreset.FLOAT, presetAmount = 0.35f, presetPeriodSec = 7f),
      )
      val clock = Layer(
        id = Ids.new("l"), name = "Clock", type = LayerType.TEXT,
        transform = Transform(y = -0.36f), motion = Motion(tiltDepth = 0.09f, swipeDepth = 0.08f),
        text = TextSpec(text = "{time}", size = 0.11f, font = TextFont.LIGHT, shadow = true),
      )
      return if (withClock) listOf(bg, card, clock) else listOf(bg, card)
    }
  }
}

/** Waits for a Play services task without needing another library. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
  addOnSuccessListener { if (cont.isActive) cont.resume(it) }
  addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
  addOnCanceledListener { cont.cancel() }
}
