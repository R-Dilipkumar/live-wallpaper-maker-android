package io.github.rdilipkumar.livewallpapermaker.engine

import android.content.Context
import android.opengl.EGLSurface
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import io.github.rdilipkumar.livewallpapermaker.engine.gl.EglCore
import io.github.rdilipkumar.livewallpapermaker.engine.gl.Fbo
import io.github.rdilipkumar.livewallpapermaker.engine.model.BatteryBehavior
import io.github.rdilipkumar.livewallpapermaker.engine.model.Direction
import io.github.rdilipkumar.livewallpapermaker.engine.model.Geometry
import io.github.rdilipkumar.livewallpapermaker.engine.model.MotionInput
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.SceneLogic
import io.github.rdilipkumar.livewallpapermaker.engine.model.StorageLayout
import io.github.rdilipkumar.livewallpapermaker.engine.model.Transition
import io.github.rdilipkumar.livewallpapermaker.engine.model.TransitionType
import io.github.rdilipkumar.livewallpapermaker.engine.render.Painter
import io.github.rdilipkumar.livewallpapermaker.engine.render.SceneDrawer
import io.github.rdilipkumar.livewallpapermaker.engine.render.SourceStore
import io.github.rdilipkumar.livewallpapermaker.engine.render.Target
import io.github.rdilipkumar.livewallpapermaker.engine.render.TiltSensor
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

enum class TriggerEvent { UNLOCK, DOUBLE_TAP, POWER_CHANGED, SCREEN_ON }

/**
 * Plays a [Project] on a surface (the wallpaper's, or the editor's preview).
 *
 * Everything runs on one render thread. Frames are drawn only while visible, and only when something
 * changed or moves on its own; a still scene costs nothing after its first frame. Public methods are
 * safe to call from any thread.
 */
