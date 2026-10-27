package io.github.rdilipkumar.livewallpapermaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rdilipkumar.livewallpapermaker.data.DepthPhotoMaker
import io.github.rdilipkumar.livewallpapermaker.data.ProjectRepository
import io.github.rdilipkumar.livewallpapermaker.engine.model.Geometry
import io.github.rdilipkumar.livewallpapermaker.engine.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The cut-out model needs Google Play services (not on test emulators), so this feeds the 3D photo
 * builder a made-up subject mask and checks what it saves and the layers it makes.
 */
@RunWith(AndroidJUnit4::class)
class DepthPhotoBuildTest {
  @Test
  fun subjectIsCutOutAndBackgroundFilled() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(ctx.cacheDir, "depth-test").apply { deleteRecursively(); mkdirs() }
    val repo = ProjectRepository(root)
    val w = 400
    val h = 600
    // Sky-blue background with a red square "subject" in the middle.
    val px = IntArray(w * h) { i ->
      val x = i % w
      val y = i / w
      if (x in 140..260 && y in 220..380) 0xFFE01010.toInt() else 0xFF5A9BD6.toInt()
    }
    val photo = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    val conf = FloatArray(w * h) { i -> if (px[i] == 0xFFE01010.toInt()) 0.98f else 0.01f }

    val result = DepthPhotoMaker(ctx, repo).buildForTest(photo, conf, "test.jpg")

    val bg = BitmapFactory.decodeFile(File(repo.storage.assetsDir, result.background.file).path)
    val subject = BitmapFactory.decodeFile(File(repo.storage.assetsDir, result.subject.file).path)
    assertEquals(w, bg.width)
    assertEquals(w, subject.width)
    // Subject: opaque red in the middle, transparent outside.
    assertEquals(255, subject.getPixel(200, 300) ushr 24)
    assertEquals(0, subject.getPixel(20, 20) ushr 24)
    // Background: the red is gone from the middle (filled with sky), the rest is untouched.
    val mid = bg.getPixel(200, 300)
    assertTrue("filled colour ${Integer.toHexString(mid)}", (mid and 0xFF) > ((mid shr 16) and 0xFF))
    val edge = bg.getPixel(20, 20)
    assertTrue(kotlin.math.abs(((edge shr 16) and 0xFF) - 0x5A) < 12)

    // Layers: background, clock, subject; subject sized so both line up at rest.
    val layers = DepthPhotoMaker.layers(result, withClock = true)
    assertEquals(listOf(LayerType.MEDIA, LayerType.TEXT, LayerType.MEDIA), layers.map { it.type })
    val bgLayer = layers[0]
    val subjectLayer = layers[2]
    assertEquals(
      Geometry.overscan(bgLayer) * bgLayer.transform.scale,
      Geometry.overscan(subjectLayer) * subjectLayer.transform.scale,
      0.0001f,
    )
    assertTrue(subjectLayer.motion.tiltDepth > bgLayer.motion.tiltDepth)
  }
}
