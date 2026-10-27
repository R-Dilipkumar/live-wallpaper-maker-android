package io.github.rdilipkumar.livewallpapermaker.data

import android.content.Context
import android.graphics.Bitmap
import io.github.rdilipkumar.livewallpapermaker.engine.model.ActiveWallpaper
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.ProjectJson
import io.github.rdilipkumar.livewallpapermaker.engine.model.StorageLayout
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ProjectSummary(
  val id: String,
  val name: String,
  val updatedAt: Long,
  val poster: File?,
  val scenes: Int,
  val layers: Int,
)

/** Projects and media live in the app's private files folder (see [StorageLayout]). */
class ProjectRepository(root: File) {
  constructor(context: Context) : this(context.filesDir)

  val storage = StorageLayout(root)

  fun list(): List<ProjectSummary> {
    val dirs = storage.projectsDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
    return dirs.mapNotNull { dir ->
      val p = ProjectJson.readProject(File(dir, "project.json")) ?: return@mapNotNull null
      val poster = storage.posterFile(p.id).takeIf { it.exists() }
      ProjectSummary(p.id, p.name, p.updatedAt, poster, p.scenes.size, p.scenes.sumOf { it.layers.size })
    }.sortedByDescending { it.updatedAt }
  }

  fun load(id: String): Project? = ProjectJson.readProject(storage.projectFile(id))

  fun save(project: Project, touch: Boolean = true): Project {
    val p = if (touch) project.copy(updatedAt = System.currentTimeMillis()) else project
    ProjectJson.writeAtomic(storage.projectFile(p.id), ProjectJson.encode(p))
    return p
  }

  fun create(template: Template): Project = save(template.build())

  fun duplicate(id: String): Project? {
    val p = load(id) ?: return null
    val now = System.currentTimeMillis()
    val copy = save(p.copy(id = Ids.new("p"), name = p.name + " (copy)", createdAt = now))
    storage.posterFile(id).takeIf { it.exists() }?.copyTo(storage.posterFile(copy.id), overwrite = true)
    return copy
  }

  fun delete(id: String) {
    storage.projectDir(id).deleteRecursively()
    gcAssets()
  }

  fun activeProjectId(): String? = ProjectJson.readActive(storage.activeFile)?.project?.id

  /** Hands the project to the wallpaper process (it watches this file). */
  fun apply(project: Project) {
    val saved = save(pruneUnusedAssets(project))
    ProjectJson.writeAtomic(storage.activeFile, ProjectJson.encodeActive(ActiveWallpaper(saved, System.currentTimeMillis())))
    val poster = storage.posterFile(saved.id)
    if (poster.exists()) poster.copyTo(storage.activePoster, overwrite = true) else storage.activePoster.delete()
  }

  fun savePoster(id: String, bitmap: Bitmap) {
    val file = storage.posterFile(id)
    file.parentFile?.mkdirs()
    val w = 360
    val h = (bitmap.height * w.toFloat() / bitmap.width).toInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
    file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    if (small !== bitmap) small.recycle()
  }

  /** Every asset file still used by a project or by the live wallpaper (including thumbnails and sources). */
  private fun referencedFiles(): Set<String> {
    val projects = (storage.projectsDir.listFiles() ?: emptyArray()).mapNotNull { ProjectJson.readProject(File(it, "project.json")) }
    val active = ProjectJson.readActive(storage.activeFile)?.project
    val names = HashSet<String>()
    for (p in projects + listOfNotNull(active)) for (a in p.assets) {
      names += a.file
      names += thumbName(a.id)
      a.prep?.sourceFile?.let { names += it }
    }
    return names
  }

  /** Deletes media no project uses. Files younger than a few minutes are kept (an import may be running). */
  fun gcAssets() {
    val keep = referencedFiles()
    val cutoff = System.currentTimeMillis() - 10 * 60_000L
    storage.assetsDir.listFiles()?.forEach { f ->
      if (f.name !in keep && f.lastModified() < cutoff) f.delete()
    }
  }

  /** Assets referenced by the project are copied with it; unused ones are dropped. */
  fun pruneUnusedAssets(project: Project): Project {
    val used = project.scenes.flatMap { s -> s.layers.flatMap { listOfNotNull(it.assetId, it.text?.fontAsset) } }.toSet()
    return project.copy(assets = project.assets.filter { it.id in used })
  }

  // ---------------- Sharing (.lwm = zip of project.json + media) ----------------

  fun export(id: String, out: OutputStream) {
    val p = load(id) ?: throw IOException("Project not found")
    val clean = pruneUnusedAssets(p).let { q -> q.copy(assets = q.assets.map { it.copy(prep = it.prep?.copy(sourceFile = null)) }) }
    ZipOutputStream(out.buffered()).use { zip ->
      zip.putNextEntry(ZipEntry("project.json"))
      zip.write(ProjectJson.encode(clean).toByteArray())
      zip.closeEntry()
      storage.posterFile(id).takeIf { it.exists() }?.let { f ->
        zip.putNextEntry(ZipEntry("poster.jpg"))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
      for (a in clean.assets) {
        val f = storage.assetFile(a)
        if (!f.exists()) continue
        zip.putNextEntry(ZipEntry("assets/" + a.file))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
    }
  }

  /** Reads a shared project. Only plain file names are accepted, so a zip cannot write outside our folders. */
  fun import(input: InputStream): Project {
    var project: Project? = null
    val staging = File(storage.root, "import-" + System.nanoTime()).apply { mkdirs() }
    try {
      ZipInputStream(input.buffered()).use { zip ->
        var total = 0L
        while (true) {
          val e = zip.nextEntry ?: break
          if (e.isDirectory) continue
          val name = e.name
          when {
            name == "project.json" -> project = ProjectJson.decode(zip.readBytes().decodeToString())
            name == "poster.jpg" -> copyLimited(zip, File(staging, "poster.jpg")).also { total += it }
            name.startsWith("assets/") -> {
              val file = name.removePrefix("assets/")
              if (!SAFE_NAME.matches(file)) continue
              total += copyLimited(zip, File(staging, file))
            }
          }
          if (total > MAX_IMPORT_BYTES) throw IOException("This file is too large to import")
        }
      }
      val p = project ?: throw IOException("Not a Live Wallpaper Maker project")
      val now = System.currentTimeMillis()
      val imported = p.copy(id = Ids.new("p"), createdAt = now, updatedAt = now, assets = p.assets.filter { SAFE_NAME.matches(it.file) })
      storage.assetsDir.mkdirs()
      for (a in imported.assets) {
        val src = File(staging, a.file)
        val dst = storage.assetFile(a)
        if (src.exists() && !dst.exists()) src.copyTo(dst)
      }
      save(imported, touch = false)
      File(staging, "poster.jpg").takeIf { it.exists() }?.copyTo(storage.posterFile(imported.id), overwrite = true)
      return imported
    } finally {
      staging.deleteRecursively()
    }
  }

  private fun copyLimited(input: InputStream, out: File): Long {
    var n = 0L
    out.outputStream().use { o ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val r = input.read(buf)
        if (r < 0) break
        n += r
        if (n > MAX_IMPORT_BYTES) throw IOException("This file is too large to import")
        o.write(buf, 0, r)
      }
    }
    return n
  }

  companion object {
    private val SAFE_NAME = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,119}$")
    private const val MAX_IMPORT_BYTES = 1_500_000_000L
    fun thumbName(assetId: String) = "$assetId-thumb.jpg"
  }
}
