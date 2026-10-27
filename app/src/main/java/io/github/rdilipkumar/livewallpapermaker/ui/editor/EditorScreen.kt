package io.github.rdilipkumar.livewallpapermaker.ui.editor

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rdilipkumar.livewallpapermaker.engine.TriggerEvent
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.SceneSwitching
import io.github.rdilipkumar.livewallpapermaker.ui.MainViewModel
import io.github.rdilipkumar.livewallpapermaker.ui.PickKind
import io.github.rdilipkumar.livewallpapermaker.wallpaper.LiveWallpaperService
import kotlinx.coroutines.launch

/** Editor-only "what if" values for trying scene rules and day colours in the preview. */
class PreviewSim {
  var locked by mutableStateOf(false)

  /** null = real time. */
  var minute by mutableStateOf<Int?>(null)

  /** null = real battery level. */
  var battery by mutableStateOf<Int?>(null)
}

enum class EditorTab(val label: String) { LAYERS("Layers"), EDIT("Edit"), EFFECTS("Effects"), MOTION("Motion"), SCENES("Scenes"), WALLPAPER("Wallpaper") }

/** While editing, scenes stay where the user put them: no timers, time windows or intros (unless "Play as wallpaper"). */
private fun Project.forEditing(): Project = copy(
  scenes = scenes.map { it.copy(timeWindow = null, onlyWhileCharging = false, isIntro = false) },
  switching = SceneSwitching(),
)

