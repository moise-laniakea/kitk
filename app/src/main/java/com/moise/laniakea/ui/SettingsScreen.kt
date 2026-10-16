package com.moise.laniakea.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moise.laniakea.data.FolderMode
import com.moise.laniakea.data.NowPlayingTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    val repo = vm.settingsRepo

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Section("Appearance")
            Toggle("Material You dynamic colors (Monet)", s.dynamicColor) { repo.update { p -> p.dynamicColor(it) } }
            Toggle("Derive accent colors from album art", s.artworkColors) { repo.update { p -> p.artworkColors(it) } }
            Text("Now Playing theme", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NowPlayingTheme.values().forEach { t ->
                    FilterChip(s.nowPlayingTheme == t, { repo.update { p -> p.nowPlayingTheme(t) } }, label = { Text(t.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) })
                }
            }
            Toggle("Use cover.jpg / folder.png from the song's folder", s.useFolderArt) { repo.update { p -> p.folderArt(it) } }

            Section("Library")
            Text("Folder browsing", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FolderMode.values().forEach { m ->
                    FilterChip(s.folderMode == m, { repo.update { p -> p.folderMode(m) } }, label = { Text(m.name.lowercase().replaceFirstChar(Char::uppercase)) })
                }
            }
            OutlinedTextField(
                s.artistDelimiters, { repo.update { p -> p.artistDelimiters(it) } },
                label = { Text("Artist separators (characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                s.genreDelimiters, { repo.update { p -> p.genreDelimiters(it) } },
                label = { Text("Genre separators (characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (s.folderWhitelist.isNotEmpty()) {
                Text("Whitelisted folders (only these are scanned)", style = MaterialTheme.typography.labelLarge)
                s.folderWhitelist.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton({ repo.update { p -> p.whitelist(s.folderWhitelist - f) }; vm.library.rescan() }) { Text("Remove") }
                    }
                }
            }
            if (s.folderExclusions.isNotEmpty()) {
                Text("Excluded folders", style = MaterialTheme.typography.labelLarge)
                s.folderExclusions.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton({ repo.update { p -> p.exclusions(s.folderExclusions - f) }; vm.library.rescan() }) { Text("Remove") }
                    }
                }
            }
            TextButton({ vm.library.rescan() }) { Text("Rescan library") }

            Section("Playback")
            Text("Fade in ${s.fadeInMs} ms", style = MaterialTheme.typography.labelLarge)
            Slider(s.fadeInMs.toFloat(), { repo.update { p -> p.fadeIn(it.roundToInt()) } }, valueRange = 0f..5000f)
            Text("Fade out ${s.fadeOutMs} ms", style = MaterialTheme.typography.labelLarge)
            Slider(s.fadeOutMs.toFloat(), { repo.update { p -> p.fadeOut(it.roundToInt()) } }, valueRange = 0f..5000f)
            Toggle("Skip silence", s.skipSilence) { vm.player.setSkipSilence(it) }
            Text(
                if (s.autoPauseMinutes == 0) "Battery saver: off" else "Battery saver: stop the player after ${s.autoPauseMinutes} min paused",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(s.autoPauseMinutes.toFloat(), { repo.update { p -> p.autoPause(it.roundToInt()) } }, valueRange = 0f..60f, steps = 11)

            Section("About")
            Text("Moïse Laniākea works fully offline. The app has no internet permission.", style = MaterialTheme.typography.bodySmall)
            Text("There is no built-in equalizer. Use the equalizer button on the Now Playing screen to open your system or third-party equalizer.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }
}
