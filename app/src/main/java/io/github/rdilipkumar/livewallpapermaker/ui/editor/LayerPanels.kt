package io.github.rdilipkumar.livewallpapermaker.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rdilipkumar.livewallpapermaker.data.Templates
import io.github.rdilipkumar.livewallpapermaker.engine.model.AssetKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.BlendMode
import io.github.rdilipkumar.livewallpapermaker.engine.model.Effects
import io.github.rdilipkumar.livewallpapermaker.engine.model.Fit
import io.github.rdilipkumar.livewallpapermaker.engine.model.GradientSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import io.github.rdilipkumar.livewallpapermaker.engine.model.ParticleKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.ParticleSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextFont
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Transform
import io.github.rdilipkumar.livewallpapermaker.ui.ChipRow
import io.github.rdilipkumar.livewallpapermaker.ui.ColorPickerDialog
import io.github.rdilipkumar.livewallpapermaker.ui.ColorSwatch
import io.github.rdilipkumar.livewallpapermaker.ui.Hint
import io.github.rdilipkumar.livewallpapermaker.ui.LabeledSlider
import io.github.rdilipkumar.livewallpapermaker.ui.MainViewModel
import io.github.rdilipkumar.livewallpapermaker.ui.SectionTitle
import io.github.rdilipkumar.livewallpapermaker.ui.SwitchRow
import io.github.rdilipkumar.livewallpapermaker.ui.percent
import io.github.rdilipkumar.livewallpapermaker.ui.rememberImageFile
import kotlin.math.roundToInt

private fun nice(name: String) = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun LayerThumb(vm: MainViewModel, project: Project, layer: Layer) {
  val mod = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
  when (layer.type) {
    LayerType.MEDIA -> {
      val asset = project.asset(layer.assetId)
      val img = rememberImageFile(asset?.let { vm.importer.thumbFile(it.id) })
      if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = mod)
      else Box(mod.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary)
      }
    }
    LayerType.GRADIENT -> {
      val colors = (layer.gradient ?: GradientSpec()).colors.map { Color(it) }.let { if (it.size < 2) it + it else it }
      Box(mod.background(Brush.verticalGradient(colors)))
    }
    LayerType.PARTICLES -> Box(mod.background(Color(0xFF1B1F2A)), contentAlignment = Alignment.Center) {
      Icon(Icons.Default.AcUnit, null, tint = Color.White)
    }
    LayerType.TEXT -> Box(mod.background(Color(0xFF1B1F2A)), contentAlignment = Alignment.Center) {
      Icon(Icons.Default.TextFields, null, tint = Color.White)
    }
  }
}

