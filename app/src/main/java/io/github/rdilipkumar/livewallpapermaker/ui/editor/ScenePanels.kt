package io.github.rdilipkumar.livewallpapermaker.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rdilipkumar.livewallpapermaker.engine.model.AnimProperty
import io.github.rdilipkumar.livewallpapermaker.engine.model.BatteryBehavior
import io.github.rdilipkumar.livewallpapermaker.engine.model.Direction
import io.github.rdilipkumar.livewallpapermaker.engine.model.Easing
import io.github.rdilipkumar.livewallpapermaker.engine.model.Keyframe
import io.github.rdilipkumar.livewallpapermaker.engine.model.KeyframeTrack
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LoopMode
import io.github.rdilipkumar.livewallpapermaker.engine.model.MotionPreset
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.ScreenFilter
import io.github.rdilipkumar.livewallpapermaker.engine.model.TouchKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.TimeWindow
import io.github.rdilipkumar.livewallpapermaker.engine.model.TransitionType
import io.github.rdilipkumar.livewallpapermaker.ui.ChipRow
import io.github.rdilipkumar.livewallpapermaker.ui.ColorPickerDialog
import io.github.rdilipkumar.livewallpapermaker.ui.ColorSwatch
import io.github.rdilipkumar.livewallpapermaker.ui.Hint
import io.github.rdilipkumar.livewallpapermaker.ui.LabeledSlider
import io.github.rdilipkumar.livewallpapermaker.ui.MainViewModel
import io.github.rdilipkumar.livewallpapermaker.ui.SectionTitle
import io.github.rdilipkumar.livewallpapermaker.ui.SwitchRow
import io.github.rdilipkumar.livewallpapermaker.ui.percent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun nice(name: String) = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun propRange(p: AnimProperty): ClosedFloatingPointRange<Float> = when (p) {
  AnimProperty.X, AnimProperty.Y -> -1f..1f
  AnimProperty.SCALE -> 0.1f..4f
  AnimProperty.ROTATION -> -360f..360f
  AnimProperty.OPACITY -> 0f..1f
}

private fun propDefault(p: AnimProperty): Float = when (p) {
  AnimProperty.SCALE, AnimProperty.OPACITY -> 1f
  else -> 0f
}

private fun propLabel(p: AnimProperty) = when (p) {
  AnimProperty.X -> "Move left/right"
  AnimProperty.Y -> "Move up/down"
  AnimProperty.SCALE -> "Size"
  AnimProperty.ROTATION -> "Turn"
  AnimProperty.OPACITY -> "Opacity"
}