class WallpaperRenderer(
  context: Context,
  private val storage: StorageLayout,
  private val callbacks: Callbacks = object : Callbacks {},
) {
  interface Callbacks {
    /** Called on the render thread when a scene is fully on screen. */
    fun onSceneShown(sceneId: String) {}
  }

  private val appContext = context.applicationContext
  private val thread = HandlerThread("lwm-render", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
  private val handler = Handler(thread.looper)

  // ---- Render-thread state ----
  private var egl: EglCore? = null
  private var pbuffer: EGLSurface? = null
  private var window: EGLSurface? = null
  private var painter: Painter? = null
  private var sources: SourceStore? = null
  private var drawer: SceneDrawer? = null
  private var fboFrom: Fbo? = null
  private var fboTo: Fbo? = null
  private var width = 0
  private var height = 0
  private var broken = false

  private var project: Project? = null
  private var visible = false
  private var userPaused = false
  private var swipe = 0.5f
  private var shadeZoom = 0f
  private var autoScenes = true
  private var tiltAllowed = true
  private var manualTilt: Pair<Float, Float>? = null
  private val tilt = TiltSensor(appContext, handler) { requestFrame() }
  private var powerSave = false
  private var thermal = PowerManager.THERMAL_STATUS_NONE

  private var clockMs = 0.0
  private var lastFrameNanos = 0L
  private var lastDrawNanos = 0L
  private var frameScheduled = false
  private var dirty = true

  private class Showing(val sceneId: String, var startClock: Double)
  private var current: Showing? = null
  private var next: Showing? = null
  private var transition: Transition? = null
  private var transitionStart = -1.0
  private var preparingSince = 0.0
  private var lastSwitchClock = 0.0
  private var introReturnId: String? = null
  private var introEndsAt = -1.0
  private var lastRuleCheckMs = 0L

  private val frameCallback = Choreographer.FrameCallback { t ->
    frameScheduled = false
    onFrame(t)
  }
  private val stopDecodersRunnable = Runnable { sources?.stopVideos() }
  private val minuteTick = Runnable {
    dirty = true
    requestFrame()
    scheduleMinuteTick()
  }

  // ---------------- Public API ----------------

  /** [surface] is a Surface or SurfaceTexture; null means it was destroyed (this call then waits until it is released). */
  fun setSurface(surface: Any?, w: Int, h: Int) {
    if (surface == null) {
      runSync { destroyWindow() }
    } else {
      handler.post { createWindow(surface, w, h) }
    }
  }

  fun setSize(w: Int, h: Int) = handler.post {
    if (w > 0 && h > 0 && (w != width || h != height)) {
      width = w
      height = h
      sources?.screenW = w
      sources?.screenH = h
      project?.let { p -> scenesOnScreen(p).forEach { sources?.prepare(it, p) } }
      dirty = true
      requestFrame()
    }
  }

  fun setVisible(v: Boolean) = handler.post { applyVisible(v) }

  fun setProject(p: Project?) = handler.post { applyProject(p) }

  /** Editor: jump to a scene. [animate] plays its transition. */
  fun showScene(sceneId: String, animate: Boolean) = handler.post {
    val p = project ?: return@post
    val scene = p.scene(sceneId) ?: return@post
    if (animate) goTo(scene, p) else {
      finishTransitionNow(p)
      if (current?.sceneId != scene.id) {
        current = Showing(scene.id, clockMs)
        currentSceneId = scene.id
        sources?.prepare(scene, p)
        releaseUnused(p)
        updateTiltSensor()
        dirty = true
      }
      requestFrame()
    }
  }

  /** Launcher page offset, 0..1. */
  fun setSwipe(x: Float) = handler.post {
    if (x != swipe) {
      swipe = x.coerceIn(0f, 1f)
      dirty = true
      requestFrame()
    }
  }

  /** How far the notification shade is pulled down (0..1). The wallpaper zooms in a little when enabled. */
  fun setShadeZoom(zoom: Float) = handler.post {
    val z = zoom.coerceIn(0f, 1f)
    if (z != shadeZoom) {
      shadeZoom = z
      dirty = true
      requestFrame()
    }
  }

  /**
   * Editor: when false, scenes only change when asked ([showScene]); no timers, time-of-day rules,
   * intros or triggers. The wallpaper always runs with it on.
   */
  fun setAutoScenes(enabled: Boolean) = handler.post {
    autoScenes = enabled
    handler.removeCallbacks(wakeRunnable)
    dirty = true
    requestFrame()
  }

  /** Editor: turn the real tilt sensor on or off. */
  fun setTiltAllowed(allowed: Boolean) = handler.post {
    tiltAllowed = allowed
    updateTiltSensor()
    dirty = true
    requestFrame()
  }

  /** Editor: fake a tilt (-1..1); null returns to the sensor. */
  fun setManualTilt(x: Float?, y: Float?) = handler.post {
    manualTilt = if (x == null || y == null) null else x to y
    dirty = true
    requestFrame()
  }

  fun setPaused(paused: Boolean) = handler.post {
    userPaused = paused
    dirty = true
    requestFrame()
  }

  fun setPowerState(batterySaver: Boolean, thermalStatus: Int) = handler.post {
    powerSave = batterySaver
    thermal = thermalStatus
    dirty = true
    requestFrame()
  }

  fun trigger(event: TriggerEvent) = handler.post { onTrigger(event) }

  fun redraw() = handler.post {
    dirty = true
    requestFrame()
  }

  /** Current scene time in seconds, for the editor's keyframe tools. */
  @Volatile
  var sceneTimeSec: Float = 0f
    private set

  @Volatile
  var currentSceneId: String? = null
    private set

  fun release() {
    runSync {
      tilt.stop()
      handler.removeCallbacksAndMessages(null)
      if (frameScheduled) Choreographer.getInstance().removeFrameCallback(frameCallback)
      frameScheduled = false
      releaseGl()
    }
    thread.quitSafely()
  }

  // ---------------- Render thread ----------------

  private fun runSync(block: () -> Unit) {
    if (Thread.currentThread() == thread) {
      block()
      return
    }
    val latch = CountDownLatch(1)
    val posted = handler.post {
      try {
        block()
      } finally {
        latch.countDown()
      }
    }
    if (posted) latch.await(2, TimeUnit.SECONDS)
  }

  private fun initGl() {
    try {
      val core = EglCore()
      egl = core
      pbuffer = core.createPbuffer(1, 1).also { core.makeCurrent(it) }
      painter = Painter()
      val store = SourceStore(appContext, storage, handler) { requestFrame() }
      sources = store
      drawer = SceneDrawer(painter!!, store)
    } catch (t: Throwable) {
      Log.e(TAG, "GL init failed", t)
      broken = true
    }
  }

  private fun makeCurrent(): Boolean {
    val core = egl ?: return false
    val s = window ?: pbuffer ?: return false
    return core.makeCurrent(s)
  }

  private fun createWindow(surface: Any, w: Int, h: Int) {
    if (broken) return
    destroyWindow()
    try {
      window = egl?.createWindowSurface(surface)
      makeCurrent()
      width = w
      height = h
      sources?.screenW = w
      sources?.screenH = h
      project?.let { p -> scenesOnScreen(p).forEach { sources?.prepare(it, p) } }
      dirty = true
      requestFrame()
    } catch (t: Throwable) {
      Log.e(TAG, "Window surface failed", t)
      window = null
    }
  }

  private fun destroyWindow() {
    val w = window ?: return
    if (frameScheduled) Choreographer.getInstance().removeFrameCallback(frameCallback)
    frameScheduled = false
    egl?.let { core ->
      pbuffer?.let { core.makeCurrent(it) }
      core.releaseSurface(w)
    }
    window = null
  }

  private fun releaseGl() {
    destroyWindow()
    if (egl != null && pbuffer != null) {
      makeCurrent()
      sources?.releaseAll()
      drawer?.release()
      fboFrom?.release()
      fboTo?.release()
      painter?.release()
      egl?.releaseSurface(pbuffer!!)
      egl?.release()
    }
    egl = null
    pbuffer = null
    painter = null
    sources = null
    drawer = null
  }

  private fun applyVisible(v: Boolean) {
    if (v == visible) return
    visible = v
    if (v) {
      handler.removeCallbacks(stopDecodersRunnable)
      makeCurrent()
      sources?.startVideos()
      lastFrameNanos = 0L
      project?.let { checkSceneRules(it, force = true) }
      updateTiltSensor()
      scheduleMinuteTick()
      dirty = true
      requestFrame()
    } else {
      tilt.stop()
      handler.removeCallbacks(minuteTick)
      handler.removeCallbacks(wakeRunnable)
      if (frameScheduled) Choreographer.getInstance().removeFrameCallback(frameCallback)
      frameScheduled = false
      handler.postDelayed(stopDecodersRunnable, HIDDEN_DECODER_TIMEOUT_MS)
    }
  }

  private fun applyProject(p: Project?) {
    makeCurrent()
    val old = project
    project = p
    if (p == null) {
      current = null
      currentSceneId = null
      next = null
      transition = null
      sources?.releaseAll()
      drawer?.retainBlurs(emptySet())
      dirty = true
      requestFrame()
      return
    }
    if (old?.id != p.id) {
      current = null
      next = null
      transition = null
      transitionStart = -1.0
      introReturnId = null
    }
    // Keep showing the same scene when the project is edited.
    val cur = current
    if (cur != null && p.scene(cur.sceneId) == null) current = null
    val nx = next
    if (nx != null && p.scene(nx.sceneId) == null) {
      next = null
      transition = null
    }
    if (current == null) {
      val scene = if (autoScenes) SceneLogic.pickScene(p, minuteOfDay(), isCharging(), null) else p.scenes.firstOrNull()
      if (scene != null) {
        current = Showing(scene.id, clockMs)
        lastSwitchClock = clockMs
      }
    }
    currentSceneId = current?.sceneId
    scenesOnScreen(p).forEach { sources?.prepare(it, p) }
    releaseUnused(p)
    updateTiltSensor()
    scheduleMinuteTick()
    dirty = true
    requestFrame()
  }

  private fun scenesOnScreen(p: Project): List<Scene> =
    listOfNotNull(current?.let { p.scene(it.sceneId) }, next?.let { p.scene(it.sceneId) })

  private fun releaseUnused(p: Project) {
    val on = scenesOnScreen(p)
    sources?.retainOnly(on, p)
    drawer?.retainBlurs(on.map { it.id }.toSet())
  }

  private fun updateTiltSensor() {
    val p = project
    val wanted = visible && tiltAllowed && manualTilt == null && p != null &&
      scenesOnScreen(p).any { SceneLogic.sceneUsesTilt(it) }
    if (wanted) tilt.start() else tilt.stop()
  }

  private fun requestFrame() {
    if (frameScheduled || !visible || window == null || broken) return
    frameScheduled = true
    Choreographer.getInstance().postFrameCallback(frameCallback)
  }

  private fun scheduleMinuteTick() {
    handler.removeCallbacks(minuteTick)
    val p = project ?: return
    if (!visible) return
    val clockText = scenesOnScreen(p).any { s -> sources?.hasClockText(s.id) == true }
    if (!clockText) return
    val now = System.currentTimeMillis()
    handler.postDelayed(minuteTick, 60_000L - now % 60_000L + 50L)
  }

  /** Frame interval in ns, or null when the wallpaper should freeze (battery saver "pause", overheating). */
  private fun frameIntervalNanos(p: Project): Long? {
    var fps = p.settings.fpsCap.coerceIn(10, 60)
    if (powerSave) when (p.settings.batterySaver) {
      BatteryBehavior.KEEP -> Unit
      BatteryBehavior.REDUCE -> fps = min(fps, 15)
      BatteryBehavior.PAUSE -> return null
    }
    if (thermal >= PowerManager.THERMAL_STATUS_CRITICAL) return null
    if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) fps = min(fps, 15)
    return 1_000_000_000L / fps
  }

  private fun onFrame(frameNanos: Long) {
    if (!visible || window == null || broken) return
    try {
      frame(frameNanos)
    } catch (t: Throwable) {
      Log.e(TAG, "Frame failed", t)
      dirty = false
    }
  }

  private fun frame(frameNanos: Long) {
    val p = project
    if (p == null) {
      if (dirty && makeCurrent()) {
        painter?.use(Target(null, width, height))
        painter?.clear(0xFF000000.toInt())
        egl?.swap(window!!)
        dirty = false
      }
      return
    }
    val interval = frameIntervalNanos(p)
    val frozen = interval == null || userPaused
    // Real time while visible (also across idle gaps, so scene timers fire); stopped while frozen.
    if (lastFrameNanos != 0L && !frozen) clockMs += (frameNanos - lastFrameNanos) / 1_000_000.0
    lastFrameNanos = if (frozen) 0L else frameNanos
    makeCurrent()

    updateDirector(p)
    val src = sources ?: return
    if (!frozen) src.tickVideos { id -> sceneTimeUs(id) }
    val newFrame = src.latchVideos()
    val onScreen = scenesOnScreen(p)
    if (src.refreshTexts(System.currentTimeMillis(), onScreen.map { it.id }.toSet())) dirty = true

    val transitioning = next != null
    val animated = !frozen && (transitioning || onScreen.any { SceneLogic.sceneIsAnimated(it, p) })
    val due = interval == null || frameNanos - lastDrawNanos >= interval - 2_000_000L
    if ((dirty || newFrame || animated) && due) {
      draw(p)
      lastDrawNanos = frameNanos
      dirty = false
    }
    if (animated || dirty || (next != null && !frozen) || newFrame) {
      requestFrame()
    } else if (!frozen) {
      scheduleWake(p)
    }
  }

  /** While idle, wake up in time for the next timed event (scene timer, end of an intro). */
  private fun scheduleWake(p: Project) {
    handler.removeCallbacks(wakeRunnable)
    if (!autoScenes) return
    var waitMs = Double.MAX_VALUE
    val timer = p.switching.timerSec
    if (timer > 0 && SceneLogic.eligibleScenes(p, minuteOfDay(), isCharging()).size > 1) {
      waitMs = minOf(waitMs, lastSwitchClock + timer * 1000.0 - clockMs)
    }
    if (introReturnId != null && introEndsAt >= 0) waitMs = minOf(waitMs, introEndsAt - clockMs)
    if (waitMs == Double.MAX_VALUE) return
    handler.postDelayed(wakeRunnable, waitMs.toLong().coerceIn(16L, 3_600_000L))
  }

  private val wakeRunnable = Runnable { requestFrame() }

  private fun sceneTimeUs(sceneId: String): Long? {
    val s = when (sceneId) {
      current?.sceneId -> current
      next?.sceneId -> next
      else -> null
    } ?: return null
    return ((clockMs - s.startClock) * 1000.0).toLong().coerceAtLeast(0L)
  }

  private fun motionFor(sceneId: String): MotionInput {
    val s = if (current?.sceneId == sceneId) current else next
    val t = ((clockMs - (s?.startClock ?: clockMs)) / 1000.0).toFloat()
    val (tx, ty) = manualTilt ?: (tilt.x to tilt.y)
    return MotionInput(timeSec = t, swipe = swipe, tiltX = tx, tiltY = ty)
  }

  private fun draw(p: Project) {
    val pt = painter ?: return
    val dr = drawer ?: return
    val core = egl ?: return
    val win = window ?: return
    if (width <= 0 || height <= 0) return
    pt.setScreenWidth(width)
    pt.screenZoom = if (p.settings.zoomOnShade) 1f + 0.07f * shadeZoom else 1f
    val cur = current?.let { p.scene(it.sceneId) }
    val nxt = next?.let { p.scene(it.sceneId) }
    val tr = transition
    val progress = transitionProgress()
    if (cur != null && nxt != null && tr != null && progress != null) {
      val needMips = tr.type == TransitionType.BLUR
      val from = ensureFbo(fboFrom, needMips).also { fboFrom = it }
      val to = ensureFbo(fboTo, needMips).also { fboTo = it }
      pt.use(Target(from, width, height))
      dr.draw(cur, p, motionFor(cur.id), width, height)
      pt.use(Target(to, width, height))
      dr.draw(nxt, p, motionFor(nxt.id), width, height)
      if (needMips) {
        from.generateMipmaps()
        to.generateMipmaps()
      }
      pt.use(Target(null, width, height))
      drawTransition(pt, tr, progress, from, to)
    } else if (cur != null) {
      pt.use(Target(null, width, height))
      dr.draw(cur, p, motionFor(cur.id), width, height)
    } else {
      pt.use(Target(null, width, height))
      pt.clear(0xFF000000.toInt())
    }
    sceneTimeSec = motionFor(current?.sceneId ?: "").timeSec
    core.swap(win)
  }

  private fun ensureFbo(existing: Fbo?, mipmap: Boolean): Fbo {
    if (existing != null && existing.width == width && existing.height == height && (!mipmap || mipmapped.contains(existing))) {
      return existing
    }
    existing?.release()
    mipmapped.remove(existing)
    return Fbo(width, height, mipmap).also { if (mipmap) mipmapped += it }
  }

  private val mipmapped = HashSet<Fbo>()

  private fun drawTransition(pt: Painter, tr: Transition, rawProgress: Float, from: Fbo, to: Fbo) {
    val p = Geometry.ease(io.github.rdilipkumar.livewallpapermaker.engine.model.Easing.EASE_IN_OUT, rawProgress)
    val (dx, dy) = when (tr.direction) {
      Direction.LEFT -> -1f to 0f
      Direction.RIGHT -> 1f to 0f
      Direction.UP -> 0f to 1f
      Direction.DOWN -> 0f to -1f
    }
    when (tr.type) {
      TransitionType.FLIP_3D -> {
        pt.clear(0xFF000000.toInt())
        val vertical = tr.direction == Direction.UP || tr.direction == Direction.DOWN
        val sign = if (tr.direction == Direction.LEFT || tr.direction == Direction.DOWN) 1f else -1f
        val angle = p * 180f
        val shade = kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat() * 0.35f
        if (angle < 90f) pt.drawTilted(from.texture, sign * angle, vertical, 0f, shade)
        else pt.drawTilted(to.texture, sign * (angle - 180f), vertical, 0f, shade)
      }
      TransitionType.CUBE -> {
        pt.clear(0xFF000000.toInt())
        val vertical = tr.direction == Direction.UP || tr.direction == Direction.DOWN
        val sign = if (tr.direction == Direction.LEFT || tr.direction == Direction.DOWN) 1f else -1f
        val half = (if (vertical) height else width) / 2f
        val angle = p * 90f
        pt.drawTilted(from.texture, sign * angle, vertical, half, p * 0.45f)
        pt.drawTilted(to.texture, sign * (angle - 90f), vertical, half, (1f - p) * 0.45f)
      }
      else -> pt.drawTransition(from.texture, to.texture, transitionCode(tr.type), p, dx, dy, tr.color)
    }
  }

  private fun transitionCode(type: TransitionType): Int = when (type) {
    TransitionType.CUT, TransitionType.CROSSFADE -> 1
    TransitionType.FADE_COLOR -> 2
    TransitionType.SLIDE -> 3
    TransitionType.PUSH -> 4
    TransitionType.ZOOM_IN -> 5
    TransitionType.ZOOM_OUT -> 6
    TransitionType.FLIP_3D -> 7
    TransitionType.CUBE -> 8
    TransitionType.CIRCLE -> 9
    TransitionType.WIPE -> 10
    TransitionType.DISSOLVE -> 11
    TransitionType.BLUR -> 12
  }

  /** null while the next scene is still loading. */
  private fun transitionProgress(): Float? {
    val tr = transition ?: return null
    if (transitionStart < 0) return null
    val d = tr.durationMs.coerceAtLeast(1)
    return ((clockMs - transitionStart) / d).toFloat().coerceIn(0f, 1f)
  }

  // ---------------- Scene director ----------------

  private fun updateDirector(p: Project) {
    val nx = next
    if (nx != null) {
      val nextScene = p.scene(nx.sceneId)
      if (transitionStart < 0) {
        val ready = nextScene == null || sources?.sceneReady(nextScene) != false
        if (ready || clockMs - preparingSince > PREPARE_TIMEOUT_MS) {
          val tr = transition
          if (tr == null || tr.type == TransitionType.CUT || tr.durationMs <= 0) {
            finishTransitionNow(p)
          } else {
            transitionStart = clockMs
            nx.startClock = clockMs
          }
        }
      } else if ((transitionProgress() ?: 0f) >= 1f) {
        finishTransitionNow(p)
      }
      return
    }
    if (!autoScenes) return
    val cur = current ?: return
    val curScene = p.scene(cur.sceneId) ?: return

    val returnId = introReturnId
    if (curScene.isIntro && returnId != null) {
      if (introEndsAt >= 0 && clockMs >= introEndsAt) {
        introReturnId = null
        introEndsAt = -1.0
        val back = p.scene(returnId) ?: SceneLogic.pickScene(p, minuteOfDay(), isCharging(), null)
        if (back != null) goTo(back, p)
      }
      return
    }

    val timer = p.switching.timerSec
    if (timer > 0 && clockMs - lastSwitchClock >= timer * 1000.0) {
      SceneLogic.nextScene(p, cur.sceneId, minuteOfDay(), isCharging())?.let { goTo(it, p) }
      lastSwitchClock = clockMs
      return
    }
    checkSceneRules(p, force = false)
  }

  /** Moves away from the current scene when its time window / charging rule no longer allows it. */
  private fun checkSceneRules(p: Project, force: Boolean) {
    if (!autoScenes) return
    val now = SystemClock.uptimeMillis()
    if (!force && now - lastRuleCheckMs < RULE_CHECK_MS) return
    lastRuleCheckMs = now
    if (next != null) return
    val cur = current ?: return
    val curScene = p.scene(cur.sceneId) ?: return
    if (curScene.isIntro) return
    val eligible = SceneLogic.eligibleScenes(p, minuteOfDay(), isCharging())
    if (eligible.none { it.id == cur.sceneId }) eligible.firstOrNull()?.let { goTo(it, p) }
  }

  private fun goTo(scene: Scene, p: Project) {
    finishTransitionNow(p)
    if (current?.sceneId == scene.id) return
    sources?.prepare(scene, p)
    next = Showing(scene.id, clockMs)
    transition = scene.transitionIn
    transitionStart = -1.0
    preparingSince = clockMs
    lastSwitchClock = clockMs
    updateTiltSensor()
    dirty = true
    requestFrame()
  }

  private fun finishTransitionNow(p: Project) {
    val nx = next ?: return
    if (transitionStart < 0) nx.startClock = clockMs
    current = nx
    next = null
    transition = null
    transitionStart = -1.0
    lastSwitchClock = clockMs
    val scene = p.scene(nx.sceneId)
    if (scene != null && scene.isIntro && introReturnId != null) {
      introEndsAt = clockMs + SceneLogic.introDurationMs(scene, p)
    }
    releaseUnused(p)
    updateTiltSensor()
    scheduleMinuteTick()
    currentSceneId = nx.sceneId
    callbacks.onSceneShown(nx.sceneId)
    dirty = true
  }

  private fun onTrigger(event: TriggerEvent) {
    val p = project ?: return
    if (!autoScenes) return
    when (event) {
      TriggerEvent.UNLOCK -> {
        val intro = SceneLogic.introScene(p)
        if (intro != null) {
          val back = next?.sceneId ?: current?.sceneId
          if (back != null && back != intro.id) {
            introReturnId = back
            introEndsAt = -1.0
            goTo(intro, p)
            return
          }
        }
        if (p.switching.onUnlock) {
          SceneLogic.nextScene(p, current?.sceneId, minuteOfDay(), isCharging())?.let { goTo(it, p) }
        }
      }
      TriggerEvent.DOUBLE_TAP -> if (p.switching.onDoubleTap) {
        SceneLogic.nextScene(p, current?.sceneId, minuteOfDay(), isCharging())?.let { goTo(it, p) }
      }
      TriggerEvent.POWER_CHANGED, TriggerEvent.SCREEN_ON -> checkSceneRules(p, force = true)
    }
    requestFrame()
  }

  private fun minuteOfDay(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
  }

  private fun isCharging(): Boolean =
    runCatching { appContext.getSystemService(BatteryManager::class.java)?.isCharging == true }.getOrDefault(false)

  // Last in the class so every field above is initialised before the render thread starts using them.
  init {
    handler.post { initGl() }
  }

  private companion object {
    const val TAG = "LwmRenderer"
    const val HIDDEN_DECODER_TIMEOUT_MS = 30_000L
    const val PREPARE_TIMEOUT_MS = 900.0
    const val RULE_CHECK_MS = 15_000L
  }
}
