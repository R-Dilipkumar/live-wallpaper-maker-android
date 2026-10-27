package io.github.rdilipkumar.livewallpapermaker.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rdilipkumar.livewallpapermaker.data.DepthPhotoMaker
import io.github.rdilipkumar.livewallpapermaker.data.Ids
import io.github.rdilipkumar.livewallpapermaker.data.Templates
import io.github.rdilipkumar.livewallpapermaker.engine.model.Fit
import io.github.rdilipkumar.livewallpapermaker.engine.model.TextSpec
import io.github.rdilipkumar.livewallpapermaker.engine.model.Transform
import io.github.rdilipkumar.livewallpapermaker.data.MediaImporter
import io.github.rdilipkumar.livewallpapermaker.data.PickedMedia
import io.github.rdilipkumar.livewallpapermaker.data.ProjectRepository
import io.github.rdilipkumar.livewallpapermaker.data.ProjectSummary
import io.github.rdilipkumar.livewallpapermaker.data.Template
import io.github.rdilipkumar.livewallpapermaker.engine.creator.BakeOptions
import io.github.rdilipkumar.livewallpapermaker.engine.model.Asset
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import io.github.rdilipkumar.livewallpapermaker.engine.model.PlaybackSettings
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.SceneSwitching
import io.github.rdilipkumar.livewallpapermaker.engine.creator.ExportOptions
import io.github.rdilipkumar.livewallpapermaker.engine.creator.SceneExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
  data object Home : Screen
  data class Editor(val projectId: String) : Screen
}

/** A picked video/GIF waiting for the user to choose trim and loop options. */
data class PendingClip(val media: PickedMedia?, val targetLayerId: String?, val reprepare: Asset? = null)

/** [label] is the full status line shown above the progress bar. */
data class ImportJob(val label: String, val progress: Float)

enum class PickKind { MEDIA, DEPTH_PHOTO }

data class ShareRequest(val uri: Uri, val mime: String)

data class PickRequest(val kind: PickKind, val targetLayerId: String?)

class MainViewModel(app: Application) : AndroidViewModel(app) {
  val repo = ProjectRepository(app)
  val importer = MediaImporter(app, repo)

  private val _screen = MutableStateFlow<Screen>(Screen.Home)
  val screen: StateFlow<Screen> = _screen
  private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
  val projects: StateFlow<List<ProjectSummary>> = _projects
  private val _activeId = MutableStateFlow<String?>(null)
  val activeId: StateFlow<String?> = _activeId
  private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val messages: SharedFlow<String> = _messages

  // ---- Editor state ----
  private val _project = MutableStateFlow<Project?>(null)
  val project: StateFlow<Project?> = _project
  val selectedSceneId = MutableStateFlow<String?>(null)
  val selectedLayerId = MutableStateFlow<String?>(null)
  private val _canUndo = MutableStateFlow(false)
  val canUndo: StateFlow<Boolean> = _canUndo
  private val _canRedo = MutableStateFlow(false)
  val canRedo: StateFlow<Boolean> = _canRedo
  val pendingClip = MutableStateFlow<PendingClip?>(null)
  private val _importJob = MutableStateFlow<ImportJob?>(null)
  val importJob: StateFlow<ImportJob?> = _importJob
  private var importRunner: Job? = null

  private val undoStack = ArrayDeque<Project>()
  private val redoStack = ArrayDeque<Project>()
  private var lastEditKey: String? = null
  private var lastEditAt = 0L
  private var saveJob: Job? = null

  init {
    refresh()
    viewModelScope.launch(Dispatchers.IO) { runCatching { repo.gcAssets() } }
  }

  fun say(text: String) {
    _messages.tryEmit(text)
  }

  fun refresh() {
    viewModelScope.launch(Dispatchers.IO) {
      _projects.value = repo.list()
      _activeId.value = repo.activeProjectId()
    }
  }

  // ---------------- Home ----------------

  fun createFromTemplate(t: Template) {
    viewModelScope.launch(Dispatchers.IO) {
      val p = repo.create(t)
      withContext(Dispatchers.Main) {
        open(p)
        // The 3D photo template starts by asking for the photo.
        if (t.id == Templates.DEPTH_PHOTO_ID) pickRequest.value = PickRequest(PickKind.DEPTH_PHOTO, null)
      }
    }
  }

