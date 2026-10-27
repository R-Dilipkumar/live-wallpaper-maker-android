package io.github.rdilipkumar.livewallpapermaker.ui.editor

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rdilipkumar.livewallpapermaker.data.MediaImporter
import io.github.rdilipkumar.livewallpapermaker.data.PickedMedia
import io.github.rdilipkumar.livewallpapermaker.engine.creator.BakeOptions
import io.github.rdilipkumar.livewallpapermaker.engine.creator.GifStreamDecoder
import io.github.rdilipkumar.livewallpapermaker.engine.creator.LoopBaker
import io.github.rdilipkumar.livewallpapermaker.engine.creator.Remuxer
import io.github.rdilipkumar.livewallpapermaker.engine.model.LoopStyle
import io.github.rdilipkumar.livewallpapermaker.ui.ChipRow
import io.github.rdilipkumar.livewallpapermaker.ui.Hint
import io.github.rdilipkumar.livewallpapermaker.ui.LabeledSlider
import io.github.rdilipkumar.livewallpapermaker.ui.MainViewModel
import io.github.rdilipkumar.livewallpapermaker.ui.PendingClip
import io.github.rdilipkumar.livewallpapermaker.ui.SectionTitle
import io.github.rdilipkumar.livewallpapermaker.ui.SwitchRow
import io.github.rdilipkumar.livewallpapermaker.ui.rememberImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private fun loopLabel(l: LoopStyle) = when (l) {
  LoopStyle.NONE -> "Plain"
  LoopStyle.CROSSFADE -> "Crossfade"
  LoopStyle.ZOOM_DISSOLVE -> "Zoom dissolve"
  LoopStyle.FADE_BLACK -> "Fade to black"
  LoopStyle.PING_PONG -> "Ping-pong"
}

private fun loopHint(l: LoopStyle) = when (l) {
  LoopStyle.NONE -> "Jumps straight back to the start. Best for clips that already loop."
  LoopStyle.CROSSFADE -> "Blends the end into the start, so you never see the jump. Best for most clips."
  LoopStyle.ZOOM_DISSOLVE -> "A gentle zoom while the end blends into the start."
  LoopStyle.FADE_BLACK -> "Fades out to black and back in."
  LoopStyle.PING_PONG -> "Plays forward, then backward. Smooth for nature and slow-motion clips. Up to 15 s."
}

private fun secs(ms: Long) = "%.1f s".format(ms / 1000f)

