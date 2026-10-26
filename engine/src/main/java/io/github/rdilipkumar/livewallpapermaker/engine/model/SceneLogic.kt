package io.github.rdilipkumar.livewallpapermaker.engine.model

import kotlin.random.Random

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

  private fun allowed(scene: Scene, minuteOfDay: Int, charging: Boolean): Boolean =
    !scene.isIntro &&
      (scene.timeWindow?.contains(minuteOfDay) ?: true) &&
      (!scene.onlyWhileCharging || charging)

  /** True when the scene shows something: any visible layer other than an empty media slot. */
  fun hasContent(scene: Scene, project: Project): Boolean = scene.layers.any { l ->
    l.visible && (l.type != LayerType.MEDIA || project.asset(l.assetId) != null)
  }

  /**
   * Scenes that may play now. Falls back to every non-intro scene, then to every scene.
   * Scenes that would only show black (e.g. a template slide whose slot is still empty) are skipped
   * whenever another scene has something to show.
   */
  fun eligibleScenes(project: Project, minuteOfDay: Int, charging: Boolean): List<Scene> {
    val filled = project.scenes.filter { hasContent(it, project) }
    val pool = if (filled.isNotEmpty()) project.copy(scenes = filled) else project
    val ok = pool.scenes.filter { allowed(it, minuteOfDay, charging) }
    if (ok.isNotEmpty()) return ok
    val normal = pool.scenes.filter { !it.isIntro && !it.onlyWhileCharging && it.timeWindow == null }
    if (normal.isNotEmpty()) return normal
    return pool.scenes.filter { !it.isIntro }.ifEmpty { pool.scenes }
  }

  /** Scene to show first, or after the current one stops being allowed. */
  fun pickScene(project: Project, minuteOfDay: Int, charging: Boolean, currentId: String?): Scene? {
    val eligible = eligibleScenes(project, minuteOfDay, charging)
    return eligible.firstOrNull { it.id == currentId } ?: eligible.firstOrNull()
  }

  /** Next scene in the rotation (timer, unlock, double-tap). */
  fun nextScene(
    project: Project,
    currentId: String?,
    minuteOfDay: Int,
    charging: Boolean,
    random: Random = Random.Default,
  ): Scene? {
    val eligible = eligibleScenes(project, minuteOfDay, charging)
    if (eligible.isEmpty()) return null
    if (eligible.size == 1) return eligible[0]
    val index = eligible.indexOfFirst { it.id == currentId }
    if (project.switching.shuffle) {
      val others = eligible.filter { it.id != currentId }
      return others[random.nextInt(others.size)]
    }
    return eligible[(index + 1).mod(eligible.size)]
  }

  fun introScene(project: Project): Scene? = project.scenes.firstOrNull { it.isIntro && hasContent(it, project) }

  fun introDurationMs(scene: Scene, project: Project): Long {
    if (scene.introDurationSec > 0f) return (scene.introDurationSec * 1000).toLong()
    val longest = scene.layers
      .filter { it.visible && it.type == LayerType.MEDIA }
      .mapNotNull { project.asset(it.assetId) }
      .filter { it.kind == AssetKind.VIDEO }
      .maxOfOrNull { it.durationMs } ?: 0L
    return if (longest > 0) longest else 3000L
  }

  fun layerIsAnimated(layer: Layer, project: Project): Boolean {
    if (!layer.visible) return false
    if (layer.motion.isTimeBased || layer.effects.isTimeBased) return true
    return when (layer.type) {
      LayerType.MEDIA -> project.asset(layer.assetId)?.kind == AssetKind.VIDEO
      LayerType.PARTICLES -> true
      LayerType.GRADIENT -> (layer.gradient?.flowSpeed ?: 0f) != 0f
      LayerType.TEXT -> false // text only changes once a minute; handled by its own check
    }
  }

  /** A scene needs a steady frame rate when anything in it moves on its own. Otherwise it is drawn only on change. */
  fun sceneIsAnimated(scene: Scene, project: Project): Boolean = scene.layers.any { layerIsAnimated(it, project) }

  fun sceneUsesTilt(scene: Scene): Boolean = scene.layers.any { it.visible && it.motion.tiltDepth != 0f }

  fun projectUsesTilt(project: Project): Boolean = project.scenes.any { sceneUsesTilt(it) }
}
