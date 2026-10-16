package com.moise.laniakea.ui

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.moise.laniakea.data.db.SongEntity
import kotlin.math.roundToInt

/** Speed, pitch, fades and skip-silence. Speed and pitch can be linked (tape-style) or independent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedPitchSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val np by vm.nowPlaying.collectAsState()
    val settings by vm.settings.collectAsState()
    var speed by remember { mutableStateOf(np.speed) }
    var pitch by remember { mutableStateOf(np.pitch) }
    var linked by remember { mutableStateOf(false) }
    var fadeIn by remember { mutableStateOf(settings.fadeInMs.toFloat()) }
    var fadeOut by remember { mutableStateOf(settings.fadeOutMs.toFloat()) }

    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Speed %.2fx".format(speed), style = MaterialTheme.typography.titleMedium)
            Slider(speed, {
                speed = (it * 20).roundToInt() / 20f
                if (linked) pitch = speed
                vm.player.setSpeedPitch(speed, pitch)
            }, valueRange = 0.25f..4f)

            Text("Pitch %.2fx".format(pitch), style = MaterialTheme.typography.titleMedium)
            Slider(pitch, {
                pitch = (it * 20).roundToInt() / 20f
                if (linked) speed = pitch
                vm.player.setSpeedPitch(speed, pitch)
            }, valueRange = 0.5f..2f)

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Link speed and pitch", Modifier.weight(1f)); Switch(linked, { linked = it })
            }
            TextButton({ speed = 1f; pitch = 1f; vm.player.setSpeedPitch(1f, 1f) }) { Text("Reset") }

            Text("Fade in  ${fadeIn.roundToInt()} ms", style = MaterialTheme.typography.titleMedium)
            Slider(fadeIn, { fadeIn = it }, onValueChangeFinished = { vm.player.setFades(fadeIn.roundToInt(), fadeOut.roundToInt()) }, valueRange = 0f..5000f)
            Text("Fade out  ${fadeOut.roundToInt()} ms", style = MaterialTheme.typography.titleMedium)
            Slider(fadeOut, { fadeOut = it }, onValueChangeFinished = { vm.player.setFades(fadeIn.roundToInt(), fadeOut.roundToInt()) }, valueRange = 0f..5000f)

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Skip silence", Modifier.weight(1f))
                Switch(settings.skipSilence, { vm.player.setSkipSilence(it) })
            }
            Box(Modifier.padding(bottom = 24.dp))
        }
    }
}

/** Sleep timer by exact duration (minutes + seconds) or by number of songs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf("30") }
    var seconds by remember { mutableStateOf("0") }
    var songs by remember { mutableStateOf("3") }
    var status by remember { mutableStateOf("") }

    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60).forEach { m ->
                    FilledTonalButton({ vm.player.sleepAfter(m); onDismiss() }) { Text("$m min") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(2) }, label = { Text("Seconds") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Button({ vm.player.sleepAfter(minutes.toIntOrNull() ?: 0, seconds.toIntOrNull() ?: 0); onDismiss() }) { Text("Stop after this duration") }
            OutlinedTextField(songs, { songs = it.filter(Char::isDigit).take(3) }, label = { Text("Songs") }, singleLine = true)
            Button({ vm.player.sleepAfterSongs(songs.toIntOrNull() ?: 1); onDismiss() }) { Text("Stop after this many songs") }
            TextButton({ vm.player.cancelSleep(); status = "Timer cancelled"; onDismiss() }) { Text("Cancel timer") }
            if (status.isNotEmpty()) Text(status)
            Box(Modifier.padding(bottom = 24.dp))
        }
    }
}

/** Pinch to zoom, drag to pan, for the album art viewer. */
@Composable
fun ZoomableArtDialog(song: SongEntity?, useFolderArt: Boolean, onDismiss: () -> Unit) {
    Dialog(onDismiss) {
        var scale by remember { mutableStateOf(1f) }
        var ox by remember { mutableStateOf(0f) }
        var oy by remember { mutableStateOf(0f) }
        Box(
            Modifier.fillMaxSize().pointerInputTransform { zoom, pan ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                if (scale == 1f) { ox = 0f; oy = 0f } else { ox += pan.x; oy += pan.y }
            },
        ) {
            Artwork(
                song, Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy),
                maxSize = 2048, useFolderArt = useFolderArt, contentScale = ContentScale.Fit,
            )
            IconButton(onDismiss, Modifier.padding(8.dp)) { Icon(Icons.Default.Close, "Close") }
        }
    }
}

private fun Modifier.pointerInputTransform(onGesture: (zoom: Float, pan: androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    this.then(Modifier.pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> onGesture(zoom, pan) } })

/** Dynamic playback queue: jump, reorder, remove, clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(vm: AppViewModel) {
    val np by vm.nowPlaying.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Queue (${np.queue.size})") },
            navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = { TextButton({ vm.player.clearQueue(); vm.back() }) { Text("Clear") } },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(np.queue, key = { i, q -> "${q.songId}-$i" }) { index, q ->
                val current = index == np.currentIndex
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    if (current) Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).clickableNoRipple { vm.player.jumpTo(index) }.padding(start = if (current) 8.dp else 32.dp)) {
                        Text(q.title, maxLines = 1, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(q.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ vm.player.moveInQueue(index, index - 1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Up") }
                    IconButton({ vm.player.moveInQueue(index, index + 1) }, enabled = index < np.queue.lastIndex) { Icon(Icons.Default.ArrowDownward, "Down") }
                    IconButton({ vm.player.removeFromQueue(index) }) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
        }
    }
}
