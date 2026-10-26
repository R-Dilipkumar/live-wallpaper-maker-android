package io.github.rdilipkumar.livewallpapermaker.engine.render

import android.content.Context
import android.os.Handler
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Gl
import io.github.rdilipkumar.livewallpapermaker.engine.model.Asset
import io.github.rdilipkumar.livewallpapermaker.engine.model.AssetKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.Fit
import io.github.rdilipkumar.livewallpapermaker.engine.model.Geometry
import io.github.rdilipkumar.livewallpapermaker.engine.model.GradientSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import io.github.rdilipkumar.livewallpapermaker.engine.model.MotionInput
import io.github.rdilipkumar.livewallpapermaker.engine.model.ParticleSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.StorageLayout
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Transform

/** Creates and frees textures/decoders for the scenes on screen. Render thread only. */
internal class SourceStore(
  private val context: Context,
  private val storage: StorageLayout,
  private val glHandler: Handler,
  private val onVideoFrame: () -> Unit,
) {
  private val images = HashMap<String, ImageSource>() // by asset id
  private val videos = HashMap<String, VideoSource>() // by "sceneId/assetId"
  private val videoSpeed = HashMap<String, Float>()
  private val texts = HashMap<String, TextSource>() // by "sceneId/layerId"
  var screenW = 1080
  var screenH = 1920
  private var videosStopped = false

  private fun videoKey(sceneId: String, assetId: String) = "$sceneId/$assetId"

  fun media(sceneId: String, asset: Asset): FrameSource? = when (asset.kind) {
    AssetKind.IMAGE -> images[asset.id]
    AssetKind.VIDEO -> videos[videoKey(sceneId, asset.id)]
  }

  fun text(sceneId: String, layer: Layer): TextSource? = texts["$sceneId/${layer.id}"]

  /** Makes sure every source the scene needs exists, and starts its videos. */
  fun prepare(scene: Scene, project: Project) {
    val maxImage = maxOf(2048, (maxOf(screenW, screenH) * 1.5f).toInt()).coerceAtMost(4096)
    for (layer in scene.layers) {
      if (!layer.visible) continue
      when (layer.type) {
        LayerType.MEDIA -> {
          val asset = project.asset(layer.assetId) ?: continue
          val file = storage.assetFile(asset)
          if (!file.exists()) continue
          when (asset.kind) {
            AssetKind.IMAGE -> if (!images.containsKey(asset.id)) images[asset.id] = ImageSource(file, maxImage)
            AssetKind.VIDEO -> {
              val key = videoKey(scene.id, asset.id)
              val v = videos.getOrPut(key) { VideoSource(file, glHandler, onVideoFrame) }
              videoSpeed[key] = layer.playbackSpeed.coerceIn(0.1f, 4f)
              if (!videosStopped) v.start()
            }
          }
        }
        LayerType.TEXT -> {
          val key = "${scene.id}/${layer.id}"
          val spec = layer.text ?: TextSpec()
          val t = texts.getOrPut(key) { TextSource(context, spec, screenH) }
          t.update(spec, screenH)
          t.refresh(System.currentTimeMillis())
        }
        else -> Unit
      }
    }
  }

  /** Frees everything not used by [scenes]. */
  fun retainOnly(scenes: Collection<Scene>, project: Project) {
    val keepImages = HashSet<String>()
    val keepVideos = HashSet<String>()
    val keepTexts = HashSet<String>()
    for (s in scenes) for (l in s.layers) {
      if (!l.visible) continue
      when (l.type) {
        LayerType.MEDIA -> project.asset(l.assetId)?.let { a ->
          if (a.kind == AssetKind.IMAGE) keepImages += a.id else keepVideos += videoKey(s.id, a.id)
        }
        LayerType.TEXT -> keepTexts += "${s.id}/${l.id}"
        else -> Unit
      }
    }
    images.keys.filter { it !in keepImages }.forEach { images.remove(it)?.release() }
    videos.keys.filter { it !in keepVideos }.forEach { videos.remove(it)?.release(); videoSpeed.remove(it) }
    texts.keys.filter { it !in keepTexts }.forEach { texts.remove(it)?.release() }
  }

  /** True when every video in the scene has decoded its first picture (or failed). */
  fun sceneReady(scene: Scene): Boolean =
    videos.filterKeys { it.startsWith(scene.id + "/") }.values.all { it.ready }

  /** Advances all running videos. [sceneTimeUs] gives each scene's local time. Returns true if any frame was released. */
  fun tickVideos(sceneTimeUs: (String) -> Long?): Boolean {
    var any = false
    for ((key, v) in videos) {
      val sceneId = key.substringBefore('/')
      val t = sceneTimeUs(sceneId) ?: continue
      val speed = videoSpeed[key] ?: 1f
      if (v.tick((t * speed).toLong())) any = true
    }
    return any
  }

  fun latchVideos(): Boolean {
    var any = false
    for (v in videos.values) if (v.latch()) any = true
    return any
  }

  fun hasVideos(sceneId: String): Boolean = videos.keys.any { it.startsWith("$sceneId/") }

  fun refreshTexts(nowMs: Long, sceneIds: Set<String>): Boolean {
    var changed = false
    for ((key, t) in texts) if (key.substringBefore('/') in sceneIds && t.refresh(nowMs)) changed = true
    return changed
  }

  fun hasClockText(sceneId: String): Boolean =
    texts.any { (k, t) -> k.startsWith("$sceneId/") && t.hasClockTokens }

  /** Frees the decoders while hidden; the last picture stays in each texture. */
  fun stopVideos() {
    videosStopped = true
    videos.values.forEach { it.stop() }
  }

  fun startVideos() {
    videosStopped = false
    videos.values.forEach { it.start() }
  }

  fun releaseAll() {
    images.values.forEach { it.release() }
    videos.values.forEach { it.release() }
    texts.values.forEach { it.release() }
    images.clear()
    videos.clear()
    texts.clear()
    videoSpeed.clear()
  }
}

