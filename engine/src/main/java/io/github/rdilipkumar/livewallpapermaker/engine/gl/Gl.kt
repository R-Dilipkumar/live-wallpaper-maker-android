package io.github.rdilipkumar.livewallpapermaker.engine.gl

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Gl {
  private const val TAG = "LwmGl"

  /** Identity texture matrix: texture rows already run bottom-to-top (FBOs, SurfaceTexture after its own matrix). */
  val IDENTITY: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

  /** Bitmaps are uploaded top row first, so their v axis must be flipped. */
  val FLIP_V: FloatArray = FloatArray(16).also {
    Matrix.setIdentityM(it, 0)
    Matrix.translateM(it, 0, 0f, 1f, 0f)
    Matrix.scaleM(it, 0, 1f, -1f, 1f)
  }

  fun compile(type: Int, source: String): Int {
    val shader = GLES30.glCreateShader(type)
    GLES30.glShaderSource(shader, source)
    GLES30.glCompileShader(shader)
    val ok = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
    if (ok[0] == 0) {
      val log = GLES30.glGetShaderInfoLog(shader)
      GLES30.glDeleteShader(shader)
      throw IllegalStateException("Shader compile failed: $log")
    }
    return shader
  }

  fun program(vertex: String, fragment: String): Int {
    val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
    val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
    val p = GLES30.glCreateProgram()
    GLES30.glAttachShader(p, vs)
    GLES30.glAttachShader(p, fs)
    GLES30.glLinkProgram(p)
    GLES30.glDeleteShader(vs)
    GLES30.glDeleteShader(fs)
    val ok = IntArray(1)
    GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
    if (ok[0] == 0) {
      val log = GLES30.glGetProgramInfoLog(p)
      GLES30.glDeleteProgram(p)
      throw IllegalStateException("Program link failed: $log")
    }
    return p
  }

  fun checkError(where: String) {
    val e = GLES30.glGetError()
    if (e != GLES30.GL_NO_ERROR) Log.w(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
  }

  fun createTexture(target: Int = GLES30.GL_TEXTURE_2D, mipmap: Boolean = false): Int {
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    GLES30.glBindTexture(target, ids[0])
    val minFilter = if (mipmap) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, minFilter)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    return ids[0]
  }

  fun createOesTexture(): Int = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

  /** Uploads a bitmap (premultiplied, as Android stores it) into a new mipmapped texture. */
  fun uploadBitmap(bitmap: Bitmap, existing: Int = 0): Int {
    val tex = if (existing != 0) existing else createTexture(mipmap = true)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    return tex
  }

  fun deleteTexture(tex: Int) {
    if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
  }

  /** Reads the bound framebuffer into a bitmap (rows flipped to normal top-down order). */
  fun readPixels(width: Int, height: Int): Bitmap {
    val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    buf.rewind()
    bmp.copyPixelsFromBuffer(buf)
    val m = android.graphics.Matrix().apply { postScale(1f, -1f) }
    val flipped = Bitmap.createBitmap(bmp, 0, 0, width, height, m, false)
    if (flipped !== bmp) bmp.recycle()
    return flipped
  }
}

/** Offscreen colour target. Its texture is in GL orientation (row 0 = bottom), so it is sampled with [Gl.IDENTITY]. */
class Fbo(width: Int, height: Int, private val mipmap: Boolean = false) {
  var width = width; private set
  var height = height; private set
  var texture = 0; private set
  private var framebuffer = 0

  init {
    allocate()
  }

  private fun allocate() {
    texture = Gl.createTexture(mipmap = mipmap)
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
      GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
    )
    if (mipmap) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    val ids = IntArray(1)
    GLES30.glGenFramebuffers(1, ids, 0)
    framebuffer = ids[0]
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
    GLES30.glFramebufferTexture2D(
      GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0,
    )
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
  }

  fun ensureSize(w: Int, h: Int) {
    if (w == width && h == height) return
    release()
    width = w
    height = h
    allocate()
  }

  fun bind() {
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
    GLES30.glViewport(0, 0, width, height)
  }

  fun generateMipmaps() {
    if (!mipmap) return
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
  }

  fun release() {
    if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
    Gl.deleteTexture(texture)
    framebuffer = 0
    texture = 0
  }
}

/** A unit square from -0.5 to 0.5 drawn as a triangle strip; layers place it with a matrix. */
class Quad {
  private val vao: Int
  private val vbo: Int
  private val emptyVao: Int

  init {
    val empty = IntArray(1)
    GLES30.glGenVertexArrays(1, empty, 0)
    emptyVao = empty[0]
    val data = floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)
    val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    buf.put(data).position(0)
    val ids = IntArray(1)
    GLES30.glGenVertexArrays(1, ids, 0)
    vao = ids[0]
    GLES30.glGenBuffers(1, ids, 0)
    vbo = ids[0]
    GLES30.glBindVertexArray(vao)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
    GLES30.glBindVertexArray(0)
  }

  fun draw() {
    GLES30.glBindVertexArray(vao)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    GLES30.glBindVertexArray(0)
  }

  /** Draws [count] points with no vertex data; the particle shader builds them from gl_VertexID. */
  fun drawPoints(count: Int) {
    GLES30.glBindVertexArray(emptyVao)
    GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
    GLES30.glBindVertexArray(0)
  }

  fun release() {
    GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
    GLES30.glDeleteVertexArrays(2, intArrayOf(vao, emptyVao), 0)
  }
}
