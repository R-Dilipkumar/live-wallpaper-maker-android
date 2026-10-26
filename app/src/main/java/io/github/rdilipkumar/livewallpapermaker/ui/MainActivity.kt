package io.github.rdilipkumar.livewallpapermaker.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rdilipkumar.livewallpapermaker.ui.editor.EditorScreen

class MainActivity : ComponentActivity() {
  private val vm: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handleIntent(intent)
    setContent {
      MaterialTheme(colorScheme = LwmColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          val screen by vm.screen.collectAsStateWithLifecycle()
          when (val s = screen) {
            Screen.Home -> HomeScreen(vm)
            is Screen.Editor -> EditorScreen(vm)
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    vm.refresh()
  }

  /** A shared .lwm project opened from another app. */
  private fun handleIntent(intent: Intent?) {
    if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { vm.importProject(it) }
  }
}