/** Draws one scene's layers, bottom to top, into the painter's current target. */
internal class SceneDrawer(private val painter: Painter, private val sources: SourceStore) {
  private val blurCaches = HashMap<String, BlurCache>()

  fun draw(scene: Scene, project: Project, input: MotionInput, w: Int, h: Int) {
    painter.clear(scene.background)
    val wf = w.toFloat()
    val hf = h.toFloat()
    for (layer in scene.layers) {
      if (!layer.visible) continue
      when (layer.type) {
        LayerType.MEDIA -> drawMedia(scene, layer, project, input, wf, hf)
        LayerType.GRADIENT -> {
          val pose = Geometry.pose(layer, wf, hf, wf, hf, input)
          painter.drawGradient(layer.gradient ?: GradientSpec(), pose, input.timeSec, layer.blend)
        }
        LayerType.PARTICLES -> {
          val pose = Geometry.pose(layer, wf, hf, wf, hf, input)
          painter.drawParticles(layer.particles ?: ParticleSpec(), pose, input.timeSec, layer.blend, h)
        }
        LayerType.TEXT -> {
          val src = sources.text(scene.id, layer) ?: continue
          if (!src.ready) continue
          val pose = Geometry.pose(layer, src.width.toFloat(), src.height.toFloat(), wf, hf, input)
          drawSource("${scene.id}/${layer.id}", src, layer, pose, input.timeSec)
        }
      }
    }
  }

  private fun drawMedia(scene: Scene, layer: Layer, project: Project, input: MotionInput, w: Float, h: Float) {
    val asset = project.asset(layer.assetId) ?: return
    val src = sources.media(scene.id, asset) ?: return
    if (!src.ready || src.texture == 0) return
    val cw = src.width.toFloat()
    val ch = src.height.toFloat()
    if (layer.fit == Fit.BLUR_FILL) {
      val bgLayer = layer.copy(fit = Fit.COVER, transform = Transform(opacity = layer.transform.opacity))
      val bgPose = Geometry.pose(bgLayer, cw, ch, w, h, input)
      val tex = blurred("${scene.id}/${layer.id}#bg", src, 0.85f)
      painter.drawTexture(tex, false, Gl.IDENTITY, bgPose, layer.effects, input.timeSec, layer.blend, extraBrightness = -0.18f)
    }
    val pose = Geometry.pose(layer, cw, ch, w, h, input)
    drawSource("${scene.id}/${layer.id}", src, layer, pose, input.timeSec)
  }

  private fun drawSource(key: String, src: FrameSource, layer: Layer, pose: io.github.rdilipkumar.livewallpapermaker.engine.model.Pose, timeSec: Float) {
    if (layer.effects.blur > 0.01f) {
      val tex = blurred(key, src, layer.effects.blur)
      painter.drawTexture(tex, false, Gl.IDENTITY, pose, layer.effects, timeSec, layer.blend)
    } else {
      painter.drawTexture(src.texture, src.oes, src.texMatrix, pose, layer.effects, timeSec, layer.blend)
    }
  }

  private fun blurred(key: String, src: FrameSource, amount: Float): Int {
    val cache = blurCaches.getOrPut(key) { BlurCache() }
    if (cache.texture == 0 || cache.sourceFrame != src.frameCounter || cache.amount != amount) {
      cache.texture = painter.blurInto(cache, src.texture, src.oes, src.texMatrix, src.width, src.height, amount)
      cache.sourceFrame = src.frameCounter
      cache.amount = amount
    }
    return cache.texture
  }

  /** Frees blur buffers of layers that are no longer on screen. */
  fun retainBlurs(sceneIds: Set<String>) {
    val drop = blurCaches.keys.filter { it.substringBefore('/') !in sceneIds }
    drop.forEach { blurCaches.remove(it)?.release() }
  }

  fun release() {
    blurCaches.values.forEach { it.release() }
    blurCaches.clear()
  }
}
