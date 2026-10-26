package io.github.rdilipkumar.livewallpapermaker.data

import io.github.rdilipkumar.livewallpapermaker.engine.model.Asset
import io.github.rdilipkumar.livewallpapermaker.engine.model.AssetKind
import io.github.rdilipkumar.livewallpapermaker.engine.model.Layer
import io.github.rdilipkumar.livewallpapermaker.engine.model.Project
import io.github.rdilipkumar.livewallpapermaker.engine.model.ProjectJson
import io.github.rdilipkumar.livewallpapermaker.engine.model.Scene
import io.github.rdilipkumar.livewallpapermaker.engine.model.VideoPrep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectRepositoryTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun repo(name: String) = ProjectRepository(tmp.newFolder(name))

  private fun sample(repo: ProjectRepository): Project {
    repo.storage.assetsDir.mkdirs()
    File(repo.storage.assetsDir, "a1.mp4").writeBytes(ByteArray(1000) { 7 })
    File(repo.storage.assetsDir, "a1-src.mp4").writeBytes(ByteArray(5000))
    File(repo.storage.assetsDir, "unused.jpg").writeBytes(ByteArray(10))
    val p = Project(
      id = "p1",
      name = "Mine",
      assets = listOf(
        Asset("a1", AssetKind.VIDEO, "a1.mp4", 10, 10, 1000, prep = VideoPrep(sourceFile = "a1-src.mp4")),
        Asset("u", AssetKind.IMAGE, "unused.jpg", 1, 1),
      ),
      scenes = listOf(Scene("s", layers = listOf(Layer("l", assetId = "a1")))),
    )
    return repo.save(p)
  }

  @Test
  fun exportImportRoundTrip() {
    val a = repo("a")
    val p = sample(a)
    val out = ByteArrayOutputStream()
    a.export(p.id, out)

    val b = repo("b")
    val imported = b.import(ByteArrayInputStream(out.toByteArray()))
    assertNotEquals(p.id, imported.id) // never overwrites an existing project
    assertEquals("Mine", imported.name)
    assertEquals(listOf("a1"), imported.assets.map { it.id }) // unused assets are not shared
    assertNull(imported.assets[0].prep?.sourceFile) // the big source copy stays private
    assertEquals(1000L, File(b.storage.assetsDir, "a1.mp4").length())
    assertFalse(File(b.storage.assetsDir, "a1-src.mp4").exists())
    assertEquals(imported, b.load(imported.id))
  }

  @Test
  fun zipCannotWriteOutsideItsFolder() {
    val bytes = ByteArrayOutputStream().also { bo ->
      ZipOutputStream(bo).use { z ->
        val p = Project(id = "x", assets = listOf(Asset("e", AssetKind.IMAGE, "../evil.jpg", 1, 1), Asset("d", AssetKind.IMAGE, "..", 1, 1)))
        z.putNextEntry(ZipEntry("project.json")); z.write(ProjectJson.encode(p).toByteArray()); z.closeEntry()
        z.putNextEntry(ZipEntry("assets/../evil.jpg")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
        z.putNextEntry(ZipEntry("../../outside.txt")); z.write(byteArrayOf(1)); z.closeEntry()
      }
    }.toByteArray()
    val r = repo("c")
    val imported = r.import(ByteArrayInputStream(bytes))
    assertTrue(imported.assets.isEmpty())
    assertFalse(File(r.storage.root, "evil.jpg").exists())
    assertFalse(File(r.storage.root.parentFile, "outside.txt").exists())
  }

  @Test(expected = IOException::class)
  fun notAProjectFails() {
    val bytes = ByteArrayOutputStream().also { bo ->
      ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("hello.txt")); z.write(byteArrayOf(1)); z.closeEntry() }
    }.toByteArray()
    repo("d").import(ByteArrayInputStream(bytes))
  }

  @Test
  fun applyAndCleanup() {
    val r = repo("e")
    val p = sample(r)
    r.apply(p)
    assertEquals(p.id, r.activeProjectId())
    // Make every file look old enough to be cleaned.
    r.storage.assetsDir.listFiles()!!.forEach { it.setLastModified(0) }
    r.delete(p.id)
    // The live wallpaper still uses a1 (and its source), so they stay; the unused file goes.
    assertTrue(File(r.storage.assetsDir, "a1.mp4").exists())
    assertTrue(File(r.storage.assetsDir, "a1-src.mp4").exists())
    assertFalse(File(r.storage.assetsDir, "unused.jpg").exists())
    assertTrue(r.list().isEmpty())
  }

  @Test
  fun duplicateAndTemplates() {
    val r = repo("f")
    val p = sample(r)
    val copy = r.duplicate(p.id)!!
    assertEquals("Mine (copy)", copy.name)
    assertEquals(2, r.list().size)
    // Every template builds a valid project with unique ids.
    for (t in Templates.all) {
      val built = t.build()
      val ids = built.scenes.map { it.id } + built.scenes.flatMap { s -> s.layers.map { it.id } }
      assertEquals(t.name, ids.size, ids.toSet().size)
      assertEquals(t.name, t.slots, built.scenes.sumOf { s -> s.layers.count { it.type.name == "MEDIA" && it.assetId == null } })
      assertEquals(built, ProjectJson.decode(ProjectJson.encode(built)))
    }
  }
}
