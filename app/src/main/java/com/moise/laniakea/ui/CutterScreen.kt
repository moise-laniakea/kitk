package com.moise.laniakea.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moise.laniakea.data.db.SongEntity
import com.moise.laniakea.tools.AudioCutter
import com.moise.laniakea.tools.RingtoneSetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Audio cutting tool: pick a range, choose one of three export formats, save and optionally set as ringtone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutterScreen(vm: AppViewModel, songId: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var song by remember { mutableStateOf<SongEntity?>(null) }
    var range by remember { mutableStateOf(0f..30f) }
    var format by remember { mutableStateOf(AudioCutter.Format.M4A) }
    var kind by remember { mutableStateOf(RingtoneSetter.Kind.RINGTONE) }
    var fadeMs by remember { mutableStateOf(500) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lastFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(songId) {
        song = vm.library.songsByIds(listOf(songId)).firstOrNull()
        song?.let { range = 0f..minOf(30f, it.durationMs / 1000f) }
    }
    val durationSec = ((song?.durationMs ?: 0L) / 1000f).coerceAtLeast(1f)

    fun export(thenSet: Boolean) {
        val s = song ?: return
        scope.launch {
            busy = true; status = "Cutting…"
            runCatching {
                withContext(Dispatchers.IO) {
                    val pcm = AudioCutter.decode(context, Uri.parse(s.uri), (range.start * 1000).toLong(), (range.endInclusive * 1000).toLong())
                    AudioCutter.applyFades(pcm, fadeMs, fadeMs)
                    val dir = File(context.cacheDir, "cuts").apply { mkdirs() }
                    val out = File(dir, "${s.title.take(40).replace(Regex("[^A-Za-z0-9 _-]"), "")}_cut.${format.extension}")
                    AudioCutter.encode(pcm, format, out)
                    out
                }
            }.onSuccess { file ->
                lastFile = file
                withContext(Dispatchers.IO) {
                    if (thenSet) {
                        if (!RingtoneSetter.canWriteSettings(context)) {
                            context.startActivity(RingtoneSetter.requestWriteSettingsIntent(context))
                            status = "Allow “Modify system settings”, then tap again."
                            return@withContext
                        }
                        val ok = RingtoneSetter.setAsDefault(context, file, "${s.title} (cut)", format.mime, kind)
                        status = if (ok) "Set as ${kind.name.lowercase()}" else "Could not set ringtone"
                    } else {
                        val uri = RingtoneSetter.publish(context, file, "${s.title} (cut)", format.mime, kind)
                        status = if (uri != null) "Saved to ${kind.folder}" else "Save failed"
                    }
                }
            }.onFailure { status = "Failed: ${it.message}" }
            busy = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Cut audio") }, navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(song?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text("${formatTime((range.start * 1000).toLong())} – ${formatTime((range.endInclusive * 1000).toLong())}  (${"%.1f".format(range.endInclusive - range.start)} s)")
            RangeSlider(range, { range = it }, valueRange = 0f..durationSec)

            Text("Format", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioCutter.Format.values().filter { AudioCutter.isSupported(it) }.forEach {
                    FilterChip(format == it, { format = it }, label = { Text(it.extension.uppercase()) })
                }
            }
            Text("Use as", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RingtoneSetter.Kind.values().forEach { FilterChip(kind == it, { kind = it }, label = { Text(it.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
            }
            Text("Edge fade: $fadeMs ms", style = MaterialTheme.typography.labelLarge)
            androidx.compose.material3.Slider(fadeMs.toFloat(), { fadeMs = it.toInt() }, valueRange = 0f..3000f)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ export(false) }, enabled = !busy && song != null) { Text("Save clip") }
                Button({ export(true) }, enabled = !busy && song != null) { Text("Set as ${kind.name.lowercase()}") }
            }
            if (status.isNotEmpty()) Text(status)
        }
    }
}