@Composable
fun MotionPanel(vm: MainViewModel, layer: Layer?, controller: PreviewController) {
  if (layer == null) return NoLayer()
  val id = layer.id
  val m = layer.motion
  fun set(key: String?, f: (io.github.rdilipkumar.livewallpapermaker.engine.model.Motion) -> io.github.rdilipkumar.livewallpapermaker.engine.model.Motion) =
    vm.updateLayer(id, key) { it.copy(motion = f(it.motion)) }

  SectionTitle("Depth")
  Hint("Give layers different depths for a 3D feel. Use negative values to move the other way. Try the swipe slider and tilt button under the preview.")
  LabeledSlider("Swipe parallax", m.swipeDepth, -0.3f..0.3f, { v -> set("swipe") { it.copy(swipeDepth = v) } }, format = ::percent)
  LabeledSlider("Tilt parallax", m.tiltDepth, -0.2f..0.2f, { v -> set("tilt") { it.copy(tiltDepth = v) } }, format = ::percent)

  SectionTitle("Animation")
  ChipRow(MotionPreset.entries, m.preset, { if (it == MotionPreset.NONE) "None" else nice(it.name) }, { p -> set(null) { it.copy(preset = p) } })
  if (m.preset != MotionPreset.NONE) {
    LabeledSlider("Amount", m.presetAmount, 0f..1f, { v -> set("pa") { it.copy(presetAmount = v) } }, format = ::percent)
    LabeledSlider("Cycle length", m.presetPeriodSec, 1f..60f, { v -> set("pp") { it.copy(presetPeriodSec = v) } }, format = { "%.1f s".format(it) })
    if (m.preset == MotionPreset.SPIN) Hint("Amount under 50% spins the other way.")
  }

  SectionTitle("Keyframes")
  Hint("Make your own movement: each track changes one property over time.")
  m.keyframes.forEachIndexed { ti, track ->
    fun setTrack(key: String?, f: (KeyframeTrack) -> KeyframeTrack) =
      set(key) { mo -> mo.copy(keyframes = mo.keyframes.toMutableList().also { it[ti] = f(it[ti]) }) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
      Column(Modifier.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(propLabel(track.property), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
          IconButton(onClick = { set(null) { mo -> mo.copy(keyframes = mo.keyframes.filterIndexed { i, _ -> i != ti }) } }) { Icon(Icons.Default.Delete, "Remove track") }
        }
        ChipRow(LoopMode.entries, track.loop, { nice(it.name) }, { l -> setTrack(null) { it.copy(loop = l) } })
        ChipRow(Easing.entries, track.easing, { nice(it.name) }, { e -> setTrack(null) { it.copy(easing = e) } })
        track.keys.forEachIndexed { ki, key ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              LabeledSlider("Key ${ki + 1} time", key.timeSec, 0f..30f, { v ->
                setTrack("k$ti-$ki-t") { tr -> tr.copy(keys = tr.keys.toMutableList().also { it[ki] = key.copy(timeSec = (v * 10).roundToInt() / 10f) }) }
              }, format = { "%.1f s".format(it) })
              LabeledSlider("Value", key.value, propRange(track.property), { v ->
                setTrack("k$ti-$ki-v") { tr -> tr.copy(keys = tr.keys.toMutableList().also { it[ki] = key.copy(value = v) }) }
              })
            }
            IconButton(onClick = { setTrack(null) { tr -> tr.copy(keys = tr.keys.filterIndexed { i, _ -> i != ki }) } }) { Icon(Icons.Default.Delete, "Remove key") }
          }
        }
        TextButton(onClick = {
          setTrack(null) { tr ->
            val last = tr.keys.maxOfOrNull { it.timeSec } ?: -2f
            tr.copy(keys = tr.keys + Keyframe((last + 2f).coerceAtMost(30f), tr.keys.lastOrNull()?.value ?: propDefault(tr.property)))
          }
        }) {
          Icon(Icons.Default.Add, null)
          Text("Add key")
        }
      }
    }
  }
  Hint("Add a track:")
  ChipRow(AnimProperty.entries, null, { propLabel(it) }, { p ->
    val d = propDefault(p)
    val end = when (p) {
      AnimProperty.X, AnimProperty.Y -> 0.1f
      AnimProperty.SCALE -> 1.2f
      AnimProperty.ROTATION -> 30f
      AnimProperty.OPACITY -> 0.3f
    }
    set(null) { it.copy(keyframes = it.keyframes + KeyframeTrack(p, listOf(Keyframe(0f, d), Keyframe(3f, end)), LoopMode.PING_PONG)) }
  })
  Hint("Preview time: %.1f s".format(controller.renderer.sceneTimeSec))
}

private val TIMERS = listOf(0, 10, 20, 30, 60, 120, 300, 600, 1800, 3600)
private fun timerLabel(s: Int) = when {
  s == 0 -> "Off"
  s < 60 -> "${s}s"
  s < 3600 -> "${s / 60} min"
  else -> "${s / 3600} h"
}

private fun hourLabel(minute: Int): String = "%02d:%02d".format(minute / 60 % 24, minute % 60)

