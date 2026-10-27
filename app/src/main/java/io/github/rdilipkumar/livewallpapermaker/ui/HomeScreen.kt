package io.github.rdilipkumar.livewallpapermaker.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rdilipkumar.livewallpapermaker.data.ProjectSummary
import io.github.rdilipkumar.livewallpapermaker.data.Template
import io.github.rdilipkumar.livewallpapermaker.data.Templates
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
  val projects by vm.projects.collectAsStateWithLifecycle()
  val activeId by vm.activeId.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var confirmDelete by remember { mutableStateOf<ProjectSummary?>(null) }

  LaunchedEffect(Unit) {
    vm.messages.collect { snackbar.showSnackbar(it, duration = if (it.length > 60) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short) }
  }

  val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) vm.importProject(uri)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Live Wallpaper Maker", fontWeight = FontWeight.SemiBold) },
        actions = {
          IconButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-lwm", "*/*")) }) {
            Icon(Icons.Default.FileOpen, contentDescription = "Import a shared wallpaper")
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    LazyVerticalGrid(
      columns = GridCells.Adaptive(150.dp),
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        Column {
          Text("Start from a template", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(8.dp))
          LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Templates.all, key = { it.id }) { t -> TemplateCard(t) { vm.createFromTemplate(t) } }
          }
        }
      }
      item(span = { GridItemSpan(maxLineSpan) }) {
        Text("My wallpapers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
      }
      if (projects.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          Text(
            "Nothing here yet. Pick a template above. Templates with no media (like Snowy night) work straight away.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      items(projects, key = { it.id }) { p ->
        ProjectCard(
          p,
          isActive = p.id == activeId,
          onOpen = { vm.openProject(p.id) },
          onDuplicate = { vm.duplicate(p.id) },
          onDelete = { confirmDelete = p },
          onShare = {
            scope.launch {
              val file = vm.exportForShare(p.id) ?: return@launch
              val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
              val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
              context.startActivity(Intent.createChooser(send, "Share wallpaper"))
            }
          },
        )
      }
    }
  }

  confirmDelete?.let { p ->
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text("Delete \"${p.name}\"?") },
      text = { Text("This removes the project and any media only it uses. It can't be undone.") },
      confirmButton = {
        TextButton(onClick = {
          vm.delete(p.id)
          confirmDelete = null
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
    )
  }
}

private fun templateIcon(id: String): ImageVector = when (id) {
  "blank" -> Icons.Default.Add
  "snow" -> Icons.Default.AcUnit
  "aurora" -> Icons.Default.AutoAwesome
  "rain" -> Icons.Default.WaterDrop
  "fireflies" -> Icons.Default.Flare
  "video" -> Icons.Default.Movie
  "depth" -> Icons.Default.Layers
  "tilt" -> Icons.Default.ScreenRotation
  "daynight" -> Icons.Default.Brightness4
  "slideshow" -> Icons.Default.ViewCarousel
  "intro" -> Icons.Default.LockOpen
  else -> Icons.Default.Wallpaper
}

private fun templateColors(id: String): List<Color> = when (id) {
  "snow" -> listOf(Color(0xFF0B1026), Color(0xFF3E4C8A))
  "aurora" -> listOf(Color(0xFF0B6E4F), Color(0xFF8338EC))
  "rain" -> listOf(Color(0xFF1F2A36), Color(0xFF5D6D7E))
  "fireflies" -> listOf(Color(0xFF03140F), Color(0xFF2E6B3F))
  "daynight" -> listOf(Color(0xFFFFB74D), Color(0xFF1A237E))
  "blank" -> listOf(Color(0xFF242833), Color(0xFF30343F))
  else -> listOf(Color(0xFF3A2C8C), Color(0xFF0B8F86))
}

@Composable
private fun TemplateCard(t: Template, onClick: () -> Unit) {
  Card(
    onClick = onClick,
    modifier = Modifier.width(150.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Box(
      Modifier.fillMaxWidth().height(86.dp).background(Brush.linearGradient(templateColors(t.id))),
      contentAlignment = Alignment.Center,
    ) {
      Icon(templateIcon(t.id), contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
      if (t.slots > 0) {
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Badge(if (t.slots == 1) "1 media" else "${t.slots} media") }
      }
    }
    Column(Modifier.padding(10.dp)) {
      Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
      Text(
        t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3, overflow = TextOverflow.Ellipsis, minLines = 3,
      )
    }
  }
}

@Composable
private fun ProjectCard(
  p: ProjectSummary,
  isActive: Boolean,
  onOpen: () -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
  onShare: () -> Unit,
) {
  var menu by remember { mutableStateOf(false) }
  val poster = rememberImageFile(p.poster, p.updatedAt)
  Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
    Box(
      Modifier.fillMaxWidth().aspectRatio(0.62f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF242833), Color(0xFF30343F)))),
    ) {
      if (poster != null) {
        Image(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
      } else {
        Icon(Icons.Default.Wallpaper, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.align(Alignment.Center).size(40.dp))
      }
      if (isActive) Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { Badge("LIVE") }
    }
    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          "${p.scenes} scene${if (p.scenes == 1) "" else "s"} · ${p.layers} layer${if (p.layers == 1) "" else "s"}",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
          DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
          DropdownMenuItem(text = { Text("Share as file") }, onClick = { menu = false; onShare() })
          DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
      }
    }
  }
}