@Composable
fun LayersPanel(vm: MainViewModel, project: Project, scene: Scene?, selectedId: String?, pick: (String?) -> Unit, onEdit: () -> Unit) {
  if (scene == null) return
  var addMenu by remember { mutableStateOf(false) }
  var particleMenu by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text("Scene: ${scene.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
    Box {
      OutlinedButton(onClick = { addMenu = true }) {
        Icon(Icons.Default.Add, null)
        Spacer(Modifier.width(4.dp))
        Text("Add layer")
      }
      DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
        DropdownMenuItem(text = { Text("Photo, GIF or video") }, leadingIcon = { Icon(Icons.Default.Movie, null) }, onClick = { addMenu = false; pick(null) })
        DropdownMenuItem(text = { Text("Empty media slot") }, leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null) }, onClick = {
          addMenu = false
          vm.addLayer(Templates.slot("Media slot"))
        })
        DropdownMenuItem(text = { Text("Gradient") }, leadingIcon = { Icon(Icons.Default.Gradient, null) }, onClick = {
          addMenu = false
          vm.addLayer(Templates.gradient(listOf(0xFF1A2980, 0xFF26D0CE)))
        })
        DropdownMenuItem(text = { Text("Particles…") }, leadingIcon = { Icon(Icons.Default.AcUnit, null) }, onClick = { addMenu = false; particleMenu = true })
        DropdownMenuItem(text = { Text("Text / clock") }, leadingIcon = { Icon(Icons.Default.TextFields, null) }, onClick = {
          addMenu = false
          vm.addLayer(Templates.text("{time}", -0.25f, 0.1f))
        })
      }
      DropdownMenu(expanded = particleMenu, onDismissRequest = { particleMenu = false }) {
        ParticleKind.entries.forEach { k ->
          DropdownMenuItem(text = { Text(nice(k.name)) }, onClick = {
            particleMenu = false
            val color = when (k) {
              ParticleKind.FIREFLIES -> 0xFFE9FF70
              ParticleKind.BOKEH -> 0xFFFFC56E
              else -> 0xFFFFFFFF
            }
            val count = when (k) {
              ParticleKind.BOKEH -> 24
              ParticleKind.FIREFLIES -> 60
              ParticleKind.RAIN -> 260
              else -> 160
            }
            vm.addLayer(Templates.particles(k, count, color, blend = if (k == ParticleKind.BOKEH || k == ParticleKind.FIREFLIES) BlendMode.SCREEN else BlendMode.NORMAL))
          })
        }
      }
    }
  }
  if (scene.layers.isEmpty()) Hint("No layers yet. Add a photo or video, or a gradient with particles.")
  Hint("Top of the list = front.")
  scene.layers.asReversed().forEach { layer ->
    val selected = layer.id == selectedId
    Row(
      Modifier
        .fillMaxWidth()
        .padding(vertical = 3.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
        .clickable { vm.selectedLayerId.value = layer.id }
        .padding(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      LayerThumb(vm, project, layer)
      Column(Modifier.weight(1f).padding(start = 10.dp)) {
        Text(layer.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = when (layer.type) {
          LayerType.MEDIA -> project.asset(layer.assetId)?.let { a ->
            when {
              a.kind == AssetKind.IMAGE -> if (a.hasAlpha) "Photo · transparent" else "Photo"
              a.prep?.fromGif == true -> "GIF · %.1f s".format(a.durationMs / 1000f)
              else -> "Video · %.1f s".format(a.durationMs / 1000f)
            }
          } ?: "Empty slot – tap to add media"
          LayerType.GRADIENT -> "Gradient"
          LayerType.PARTICLES -> nice(layer.particles?.kind?.name ?: "particles")
          LayerType.TEXT -> "Text"
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      IconButton(onClick = { vm.updateLayer(layer.id, null) { it.copy(visible = !it.visible) } }) {
        Icon(if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Show or hide")
      }
      if (selected) {
        IconButton(onClick = { vm.moveLayer(layer.id, up = true) }) { Icon(Icons.Default.KeyboardArrowUp, "Move forward") }
        IconButton(onClick = { vm.moveLayer(layer.id, up = false) }) { Icon(Icons.Default.KeyboardArrowDown, "Move back") }
      }
    }
    if (selected) {
      FlowRow(Modifier.padding(start = 56.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = onEdit, label = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) })
        if (layer.type == LayerType.MEDIA && layer.assetId == null) {
          AssistChip(onClick = { pick(layer.id) }, label = { Text("Add media") }, leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(16.dp)) })
        }
        AssistChip(onClick = { vm.duplicateLayer(layer.id) }, label = { Text("Copy") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) })
        AssistChip(onClick = { vm.deleteLayer(layer.id) }, label = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)) })
      }
    }
  }
}

@Composable
fun NoLayer() = Hint("Select a layer in the Layers tab, or tap it in the preview.")

