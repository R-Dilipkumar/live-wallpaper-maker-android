package io.github.rdilipkumar.livewallpapermaker.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.rdilipkumar.livewallpapermaker.engine.creator.ExportOptions
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.SceneLogic
import io.github.rdilipkumar.livewallpapermaker.ui.ChipRow
import io.github.rdilipkumar.livewallpapermaker.ui.Hint
import io.github.rdilipkumar.livewallpapermaker.ui.MainViewModel
import io.github.rdilipkumar.livewallpapermaker.ui.SectionTitle
import io.github.rdilipkumar.livewallpapermaker.ui.SwitchRow

/** "Share as video or GIF": renders the current scene and saves it to the gallery. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: MainViewModel, project: Project, scene: Scene?, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var gif by remember { mutableStateOf(false) }
  var seconds by remember { mutableStateOf(8) }
  var sway by remember { mutableStateOf(scene?.let { SceneLogic.sceneUsesTilt(it) } ?: false) }
  val lengths = if (gif) listOf(3, 5, 8) else listOf(5, 8, 15, 30)
  if (seconds !in lengths) seconds = lengths[1]

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
      Text("Share as video or GIF", style = MaterialTheme.typography.titleLarge)
      Hint("Makes a clip of \"${scene?.name ?: "this scene"}\" to post or send. It's saved in your gallery too.")
      SectionTitle("Format")
      ChipRow(listOf(false, true), gif, { if (it) "GIF (small, loops everywhere)" else "Video (sharp, MP4)" }, { gif = it })
      SectionTitle("Length")
      ChipRow(lengths, seconds, { "$it s" }, { seconds = it })
      SwitchRow("Show the depth", sway, { sway = it }, "Gently sways the tilt so 3D and parallax layers move")
      Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(
          onClick = {
            val id = scene?.id ?: project.scenes.first().id
            vm.exportScene(
              id,
              ExportOptions(
                seconds = seconds.toFloat(),
                width = if (gif) 360 else 720,
                screenAspect = screenAspectOf(context),
                gif = gif,
                tiltSway = sway,
              ),
            )
            onDismiss()
          },
          modifier = Modifier.weight(1f),
        ) { Text("Make it") }
      }
    }
  }
}

private fun screenAspectOf(context: android.content.Context): Float {
  val b = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
  val w = minOf(b.width(), b.height()).toFloat()
  val h = maxOf(b.width(), b.height()).toFloat()
  return if (h > 0f) (w / h).coerceIn(0.4f, 0.75f) else 9f / 19.5f
}
