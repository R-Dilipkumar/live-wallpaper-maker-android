package io.github.rdilipkumar.livewallpapermaker.wallpaper

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.FileObserver
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import io.github.rdilipkumar.livewallpapermaker.data.Templates
import io.github.rdilipkumar.livewallpapermaker.engine.TriggerEvent
import io.github.rdilipkumar.livewallpapermaker.engine.WallpaperRenderer
import io.github.rdilipkumar.livewallpapermaker.engine.model.ActiveWallpaper
import io.github.rdilipkumar.livewallpapermaker.engine.model.ProjectJson
import io.github.rdilipkumar.livewallpapermaker.engine.model.StorageLayout
import kotlin.math.hypot

/**
 * The live wallpaper. Runs in its own small process (":wallpaper") and only plays what the editor
 * applied (files/active.json). It reloads by itself when that file is replaced.
 */
class LiveWallpaperService : WallpaperService() {

  override fun onCreateEngine(): Engine = LwmEngine()

  private inner class LwmEngine : Engine() {
    private val storage = StorageLayout(filesDir)
    private var renderer: WallpaperRenderer? = null
    private var observer: FileObserver? = null
    private var active: ActiveWallpaper? = null
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var surfaceSet = false

    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { pushPower() }

    private val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val r = renderer ?: return
        when (intent.action) {
          Intent.ACTION_USER_PRESENT -> r.trigger(TriggerEvent.UNLOCK)
          Intent.ACTION_SCREEN_ON -> r.trigger(TriggerEvent.SCREEN_ON)
          Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> r.trigger(TriggerEvent.POWER_CHANGED)
          PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> pushPower()
        }
      }
    }

    override fun onCreate(surfaceHolder: SurfaceHolder) {
      super.onCreate(surfaceHolder)
      setTouchEventsEnabled(false)
      setOffsetNotificationsEnabled(true)
      renderer = WallpaperRenderer(this@LiveWallpaperService, storage)
      loadActive()
      observer = object : FileObserver(storage.root, CLOSE_WRITE or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
          if (path == StorageLayout.ACTIVE_NAME) loadActive()
        }
      }.also { it.startWatching() }
      val filter = IntentFilter().apply {
        addAction(Intent.ACTION_USER_PRESENT)
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
        addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
      }
      ContextCompat.registerReceiver(this@LiveWallpaperService, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
      runCatching { power.addThermalStatusListener(mainExecutor, thermalListener) }
      pushPower()
    }

    private fun loadActive() {
      val loaded = ProjectJson.readActive(storage.activeFile)
      active = loaded
      renderer?.setProject(loaded?.project ?: Templates.fallbackWallpaper())
      runCatching { notifyColorsChanged() }
    }

    private fun pushPower() {
      renderer?.setPowerState(power.isPowerSaveMode, runCatching { power.currentThermalStatus }.getOrDefault(0))
    }

    override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
      super.onSurfaceChanged(holder, format, width, height)
      val r = renderer ?: return
      if (!surfaceSet) {
        r.setSurface(holder.surface, width, height)
        surfaceSet = true
      } else {
        r.setSize(width, height)
      }
    }

    override fun onSurfaceDestroyed(holder: SurfaceHolder) {
      renderer?.setSurface(null, 0, 0)
      surfaceSet = false
      super.onSurfaceDestroyed(holder)
    }

    override fun onVisibilityChanged(visible: Boolean) {
      if (visible) pushPower()
      renderer?.setVisible(visible)
    }

    override fun onOffsetsChanged(
      xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int,
    ) {
      renderer?.setSwipe(xOffset)
    }

    override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
      if (action == WallpaperManager.COMMAND_TAP) {
        val now = SystemClock.uptimeMillis()
        val close = hypot(x - lastTapX, y - lastTapY) < 150f
        if (now - lastTapAt < 400L && close) {
          renderer?.trigger(TriggerEvent.DOUBLE_TAP)
          lastTapAt = 0L
        } else {
          lastTapAt = now
          lastTapX = x.toFloat()
          lastTapY = y.toFloat()
        }
      }
      return null
    }

    override fun onComputeColors(): WallpaperColors? {
      val file = storage.activePoster
      if (!file.exists()) return null
      return runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        BitmapFactory.decodeFile(file.path, opts)?.let { bmp ->
          WallpaperColors.fromBitmap(bmp).also { bmp.recycle() }
        }
      }.getOrNull()
    }

    override fun onZoomChanged(zoom: Float) {
      renderer?.setShadeZoom(zoom)
    }

    override fun onDestroy() {
      runCatching { unregisterReceiver(receiver) }
      runCatching { power.removeThermalStatusListener(thermalListener) }
      observer?.stopWatching()
      renderer?.release()
      renderer = null
      super.onDestroy()
    }
  }
}
