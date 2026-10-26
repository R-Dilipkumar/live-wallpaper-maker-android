package io.github.rdilipkumar.livewallpapermaker.engine.render

import android.graphics.Color
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Fbo
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Gl
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Quad
import io.github.rdilipkumar.livewallpapermaker.engine.model.BlendMode
import io.github.rdilipkumar.livewallpapermaker.engine.model.Easing
import io.github.rdilipkumar.livewallpapermaker.engine.model.Effects
import io.github.rdilipkumar.livewallpapermaker.engine.model.Geometry
import io.github.rdilipkumar.livewallpapermaker.engine.model.MaskShape
import io.github.rdilipkumar.livewallpapermaker.engine.model.GradientSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.ParticleSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Pose
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Where drawing goes: the screen (fbo == null) or an offscreen buffer. */
internal class Target(val fbo: Fbo?, val width: Int, val height: Int) {
  fun bind() {
    if (fbo != null) fbo.bind() else {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
      GLES30.glViewport(0, 0, width, height)
    }
  }
}

private class LayerProgram(oes: Boolean) {
  val id = Gl.program(Shaders.QUAD_VS, Shaders.layerFs(oes))
  val mvp = GLES30.glGetUniformLocation(id, "uMvp")
  val tex = GLES30.glGetUniformLocation(id, "uTex")
  val texMatrix = GLES30.glGetUniformLocation(id, "uTexMatrix")
  val opacity = GLES30.glGetUniformLocation(id, "uOpacity")
  val size = GLES30.glGetUniformLocation(id, "uSize")
  val time = GLES30.glGetUniformLocation(id, "uTime")
  val adjust = GLES30.glGetUniformLocation(id, "uAdjust")
  val tint = GLES30.glGetUniformLocation(id, "uTint")
  val fx = GLES30.glGetUniformLocation(id, "uFx")
  val wave = GLES30.glGetUniformLocation(id, "uWave")
  val mask = GLES30.glGetUniformLocation(id, "uMask")
  val feather = GLES30.glGetUniformLocation(id, "uFeather")
  val day = GLES30.glGetUniformLocation(id, "uDay")
  val dim = GLES30.glGetUniformLocation(id, "uDim")
}

/** A tap effect in progress: position in pixels, age 0..1 of its life. */
internal class TouchFx(val x: Float, val y: Float, val age: Float, val ageSec: Float)

/** Owns the shader programs and draws single layers, blurs and transitions. Render thread only. */
internal class Painter {
  val quad = Quad()
  private val layer2d = LayerProgram(false)
  private val layerOes = LayerProgram(true)
  private val gradient = Gl.program(Shaders.QUAD_VS, Shaders.GRADIENT_FS)
  private val particles = Gl.program(Shaders.PARTICLE_VS, Shaders.PARTICLE_FS)
  private val blur = Gl.program(Shaders.QUAD_VS, Shaders.BLUR_FS)
  private val transition = Gl.program(Shaders.QUAD_VS, Shaders.TRANSITION_FS)
  private val ripple = Gl.program(Shaders.QUAD_VS, Shaders.RIPPLE_FS)
  private val burst = Gl.program(Shaders.BURST_VS, Shaders.BURST_FS)
  private val glow = Gl.program(Shaders.QUAD_VS, Shaders.GLOW_FS)

  /** Colour cast for the time of day (null = none). Applied once per layer, never to finished scenes. */
  var dayTint: io.github.rdilipkumar.livewallpapermaker.engine.model.SceneLogic.DayTint? = null

  private val proj = FloatArray(16)
  private val model = FloatArray(16)
  private val mvp = FloatArray(16)
  private val fullTarget = FloatArray(16).also {
    Matrix.setIdentityM(it, 0)
    Matrix.scaleM(it, 0, 2f, -2f, 1f)
  }
  private val tmpColor = FloatArray(16)

  var target: Target = Target(null, 1, 1); private set

  /** Effect sizes are tuned for a 1080 px wide screen and scaled for others (e.g. the small editor preview). */
  var pxScale = 1f; private set

  /** Extra zoom around the screen centre (notification shade effect); only applied when drawing to the screen. */
  var screenZoom = 1f