  fun openProject(id: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val p = repo.load(id)
      withContext(Dispatchers.Main) {
        if (p == null) say("That wallpaper could not be opened") else open(p)
      }
    }
  }

  private fun open(p: Project) {
    undoStack.clear()
    redoStack.clear()
    updateUndoFlags()
    _project.value = p
    selectedSceneId.value = p.scenes.firstOrNull()?.id
    selectedLayerId.value = p.scenes.firstOrNull()?.layers?.lastOrNull()?.id
    _screen.value = Screen.Editor(p.id)
  }

  fun closeEditor() {
    val p = _project.value
    saveJob?.cancel()
    importRunner?.cancel()
    pendingClip.value = null
    if (p != null) {
      viewModelScope.launch(Dispatchers.IO) {
        repo.save(repo.pruneUnusedAssets(p))
        refresh()
      }
    }
    _project.value = null
    _screen.value = Screen.Home
  }

  fun duplicate(id: String) = viewModelScope.launch(Dispatchers.IO) {
    repo.duplicate(id)
    refresh()
  }

  fun delete(id: String) = viewModelScope.launch(Dispatchers.IO) {
    if (id == repo.activeProjectId()) {
      say("This is your live wallpaper. It keeps playing; only the editable copy is deleted.")
    }
    repo.delete(id)
    refresh()
  }

  fun exportTo(id: String, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
    try {
      getApplication<Application>().contentResolver.openOutputStream(uri)?.use { repo.export(id, it) }
      say("Saved")
    } catch (e: Exception) {
      say("Could not save: ${e.message}")
    }
  }

  /** Writes an export into the cache for the share sheet. */
  suspend fun exportForShare(id: String): File? = withContext(Dispatchers.IO) {
    runCatching {
      val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
      dir.listFiles()?.forEach { it.delete() }
      val name = (repo.load(id)?.name ?: "wallpaper").replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "wallpaper" }
      val file = File(dir, "$name.lwm")
      file.outputStream().use { repo.export(id, it) }
      file
    }.onFailure { say("Could not export: ${it.message}") }.getOrNull()
  }

  fun importProject(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
    try {
      val p = getApplication<Application>().contentResolver.openInputStream(uri)?.use { repo.import(it) }
        ?: throw IllegalStateException("Can't open the file")
      refresh()
      withContext(Dispatchers.Main) { open(p) }
      say("Imported \"${p.name}\"")
    } catch (e: Exception) {
      say("Not a valid wallpaper file (${e.message})")
    }
  }

  // ---------------- Editing ----------------

  val currentScene: Scene?
    get() {
      val p = _project.value ?: return null
      return p.scenes.firstOrNull { it.id == selectedSceneId.value } ?: p.scenes.firstOrNull()
    }

  val currentLayer: Layer?
    get() = currentScene?.layers?.firstOrNull { it.id == selectedLayerId.value }

  /**
   * Applies a change. Edits with the same [key] within a moment (a slider drag, a pinch) count as one undo step.
   */
  fun edit(key: String?, change: (Project) -> Project) {
    val p = _project.value ?: return
    val updated = change(p)
    if (updated == p) return
    val now = SystemClock.uptimeMillis()
    val merge = key != null && key == lastEditKey && now - lastEditAt < 900
    if (!merge) {
      undoStack.addLast(p)
      if (undoStack.size > 80) undoStack.removeFirst()
      redoStack.clear()
    }
    lastEditKey = key
    lastEditAt = now
    _project.value = updated
    updateUndoFlags()
    scheduleSave()
  }

  fun undo() {
    val p = _project.value ?: return
    val prev = undoStack.removeLastOrNull() ?: return
    redoStack.addLast(p)
    _project.value = prev
    lastEditKey = null
    fixSelection()
    updateUndoFlags()
    scheduleSave()
  }

  fun redo() {
    val p = _project.value ?: return
    val next = redoStack.removeLastOrNull() ?: return
    undoStack.addLast(p)
    _project.value = next
    lastEditKey = null
    fixSelection()
    updateUndoFlags()
    scheduleSave()
  }

  private fun fixSelection() {
    val p = _project.value ?: return
    if (p.scenes.none { it.id == selectedSceneId.value }) selectedSceneId.value = p.scenes.firstOrNull()?.id
    if (currentScene?.layers?.none { it.id == selectedLayerId.value } == true) selectedLayerId.value = null
  }

  private fun updateUndoFlags() {
    _canUndo.value = undoStack.isNotEmpty()
    _canRedo.value = redoStack.isNotEmpty()
  }

  private fun scheduleSave() {
    saveJob?.cancel()
    saveJob = viewModelScope.launch(Dispatchers.IO) {
      delay(600)
      _project.value?.let { repo.save(it) }
    }
  }

  fun rename(name: String) = edit("rename") { it.copy(name = name.take(60)) }

  fun updateScene(sceneId: String, key: String?, f: (Scene) -> Scene) = edit(key?.let { "$sceneId/$it" }) { p ->
    p.copy(scenes = p.scenes.map { if (it.id == sceneId) f(it) else it })
  }

  fun updateLayer(layerId: String, key: String?, f: (Layer) -> Layer) = edit(key?.let { "$layerId/$it" }) { p ->
    p.copy(scenes = p.scenes.map { s -> s.copy(layers = s.layers.map { if (it.id == layerId) f(it) else it }) })
  }

  fun updateSwitching(key: String, f: (SceneSwitching) -> SceneSwitching) = edit("switch/$key") { it.copy(switching = f(it.switching)) }

  fun updateSettings(key: String, f: (PlaybackSettings) -> PlaybackSettings) = edit("settings/$key") { it.copy(settings = f(it.settings)) }

  fun addLayer(layer: Layer) {
    val scene = currentScene ?: return
    updateScene(scene.id, null) { it.copy(layers = it.layers + layer) }
    selectedLayerId.value = layer.id
    val videos = (currentScene?.layers ?: emptyList()).count { l -> _project.value?.asset(l.assetId)?.kind?.name == "VIDEO" }
    if (videos > 2) say("Tip: more than 2 videos in one scene can be too much for some phones")
  }

  fun deleteLayer(layerId: String) {
    val scene = currentScene ?: return
    updateScene(scene.id, null) { it.copy(layers = it.layers.filter { l -> l.id != layerId }) }
    if (selectedLayerId.value == layerId) selectedLayerId.value = currentScene?.layers?.lastOrNull()?.id
  }

  fun duplicateLayer(layerId: String) {
    val scene = currentScene ?: return
    val i = scene.layers.indexOfFirst { it.id == layerId }
    if (i < 0) return
    val copy = scene.layers[i].copy(id = Ids.new("l"), name = scene.layers[i].name + " copy")
    updateScene(scene.id, null) { s -> s.copy(layers = s.layers.toMutableList().apply { add(i + 1, copy) }) }
    selectedLayerId.value = copy.id
  }

  /** [up] = towards the front. */
  fun moveLayer(layerId: String, up: Boolean) {
    val scene = currentScene ?: return
    val i = scene.layers.indexOfFirst { it.id == layerId }
    val j = if (up) i + 1 else i - 1
    if (i < 0 || j !in scene.layers.indices) return
    updateScene(scene.id, null) { s -> s.copy(layers = s.layers.toMutableList().apply { add(j, removeAt(i)) }) }
  }

  fun addScene(copyOf: Scene? = null) {
    val p = _project.value ?: return
    val scene = copyOf?.copy(
      id = Ids.new("s"),
      name = copyOf.name + " copy",
      layers = copyOf.layers.map { it.copy(id = Ids.new("l")) },
    ) ?: Scene(id = Ids.new("s"), name = "Scene ${p.scenes.size + 1}")
    edit(null) { it.copy(scenes = it.scenes + scene) }
    selectedSceneId.value = scene.id
    selectedLayerId.value = scene.layers.lastOrNull()?.id
  }

  fun deleteScene(sceneId: String) {
    val p = _project.value ?: return
    if (p.scenes.size <= 1) {
      say("A wallpaper needs at least one scene")
      return
    }
    edit(null) { it.copy(scenes = it.scenes.filter { s -> s.id != sceneId }) }
    if (selectedSceneId.value == sceneId) selectedSceneId.value = _project.value?.scenes?.firstOrNull()?.id
    fixSelection()
  }

  fun moveScene(sceneId: String, forward: Boolean) = edit(null) { p ->
    val i = p.scenes.indexOfFirst { it.id == sceneId }
    val j = if (forward) i + 1 else i - 1
    if (i < 0 || j !in p.scenes.indices) p else p.copy(scenes = p.scenes.toMutableList().apply { add(j, removeAt(i)) })
  }

  // ---------------- Media ----------------

  /** A photo, GIF or video was picked for [targetLayerId] (null = new layer). */
  fun onMediaPicked(uri: Uri, targetLayerId: String?) {
    viewModelScope.launch {
      try {
        when (val media = importer.inspect(uri)) {
          is PickedMedia.Image -> runImport(media.label) {
            val asset = importer.importImage(media.uri, media.label)
            attach(asset, targetLayerId)
          }
          else -> pendingClip.value = PendingClip(media, targetLayerId)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        say(e.message ?: "This file could not be used")
      }
    }
  }

  /** The editor watches this and opens the matching picker (used to start the 3D photo template). */
  val pickRequest = MutableStateFlow<PickRequest?>(null)

  private val depthMaker = DepthPhotoMaker(app, repo)

  /** Makes a 3D photo from [uri] and adds its layers (background, optional clock, subject) to the current scene. */
  fun makeDepthPhoto(uri: Uri, withClock: Boolean = true) {
    runImport("3D photo") {
      val label = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
          ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
      }.getOrNull()?.takeIf { n -> !n.substringBeforeLast('.').all { it.isDigit() } } ?: "Photo"
      val photo = depthMaker.make(uri, label) { status, p -> _importJob.value = ImportJob(status, p) }
      val scene = currentScene ?: return@runImport
      val layers = DepthPhotoMaker.layers(photo, withClock)
      edit(null) { it.copy(assets = it.assets + photo.background + photo.subject) }
      updateScene(scene.id, null) { it.copy(layers = it.layers + layers) }
      selectedLayerId.value = layers.last().id
      say("3D photo ready. Tilt your phone or move the slider to see the depth.")
    }
  }

  /** A transparent GIF kept as an animated sticker. */
  fun startSticker(pending: PendingClip) {
    val gif = pending.media as? PickedMedia.Gif ?: return
    pendingClip.value = null
    runImport(gif.label) {
      val asset = importer.importSticker(gif)
      attach(asset, pending.targetLayerId, fit = Fit.CONTAIN, scale = 0.5f)
    }
  }

  /** For a motion photo: use the picture instead of its video. */
  fun useStill(pending: PendingClip) {
    val still = (pending.media as? PickedMedia.Video)?.stillUri ?: return
    pendingClip.value = null
    runImport("Photo") {
      attach(importer.importImage(still, "Photo"), pending.targetLayerId)
    }
  }

  fun importFont(uri: Uri, layerId: String) {
    runImport("font") {
      val asset = importer.importFont(uri)
      edit(null) { it.copy(assets = it.assets + asset) }
      updateLayer(layerId, null) { l -> l.copy(text = (l.text ?: TextSpec()).copy(fontAsset = asset.id)) }
      say("Font \"${asset.label}\" added")
    }
  }

  fun requestReprepare(layer: Layer) {
    val asset = _project.value?.asset(layer.assetId) ?: return
    if (asset.prep?.sourceFile == null) {
      say("This clip can't be changed; add it again to pick new options")
      return
    }
    pendingClip.value = PendingClip(null, layer.id, asset)
  }

  fun startClip(pending: PendingClip, options: BakeOptions) {
    pendingClip.value = null
    val label = pending.media?.label ?: pending.reprepare?.label ?: "clip"
    runImport(label) {
      val text = "Preparing $label"
      val asset = if (pending.reprepare != null) {
        importer.reprepare(pending.reprepare, options) { p -> _importJob.value = ImportJob(text, p) }
      } else {
        importer.importClip(pending.media!!, options) { p -> _importJob.value = ImportJob(text, p) }
      }
      attach(asset, pending.targetLayerId)
    }
  }

  private fun runImport(label: String, block: suspend () -> Unit) {
    importRunner?.cancel()
    _importJob.value = ImportJob("Preparing $label", 0f)
    importRunner = viewModelScope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        say("Cancelled")
      } catch (e: Exception) {
        say(e.message ?: "Import failed")
      } finally {
        _importJob.value = null
      }
    }
  }

  fun cancelImport() {
    importRunner?.cancel()
  }

  /** Puts [asset] into the layer (or a new layer on top of the current scene). */
  private fun attach(asset: Asset, targetLayerId: String?, fit: Fit? = null, scale: Float? = null) {
    val p = _project.value ?: return
    val target = targetLayerId?.let { id -> p.scenes.flatMap { it.layers }.firstOrNull { it.id == id } }
    if (target != null) {
      edit(null) { q ->
        q.copy(
          assets = q.assets + asset,
          scenes = q.scenes.map { s ->
            s.copy(layers = s.layers.map { l -> if (l.id == target.id) l.copy(assetId = asset.id, type = LayerType.MEDIA, fit = fit ?: l.fit) else l })
          },
        )
      }
      selectedLayerId.value = target.id
    } else {
      val layer = Layer(
        id = Ids.new("l"),
        name = asset.label.substringBeforeLast('.').take(24).ifEmpty { "Media" },
        assetId = asset.id,
        fit = fit ?: Fit.COVER,
        transform = Transform(scale = scale ?: 1f),
      )
      edit(null) { it.copy(assets = it.assets + asset) }
      addLayer(layer)
    }
  }

  // ---------------- Export ----------------

  /** Set after an export; the editor opens the share sheet for it. */
  val shareRequest = MutableStateFlow<ShareRequest?>(null)
  private val exportDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "lwm-export") }.asCoroutineDispatcher()

  /** Renders [sceneId] to a video or GIF, saves it to the gallery and offers to share it. */
  fun exportScene(sceneId: String, options: ExportOptions) {
    val p = _project.value ?: return
    importRunner?.cancel()
    val what = if (options.gif) "GIF" else "video"
    _importJob.value = ImportJob("Making the $what", 0f)
    importRunner = viewModelScope.launch {
      val app = getApplication<Application>()
      val tmp = File(File(app.cacheDir, "exports").apply { mkdirs() }, "export-${System.nanoTime()}." + if (options.gif) "gif" else "mp4")
      try {
        val ctx = currentCoroutineContext()
        withContext(exportDispatcher) {
          SceneExporter.export(app, repo.storage, p, sceneId, options, tmp, isCancelled = { !ctx.isActive }) { f ->
            _importJob.value = ImportJob("Making the $what", f)
          }
        }
        val uri = withContext(Dispatchers.IO) { saveToGallery(tmp, options.gif, p.name) }
        say(if (options.gif) "Saved to Pictures/Live Wallpaper Maker" else "Saved to Movies/Live Wallpaper Maker")
        shareRequest.value = ShareRequest(uri, if (options.gif) "image/gif" else "video/mp4")
      } catch (e: CancellationException) {
        say("Cancelled")
      } catch (e: Exception) {
        say("Export failed: ${e.message}")
      } finally {
        tmp.delete()
        _importJob.value = null
      }
    }
  }

  private fun saveToGallery(file: File, gif: Boolean, name: String): Uri {
    val resolver = getApplication<Application>().contentResolver
    val clean = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Wallpaper" }
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    val values = android.content.ContentValues().apply {
      put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$clean $stamp." + if (gif) "gif" else "mp4")
      put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (gif) "image/gif" else "video/mp4")
      put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, (if (gif) "Pictures" else "Movies") + "/Live Wallpaper Maker")
      put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val collection = if (gif) {
      android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
      android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    val uri = resolver.insert(collection, values) ?: throw java.io.IOException("Couldn't save to the gallery")
    try {
      resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw java.io.IOException("Couldn't write the file")
      values.clear()
      values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
      return uri
    } catch (e: Exception) {
      resolver.delete(uri, null, null)
      throw e
    }
  }

  // ---------------- Apply ----------------

  /** Saves and hands the project to the wallpaper. */
  suspend fun apply(poster: Bitmap?): Project? {
    val p = _project.value ?: return null
    return withContext(Dispatchers.IO) {
      val clean = repo.pruneUnusedAssets(p)
      if (poster != null) runCatching { repo.savePoster(clean.id, poster) }
      repo.apply(clean)
      _activeId.value = clean.id
      clean
    }
  }

  fun savePoster(poster: Bitmap) {
    val id = _project.value?.id ?: return
    viewModelScope.launch(Dispatchers.IO) { runCatching { repo.savePoster(id, poster) } }
  }
}
