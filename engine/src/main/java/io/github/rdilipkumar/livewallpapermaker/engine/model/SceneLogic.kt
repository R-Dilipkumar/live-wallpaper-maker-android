package io.github.rdilipkumar.livewallpapermaker.engine.model

import kotlin.math.abs
import kotlin.random.Random

/** What the phone is doing right now; scenes can be limited to some of these situations. */
data class Conditions(
  val minuteOfDay: Int = 12 * 60,
  val charging: Boolean = false,
  /** True while the lock screen is showing. */
  val locked: Boolean = false,
  /** Battery level in percent. */
  val battery: Int = 100,
)

/** Pure rules for picking scenes and deciding how often to redraw. No Android calls, so they are unit-tested. */
object SceneLogic {

  fun TimeWindow.contains(minuteOfDay: Int): Boolean {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    val s = ((startMinute % 1440) + 1440) % 1440
    val e = ((endMinute % 1440) + 1440) % 1440
    return when {
      s == e -> true
      s < e -> m in s until e
      else -> m >= s || m < e
    }
  }

  private fun allowed(scene: Scene, c: Conditions): Boolean =
    !scene.isIntro &&
      (scene.timeWindow?.contains(c.minuteOfDay) ?: true) &&
      (!scene.onlyWhileCharging || c.charging) &&
      when (scene.showOn) {
        ScreenFilter.ANY -> true
        ScreenFilter.LOCK_SCREEN -> c.locked
        ScreenFilter.HOME_SCREEN -> !c.locked
      } &&
      (scene.batteryBelow <= 0 || c.battery <= scene.batteryBelow)

  /** Scenes with no rules at all. */
  private fun unconditional(scene: Scene): Boolean =
    !scene.isIntro && !scene.onlyWhileCharging && scene.timeWindow == null &&
      scene.showOn == ScreenFilter.ANY && scene.batteryBelow <= 0

  /** True when the scene shows something: any visible layer other than an empty media slot. */
  fun hasContent(scene: Scene, project: Project): Boolean = scene.layers.any { l ->
    l.visible && (l.type != LayerType.MEDIA || project.asset(l.assetId) != null)
  }

  /**
   * Scenes that may play now. Falls back to scenes without rules, then to every non-intro scene.
   * Scenes that would only show black (e.g. a template slide whose slot is still empty) are skipped
   * whenever another scene has something to show.
   */
  fun eligibleScenes(project: Project, c: Conditions): List<Scene> {
    val filled = project.scenes.filter { hasContent(it, project) }
    val pool = filled.ifEmpty { project.scenes }
    val ok = pool.filter { allowed(it, c) }
    if (ok.isNotEmpty()) return ok
    val normal = pool.filter { unconditional(it) }
    if (normal.isNotEmpty()) return normal
    return pool.filter { !it.isIntro }.ifEmpty { pool }
  }

  fun eligibleScenes(project: Project, minuteOfDay: Int, charging: Boolean): List<Scene> =
    eligibleScenes(project, Conditions(minuteOfDay, charging))

  /** Scene to show first, or after the current one stops being allowed. */
  fun pickScene(project: Project, c: Conditions, currentId: String?): Scene? {
    val eligible = eligibleScenes(project, c)
    return eligible.firstOrNull { it.id == currentId } ?: eligible.firstOrNull()
  }

  fun pickScene(project: Project, minuteOfDay: Int, charging: Boolean, currentId: String?): Scene? =
    pickScene(project, Conditions(minuteOfDay, charging), currentId)

  /** Next scene in the rotation (timer, unlock, double-tap). */
  fun nextScene(project: Project, currentId: String?, c: Conditions, random: Random = Random.Default): Scene? {
    val eligible = eligibleScenes(project, c)
    if (eligible.isEmpty()) return null
    if (eligible.size == 1) return eligible[0]
    val index = eligible.indexOfFirst { it.id == currentId }
    if (project.switching.shuffle) {
      val others = eligible.filter { it.id != currentId }
      return others[random.nextInt(others.size)]
    }
    return eligible[(index + 1).mod(eligible.size)]
  }

  fun nextScene(
    project: Project,
    currentId: String?,
    minuteOfDay: Int,
    charging: Boolean,
    random: Random = Random.Default,
  ): Scene? = nextScene(project, currentId, Conditions(minuteOfDay, charging), random)

  fun introScene(project: Project): Scene? = project.scenes.firstOrNull { it.isIntro && hasContent(it, project) }