  fun use(t: Target) {
    target = t
    t.bind()
    Matrix.orthoM(proj, 0, 0f, t.width.toFloat(), t.height.toFloat(), 0f, -1f, 1f)
    if (t.fbo == null && screenZoom != 1f) {
      val cx = t.width / 2f
      val cy = t.height / 2f
      Matrix.translateM(proj, 0, cx, cy, 0f)
      Matrix.scaleM(proj, 0, screenZoom, screenZoom, 1f)
      Matrix.translateM(proj, 0, -cx, -cy, 0f)
    }
  }

  fun setScreenWidth(screenW: Int) {
    pxScale = max(0.2f, screenW / 1080f)
  }

  fun clear(argb: Int) {
    GLES30.glClearColor(Color.red(argb) / 255f, Color.green(argb) / 255f, Color.blue(argb) / 255f, 1f)
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
  }

  private fun poseMatrix(pose: Pose): FloatArray {
    Matrix.setIdentityM(model, 0)
    Matrix.translateM(model, 0, pose.cx, pose.cy, 0f)
    if (pose.rotation != 0f) Matrix.rotateM(model, 0, pose.rotation, 0f, 0f, 1f)
    Matrix.scaleM(model, 0, pose.width, pose.height, 1f)
    Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)
    return mvp
  }

  fun setBlend(mode: BlendMode) {
    GLES30.glEnable(GLES30.GL_BLEND)
    when (mode) {
      BlendMode.NORMAL -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
      BlendMode.ADD -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
      BlendMode.SCREEN -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
      BlendMode.MULTIPLY -> GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }
  }

  private fun bindTexture(unit: Int, tex: Int, oes: Boolean) {
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
    GLES30.glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, tex)
  }

  /** Draws a texture at [pose] with the layer's effects. [effects] null = plain copy. */
  fun drawTexture(
    tex: Int,
    oes: Boolean,
    texMatrix: FloatArray,
    pose: Pose,
    effects: Effects?,
    timeSec: Float,
    blend: BlendMode = BlendMode.NORMAL,
    extraBrightness: Float = 0f,
    matrix: FloatArray? = null,
    mask: MaskShape = MaskShape.NONE,
    feather: Float = 0.15f,
    applyDay: Boolean = true,
  ) {
    if (tex == 0 || pose.opacity <= 0.001f) return
    val p = if (oes) layerOes else layer2d
    GLES30.glUseProgram(p.id)
    GLES30.glUniform1i(p.mask, mask.ordinal)
    GLES30.glUniform1f(p.feather, feather)
    setDay(p.day, p.dim, applyDay)
    GLES30.glUniformMatrix4fv(p.mvp, 1, false, matrix ?: poseMatrix(pose), 0)
    GLES30.glUniformMatrix4fv(p.texMatrix, 1, false, texMatrix, 0)
    bindTexture(0, tex, oes)
    GLES30.glUniform1i(p.tex, 0)
    GLES30.glUniform1f(p.opacity, pose.opacity)
    GLES30.glUniform2f(p.size, max(1f, pose.width), max(1f, pose.height))
    GLES30.glUniform1f(p.time, timeSec)
    val e = effects ?: NEUTRAL
    GLES30.glUniform4f(
      p.adjust,
      e.brightness * 0.5f + extraBrightness,
      e.contrast,
      e.saturation,
      (e.hue * PI / 180.0).toFloat(),
    )
    GLES30.glUniform4f(
      p.tint,
      Color.red(e.tintColor) / 255f, Color.green(e.tintColor) / 255f, Color.blue(e.tintColor) / 255f,
      e.tintAmount,
    )
    GLES30.glUniform4f(
      p.fx,
      e.vignette,
      e.grain,
      if (e.pixelate > 0.01f) (2f + e.pixelate * 46f) * pxScale else 0f,
      e.chromatic * 14f * pxScale,
    )
    GLES30.glUniform2f(p.wave, e.wave * 34f * pxScale, e.waveSpeed)
    setBlend(blend)
    quad.draw()
  }

  private fun setDay(dayLoc: Int, dimLoc: Int, apply: Boolean) {
    val d = dayTint
    if (apply && d != null) {
      GLES30.glUniform4f(dayLoc, d.r, d.g, d.b, d.amount)
      GLES30.glUniform1f(dimLoc, d.dim)
    } else {
      GLES30.glUniform4f(dayLoc, 1f, 1f, 1f, 0f)
      GLES30.glUniform1f(dimLoc, 0f)
    }
  }

  fun drawGradient(spec: GradientSpec, pose: Pose, timeSec: Float, blend: BlendMode, mask: MaskShape = MaskShape.NONE, feather: Float = 0.15f) {
    if (pose.opacity <= 0.001f) return
    val colors = spec.colors.ifEmpty { listOf(Color.BLACK, Color.BLACK) }.take(4)
      .let { if (it.size == 1) it + it else it }
    GLES30.glUseProgram(gradient)
    GLES30.glUniform1i(loc(gradient, "uMask"), mask.ordinal)
    GLES30.glUniform1f(loc(gradient, "uFeather"), feather)
    setDay(loc(gradient, "uDay"), loc(gradient, "uDim"), true)
    GLES30.glUniformMatrix4fv(loc(gradient, "uMvp"), 1, false, poseMatrix(pose), 0)
    for (i in 0 until 4) {
      val c = colors[minOf(i, colors.size - 1)]
      tmpColor[i * 4] = Color.red(c) / 255f
      tmpColor[i * 4 + 1] = Color.green(c) / 255f
      tmpColor[i * 4 + 2] = Color.blue(c) / 255f
      tmpColor[i * 4 + 3] = Color.alpha(c) / 255f
    }
    GLES30.glUniform4fv(loc(gradient, "uColors"), 4, tmpColor, 0)
    GLES30.glUniform1i(loc(gradient, "uCount"), colors.size)
    val rad = Math.toRadians(spec.angleDeg.toDouble())
    GLES30.glUniform2f(loc(gradient, "uDir"), cos(rad).toFloat(), sin(rad).toFloat())
    GLES30.glUniform1i(loc(gradient, "uRadial"), if (spec.radial) 1 else 0)
    GLES30.glUniform1i(loc(gradient, "uFlow"), if (spec.flowSpeed != 0f) 1 else 0)
    GLES30.glUniform1f(loc(gradient, "uPhase"), Geom.fract(timeSec * spec.flowSpeed / 60f))
    GLES30.glUniform1f(loc(gradient, "uOpacity"), pose.opacity)
    GLES30.glUniform2f(loc(gradient, "uSize"), max(1f, pose.width), max(1f, pose.height))
    setBlend(blend)
    quad.draw()
  }

  fun drawParticles(spec: ParticleSpec, pose: Pose, timeSec: Float, blend: BlendMode, screenH: Int, wind: Float = spec.wind) {
    if (pose.opacity <= 0.001f || spec.count <= 0) return
    GLES30.glUseProgram(particles)
    GLES30.glUniformMatrix4fv(loc(particles, "uMvp"), 1, false, poseMatrix(pose), 0)
    GLES30.glUniform1f(loc(particles, "uTime"), timeSec * spec.speed)
    GLES30.glUniform1i(loc(particles, "uKind"), spec.kind.ordinal)
    GLES30.glUniform1f(loc(particles, "uSizeScale"), spec.size * screenH / 2400f)
    GLES30.glUniform1f(loc(particles, "uWind"), wind)
    val c = spec.color
    GLES30.glUniform4f(loc(particles, "uColor"), Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, Color.alpha(c) / 255f)
    GLES30.glUniform1f(loc(particles, "uOpacity"), pose.opacity * spec.opacity)
    setBlend(if (blend == BlendMode.NORMAL) BlendMode.NORMAL else blend)
    quad.drawPoints(spec.count.coerceIn(1, 2000))
  }

  /** Copies [tex] so it fills the whole current target (used for blurs and for showing offscreen scenes). */
  fun copyFull(tex: Int, oes: Boolean, texMatrix: FloatArray) {
    val p = if (oes) layerOes else layer2d
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glUseProgram(p.id)
    GLES30.glUniformMatrix4fv(p.mvp, 1, false, fullTarget, 0)
    GLES30.glUniformMatrix4fv(p.texMatrix, 1, false, texMatrix, 0)
    bindTexture(0, tex, oes)
    GLES30.glUniform1i(p.tex, 0)
    GLES30.glUniform1f(p.opacity, 1f)
    GLES30.glUniform2f(p.size, target.width.toFloat(), target.height.toFloat())
    GLES30.glUniform1f(p.time, 0f)
    GLES30.glUniform4f(p.adjust, 0f, 1f, 1f, 0f)
    GLES30.glUniform4f(p.tint, 0f, 0f, 0f, 0f)
    GLES30.glUniform4f(p.fx, 0f, 0f, 0f, 0f)
    GLES30.glUniform2f(p.wave, 0f, 0f)
    GLES30.glUniform1i(p.mask, 0)
    setDay(p.day, p.dim, false)
    quad.draw()
  }

  /** Copies the finished scene in [sceneTex] to the current target, bent by water ripples. */
  fun drawRipples(sceneTex: Int, touches: List<TouchFx>, strength: Float) {
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glUseProgram(ripple)
    GLES30.glUniformMatrix4fv(loc(ripple, "uMvp"), 1, false, fullTarget, 0)
    bindTexture(0, sceneTex, false)
    GLES30.glUniform1i(loc(ripple, "uScene"), 0)
    val n = minOf(6, touches.size)
    val data = FloatArray(24)
    for (i in 0 until n) {
      val t = touches[touches.size - n + i]
      data[i * 4] = t.x / target.width
      data[i * 4 + 1] = 1f - t.y / target.height
      data[i * 4 + 2] = t.age
      data[i * 4 + 3] = strength
    }
    GLES30.glUniform4fv(loc(ripple, "uRipples"), 6, data, 0)
    GLES30.glUniform1i(loc(ripple, "uCount"), n)
    GLES30.glUniform2f(loc(ripple, "uSize"), target.width.toFloat(), target.height.toFloat())
    quad.draw()
  }

  fun drawSparkles(t: TouchFx, color: Int, size: Float) {
    GLES30.glUseProgram(burst)
    GLES30.glUniformMatrix4fv(loc(burst, "uMvp"), 1, false, proj, 0)
    GLES30.glUniform2f(loc(burst, "uOrigin"), t.x, t.y)
    GLES30.glUniform1f(loc(burst, "uAge"), t.ageSec)
    GLES30.glUniform1f(loc(burst, "uScale"), pxScale * size)
    GLES30.glUniform4f(loc(burst, "uColor"), Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, 1f)
    setBlend(BlendMode.ADD)
    quad.drawPoints(48)
  }

  fun drawGlow(t: TouchFx, color: Int, size: Float) {
    val r = (0.10f + 0.32f * Geometry.ease(Easing.EASE_OUT, t.age)) * target.width * size
    val pose = Pose(t.x, t.y, r * 2f, r * 2f, 0f, 1f)
    GLES30.glUseProgram(glow)
    GLES30.glUniformMatrix4fv(loc(glow, "uMvp"), 1, false, poseMatrix(pose), 0)
    GLES30.glUniform4f(loc(glow, "uColor"), Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, 0.85f)
    GLES30.glUniform1f(loc(glow, "uFade"), (1f - t.age) * (1f - t.age))
    setBlend(BlendMode.ADD)
    quad.draw()
  }

  /**
   * Blurs [tex] into [cache] (two small buffers) and returns the blurred texture (GL orientation).
   * The caller re-binds its own target afterwards.
   */
  fun blurInto(cache: BlurCache, tex: Int, oes: Boolean, texMatrix: FloatArray, srcW: Int, srcH: Int, amount: Float): Int {
    val a = amount.coerceIn(0.05f, 1f)
    val down = 3f + a * 7f
    val w = max(8, (srcW / down).roundToInt())
    val h = max(8, (srcH / down).roundToInt())
    val (fa, fb) = cache.ensure(w, h)
    val saved = target
    use(Target(fa, w, h))
    copyFull(tex, oes, texMatrix)
    GLES30.glUseProgram(blur)
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glUniformMatrix4fv(loc(blur, "uMvp"), 1, false, fullTarget, 0)
    GLES30.glUniform1i(loc(blur, "uTex"), 0)
    val spread = 1f + a * 1.5f
    val passes = 2 + (a * 3).roundToInt()
    repeat(passes) {
      use(Target(fb, w, h))
      GLES30.glUseProgram(blur)
      bindTexture(0, fa.texture, false)
      GLES30.glUniform2f(loc(blur, "uStep"), spread / w, 0f)
      quad.draw()
      use(Target(fa, w, h))
      GLES30.glUseProgram(blur)
      bindTexture(0, fb.texture, false)
      GLES30.glUniform2f(loc(blur, "uStep"), 0f, spread / h)
      quad.draw()
    }
    use(saved)
    return fa.texture
  }

  /** 2D transition between two offscreen scenes, drawn over the whole current target. */
  fun drawTransition(fromTex: Int, toTex: Int, type: Int, progress: Float, dirX: Float, dirY: Float, color: Int) {
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glUseProgram(transition)
    GLES30.glUniformMatrix4fv(loc(transition, "uMvp"), 1, false, fullTarget, 0)
    bindTexture(0, fromTex, false)
    bindTexture(1, toTex, false)
    GLES30.glUniform1i(loc(transition, "uFrom"), 0)
    GLES30.glUniform1i(loc(transition, "uTo"), 1)
    GLES30.glUniform1f(loc(transition, "uP"), progress)
    GLES30.glUniform1i(loc(transition, "uType"), type)
    GLES30.glUniform2f(loc(transition, "uDir"), dirX, dirY)
    GLES30.glUniform4f(loc(transition, "uColor"), Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, 1f)
    GLES30.glUniform2f(loc(transition, "uSize"), target.width.toFloat(), target.height.toFloat())
    quad.draw()
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
  }

  /**
   * Draws a full-screen texture on a quad turned [angleDeg] around the vertical (or horizontal) axis,
   * seen through a perspective camera. [pivotDepth] > 0 turns it around a point behind the screen (cube faces).
   */
  fun drawTilted(tex: Int, angleDeg: Float, vertical: Boolean, pivotDepth: Float, shade: Float) {
    val w = target.width.toFloat()
    val h = target.height.toFloat()
    val fov = 40f
    val dist = (h / 2f) / tan(Math.toRadians(fov / 2.0)).toFloat()
    val persp = FloatArray(16)
    Matrix.perspectiveM(persp, 0, fov, w / h, dist * 0.1f, dist * 4f)
    Matrix.setIdentityM(model, 0)
    Matrix.translateM(model, 0, 0f, 0f, -dist - pivotDepth)
    if (vertical) Matrix.rotateM(model, 0, angleDeg, 1f, 0f, 0f) else Matrix.rotateM(model, 0, angleDeg, 0f, 1f, 0f)
    Matrix.translateM(model, 0, 0f, 0f, pivotDepth)
    Matrix.scaleM(model, 0, w, -h, 1f)
    val m = FloatArray(16)
    Matrix.multiplyMM(m, 0, persp, 0, model, 0)
    val pose = Pose(0f, 0f, w, h, 0f, 1f)
    drawTexture(tex, false, Gl.IDENTITY, pose, null, 0f, BlendMode.NORMAL, extraBrightness = -shade, matrix = m, applyDay = false)
  }

  private val locations = HashMap<Long, Int>()
  private fun loc(program: Int, name: String): Int {
    val key = (program.toLong() shl 32) or name.hashCode().toLong().and(0xffffffffL)
    return locations.getOrPut(key) { GLES30.glGetUniformLocation(program, name) }
  }

  fun release() {
    quad.release()
    for (p in listOf(layer2d.id, layerOes.id, gradient, particles, blur, transition, ripple, burst, glow)) GLES30.glDeleteProgram(p)
  }

  companion object {
    private val NEUTRAL = Effects()
  }
}

/** Two small buffers reused for one layer's blur. */
internal class BlurCache {
  private var a: Fbo? = null
  private var b: Fbo? = null
  var sourceFrame = -1L
  var amount = -1f
  var texture = 0

  fun ensure(w: Int, h: Int): Pair<Fbo, Fbo> {
    val fa = a?.also { it.ensureSize(w, h) } ?: Fbo(w, h).also { a = it }
    val fb = b?.also { it.ensureSize(w, h) } ?: Fbo(w, h).also { b = it }
    return fa to fb
  }

  fun release() {
    a?.release()
    b?.release()
    a = null
    b = null
    texture = 0
    sourceFrame = -1L
  }
}

internal object Geom {
  fun fract(x: Float): Float = x - kotlin.math.floor(x)
}
