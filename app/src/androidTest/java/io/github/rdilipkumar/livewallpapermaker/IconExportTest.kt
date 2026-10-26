package io.github.rdilipkumar.livewallpapermaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders the launcher icon to a PNG (used for the README). Also checks the icon resources load. */
@RunWith(AndroidJUnit4::class)
class IconExportTest {
  @Test
  fun exportIcon() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val icon = ctx.getDrawable(R.mipmap.ic_launcher)!!
    val size = 512
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    icon.setBounds(0, 0, size, size)
    icon.draw(canvas)
    // Rounded-square mask like most launchers.
    val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(mask).drawPath(Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 120f, 120f, Path.Direction.CW) }, Paint(Paint.ANTI_ALIAS_FLAG))
    canvas.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
    val out = File(ctx.getExternalFilesDir(null), "icon.png")
    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    assertTrue(out.length() > 1000)
  }
}