/** Trim and loop options for a video or GIF, before it is prepared. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(vm: MainViewModel, pending: PendingClip) {
  val context = LocalContext.current
  val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val media = pending.media
  val prep = pending.reprepare?.prep
  val isGif = media is PickedMedia.Gif || prep?.fromGif == true

  // Source length, file and URI (for re-preparing, the stored source copy).
  var durationMs by remember { mutableLongStateOf(0L) }
  var previewUri by remember { mutableStateOf<Uri?>(null) }
  LaunchedEffect(pending) {
    if (media != null) {
      durationMs = when (media) {
        is PickedMedia.Video -> media.durationMs
        is PickedMedia.Gif -> media.durationMs
        else -> 0
      }
      previewUri = if (media is PickedMedia.Video) media.uri else null
    } else if (prep?.sourceFile != null) {
      val f = File(vm.repo.storage.assetsDir, prep.sourceFile!!)
      durationMs = withContext(Dispatchers.IO) {
        runCatching {
          if (prep.fromGif) GifStreamDecoder(f.readBytes()).scanDelays().sum().toLong()
          else Remuxer.probe(context, Uri.fromFile(f)).durationMs
        }.getOrDefault(0L)
      }
      previewUri = if (!prep.fromGif) Uri.fromFile(f) else null
    }
  }

  var start by remember { mutableFloatStateOf(prep?.trimStartMs?.toFloat() ?: 0f) }
  var end by remember { mutableFloatStateOf(prep?.trimEndMs?.takeIf { it > 0 }?.toFloat() ?: 0f) }
  LaunchedEffect(durationMs) {
    if (durationMs > 0) {
      if (end <= 0f || end > durationMs) end = minOf(durationMs, if (isGif) durationMs else 10_000L).toFloat()
      if (start >= end) start = 0f
    }
  }
  var loop by remember { mutableStateOf(prep?.loop ?: if (isGif) LoopStyle.NONE else LoopStyle.CROSSFADE) }
  var window by remember { mutableIntStateOf(prep?.loopWindowMs ?: 600) }
  var quality by remember { mutableIntStateOf(1080) }
  var smooth by remember { mutableStateOf(prep?.smoothGif ?: false) }
  val gif = media as? PickedMedia.Gif
  var asSticker by remember { mutableStateOf(gif?.transparent == true) }
  val motion = (media as? PickedMedia.Video)?.stillUri != null

  ModalBottomSheet(onDismissRequest = { vm.pendingClip.value = null }, sheetState = sheet) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
    ) {
      Text(
        when {
          pending.reprepare != null -> "Trim & loop"
          motion -> "Add motion photo"
          isGif -> "Add GIF"
          else -> "Add video"
        },
        style = MaterialTheme.typography.titleLarge,
      )
      Text(media?.label ?: pending.reprepare?.label ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(10.dp))

      val uri = previewUri
      if (uri != null) {
        var videoView by remember { mutableStateOf<VideoView?>(null) }
        AndroidView(
          factory = { ctx ->
            VideoView(ctx).apply {
              setOnPreparedListener { mp ->
                mp.setVolume(0f, 0f)
                mp.isLooping = true
                start()
              }
              setOnErrorListener { _, _, _ -> true }
              setVideoURI(uri)
              videoView = this
            }
          },
          onRelease = { it.stopPlayback() },
          modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
        )
        // Keep the preview playing inside the trim range.
        LaunchedEffect(videoView) {
          val v = videoView ?: return@LaunchedEffect
          while (true) {
            delay(150)
            runCatching { if (v.isPlaying && (v.currentPosition > end || v.currentPosition < start - 300)) v.seekTo(start.toInt()) }
          }
        }
        LaunchedEffect(start) { runCatching { videoView?.seekTo(start.toInt()) } }
      } else if (pending.reprepare != null) {
        val thumb = rememberImageFile(vm.importer.thumbFile(pending.reprepare.id))
        if (thumb != null) Box(Modifier.fillMaxWidth()) {
          Image(thumb, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(160.dp))
        }
      } else if (media is PickedMedia.Gif) {
        Hint("${media.width}×${media.height} · ${media.frames} frames · ${secs(media.durationMs)}")
      }

      if (motion) {
        Hint("This photo has a short video inside. It becomes a looping live layer.")
        OutlinedButton(onClick = { vm.useStill(pending) }, modifier = Modifier.fillMaxWidth()) { Text("Use the still photo instead") }
      }
      if (gif != null && gif.transparent) {
        SwitchRow(
          "Keep transparency (animated sticker)", asSticker, { asSticker = it },
          "Floats over other layers. Turn off to make it a full-screen clip with a black background.",
        )
      }
      if (durationMs > 0 && !asSticker) {
        SectionTitle("Trim")
        val maxLen = if (loop == LoopStyle.PING_PONG) LoopBaker.PING_PONG_MAX_MS else MediaImporter.MAX_CLIP_MS
        RangeSlider(
          value = start..end,
          onValueChange = { r ->
            var s = r.start
            var e = r.endInclusive
            if (e - s > maxLen) {
              if (s != start) e = s + maxLen else s = e - maxLen
            }
            if (e - s < 300) e = minOf(durationMs.toFloat(), s + 300)
            start = s.coerceAtLeast(0f)
            end = e.coerceAtMost(durationMs.toFloat())
          },
          valueRange = 0f..durationMs.toFloat(),
        )
        Text("${secs(start.toLong())} – ${secs(end.toLong())}  (${secs((end - start).toLong())} of ${secs(durationMs)})", style = MaterialTheme.typography.bodySmall)
        Hint("Shorter clips use less storage. Up to ${maxLen / 1000} s.")
      }

      if (!asSticker) {
      SectionTitle("Loop")
      ChipRow(LoopStyle.entries, loop, ::loopLabel, { loop = it })
      Hint(loopHint(loop))
      if (loop == LoopStyle.CROSSFADE || loop == LoopStyle.ZOOM_DISSOLVE || loop == LoopStyle.FADE_BLACK) {
        LabeledSlider("Blend length", window.toFloat(), 200f..2000f, { window = it.toInt() }, format = { "%.1f s".format(it / 1000f) })
      }
      if (isGif) {
        SwitchRow("Smooth between frames", smooth, { smooth = it }, "Softer motion for choppy GIFs (adds slight ghosting)")
      }
      SectionTitle("Quality")
      ChipRow(listOf(720, 1080), quality, { if (it == 720) "720p · smaller" else "1080p · sharper" }, { quality = it })
      }

      Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { vm.pendingClip.value = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(
          onClick = {
            if (asSticker) {
              vm.startSticker(pending)
              return@Button
            }
            vm.startClip(
              pending,
              BakeOptions(
                trimStartMs = start.toLong(),
                trimEndMs = end.toLong(),
                loop = loop,
                loopWindowMs = window,
                maxShortSide = quality,
                smoothGif = smooth,
              ),
            )
          },
          enabled = durationMs > 0,
          modifier = Modifier.weight(1f),
        ) { Text("Prepare") }
      }
    }
  }
}
