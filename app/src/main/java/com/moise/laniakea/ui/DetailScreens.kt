package com.moise.laniakea.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.moise.laniakea.data.db.SongEntity
import com.moise.laniakea.data.library.AlbumItem
import com.moise.laniakea.data.library.SmartRules
import com.moise.laniakea.data.library.evaluate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    subtitle: String? = null,
    vm: AppViewModel,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(title, maxLines = 1); if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall) } },
                navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { actions() },
            )
        },
        bottomBar = { MiniPlayer(vm) },
    ) { padding -> Column(Modifier.padding(padding).fillMaxSize()) { content() } }
}

@Composable
private fun PlayShuffleRow(vm: AppViewModel, songs: List<SongEntity>) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ vm.play(songs) }, enabled = songs.isNotEmpty()) { Icon(Icons.Default.PlayArrow, null); Text("Play") }
        FilledTonalButton({ vm.shuffleAll(songs) }, enabled = songs.isNotEmpty()) { Icon(Icons.Default.Shuffle, null); Text("Shuffle") }
    }
}

// ---- Album ---------------------------------------------------------------------------------------------

@Composable
fun AlbumScreen(vm: AppViewModel, key: String) {
    val albums by vm.library.albums.collectAsState()
    val album = albums.firstOrNull { it.key == key }
    DetailScaffold(album?.title ?: "Album", album?.artist, vm) {
        if (album != null) {
            PlayShuffleRow(vm, album.songs)
            SongList(vm, album.songs)
        }
    }
}

// ---- Folder (from the linear list) --------------------------------------------------------------------------

@Composable
fun FolderScreen(vm: AppViewModel, path: String) {
    val songs by vm.library.songs.collectAsState()
    val inFolder = remember(songs, path) { songs.filter { it.folder == path } }
    DetailScaffold(path.substringAfterLast('/'), path, vm) {
        PlayShuffleRow(vm, inFolder)
        SongList(vm, inFolder)
    }
}

// ---- Artist hub: Albums / Singles / Appears In / Songs ----------------------------------------------------------

@Composable
fun ArtistScreen(vm: AppViewModel, name: String) {
    val songs by vm.library.songs.collectAsState()
    val settings by vm.settings.collectAsState()
    val hub = remember(songs, name, settings.artistDelimiters) { vm.library.artistHub(name, settings) }
    val sections = listOf("Albums" to hub.albums.size, "Singles" to hub.singles.size, "Appears In" to hub.appearsIn.size, "Songs" to hub.songs.size)
    var tab by remember { mutableStateOf(0) }

    DetailScaffold(name, "${hub.songs.size} songs", vm) {
        PlayShuffleRow(vm, hub.songs)
        TabRow(tab) {
            sections.forEachIndexed { i, (label, n) -> Tab(tab == i, { tab = i }, text = { Text("$label ($n)") }) }
        }
        when (tab) {
            0 -> AlbumRows(vm, hub.albums)
            1 -> AlbumRows(vm, hub.singles)
            2 -> AlbumRows(vm, hub.appearsIn)
            else -> SongList(vm, hub.songs)
        }
    }
}

@Composable
private fun AlbumRows(vm: AppViewModel, albums: List<AlbumItem>) {
    val settings by vm.settings.collectAsState()
    LazyColumn(Modifier.fillMaxSize()) {
        items(albums, key = { it.key }) { a ->
            SimpleRow(a.title, listOfNotNull(a.artist, a.year.takeIf { it > 0 }?.toString(), "${a.songs.size} songs").joinToString(" · "), leading = {
                Artwork(a.songs.first(), Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)), 96, settings.useFolderArt)
            }) { vm.open(Screen.Album(a.key)) }
        }
    }
}

// ---- Playlist (manual, smart, nested, or built-in) -----------------------------------------------------------------

@Composable
fun PlaylistScreen(vm: AppViewModel, id: Long) {
    val songs by vm.library.songs.collectAsState()
    val favorites by vm.library.favorites.collectAsState()
    val playlists by vm.playlists.collectAsState(emptyList())
    val playlist = playlists.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    val editable = id > 0 && playlist?.isSmart == false

    LaunchedEffect(id, songs, favorites, reload, playlist) {
        items = when (id) {
            PlaylistIds.FAVORITES -> favorites
            PlaylistIds.MOST_PLAYED -> SmartRules.MostPlayed.evaluate(songs)
            PlaylistIds.RECENT -> SmartRules.RecentlyAdded.evaluate(songs)
            else -> vm.library.resolvePlaylist(id)
        }
    }

    var exportText by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        vm.getApplication<android.app.Application>().contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray()) }
    }

    val title = when (id) {
        PlaylistIds.FAVORITES -> "Favorites"; PlaylistIds.MOST_PLAYED -> "Most played"; PlaylistIds.RECENT -> "Recently added"
        else -> playlist?.name ?: "Playlist"
    }
    DetailScaffold(title, "${items.size} songs", vm, actions = {
        if (id > 0) {
            IconButton({ vm.exportPlaylist(id, "m3u8") { exportText = it; saver.launch("$title.m3u8") } }) { Icon(Icons.Default.FileDownload, "Export") }
            IconButton({ playlist?.let { vm.deletePlaylist(it); vm.back() } }) { Icon(Icons.Default.Delete, "Delete") }
        }
    }) {
        PlayShuffleRow(vm, items)
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { i, s -> "${s.id}-$i" }) { index, song ->
                SongRow(
                    song, song.id in vm.selected, vm.selecting, true,
                    onClick = { if (vm.selecting) vm.toggleSelect(song.id) else vm.play(items, index) },
                    onLongClick = { vm.toggleSelect(song.id) },
                    trailing = {
                        if (editable) {
                            IconButton({ scope.launch { vm.library.movePlaylistSong(id, index, index - 1); reload++ } }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Up") }
                            IconButton({ scope.launch { vm.library.movePlaylistSong(id, index, index + 1); reload++ } }, enabled = index < items.lastIndex) { Icon(Icons.Default.ArrowDownward, "Down") }
                            IconButton({ scope.launch { vm.library.removeFromPlaylist(id, setOf(index)); reload++ } }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                    },
                )
            }
        }
    }
}