private fun screenAspect(context: Context): Float {
  val wm = context.getSystemService(WindowManager::class.java)
  val b = wm.maximumWindowMetrics.bounds
  val w = minOf(b.width(), b.height()).toFloat()
  val h = maxOf(b.width(), b.height()).toFloat()
  return if (h > 0f) w / h else 9f / 20f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: MainViewModel) {
  val project = vm.project.collectAsStateWithLifecycle().value ?: return
  val sceneId by vm.selectedSceneId.collectAsStateWithLifecycle()
  val layerId by vm.selectedLayerId.collectAsStateWithLifecycle()
  val canUndo by vm.canUndo.collectAsStateWithLifecycle()
  val canRedo by vm.canRedo.collectAsStateWithLifecycle()
  val importJob by vm.importJob.collectAsStateWithLifecycle()
  val pending by vm.pendingClip.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val controller = rememberPreviewController(vm.repo.storage)
  val aspect = remember { screenAspect(context) }

  var tab by rememberSaveable { mutableStateOf(EditorTab.LAYERS) }
  var playAsWallpaper by remember { mutableStateOf(false) }
  var paused by remember { mutableStateOf(false) }
  var tiltOn by remember { mutableStateOf(true) }
  var swipe by remember { mutableFloatStateOf(0.5f) }
  var renaming by remember { mutableStateOf(false) }
  var pickTarget by remember { mutableStateOf<String?>(null) }
  var fontTarget by remember { mutableStateOf<String?>(null) }
  var exporting by remember { mutableStateOf(false) }
  val sim = remember { PreviewSim() }
  val pickRequest by vm.pickRequest.collectAsStateWithLifecycle()

  val scene = project.scenes.firstOrNull { it.id == sceneId } ?: project.scenes.firstOrNull()
  val layer = scene?.layers?.firstOrNull { it.id == layerId }

  LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
  LaunchedEffect(playAsWallpaper) { controller.renderer.setAutoScenes(playAsWallpaper) }
  val shown = remember(project, playAsWallpaper) { if (playAsWallpaper) project else project.forEditing() }
  // Leaving the editor saves the current preview as the project's thumbnail.
  val close = {
    controller.capture()?.let { vm.savePoster(it) }
    vm.closeEditor()
  }
  BackHandler { close() }
  LaunchedEffect(shown) { controller.renderer.setProject(shown) }
  LaunchedEffect(scene?.id, playAsWallpaper) {
    if (!playAsWallpaper && scene != null) controller.renderer.showScene(scene.id, animate = false)
  }
  LaunchedEffect(swipe) { controller.renderer.setSwipe(swipe) }
  LaunchedEffect(tiltOn) { controller.renderer.setTiltAllowed(tiltOn) }
  LaunchedEffect(paused) { controller.renderer.setPaused(paused) }
  // Pretend lock screen / time / battery, so scene rules and day colours can be tried in the preview.
  LaunchedEffect(playAsWallpaper, sim.locked) { controller.renderer.setSimulatedLock(if (playAsWallpaper) sim.locked else null) }
  LaunchedEffect(sim.minute) { controller.renderer.setTimeOverride(sim.minute) }
  LaunchedEffect(sim.battery) { controller.renderer.setBatteryOverride(sim.battery) }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) vm.onMediaPicked(uri, pickTarget)
  }
  val pick: (String?) -> Unit = { target ->
    pickTarget = target
    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
  }
  val depthPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) vm.makeDepthPhoto(uri)
  }
  val pickDepth: () -> Unit = { depthPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
  val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    val target = fontTarget
    if (uri != null && target != null) vm.importFont(uri, target)
  }
  val pickFont: (String) -> Unit = { layerIdForFont ->
    fontTarget = layerIdForFont
    fontPicker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/octet-stream"))
  }
  val share by vm.shareRequest.collectAsStateWithLifecycle()
  LaunchedEffect(share) {
    val r = share ?: return@LaunchedEffect
    vm.shareRequest.value = null
    val send = Intent(Intent.ACTION_SEND).apply {
      type = r.mime
      putExtra(Intent.EXTRA_STREAM, r.uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share your wallpaper")) }
  }
  LaunchedEffect(pickRequest) {
    val r = pickRequest ?: return@LaunchedEffect
    vm.pickRequest.value = null
    when (r.kind) {
      PickKind.DEPTH_PHOTO -> pickDepth()
      PickKind.MEDIA -> pick(r.targetLayerId)
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        title = {
          Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { renaming = true })
        },
        actions = {
          IconButton(onClick = { vm.undo() }, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
          IconButton(onClick = { vm.redo() }, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
          IconButton(onClick = { exporting = true }, enabled = importJob == null) { Icon(Icons.Default.Share, "Export as video or GIF") }
          Button(
            onClick = {
              scope.launch {
                val missing = project.scenes.sumOf { s -> s.layers.count { it.type == LayerType.MEDIA && it.assetId == null } }
                val poster = controller.capture()
                vm.apply(poster)
                poster?.recycle()
                setAsWallpaper(context, vm)
                if (missing > 0) vm.say("$missing media slot${if (missing == 1) " is" else "s are"} still empty")
              }
            },
            enabled = importJob == null,
            modifier = Modifier.padding(end = 8.dp),
          ) { Text("Apply") }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
      importJob?.let { job ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("${job.label}… ${(job.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
          }
          IconButton(onClick = { vm.cancelImport() }) { Icon(Icons.Default.Close, "Cancel") }
        }
      }
      Box(Modifier.fillMaxWidth().weight(0.52f).padding(top = 4.dp), contentAlignment = Alignment.Center) {
        PreviewSurface(
          controller = controller,
          project = shown,
          scene = scene,
          selectedLayerId = layerId,
          swipe = swipe,
          onSelect = { vm.selectedLayerId.value = it },
          onTransform = { id, t -> vm.updateLayer(id, "gesture") { it.copy(transform = t) } },
          playMode = playAsWallpaper,
          onWallpaperTap = { x, y, double ->
            controller.renderer.touch(x, y)
            if (double) controller.renderer.trigger(TriggerEvent.DOUBLE_TAP)
          },
          modifier = Modifier.fillMaxHeight().aspectRatio(aspect).clip(RoundedCornerShape(14.dp)),
        )
      }
      PreviewControls(
        paused = paused, onPause = { paused = it },
        swipe = swipe, onSwipe = { swipe = it },
        tiltOn = tiltOn, onTilt = { tiltOn = it },
        playAsWallpaper = playAsWallpaper, onPlayAsWallpaper = { playAsWallpaper = it },
        locked = sim.locked, onLocked = { lock ->
          sim.locked = lock
          if (lock) controller.renderer.trigger(TriggerEvent.SCREEN_ON)
        },
        onTrigger = { e ->
          if (e == TriggerEvent.UNLOCK) sim.locked = false
          controller.renderer.trigger(e)
        },
      )
      ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
        EditorTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
      }
      Column(
        Modifier.fillMaxWidth().weight(0.48f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        when (tab) {
          EditorTab.LAYERS -> LayersPanel(vm, project, scene, layerId, pick, pickDepth, onEdit = { tab = EditorTab.EDIT })
          EditorTab.EDIT -> EditPanel(vm, project, layer, pick, pickFont)
          EditorTab.EFFECTS -> EffectsPanel(vm, layer)
          EditorTab.MOTION -> MotionPanel(vm, layer, controller)
          EditorTab.SCENES -> ScenesPanel(vm, project, scene, controller, sim, playAsWallpaper)
          EditorTab.WALLPAPER -> WallpaperPanel(vm, project, sim)
        }
      }
    }
  }

  pending?.let { ImportSheet(vm, it) }
  if (exporting) ExportSheet(vm, project, scene, onDismiss = { exporting = false })

  if (renaming) {
    var name by remember { mutableStateOf(project.name) }
    AlertDialog(
      onDismissRequest = { renaming = false },
      title = { Text("Name") },
      text = { OutlinedTextField(name, { name = it }, singleLine = true) },
      confirmButton = {
        TextButton(onClick = {
          vm.rename(name.ifBlank { "Untitled" })
          renaming = false
        }) { Text("Save") }
      },
      dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun PreviewControls(
  paused: Boolean, onPause: (Boolean) -> Unit,
  swipe: Float, onSwipe: (Float) -> Unit,
  tiltOn: Boolean, onTilt: (Boolean) -> Unit,
  playAsWallpaper: Boolean, onPlayAsWallpaper: (Boolean) -> Unit,
  locked: Boolean, onLocked: (Boolean) -> Unit,
  onTrigger: (TriggerEvent) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    IconButton(onClick = { onPause(!paused) }) {
      Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Play" else "Pause")
    }
    Slider(value = swipe, onValueChange = onSwipe, modifier = Modifier.weight(1f))
    FilledIconToggleButton(checked = tiltOn, onCheckedChange = onTilt) {
      Icon(Icons.Default.ScreenRotation, "Use phone tilt", modifier = Modifier.size(20.dp))
    }
    FilledIconToggleButton(checked = playAsWallpaper, onCheckedChange = onPlayAsWallpaper) {
      Icon(Icons.Default.SlowMotionVideo, "Play as wallpaper (timers, intros, time of day)", modifier = Modifier.size(20.dp))
    }
    if (playAsWallpaper) {
      if (locked) {
        IconButton(onClick = { onTrigger(TriggerEvent.UNLOCK) }) { Icon(Icons.Default.LockOpen, "Simulate unlock") }
      } else {
        IconButton(onClick = { onLocked(true) }) { Icon(Icons.Default.Lock, "Show the lock screen") }
      }
      IconButton(onClick = { onTrigger(TriggerEvent.DOUBLE_TAP) }) { Icon(Icons.Default.TouchApp, "Simulate double-tap") }
    }
  }
}

/** Opens the system screen to set our live wallpaper, unless it is already the current one. */
private fun setAsWallpaper(context: Context, vm: MainViewModel) {
  val ours = ComponentName(context, LiveWallpaperService::class.java)
  val current = runCatching { WallpaperManager.getInstance(context).wallpaperInfo?.component }.getOrNull()
  if (current == ours) {
    vm.say("Wallpaper updated")
    return
  }
  val change = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ours)
  val chooser = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
  for (intent in listOf(change, chooser)) {
    try {
      context.startActivity(intent)
      return
    } catch (_: Exception) {
    }
  }
  vm.say("Open your phone's wallpaper settings and choose \"Live Wallpaper Maker\"")
}
