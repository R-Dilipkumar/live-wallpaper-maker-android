package io.github.rdilipkumar.livewallpapermaker.engine.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/**
 * One EGL display + GLES 3 context. [recordable] is needed when drawing into a MediaCodec encoder surface.
 * All calls must happen on the thread that owns the context.
 */
class EglCore(recordable: Boolean = false) {
  val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
  val config: EGLConfig
  val context: EGLContext

  init {
    check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
    val version = IntArray(2)
    check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

    val attribs = mutableListOf(
      EGL14.EGL_RED_SIZE, 8,
      EGL14.EGL_GREEN_SIZE, 8,
      EGL14.EGL_BLUE_SIZE, 8,
      EGL14.EGL_ALPHA_SIZE, 8,
      EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
      EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
    )
    if (recordable) attribs += listOf(EGL_RECORDABLE_ANDROID, 1)
    attribs += EGL14.EGL_NONE
    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    check(EGL14.eglChooseConfig(display, attribs.toIntArray(), 0, configs, 0, 1, count, 0) && count[0] > 0) {
      "No suitable EGL config"
    }
    config = configs[0]!!
    context = EGL14.eglCreateContext(
      display, config, EGL14.EGL_NO_CONTEXT,
      intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
    )
    check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
  }

  /** [surface] is a Surface or SurfaceTexture. */
  fun createWindowSurface(surface: Any): EGLSurface {
    val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
    check(s != null && s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    return s
  }

  fun createPbuffer(width: Int, height: Int): EGLSurface {
    val s = EGL14.eglCreatePbufferSurface(
      display, config,
      intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
    )
    check(s != null && s != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
    return s
  }

  fun makeCurrent(surface: EGLSurface): Boolean = EGL14.eglMakeCurrent(display, surface, surface, context)

  fun makeNothingCurrent() {
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
  }

  fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

  fun setPresentationTime(surface: EGLSurface, nanos: Long) {
    EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
  }

  fun querySize(surface: EGLSurface): Pair<Int, Int> {
    val v = IntArray(1)
    EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, v, 0)
    val w = v[0]
    EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, v, 0)
    return w to v[0]
  }

  fun releaseSurface(surface: EGLSurface) {
    EGL14.eglDestroySurface(display, surface)
  }

  fun release() {
    makeNothingCurrent()
    EGL14.eglDestroyContext(display, context)
    EGL14.eglReleaseThread()
    EGL14.eglTerminate(display)
  }

  private companion object {
    const val EGL_OPENGL_ES3_BIT = 0x40
    const val EGL_RECORDABLE_ANDROID = 0x3142
  }
}
