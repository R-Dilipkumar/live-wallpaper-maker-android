package io.github.rdilipkumar.livewallpapermaker.engine.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What the wallpaper is showing right now: a snapshot of a project taken when the user tapped Apply. */
@Serializable
data class ActiveWallpaper(val project: Project, val appliedAt: Long = 0)

object ProjectJson {
  val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    explicitNulls = false
  }

  fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)
  fun decode(text: String): Project = json.decodeFromString(Project.serializer(), text)

  fun encodeActive(active: ActiveWallpaper): String = json.encodeToString(ActiveWallpaper.serializer(), active)
  fun decodeActive(text: String): ActiveWallpaper = json.decodeFromString(ActiveWallpaper.serializer(), text)

  fun readProject(file: File): Project? = runCatching { decode(file.readText()) }.getOrNull()
  fun readActive(file: File): ActiveWallpaper? = runCatching { decodeActive(file.readText()) }.getOrNull()

  /** Writes to a temp file and renames it, so readers never see half a file. */
  fun writeAtomic(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
      file.delete()
      if (!tmp.renameTo(file)) {
        file.writeText(text)
        tmp.delete()
      }
    }
  }
}

/** File layout shared by the editor and the wallpaper process (both use the app's files folder). */
class StorageLayout(val root: File) {
  val assetsDir: File get() = File(root, "assets")
  val projectsDir: File get() = File(root, "projects")
  val activeFile: File get() = File(root, ACTIVE_NAME)
  val activePoster: File get() = File(root, "active-poster.jpg")

  fun assetFile(asset: Asset): File = File(assetsDir, asset.file)
  fun projectDir(id: String): File = File(projectsDir, id)
  fun projectFile(id: String): File = File(projectDir(id), "project.json")
  fun posterFile(id: String): File = File(projectDir(id), "poster.jpg")

  companion object {
    const val ACTIVE_NAME = "active.json"
  }
}
