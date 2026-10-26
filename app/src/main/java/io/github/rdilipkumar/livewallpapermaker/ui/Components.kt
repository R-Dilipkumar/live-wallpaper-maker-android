package io.github.rdilipkumar.livewallpapermaker.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

val LwmColors = darkColorScheme(
  primary = Color(0xFFB7A6FF),
  onPrimary = Color(0xFF21155E),
  primaryContainer = Color(0xFF3A2C8C),
  onPrimaryContainer = Color(0xFFE6DEFF),
  secondary = Color(0xFF5FD8CB),
  onSecondary = Color(0xFF00382F),
  secondaryContainer = Color(0xFF0B5A54),
  background = Color(0xFF0F1115),
  surface = Color(0xFF0F1115),
  surfaceContainer = Color(0xFF181B22),
  surfaceContainerHigh = Color(0xFF20242D),
  surfaceContainerHighest = Color(0xFF292E39),
  surfaceVariant = Color(0xFF242833),
  onSurfaceVariant = Color(0xFFC4C6D0),
)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier.padding(top = 14.dp, bottom = 4.dp),
  )
}

@Composable
fun LabeledSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  onChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  steps: Int = 0,
  format: (Float) -> String = { "%.2f".format(it) },
) {
  Column(modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
      Text(format(value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.height(36.dp))
  }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    for (o in options) {
      FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
    }
  }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
  Row(
    Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyMedium)
      if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
fun ColorSwatch(argb: Int, onClick: () -> Unit, size: Int = 34) {
  Box(
    Modifier
      .size(size.dp)
      .clip(CircleShape)
      .background(Color(argb))
      .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
      .clickable(onClick = onClick),
  )
}

private val PRESET_COLORS = listOf(
  0xFFFFFFFF, 0xFF000000, 0xFFFF5252, 0xFFFF9800, 0xFFFFEB3B, 0xFF8BC34A, 0xFF26D0CE, 0xFF2196F3,
  0xFF3F51B5, 0xFF9C27B0, 0xFFE91E63, 0xFF795548, 0xFF0B1026, 0xFF1A2980, 0xFFE9FF70, 0xFFFFC56E,
).map { it.toInt() }

/** Small HSV picker: preset colours plus hue / saturation / brightness / opacity sliders. */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit, allowAlpha: Boolean = false) {
  val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
  var h by remember { mutableFloatStateOf(hsv[0]) }
  var s by remember { mutableFloatStateOf(hsv[1]) }
  var v by remember { mutableFloatStateOf(hsv[2]) }
  var a by remember { mutableFloatStateOf(android.graphics.Color.alpha(initial) / 255f) }
  val color = android.graphics.Color.HSVToColor((a * 255).roundToInt(), floatArrayOf(h, s, v))
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = { onPick(color) }) { Text("Use colour") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    title = { Text("Colour") },
    text = {
      Column {
        Box(
          Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(color)),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          PRESET_COLORS.forEach { c ->
            ColorSwatch(c, onClick = {
              val t = FloatArray(3)
              android.graphics.Color.colorToHSV(c, t)
              h = t[0]; s = t[1]; v = t[2]
            }, size = 28)
          }
        }
        Box(
          Modifier.fillMaxWidth().padding(top = 12.dp).height(10.dp).clip(RoundedCornerShape(5.dp))
            .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })),
        )
        LabeledSlider("Hue", h, 0f..359f, { h = it }, format = { "${it.roundToInt()}°" })
        LabeledSlider("Saturation", s, 0f..1f, { s = it }, format = { "${(it * 100).roundToInt()}%" })
        LabeledSlider("Brightness", v, 0f..1f, { v = it }, format = { "${(it * 100).roundToInt()}%" })
        if (allowAlpha) LabeledSlider("Opacity", a, 0f..1f, { a = it }, format = { "${(it * 100).roundToInt()}%" })
      }
    },
  )
}

/** Loads a small image file off the main thread. */
@Composable
fun rememberImageFile(file: File?, key: Any? = null): ImageBitmap? {
  var bmp by remember(file?.path, key) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(file?.path, key) {
    bmp = withContext(Dispatchers.IO) {
      file?.takeIf { it.exists() }?.let { f ->
        runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
      }
    }
  }
  return bmp
}

@Composable
fun Hint(text: String) {
  Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.secondary) {
  Text(
    text,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    color = Color.Black,
    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

fun Color.argb(): Int = toArgb()

fun percent(v: Float) = "${(v * 100).roundToInt()}%"
