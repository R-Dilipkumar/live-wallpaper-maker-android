package io.github.rdilipkumar.livewallpapermaker.engine.render

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phone tilt relative to how it is usually held. The "rest" pose slowly follows the phone, so the
 * wallpaper re-centres a few seconds after the user changes how they hold it.
 * Uses the game rotation vector (gyro + accelerometer, no compass) and runs only while registered.
 */
internal class TiltSensor(context: Context, private val handler: Handler, private val onChange: () -> Unit) :
  SensorEventListener {
  private val manager = context.getSystemService(SensorManager::class.java)
  private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

  val available: Boolean get() = sensor != null
  var x = 0f; private set
  var y = 0f; private set
  private var running = false
  private var hasRef = false
  private val ref = FloatArray(4)
  private val cur = FloatArray(4)
  private val rCur = FloatArray(9)
  private val rRef = FloatArray(9)
  private val change = FloatArray(3)

  fun start() {
    if (running || sensor == null) return
    running = true
    hasRef = false
    manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
  }

  fun stop() {
    if (!running) return
    running = false
    manager?.unregisterListener(this)
  }

  /** Lets the editor fake a tilt without the sensor. */
  fun setManual(tx: Float, ty: Float) {
    x = tx.coerceIn(-1f, 1f)
    y = ty.coerceIn(-1f, 1f)
  }

  override fun onSensorChanged(event: SensorEvent) {
    val v = event.values
    cur[0] = v[0]
    cur[1] = v[1]
    cur[2] = v[2]
    cur[3] = if (v.size > 3) v[3] else sqrt(maxOf(0f, 1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]))
    if (!hasRef) {
      cur.copyInto(ref)
      hasRef = true
    } else {
      // Follow the current pose slowly (normalised lerp of quaternions, same hemisphere).
      val dot = ref[0] * cur[0] + ref[1] * cur[1] + ref[2] * cur[2] + ref[3] * cur[3]
      val sign = if (dot < 0f) -1f else 1f
      for (i in 0 until 4) ref[i] += (cur[i] * sign - ref[i]) * FOLLOW
      val n = sqrt(ref[0] * ref[0] + ref[1] * ref[1] + ref[2] * ref[2] + ref[3] * ref[3])
      if (n > 0f) for (i in 0 until 4) ref[i] /= n
    }
    SensorManager.getRotationMatrixFromVector(rCur, cur)
    SensorManager.getRotationMatrixFromVector(rRef, ref)
    SensorManager.getAngleChange(change, rCur, rRef)
    val tx = (change[2] / MAX_ANGLE).coerceIn(-1f, 1f)
    val ty = (-change[1] / MAX_ANGLE).coerceIn(-1f, 1f)
    val nx = x + (tx - x) * SMOOTH
    val ny = y + (ty - y) * SMOOTH
    val moved = abs(nx - x) > 0.002f || abs(ny - y) > 0.002f
    x = nx
    y = ny
    if (moved) onChange()
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  private companion object {
    const val MAX_ANGLE = 0.42f // ~24 degrees of tilt = full movement
    const val FOLLOW = 0.004f
    const val SMOOTH = 0.22f
  }
}
