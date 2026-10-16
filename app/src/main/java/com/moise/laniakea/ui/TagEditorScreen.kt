package com.moise.laniakea.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.net.Uri
import com.moise.laniakea.data.db.SongEntity
import com.moise.laniakea.data.files.FileOps
import com.moise.laniakea.data.library.splitMulti
import com.moise.laniakea.data.tags.ArtworkLoader
import com.moise.laniakea.data.tags.TagIO
import com.moise.laniakea.lyrics.LyricsLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tag editor and Tag Editor+ (batch). With several songs selected, every field has an "apply" checkbox so only
 * ticked fields are written; with one song, all fields are written. Artist and genre accept several values
 * split by the configurable delimiters in settings (dual-artist support) and are stored as multi-value tags.
 * Album art can be replaced or removed for the whole batch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagEditorScreen(vm: AppViewModel, songIds: List<Long>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = vm.settings.value
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    val batch = songIds.size > 1

    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var albumArtist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var track by remember { mutableStateOf("") }
    var disc by remember { mutableStateOf("") }
    var composer by remember { mutableStateOf("") }
    var lyricsText by remember { mutableStateOf("") }
    var newCover by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    var removeCover by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var applyTo by remember { mutableStateOf(setOf<TagIO.Field>()) }

    fun mark(f: TagIO.Field) { applyTo = applyTo + f }

    LaunchedEffect(songIds) {
        songs = vm.library.songsByIds(songIds)
        if (songs.size == 1) withContext(Dispatchers.IO) {
            TagIO.read(context, Uri.parse(songs[0].uri))?.let { t ->
                title = t.title; artist = t.artists.joinToString("; "); albumArtist = t.albumArtists.joinToString("; ")
                album = t.album; genre = t.genres.joinToString("; "); year = t.year.takeIf { it > 0 }?.toString().orEmpty()
                track = t.track.takeIf { it > 0 }?.toString().orEmpty(); disc = t.disc.takeIf { it > 0 }?.toString().orEmpty()
                composer = t.composer; lyricsText = t.lyrics
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let {
                newCover = ArtworkLoader.prepareForEmbedding(it); removeCover = false
            }
        }
    }

    fun save() {
        scope.launch {
            saving = true
            // Scoped storage (Android 11+): ask once for write access to the whole selection.
            FileOps.requestWrite(context, songs)?.let { sender ->
                saving = false
                vm.needApproval(sender) { /* user taps Save again with access granted */ }
                status = "Grant write access, then tap Save again."
                return@launch
            }
            val fields = if (batch) applyTo else TagIO.Field.values().toSet()
            val tags = TagIO.Tags(
                title = title,
                artists = splitMulti(artist, settings.artistDelimiters),
                albumArtists = splitMulti(albumArtist, settings.artistDelimiters),
                album = album,
                genres = splitMulti(genre, settings.genreDelimiters),
                year = year.toIntOrNull() ?: 0,
                track = track.toIntOrNull() ?: 0,
                disc = disc.toIntOrNull() ?: 0,
                composer = composer,
                lyrics = lyricsText,
            )
            var ok = 0
            withContext(Dispatchers.IO) {
                for (s in songs) {
                    val uri = Uri.parse(s.uri)
                    var good = TagIO.write(context, uri, tags, fields)
                    newCover?.let { (bytes, mime) -> good = TagIO.writeCover(context, uri, bytes, mime) && good }
                    if (removeCover) good = TagIO.removeCover(context, uri) && good
                    if (good) { ok++; ArtworkLoader.invalidate(s); vm.library.refreshFromFile(s) }
                }
                if (!batch && songs.isNotEmpty() && TagIO.Field.LYRICS in fields && lyricsText.contains(Regex("""\[\d+:\d+"""))) {
                    LyricsLoader.saveSidecarLrc(songs[0], lyricsText)   // keep timestamps in a sidecar .lrc
                }
            }
            saving = false
            status = "Saved $ok of ${songs.size}"
            if (ok == songs.size) vm.back()
        }
    }

    @Composable
    fun field(label: String, value: String, f: TagIO.Field, onChange: (String) -> Unit, numeric: Boolean = false, singleLine: Boolean = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (batch) Checkbox(f in applyTo, { applyTo = if (it) applyTo + f else applyTo - f })
            OutlinedTextField(
                value, { onChange(if (numeric) it.filter(Char::isDigit) else it); mark(f) },
                label = { Text(label) }, singleLine = singleLine, modifier = Modifier.weight(1f),
            )
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (batch) "Edit ${songIds.size} songs" else "Edit tags") },
            navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = { IconButton({ save() }, enabled = !saving) { Icon(Icons.Default.Check, "Save") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (batch) Text("Tick the fields you want to change for every selected song.", style = MaterialTheme.typography.bodySmall)
            if (!batch) songs.firstOrNull()?.let {
                Artwork(it, Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)), 400, settings.useFolderArt)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ picker.launch("image/*") }) { Text("Replace art") }
                FilledTonalButton({ removeCover = true; newCover = null }) { Text("Remove art") }
            }
            if (newCover != null) Text("New cover ready (${newCover!!.first.size / 1024} KB)", style = MaterialTheme.typography.bodySmall)
            if (removeCover) Text("Cover will be removed", style = MaterialTheme.typography.bodySmall)

            if (!batch) field("Title", title, TagIO.Field.TITLE, { title = it })
            field("Artist (separate with ${settings.artistDelimiters.toList().joinToString(" ")})", artist, TagIO.Field.ARTIST, { artist = it })
            field("Album artist", albumArtist, TagIO.Field.ALBUM_ARTIST, { albumArtist = it })
            field("Album", album, TagIO.Field.ALBUM, { album = it })
            field("Genre", genre, TagIO.Field.GENRE, { genre = it })
            field("Year", year, TagIO.Field.YEAR, { year = it }, numeric = true)
            if (!batch) field("Track", track, TagIO.Field.TRACK, { track = it }, numeric = true)
            field("Disc", disc, TagIO.Field.DISC, { disc = it }, numeric = true)
            field("Composer", composer, TagIO.Field.COMPOSER, { composer = it })
            if (!batch) field("Lyrics (plain or LRC)", lyricsText, TagIO.Field.LYRICS, { lyricsText = it }, singleLine = false)

            Button({ save() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Save") }
            if (status.isNotEmpty()) Text(status)
        }
    }
}
