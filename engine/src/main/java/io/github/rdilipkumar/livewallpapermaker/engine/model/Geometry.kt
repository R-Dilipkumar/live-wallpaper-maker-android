package io.github.rdilipkumar.livewallpapermaker.engine.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where a layer is drawn: centre, size and rotation in screen pixels (y grows downwards). */
data class Pose(
  val cx: Float,
  val cy: Float,
  val width: Float,
  val height: Float,
  val rotation: Float,
  val opacity: Float,
)

/**
 * Inputs that move layers.
 * [swipe] is the launcher's page offset (0 = first page, 1 = last, 0.5 = middle).
 * [tiltX]/[tiltY] are -1..1 (phone tilted fully left/up .. right/down).
 */
data class MotionInput(
  val timeSec: Float = 0f,
  val swipe: Float = 0.5f,
  val tiltX: Float = 0f,
  val tiltY: Float = 0f,
)

object Geometry {

  fun fitSize(fit: Fit, contentW: Float, contentH: Float, screenW: Float, screenH: Float): Pair<Float, Float> {
    if (contentW <= 0f || contentH <= 0f) return screenW to screenH
    return when (fit) {
      Fit.COVER -> {
        val s = max(screenW / contentW, screenH / contentH)
        contentW * s to contentH * s
      }
      Fit.CONTAIN, Fit.BLUR_FILL -> {
        val s = min(screenW / contentW, screenH / contentH)
        contentW * s to contentH * s
      }
      Fit.STRETCH -> screenW to screenH
    }
  }

  /** Cover layers grow just enough that swipe/tilt movement never shows their edges. */
  fun overscan(layer: Layer): Float =
    if (layer.fit == Fit.COVER || layer.type == LayerType.GRADIENT) {
      1f + abs(layer.motion.swipeDepth) + 2f * abs(layer.motion.tiltDepth)
    } else 1f

  /** Size of the layer's content before fitting. Gradients and particles fill the screen. */
  fun pose(
    layer: Layer,
    contentW: Float,
    contentH: Float,
    screenW: Float,
    screenH: Float,
    input: MotionInput,
  ): Pose {
    val (fw, fh) = when (layer.type) {
      LayerType.GRADIENT, LayerType.PARTICLES -> screenW to screenH
      LayerType.TEXT -> contentW to contentH
      LayerType.MEDIA -> fitSize(layer.fit, contentW, contentH, screenW, screenH)
    }
    val t = layer.transform
    var cx = screenW / 2f + t.x * screenW
    var cy = screenH / 2f + t.y * screenH
    var scale = t.scale * overscan(layer)
    var rotation = t.rotation
    var opacity = t.opacity

    val m = layer.motion
    cx += (0.5f - input.swipe) * m.swipeDepth * screenW
    cx += input.tiltX * m.tiltDepth * screenW
    cy += input.tiltY * m.tiltDepth * screenH

    val p = presetOffsets(m.preset, m.presetAmount, m.presetPeriodSec, input.timeSec)
    cx += p.dx * screenW
    cy += p.dy * screenH
    scale *= p.scale
    rotation += p.rotation
    opacity *= p.opacity

    for (track in m.keyframes) {
      val v = evaluate(track, input.timeSec) ?: continue
      when (track.property) {
        AnimProperty.X -> cx += v * screenW
        AnimProperty.Y -> cy += v * screenH
        AnimProperty.SCALE -> scale *= v
        AnimProperty.ROTATION -> rotation += v
        AnimProperty.OPACITY -> opacity *= v
      }
    }
    return Pose(cx, cy, fw * scale, fh * scale, rotation, opacity.coerceIn(0f, 1f))
  }

  data class PresetOffsets(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
  )

  fun presetOffsets(preset: MotionPreset, amount: Float, periodSec: Float, timeSec: Float): PresetOffsets {
    if (preset == MotionPreset.NONE) return PresetOffsets()
    val period = max(0.5f, periodSec)
    val a = amount.coerceIn(0f, 1f)
    val phase = (2.0 * PI * timeSec / period).toFloat()
    val wave = sin(phase)
    val rise = 0.5f - 0.5f * cos(phase) // 0 → 1 → 0, smooth at both ends
    return when (preset) {
      MotionPreset.NONE -> PresetOffsets()
      MotionPreset.KEN_BURNS -> PresetOffsets(
        dx = 0.04f * a * sin(phase * 0.5f),
        dy = 0.02f * a * wave,
        scale = 1f + 0.18f * a * rise,
      )
      MotionPreset.FLOAT -> PresetOffsets(dy = 0.025f * a * wave)
      MotionPreset.PULSE -> PresetOffsets(scale = 1f + 0.06f * a * wave)
      MotionPreset.SWAY -> PresetOffsets(rotation = 10f * a * wave)
      MotionPreset.SPIN -> PresetOffsets(rotation = 360f * (timeSec / period) * (if (a >= 0.5f) 1f else -1f))
      MotionPreset.BREATHE -> PresetOffsets(opacity = 1f - 0.7f * a * rise)
      MotionPreset.DRIFT -> PresetOffsets(
        dx = 0.05f * a * wave,
        dy = 0.035f * a * sin(phase * 0.77f + 1.3f),
      )
    }
  }

  fun ease(easing: Easing, t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return when (easing) {
      Easing.LINEAR -> x
      Easing.EASE_IN -> x * x
      Easing.EASE_OUT -> 1f - (1f - x) * (1f - x)
      Easing.EASE_IN_OUT -> x * x * (3f - 2f * x)
    }
  }

  /** Value of a keyframe track at [timeSec], or null when the track has no keys. */
  fun evaluate(track: KeyframeTrack, timeSec: Float): Float? {
    val keys = track.keys.sortedBy { it.timeSec }
    if (keys.isEmpty()) return null
    if (keys.size == 1) return keys[0].value
    val start = keys.first().timeSec
    val length = keys.last().timeSec - start
    if (length <= 0f) return keys.last().value
    val local = timeSec - start
    val t = when (track.loop) {
      LoopMode.ONCE -> local.coerceIn(0f, length)
      LoopMode.LOOP -> positiveMod(local, length)
      LoopMode.PING_PONG -> {
        val m = positiveMod(local, 2f * length)
        if (m <= length) m else 2f * length - m
      }
    } + start
    val i = keys.indexOfLast { it.timeSec <= t }.coerceIn(0, keys.size - 2)
    val a = keys[i]
    val b = keys[i + 1]
    val span = b.timeSec - a.timeSec
    val f = if (span <= 0f) 1f else ease(track.easing, (t - a.timeSec) / span)
    return a.value + (b.value - a.value) * f
  }

  fun positiveMod(x: Float, m: Float): Float {
    val r = x % m
    return if (r < 0f) r + m else r
  }

  /** True when [px],[py] falls inside the rotated rectangle of [pose]. */
  fun hitTest(pose: Pose, px: Float, py: Float): Boolean {
    val rad = Math.toRadians(-pose.rotation.toDouble())
    val dx = px - pose.cx
    val dy = py - pose.cy
    val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
    val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
    return abs(lx) <= pose.width / 2f && abs(ly) <= pose.height / 2f
  }
}
