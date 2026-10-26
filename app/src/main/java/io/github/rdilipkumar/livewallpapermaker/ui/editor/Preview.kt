package io.github.rdilipkumar.livewallpapermaker.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.rdilipkumar.livewallpapermaker.engine.WallpaperRenderer
import io.github.rdilipkumar.livewallpapermaker.engine.model.Geometry
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import io.github.rdilipkumar.livewallpapermaker.engine.model.MotionInput
import io.github.rdilipkumar.livewallpapermaker.engine.model.Pose
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.StorageLayout
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Transform
import io.github.rdilipkumar.livewallpapermaker.engine.render.TextRenderer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns the editor's renderer (the same engine the wallpaper uses) and its TextureView. */
class PreviewController(context: Context, storage: StorageLayout) {
  val renderer = WallpaperRenderer(context, storage)
  var textureView: TextureView? = null

  /** Current preview picture, for the project poster and Material You colours. */
  fun capture(): Bitmap? = textureView?.takeIf { it.isAvailable }?.bitmap

  fun release() = renderer.release()
}

@Composable
fun rememberPreviewController(storage: StorageLayout): PreviewController {
  val context = LocalContext.current
  val controller = remember { PreviewController(context.applicationContext, storage) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> controller.renderer.setVisible(true)
        Lifecycle.Event.ON_PAUSE -> controller.renderer.setVisible(false)
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.renderer.setVisible(true)
    onDispose {
      lifecycle.removeObserver(observer)
      controller.release()
    }
  }
  return controller
}

/** Content size of a layer before fitting, in preview pixels. */
fun contentSize(context: Context, layer: Layer, project: Project, w: Float, h: Float): Pair<Float, Float> = when (layer.type) {
  LayerType.MEDIA -> project.asset(layer.assetId)?.let { it.width.toFloat() to it.height.toFloat() } ?: (w to h)
  LayerType.TEXT -> TextRenderer.measure(context, layer.text ?: TextSpec(), h.roundToInt()).let { it.first.toFloat() to it.second.toFloat() }
  else -> w to h
}

/**
 * The live preview plus a selection box. Tap picks the top-most layer under the finger;
 * drag / pinch / twist moves, scales and turns the selected layer (with gentle snapping).
 */
@Composable
fun PreviewSurface(
  controller: PreviewController,
  project: Project,
  scene: Scene?,
  selectedLayerId: String?,
  swipe: Float,
  onSelect: (String?) -> Unit,
  onTransform: (String, Transform) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var size by remember { mutableStateOf(IntSize.Zero) }
  val latestScene by rememberUpdatedState(scene)
  val latestProject by rememberUpdatedState(project)
  val latestSelected by rememberUpdatedState(selectedLayerId)
  val latestSwipe by rememberUpdatedState(swipe)

  fun poseOf(layer: Layer): Pose {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val (cw, ch) = contentSize(context, layer, latestProject, w, h)
    return Geometry.pose(layer, cw, ch, w, h, MotionInput(swipe = latestSwipe))
  }

  Box(modifier.onSizeChanged { size = it }) {
    AndroidView(
      factory = { ctx ->
        TextureView(ctx).apply {
          isOpaque = true
          controller.textureView = this
          surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
              controller.renderer.setSurface(st, width, height)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
              controller.renderer.setSize(width, height)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
              controller.renderer.setSurface(null, 0, 0)
              return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
          }
        }
      },
      modifier = Modifier.fillMaxSize(),
    )
    Canvas(
      Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
          detectTapGestures { pos ->
            val s = latestScene ?: return@detectTapGestures
            val visible = s.layers.filter { it.visible }.asReversed()
            val hit = visible.firstOrNull { l ->
              (l.type == LayerType.MEDIA || l.type == LayerType.TEXT) && Geometry.hitTest(poseOf(l), pos.x, pos.y)
            } ?: visible.firstOrNull { it.type == LayerType.GRADIENT || it.type == LayerType.PARTICLES }
            onSelect(hit?.id ?: latestSelected)
          }
        }
        .pointerInput(Unit) {
          // The raw (unsnapped) transform is kept here while a gesture runs, so small moves add up
          // even before the project state has been recomposed.
          var rawLayer: String? = null
          var raw = Transform()
          var lastAt = 0L
          detectTransformGestures { _, pan, zoom, rotation ->
            val id = latestSelected ?: return@detectTransformGestures
            val layer = latestScene?.layers?.firstOrNull { it.id == id } ?: return@detectTransformGestures
            if (size.width == 0) return@detectTransformGestures
            val now = android.os.SystemClock.uptimeMillis()
            if (rawLayer != id || now - lastAt > 250) {
              rawLayer = id
              raw = layer.transform
            }
            lastAt = now
            raw = raw.copy(
              x = (raw.x + pan.x / size.width).coerceIn(-2f, 2f),
              y = (raw.y + pan.y / size.height).coerceIn(-2f, 2f),
              scale = (raw.scale * zoom).coerceIn(0.05f, 20f),
              rotation = raw.rotation + rotation,
            )
            var x = raw.x
            var y = raw.y
            var r = raw.rotation
            if (abs(x) < 0.012f) x = 0f
            if (abs(y) < 0.012f) y = 0f
            val nearest = (r / 90f).roundToInt() * 90f
            if (abs(r - nearest) < 3f) r = nearest
            onTransform(id, raw.copy(x = x, y = y, rotation = r))
          }
        },
    ) {
      val s = latestScene ?: return@Canvas
      // Empty media slots: a soft box with a plus, so it's clear something goes there.
      for (slot in s.layers) {
        if (!slot.visible || slot.type != LayerType.MEDIA || slot.assetId != null) continue
        val p = poseOf(slot)
        val tl = Offset(p.cx - p.width / 2f, p.cy - p.height / 2f)
        rotate(p.rotation, pivot = Offset(p.cx, p.cy)) {
          drawRect(Color.White.copy(alpha = 0.08f), tl, Size(p.width, p.height))
          val arm = minOf(p.width, p.height, size.width.toFloat()) * 0.08f
          drawLine(Color.White.copy(alpha = 0.6f), Offset(p.cx - arm, p.cy), Offset(p.cx + arm, p.cy), strokeWidth = 6f)
          drawLine(Color.White.copy(alpha = 0.6f), Offset(p.cx, p.cy - arm), Offset(p.cx, p.cy + arm), strokeWidth = 6f)
        }
      }
      val layer = s.layers.firstOrNull { it.id == selectedLayerId } ?: return@Canvas
      if (!layer.visible) return@Canvas
      val p = poseOf(layer)
      val topLeft = Offset(p.cx - p.width / 2f, p.cy - p.height / 2f)
      rotate(p.rotation, pivot = Offset(p.cx, p.cy)) {
        drawRect(Color.Black.copy(alpha = 0.5f), topLeft, Size(p.width, p.height), style = Stroke(width = 5f))
        drawRect(
          Color(0xFFB7A6FF), topLeft, Size(p.width, p.height),
          style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f))),
        )
      }
    }
  }
}