@Composable
fun EditPanel(vm: MainViewModel, project: Project, layer: Layer?, pick: (String?) -> Unit) {
  if (layer == null) return NoLayer()
  val id = layer.id
  var colorTarget by remember(id) { mutableStateOf<Pair<Int, (Int) -> Unit>?>(null) }

  OutlinedTextField(
    value = layer.name,
    onValueChange = { v -> vm.updateLayer(id, "name") { it.copy(name = v.take(40)) } },
    label = { Text("Layer name") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )

  when (layer.type) {
    LayerType.MEDIA -> MediaSection(vm, project, layer, pick)
    LayerType.GRADIENT -> {
      val g = layer.gradient ?: GradientSpec()
      SectionTitle("Gradient")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        g.colors.forEachIndexed { i, c ->
          ColorSwatch(c, onClick = {
            colorTarget = c to { picked -> vm.updateLayer(id, null) { l -> l.copy(gradient = g.copy(colors = g.colors.toMutableList().also { it[i] = picked })) } }
          })
        }
        if (g.colors.size < 4) IconButton(onClick = { vm.updateLayer(id, null) { it.copy(gradient = g.copy(colors = g.colors + g.colors.last())) } }) { Icon(Icons.Default.Add, "Add colour") }
        if (g.colors.size > 2) IconButton(onClick = { vm.updateLayer(id, null) { it.copy(gradient = g.copy(colors = g.colors.dropLast(1))) } }) { Icon(Icons.Default.Remove, "Remove colour") }
      }
      SwitchRow("Round (radial)", g.radial, { v -> vm.updateLayer(id, null) { it.copy(gradient = g.copy(radial = v)) } })
      if (!g.radial) LabeledSlider("Angle", g.angleDeg, 0f..360f, { v -> vm.updateLayer(id, "angle") { it.copy(gradient = g.copy(angleDeg = v)) } }, format = { "${it.roundToInt()}°" })
      LabeledSlider("Flowing colours", g.flowSpeed, 0f..12f, { v -> vm.updateLayer(id, "flow") { it.copy(gradient = g.copy(flowSpeed = v)) } }, format = { if (it == 0f) "Off" else "%.1f/min".format(it) })
    }
    LayerType.PARTICLES -> {
      val p = layer.particles ?: ParticleSpec()
      SectionTitle("Particles")
      ChipRow(ParticleKind.entries, p.kind, { nice(it.name) }, { k -> vm.updateLayer(id, null) { it.copy(particles = p.copy(kind = k)) } })
      LabeledSlider("Amount", p.count.toFloat(), 5f..1000f, { v -> vm.updateLayer(id, "count") { it.copy(particles = p.copy(count = v.roundToInt())) } }, format = { it.roundToInt().toString() })
      LabeledSlider("Size", p.size, 0.25f..3f, { v -> vm.updateLayer(id, "psize") { it.copy(particles = p.copy(size = v)) } })
      LabeledSlider("Speed", p.speed, 0f..3f, { v -> vm.updateLayer(id, "pspeed") { it.copy(particles = p.copy(speed = v)) } })
      LabeledSlider("Wind", p.wind, -1f..1f, { v -> vm.updateLayer(id, "wind") { it.copy(particles = p.copy(wind = v)) } })
      LabeledSlider("Strength", p.opacity, 0f..1f, { v -> vm.updateLayer(id, "popac") { it.copy(particles = p.copy(opacity = v)) } }, format = ::percent)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Colour", Modifier.weight(1f))
        ColorSwatch(p.color, onClick = { colorTarget = p.color to { c -> vm.updateLayer(id, null) { it.copy(particles = p.copy(color = c)) } } })
      }
    }
    LayerType.TEXT -> {
      val t = layer.text ?: TextSpec()
      SectionTitle("Text")
      OutlinedTextField(
        value = t.text,
        onValueChange = { v -> vm.updateLayer(id, "text") { it.copy(text = t.copy(text = v.take(200))) } },
        label = { Text("Text") },
        modifier = Modifier.fillMaxWidth(),
      )
      Hint("Live tokens: tap to insert")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("{time}", "{date}", "{day}", "{month}", "{year}", "{hour}", "{minute}").forEach { token ->
          AssistChip(onClick = { vm.updateLayer(id, null) { it.copy(text = t.copy(text = t.text + token)) } }, label = { Text(token) })
        }
      }
      LabeledSlider("Size", t.size, 0.015f..0.3f, { v -> vm.updateLayer(id, "tsize") { it.copy(text = t.copy(size = v)) } }, format = { "${(it * 1000).roundToInt() / 10f}%" })
      ChipRow(TextFont.entries, t.font, { nice(it.name) }, { f -> vm.updateLayer(id, null) { it.copy(text = t.copy(font = f)) } })
      SwitchRow("Bold", t.bold, { v -> vm.updateLayer(id, null) { it.copy(text = t.copy(bold = v)) } })
      SwitchRow("Shadow", t.shadow, { v -> vm.updateLayer(id, null) { it.copy(text = t.copy(shadow = v)) } })
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Colour", Modifier.weight(1f))
        ColorSwatch(t.color, onClick = { colorTarget = t.color to { c -> vm.updateLayer(id, null) { it.copy(text = t.copy(color = c)) } } })
      }
    }
  }

  TransformSection(vm, layer)

  colorTarget?.let { (initial, apply) ->
    ColorPickerDialog(initial, onDismiss = { colorTarget = null }, onPick = { c ->
      apply(c)
      colorTarget = null
    }, allowAlpha = layer.type == LayerType.GRADIENT)
  }
}