  fun introDurationMs(scene: Scene, project: Project): Long {
    if (scene.introDurationSec > 0f) return (scene.introDurationSec * 1000).toLong()
    val longest = scene.layers
      .filter { it.visible && it.type == LayerType.MEDIA }
      .mapNotNull { project.asset(it.assetId) }
      .filter { it.kind == AssetKind.VIDEO || it.kind == AssetKind.STICKER }
      .maxOfOrNull { if (it.kind == AssetKind.STICKER) (it.sticker?.totalMs ?: 0).toLong() else it.durationMs } ?: 0L
    return if (longest > 0) longest else 3000L
  }

  fun layerIsAnimated(layer: Layer, project: Project): Boolean {
    if (!layer.visible) return false
    if (layer.motion.isTimeBased || layer.effects.isTimeBased) return true
    return when (layer.type) {
      LayerType.MEDIA -> project.asset(layer.assetId)?.let { a ->
        a.kind == AssetKind.VIDEO || (a.kind == AssetKind.STICKER && (a.sticker?.delays?.size ?: 0) > 1)
      } ?: false
      LayerType.PARTICLES -> true
      LayerType.GRADIENT -> (layer.gradient?.flowSpeed ?: 0f) != 0f
      LayerType.TEXT -> false // text only changes once a minute; handled by its own check
    }
  }

  /** A scene needs a steady frame rate when anything in it moves on its own. Otherwise it is drawn only on change. */
  fun sceneIsAnimated(scene: Scene, project: Project): Boolean = scene.layers.any { layerIsAnimated(it, project) }

  fun sceneUsesTilt(scene: Scene): Boolean =
    scene.layers.any { it.visible && (it.motion.tiltDepth != 0f || it.particles?.followTilt == true) }

  fun projectUsesTilt(project: Project): Boolean = project.scenes.any { sceneUsesTilt(it) }

  // ---------------- Colour that follows the day ----------------

  /** A tint (RGB 0..1) plus how much of it to apply. */
  data class DayTint(val r: Float, val g: Float, val b: Float, val amount: Float, val dim: Float)

  private class Stop(val minute: Int, val r: Float, val g: Float, val b: Float, val amount: Float, val dim: Float)

  // Night blue → dawn orange → clear day → golden hour → sunset rose → night blue.
  private val stops = listOf(
    Stop(0, 0.25f, 0.35f, 0.85f, 0.35f, 0.25f),
    Stop(5 * 60, 0.25f, 0.35f, 0.85f, 0.30f, 0.20f),
    Stop(6 * 60 + 30, 1.0f, 0.62f, 0.35f, 0.30f, 0.05f),
    Stop(9 * 60, 1.0f, 0.95f, 0.85f, 0.05f, 0f),
    Stop(15 * 60 + 30, 1.0f, 0.95f, 0.85f, 0.05f, 0f),
    Stop(17 * 60 + 30, 1.0f, 0.72f, 0.35f, 0.28f, 0f),
    Stop(19 * 60, 0.95f, 0.45f, 0.55f, 0.32f, 0.08f),
    Stop(20 * 60 + 30, 0.25f, 0.35f, 0.85f, 0.35f, 0.22f),
    Stop(24 * 60, 0.25f, 0.35f, 0.85f, 0.35f, 0.25f),
  )

  /** The colour cast for this minute of the day, scaled by [strength] (0..1). */
  fun dayTint(minuteOfDay: Int, strength: Float): DayTint {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    val i = stops.indexOfLast { it.minute <= m }.coerceIn(0, stops.size - 2)
    val a = stops[i]
    val b = stops[i + 1]
    val f = if (b.minute == a.minute) 0f else (m - a.minute).toFloat() / (b.minute - a.minute)
    val s = f * f * (3f - 2f * f)
    fun lerp(x: Float, y: Float) = x + (y - x) * s
    val k = strength.coerceIn(0f, 1f)
    return DayTint(lerp(a.r, b.r), lerp(a.g, b.g), lerp(a.b, b.b), lerp(a.amount, b.amount) * k, lerp(a.dim, b.dim) * k)
  }

  /** True when two tints differ enough to be worth a redraw. */
  fun tintChanged(a: DayTint?, b: DayTint): Boolean =
    a == null || abs(a.amount - b.amount) > 0.003f || abs(a.r - b.r) + abs(a.g - b.g) + abs(a.b - b.b) > 0.01f
}
