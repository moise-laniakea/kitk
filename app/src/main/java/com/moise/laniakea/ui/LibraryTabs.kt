package com.moise.laniakea.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.moise.laniakea.data.FolderMode
import com.moise.laniakea.data.db.SongEntity
import com.moise.laniakea.data.library.FolderNode
import com.moise.laniakea.data.library.SmartRules
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------------
// Songs
// ---------------------------------------------------------------------------------------------------

@Composable
fun SongList(vm: AppViewModel, songs: List<SongEntity>, query: String = "") {
    val settings by vm.settings.collectAsState()
    val list = remember(songs, query) {
        if (query.isBlank()) songs else songs.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
    }
    val selecting = vm.selecting
    if (settings.gridView) {
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { song ->
                SongCard(
                    song, song.id in vm.selected, settings.useFolderArt,
                    onClick = { if (selecting) vm.toggleSelect(song.id) else vm.play(list, list.indexOf(song)) },
                    onLongClick = { vm.toggleSelect(song.id) },
                )
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song, song.id in vm.selected, selecting, settings.useFolderArt,
                    onClick = { if (selecting) vm.toggleSelect(song.id) else vm.play(list, index) },
                    onLongClick = { vm.toggleSelect(song.id) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Artists and albums
// ---------------------------------------------------------------------------------------------------

@Composable
fun ArtistList(vm: AppViewModel, query: String) {
    val artists by vm.library.artists.collectAsState()
    val list = artists.filter { query.isBlank() || it.name.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.name }) { a ->
            SimpleRow(a.name, "${a.albumCount} albums · ${a.songCount} songs") { vm.open(Screen.Artist(a.name)) }
        }
    }
}

@Composable
fun AlbumGrid(vm: AppViewModel, query: String) {
    val albums by vm.library.albums.collectAsState()
    val settings by vm.settings.collectAsState()
    val list = albums.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
    if (settings.gridView) {
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize()) {
            items(list, key = { it.key }) { album ->
                Card(Modifier.padding(6.dp).clickableNoRipple { vm.open(Screen.Album(album.key)) }) {
                    Artwork(album.songs.first(), Modifier.fillMaxWidth().aspectRatioSquare(), 320, settings.useFolderArt)
                    Column(Modifier.padding(8.dp)) {
                        Text(album.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(album.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.key }) { album ->
                SimpleRow(album.title, "${album.artist} · ${album.songs.size} songs", leading = {
                    Artwork(album.songs.first(), Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), 96, settings.useFolderArt)
                }) { vm.open(Screen.Album(album.key)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Folders: linear (flat) and hierarchical browsers, with whitelist / exclusion
// ---------------------------------------------------------------------------------------------------

@Composable
fun FolderTab(vm: AppViewModel, query: String) {
    val settings by vm.settings.collectAsState()
    when (settings.folderMode) {
        FolderMode.LINEAR -> LinearFolders(vm, query)
        FolderMode.HIERARCHICAL -> HierarchicalFolders(vm, query)
    }
}

@Composable
private fun FolderActions(vm: AppViewModel, path: String, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val whitelisted = path in settings.folderWhitelist
    DropdownMenu(true, onDismiss) {
        DropdownMenuItem(
            text = { Text(if (whitelisted) "Remove from whitelist" else "Whitelist only this folder") },
            leadingIcon = { Icon(Icons.Default.Visibility, null) },
            onClick = {
                vm.settingsRepo.update { it.whitelist(if (whitelisted) settings.folderWhitelist - path else settings.folderWhitelist + path) }
                vm.library.rescan(); onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Exclude from library") },
            leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
            onClick = {
                vm.settingsRepo.update { it.exclusions(settings.folderExclusions + path) }
                vm.library.rescan(); onDismiss()
            },
        )
    }
}

@Composable
private fun LinearFolders(vm: AppViewModel, query: String) {
    val folders by vm.library.folders.collectAsState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(folders.filter { query.isBlank() || it.name.contains(query, true) }, key = { it.path }) { f ->
            Box {
                SimpleRow(f.name, "${f.path} · ${f.songCount} songs", leading = { Icon(Icons.Default.Folder, null) }) {
                    vm.open(Screen.Folder(f.path))
                }
                Box(Modifier.align(Alignment.CenterEnd)) {
                    IconButton({ menuFor = f.path }) { Icon(Icons.Default.Visibility, "Folder options") }
                    if (menuFor == f.path) FolderActions(vm, f.path) { menuFor = null }
                }
            }
        }
    }
}

@Composable
private fun HierarchicalFolders(vm: AppViewModel, query: String) {
    val root by vm.library.folderTree.collectAsState()
    var currentPath by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun find(node: FolderNode, path: String?): FolderNode? = when {
        path == null || node.path == path -> node
        else -> node.children.firstNotNullOfOrNull { c -> if (path.startsWith(c.path)) find(c, path) else null }
    }
    val node = find(root, currentPath) ?: root
    val settings by vm.settings.collectAsState()

    Column(Modifier.fillMaxSize()) {
        if (node.path != root.path) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ currentPath = node.path.substringBeforeLast('/').takeIf { it.length > root.path.length } ?: root.path.takeIf { false } }) {
                    Icon(Icons.Default.ArrowBack, "Up")
                }
                Text(node.path, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(node.children.filter { query.isBlank() || it.name.contains(query, true) }, key = { it.path }) { child ->
                Box {
                    SimpleRow(child.name, "${child.totalSongs} songs", leading = { Icon(Icons.Default.Folder, null) }) { currentPath = child.path }
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        IconButton({ menuFor = child.path }) { Icon(Icons.Default.Visibility, "Folder options") }
                        if (menuFor == child.path) FolderActions(vm, child.path) { menuFor = null }
                    }
                }
            }
            val songs = node.songs.filter { query.isBlank() || it.title.contains(query, true) }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                SongRow(
                    song, song.id in vm.selected, vm.selecting, settings.useFolderArt,
                    onClick = { if (vm.selecting) vm.toggleSelect(song.id) else vm.play(songs, i) },
                    onLongClick = { vm.toggleSelect(song.id) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Playlists
// ---------------------------------------------------------------------------------------------------

@Composable
fun PlaylistTab(vm: AppViewModel, onImport: () -> Unit) {
    val playlists by vm.playlists.collectAsState(emptyList())
    var creating by remember { mutableStateOf(false) }
    var smart by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ smart = false; creating = true }) { Text("New") }
            FilledTonalButton({ smart = true; creating = true }) { Text("Smart") }
            FilledTonalButton(onImport) { Text("Import") }
        }
        LazyColumn(Modifier.weight(1f)) {
            item { SimpleRow("Favorites", "Songs you hearted", { Icon(Icons.Default.Favorite, null) }) { vm.open(Screen.Playlist(PlaylistIds.FAVORITES)) } }
            item { SimpleRow("Most played", "Automatic · top 50", { Icon(Icons.Default.AutoAwesome, null) }) { vm.open(Screen.Playlist(PlaylistIds.MOST_PLAYED)) } }
            item { SimpleRow("Recently added", "Automatic · last 30 days", { Icon(Icons.Default.History, null) }) { vm.open(Screen.Playlist(PlaylistIds.RECENT)) } }
            // Nested playlists: roots first, children indented under their parent.
            val byParent = playlists.groupBy { it.parentId }
            fun flatten(parent: Long?, depth: Int): List<Pair<com.moise.laniakea.data.db.PlaylistEntity, Int>> =
                byParent[parent].orEmpty().flatMap { listOf(it to depth) + flatten(it.id, depth + 1) }
            items(flatten(null, 0), key = { it.first.id }) { (p, depth) ->
                SimpleRow(
                    (if (depth > 0) "↳ " else "") + p.name,
                    if (p.isSmart) "Smart playlist" else "Playlist",
                    leading = {
                        Box(Modifier.padding(start = (depth * 16).dp)) { Icon(if (p.isSmart) Icons.Default.AutoAwesome else Icons.Default.PlaylistPlay, null) }
                    },
                ) { vm.open(Screen.Playlist(p.id)) }
            }
        }
    }

    if (creating) NewPlaylistDialog(smart, onDismiss = { creating = false }) { name, rules ->
        vm.createPlaylist(name, rules); creating = false
    }
}

/** Reserved ids for built-in automatic playlists (never collide with Room auto-ids, which are positive). */
object PlaylistIds {
    const val FAVORITES = -1L
    const val MOST_PLAYED = -2L
    const val RECENT = -3L
}

@Composable
private fun NewPlaylistDialog(smart: Boolean, onDismiss: () -> Unit, onCreate: (String, SmartRules?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var minPlays by remember { mutableStateOf("1") }
    var limit by remember { mutableStateOf("50") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (smart) "New smart playlist" else "New playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                if (smart) {
                    OutlinedTextField(artist, { artist = it }, label = { Text("Artist contains") }, singleLine = true)
                    OutlinedTextField(genre, { genre = it }, label = { Text("Genre contains") }, singleLine = true)
                    OutlinedTextField(minPlays, { minPlays = it.filter(Char::isDigit) }, label = { Text("Minimum plays") }, singleLine = true)
                    OutlinedTextField(limit, { limit = it.filter(Char::isDigit) }, label = { Text("Max songs") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onCreate(
                    name.trim(),
                    if (smart) SmartRules(
                        minPlays = minPlays.toIntOrNull() ?: 0, artistContains = artist, genreContains = genre,
                        limit = limit.toIntOrNull() ?: 50,
                    ) else null,
                )
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