@Composable
private fun MediaSection(vm: MainViewModel, project: Project, layer: Layer, pick: (String?) -> Unit) {
  val id = layer.id
  val asset = project.asset(layer.assetId)
  SectionTitle("Media")
  if (asset == null) {
    OutlinedButton(onClick = { pick(id) }, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Default.AddPhotoAlternate, null)
      Spacer(Modifier.width(6.dp))
      Text("Add photo, GIF or video")
    }
  } else {
    Row(verticalAlignment = Alignment.CenterVertically) {
      val img = rememberImageFile(vm.importer.thumbFile(asset.id))
      val m = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
      if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = m) else Box(m) { Icon(Icons.Default.ImageIcon, null, Modifier.align(Alignment.Center)) }
      Column(Modifier.weight(1f).padding(start = 10.dp)) {
        Text(asset.label.ifEmpty { "Media" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val info = if (asset.kind == AssetKind.VIDEO) {
          "${asset.width}×${asset.height} · %.1f s · %s loop".format(asset.durationMs / 1000f, nice(asset.prep?.loop?.name ?: "plain"))
        } else "${asset.width}×${asset.height}${if (asset.hasAlpha) " · transparent" else ""}"
        Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
      OutlinedButton(onClick = { pick(id) }) { Text("Replace") }
      if (asset.kind == AssetKind.VIDEO) OutlinedButton(onClick = { vm.requestReprepare(layer) }) { Text("Trim & loop") }
    }
  }
  SectionTitle("Fit")
  ChipRow(Fit.entries, layer.fit, { if (it == Fit.BLUR_FILL) "Blur fill" else nice(it.name) }, { f -> vm.updateLayer(id, null) { it.copy(fit = f) } })
  if (asset?.kind == AssetKind.VIDEO) {
    LabeledSlider("Playback speed", layer.playbackSpeed, 0.25f..3f, { v -> vm.updateLayer(id, "speed") { it.copy(playbackSpeed = v) } }, format = { "%.2fx".format(it) })
  }
}

@Composable
private fun TransformSection(vm: MainViewModel, layer: Layer) {
  val id = layer.id
  val t = layer.transform
  SectionTitle("Position")
  Hint("Or drag, pinch and twist the layer in the preview.")
  LabeledSlider("Left / right", t.x, -1f..1f, { v -> vm.updateLayer(id, "tx") { it.copy(transform = t.copy(x = v)) } }, format = ::percent)
  LabeledSlider("Up / down", t.y, -1f..1f, { v -> vm.updateLayer(id, "ty") { it.copy(transform = t.copy(y = v)) } }, format = ::percent)
  LabeledSlider("Size", t.scale, 0.1f..4f, { v -> vm.updateLayer(id, "ts") { it.copy(transform = t.copy(scale = v)) } }, format = { "%.2fx".format(it) })
  LabeledSlider("Turn", t.rotation, -180f..180f, { v -> vm.updateLayer(id, "tr") { it.copy(transform = t.copy(rotation = v)) } }, format = { "${it.roundToInt()}°" })
  LabeledSlider("Opacity", t.opacity, 0f..1f, { v -> vm.updateLayer(id, "to") { it.copy(transform = t.copy(opacity = v)) } }, format = ::percent)
  SectionTitle("Blend")
  ChipRow(BlendMode.entries, layer.blend, { nice(it.name) }, { b -> vm.updateLayer(id, null) { it.copy(blend = b) } })
  OutlinedButton(onClick = { vm.updateLayer(id, null) { it.copy(transform = Transform()) } }, modifier = Modifier.padding(top = 8.dp)) { Text("Reset position") }
}

private val EFFECT_PRESETS: List<Pair<String, Effects>> = listOf(
  "None" to Effects(),
  "Cinematic" to Effects(contrast = 1.15f, saturation = 0.85f, vignette = 0.45f, grain = 0.12f),
  "Vintage" to Effects(saturation = 0.7f, tintColor = 0xFFC08A4C.toInt(), tintAmount = 0.35f, grain = 0.25f, vignette = 0.5f),
  "Dreamy" to Effects(blur = 0.12f, brightness = 0.08f, saturation = 1.2f, tintColor = 0xFFFF8FB1.toInt(), tintAmount = 0.15f),
  "Noir" to Effects(saturation = 0f, contrast = 1.35f, vignette = 0.55f, grain = 0.18f),
  "Glitch" to Effects(chromatic = 0.6f, wave = 0.08f, waveSpeed = 3f),
  "Neon" to Effects(saturation = 1.7f, contrast = 1.2f, hue = 20f),
  "Dim" to Effects(brightness = -0.35f, saturation = 0.9f),
  "Retro pixels" to Effects(pixelate = 0.25f, saturation = 1.2f),
  "Underwater" to Effects(wave = 0.25f, waveSpeed = 0.8f, tintColor = 0xFF26A6D0.toInt(), tintAmount = 0.3f),
)

@Composable
fun EffectsPanel(vm: MainViewModel, layer: Layer?) {
  if (layer == null) return NoLayer()
  if (layer.type == LayerType.GRADIENT || layer.type == LayerType.PARTICLES) {
    Hint("Effects work on photos, videos and text. Gradients and particles have their own settings in Edit.")
    return
  }
  val id = layer.id
  val e = layer.effects
  var tintPicker by remember(id) { mutableStateOf(false) }
  SectionTitle("Looks")
  ChipRow(EFFECT_PRESETS, EFFECT_PRESETS.firstOrNull { it.second == e }, { it.first }, { p -> vm.updateLayer(id, null) { it.copy(effects = p.second) } })
  fun set(key: String, f: (Effects) -> Effects) = vm.updateLayer(id, key) { it.copy(effects = f(it.effects)) }
  SectionTitle("Colour")
  LabeledSlider("Brightness", e.brightness, -1f..1f, { v -> set("br") { it.copy(brightness = v) } })
  LabeledSlider("Contrast", e.contrast, 0f..2f, { v -> set("co") { it.copy(contrast = v) } })
  LabeledSlider("Saturation", e.saturation, 0f..2f, { v -> set("sa") { it.copy(saturation = v) } })
  LabeledSlider("Hue", e.hue, -180f..180f, { v -> set("hu") { it.copy(hue = v) } }, format = { "${it.roundToInt()}°" })
  Row(verticalAlignment = Alignment.CenterVertically) {
    LabeledSlider("Tint", e.tintAmount, 0f..1f, { v -> set("ti") { it.copy(tintAmount = v) } }, Modifier.weight(1f), format = ::percent)
    Spacer(Modifier.width(10.dp))
    ColorSwatch(e.tintColor, onClick = { tintPicker = true })
  }
  SectionTitle("Style")
  LabeledSlider("Blur", e.blur, 0f..1f, { v -> set("bl") { it.copy(blur = v) } }, format = ::percent)
  LabeledSlider("Vignette", e.vignette, 0f..1f, { v -> set("vi") { it.copy(vignette = v) } }, format = ::percent)
  LabeledSlider("Film grain", e.grain, 0f..1f, { v -> set("gr") { it.copy(grain = v) } }, format = ::percent)
  LabeledSlider("Pixelate", e.pixelate, 0f..1f, { v -> set("px") { it.copy(pixelate = v) } }, format = ::percent)
  LabeledSlider("Colour split", e.chromatic, 0f..1f, { v -> set("ch") { it.copy(chromatic = v) } }, format = ::percent)
  LabeledSlider("Wave", e.wave, 0f..1f, { v -> set("wa") { it.copy(wave = v) } }, format = ::percent)
  if (e.wave > 0f) LabeledSlider("Wave speed", e.waveSpeed, 0f..4f, { v -> set("ws") { it.copy(waveSpeed = v) } })
  OutlinedButton(onClick = { vm.updateLayer(id, null) { it.copy(effects = Effects()) } }, modifier = Modifier.padding(top = 8.dp)) { Text("Reset effects") }
  if (tintPicker) {
    ColorPickerDialog(e.tintColor, onDismiss = { tintPicker = false }, onPick = { c ->
      set("tc") { it.copy(tintColor = c, tintAmount = if (it.tintAmount == 0f) 0.3f else it.tintAmount) }
      tintPicker = false
    })
  }
}