@Composable
fun ScenesPanel(vm: MainViewModel, project: Project, scene: Scene?, controller: PreviewController, sim: PreviewSim, playing: Boolean) {
  if (scene == null) return
  val scope = rememberCoroutineScope()
  var colorPicker by remember { mutableStateOf<String?>(null) }
  SectionTitle("Scenes")
  Hint("A wallpaper can have several scenes. It moves between them on a timer, on unlock, on double-tap or by time of day.")
  ChipRow(project.scenes, scene, { s -> (if (s.isIntro) "▶ " else "") + s.name }, { s ->
    vm.selectedSceneId.value = s.id
    vm.selectedLayerId.value = s.layers.lastOrNull()?.id
  })
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = { vm.addScene() }) { Icon(Icons.Default.Add, "New scene") }
    IconButton(onClick = { vm.addScene(scene) }) { Icon(Icons.Default.ContentCopy, "Duplicate scene") }
    IconButton(onClick = { vm.moveScene(scene.id, forward = false) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Move earlier") }
    IconButton(onClick = { vm.moveScene(scene.id, forward = true) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Move later") }
    IconButton(onClick = { vm.deleteScene(scene.id) }) { Icon(Icons.Default.Delete, "Delete scene") }
  }
  OutlinedTextField(scene.name, { v -> vm.updateScene(scene.id, "name") { it.copy(name = v.take(30)) } }, label = { Text("Scene name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
    Text("Background colour", Modifier.weight(1f))
    ColorSwatch(scene.background, onClick = { colorPicker = "bg" })
  }

  SectionTitle("Transition into this scene")
  val tr = scene.transitionIn
  ChipRow(TransitionType.entries, tr.type, { if (it == TransitionType.FLIP_3D) "3D flip" else nice(it.name) }, { t -> vm.updateScene(scene.id, null) { it.copy(transitionIn = tr.copy(type = t)) } })
  if (tr.type != TransitionType.CUT) {
    LabeledSlider("Duration", tr.durationMs.toFloat(), 150f..4000f, { v -> vm.updateScene(scene.id, "tdur") { it.copy(transitionIn = tr.copy(durationMs = v.roundToInt())) } }, format = { "%.1f s".format(it / 1000f) })
  }
  if (tr.type in setOf(TransitionType.SLIDE, TransitionType.PUSH, TransitionType.WIPE, TransitionType.FLIP_3D, TransitionType.CUBE)) {
    ChipRow(Direction.entries, tr.direction, { nice(it.name) }, { d -> vm.updateScene(scene.id, null) { it.copy(transitionIn = tr.copy(direction = d)) } })
  }
  if (tr.type == TransitionType.FADE_COLOR) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Fade colour", Modifier.weight(1f))
      ColorSwatch(tr.color, onClick = { colorPicker = "fade" })
    }
  }
  OutlinedButton(onClick = {
    val other = project.scenes.firstOrNull { it.id != scene.id }
    if (other == null) vm.say("Add another scene to see transitions") else scope.launch {
      controller.renderer.showScene(other.id, animate = false)
      delay(500)
      controller.renderer.showScene(scene.id, animate = true)
    }
  }) {
    Icon(Icons.Default.PlayArrow, null)
    Spacer(Modifier.width(4.dp))
    Text("Preview transition")
  }

  SectionTitle("When this scene plays")
  val window = scene.timeWindow
  SwitchRow("Only at certain hours", window != null, { on -> vm.updateScene(scene.id, null) { it.copy(timeWindow = if (on) TimeWindow() else null) } })
  if (window != null) {
    LabeledSlider("From", window.startMinute.toFloat(), 0f..1410f, { v -> vm.updateScene(scene.id, "tw1") { it.copy(timeWindow = window.copy(startMinute = (v / 30).roundToInt() * 30)) } }, format = { hourLabel(it.roundToInt()) })
    LabeledSlider("Until", window.endMinute.toFloat(), 0f..1410f, { v -> vm.updateScene(scene.id, "tw2") { it.copy(timeWindow = window.copy(endMinute = (v / 30).roundToInt() * 30)) } }, format = { hourLabel(it.roundToInt()) })
  }
  SwitchRow("Only while charging", scene.onlyWhileCharging, { v -> vm.updateScene(scene.id, null) { it.copy(onlyWhileCharging = v) } })
  Text("Show on", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
  ChipRow(ScreenFilter.entries, scene.showOn, {
    when (it) {
      ScreenFilter.ANY -> "Both screens"
      ScreenFilter.LOCK_SCREEN -> "Lock screen"
      ScreenFilter.HOME_SCREEN -> "Home screen"
    }
  }, { f -> vm.updateScene(scene.id, null) { it.copy(showOn = f) } })
  LabeledSlider("Only when battery is below", scene.batteryBelow.toFloat(), 0f..60f, { v ->
    vm.updateScene(scene.id, "battery") { it.copy(batteryBelow = ((v / 5).roundToInt() * 5)) }
  }, format = { if (it < 1f) "Off" else "${it.roundToInt()}%" })
  SwitchRow("Unlock intro", scene.isIntro, { v -> vm.updateScene(scene.id, null) { it.copy(isIntro = v) } }, "Plays once each time you unlock, then the normal scene returns")
  if (scene.isIntro) {
    LabeledSlider("Intro length", scene.introDurationSec, 0f..30f, { v -> vm.updateScene(scene.id, "intro") { it.copy(introDurationSec = (v * 2).roundToInt() / 2f) } }, format = { if (it == 0f) "One loop" else "%.1f s".format(it) })
  }
  Hint("Turn on ▶ under the preview to try timers, intros, the lock screen and these rules.")
  TryInPreview(sim, playing)

  SectionTitle("Switching scenes")
  val sw = project.switching
  Text("Next scene every", style = MaterialTheme.typography.bodyMedium)
  ChipRow(TIMERS, TIMERS.minByOrNull { kotlin.math.abs(it - sw.timerSec) }, ::timerLabel, { t -> vm.updateSwitching("timer") { it.copy(timerSec = t) } })
  SwitchRow("Next scene when unlocking", sw.onUnlock, { v -> vm.updateSwitching("unlock") { it.copy(onUnlock = v) } })
  SwitchRow("Next scene on double-tap", sw.onDoubleTap, { v -> vm.updateSwitching("tap") { it.copy(onDoubleTap = v) } }, "Double-tap an empty spot on the home screen (works on most launchers)")
  SwitchRow("Random order", sw.shuffle, { v -> vm.updateSwitching("shuffle") { it.copy(shuffle = v) } })

  colorPicker?.let { which ->
    ColorPickerDialog(if (which == "bg") scene.background else tr.color, onDismiss = { colorPicker = null }, onPick = { c ->
      vm.updateScene(scene.id, null) { if (which == "bg") it.copy(background = c) else it.copy(transitionIn = it.transitionIn.copy(color = c)) }
      colorPicker = null
    })
  }
}

@Composable
fun WallpaperPanel(vm: MainViewModel, project: Project, sim: PreviewSim) {
  val s = project.settings
  var tapColour by remember { mutableStateOf(false) }
  SectionTitle("When you tap the home screen")
  ChipRow(TouchKind.entries, s.touch.kind, {
    when (it) {
      TouchKind.NONE -> "Nothing"
      TouchKind.RIPPLE -> "Water ripple"
      TouchKind.SPARKLES -> "Sparkles"
      TouchKind.GLOW -> "Glow"
    }
  }, { k -> vm.updateSettings("touch") { it.copy(touch = it.touch.copy(kind = k)) } })
  if (s.touch.kind != TouchKind.NONE) {
    LabeledSlider("Size", s.touch.size, 0.5f..2f, { v -> vm.updateSettings("touchsize") { it.copy(touch = it.touch.copy(size = v)) } }, format = { "%.1fx".format(it) })
    if (s.touch.kind != TouchKind.RIPPLE) Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Colour", Modifier.weight(1f))
      ColorSwatch(s.touch.color, onClick = { tapColour = true })
    }
    Hint("Turn on ▶ under the preview and tap it to try. On the phone, tap an empty spot of the home screen.")
  }
  SectionTitle("Colours that follow the day")
  LabeledSlider("Strength", s.dayCycle, 0f..1f, { v -> vm.updateSettings("day") { it.copy(dayCycle = v) } }, format = { if (it == 0f) "Off" else percent(it) })
  Hint("Warm mornings, golden evenings, cool blue nights.")
  if (s.dayCycle > 0f) TimeSlider(sim)
  if (tapColour) {
    ColorPickerDialog(s.touch.color, onDismiss = { tapColour = false }, onPick = { c ->
      vm.updateSettings("touchcolour") { it.copy(touch = it.touch.copy(color = c)) }
      tapColour = false
    })
  }
  SectionTitle("Smoothness")
  ChipRow(listOf(15, 24, 30, 60), s.fpsCap, { "$it fps" }, { f -> vm.updateSettings("fps") { it.copy(fpsCap = f) } })
  Hint("30 fps looks smooth and saves battery. Videos never play faster than they were made.")
  SectionTitle("Battery Saver on")
  ChipRow(BatteryBehavior.entries, s.batterySaver, {
    when (it) {
      BatteryBehavior.KEEP -> "Keep playing"
      BatteryBehavior.REDUCE -> "Slow down"
      BatteryBehavior.PAUSE -> "Pause"
    }
  }, { b -> vm.updateSettings("battery") { it.copy(batterySaver = b) } })
  SwitchRow("Zoom in when the notification shade opens", s.zoomOnShade, { v -> vm.updateSettings("zoom") { it.copy(zoomOnShade = v) } })
  SectionTitle("Good to know")
  Hint("• The wallpaper only draws while you can see it, and still scenes cost nothing after the first frame.")
  Hint("• Swipe parallax and double-tap depend on your launcher.")
  Hint("• Keep to 2 videos per scene; more can be too much for some phones.")
  Hint("• Videos play without sound.")
  Spacer(Modifier.padding(8.dp))
  Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
    Column(Modifier.padding(12.dp)) {
      Text("${project.scenes.size} scenes · ${project.scenes.sumOf { it.layers.size }} layers · ${project.assets.size} media files", style = MaterialTheme.typography.bodySmall)
    }
  }
}


/** Lets the time of day be faked in the preview (for day colours and time rules). */
@Composable
fun TimeSlider(sim: PreviewSim) {
  val minute = sim.minute
  LabeledSlider(
    if (minute == null) "Preview time: now" else "Preview time",
    (minute ?: java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }).toFloat(),
    0f..1439f,
    { v -> sim.minute = ((v / 15).roundToInt() * 15).coerceAtMost(1439) },
    format = { hourLabel(it.roundToInt()) },
  )
  if (minute != null) TextButton(onClick = { sim.minute = null }) { Text("Back to the real time") }
}

@Composable
private fun TryInPreview(sim: PreviewSim, playing: Boolean) {
  Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
    Column(Modifier.padding(12.dp)) {
      Text("Try it in the preview", style = MaterialTheme.typography.titleSmall)
      if (!playing) Hint("These only matter while ▶ (play as wallpaper) is on.")
      TimeSlider(sim)
      val battery = sim.battery
      LabeledSlider(
        if (battery == null) "Battery: real" else "Battery",
        (battery ?: 100).toFloat(), 1f..100f,
        { v -> sim.battery = v.roundToInt() },
        format = { "${it.roundToInt()}%" },
      )
      if (battery != null) TextButton(onClick = { sim.battery = null }) { Text("Back to the real battery") }
    }
  }
}
