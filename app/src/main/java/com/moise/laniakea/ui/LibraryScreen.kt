package com.moise.laniakea.ui

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moise.laniakea.data.SortMode
import kotlinx.coroutines.launch

private enum class LibTab(val label: String) { SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums"), FOLDERS("Folders"), PLAYLISTS("Playlists") }

private fun LibTab.icon() = when (this) {
    LibTab.SONGS -> Icons.Default.MusicNote
    LibTab.ARTISTS -> Icons.Default.Person
    LibTab.ALBUMS -> Icons.Default.Album
    LibTab.FOLDERS -> Icons.Default.Folder
    LibTab.PLAYLISTS -> Icons.Default.PlaylistPlay
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: AppViewModel, onImportPlaylist: () -> Unit) {
    val songs by vm.library.songs.collectAsState()
    val settings by vm.settings.collectAsState()
    val np by vm.nowPlaying.collectAsState()
    val scanning by vm.library.scanning.collectAsState()
    val tabs = LibTab.values()
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        topBar = {
            if (vm.selecting) SelectionBar(vm, songs) else TopAppBar(
                title = {
                    if (searching) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                    else Text("Moïse Laniākea", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                },
                actions = {
                    IconButton({ searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    Box {
                        IconButton({ sortMenu = true }) { Icon(Icons.Default.Sort, "Sort and view") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Shuffle all") },
                                leadingIcon = { Icon(Icons.Default.Shuffle, null) },
                                onClick = { vm.shuffleAll(songs); sortMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(if (settings.gridView) "Show as list" else "Show as grid") },
                                leadingIcon = { Icon(if (settings.gridView) Icons.Default.ViewList else Icons.Default.GridView, null) },
                                onClick = { vm.settingsRepo.update { it.grid(!settings.gridView) }; sortMenu = false },
                            )
                            androidx.compose.material3.HorizontalDivider()
                            SortMode.values().forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } + if (settings.sortMode == m) (if (settings.sortDescending) " ▼" else " ▲") else "") },
                                    onClick = {
                                        val desc = settings.sortMode == m && !settings.sortDescending
                                        vm.settingsRepo.update { it.sort(m, desc) }; sortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton({ vm.open(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            Column {
                MiniPlayer(vm)
                // Portrait uses the tab row at the top of the content; landscape uses a side rail instead.
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (landscape) {
                NavigationRail {
                    tabs.forEachIndexed { i, t ->
                        NavigationRailItem(
                            selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            icon = { Icon(t.icon(), t.label) }, label = { Text(t.label) },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!landscape) {
                    ScrollableTabRow(pager.currentPage, edgePadding = 0.dp) {
                        tabs.forEachIndexed { i, t ->
                            Tab(pager.currentPage == i, { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(t.label) })
                        }
                    }
                }
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    when (tabs[page]) {
                        LibTab.SONGS -> if (songs.isEmpty() && !scanning) EmptyLibrary(vm) else SongList(vm, songs, query)
                        LibTab.ARTISTS -> ArtistList(vm, query)
                        LibTab.ALBUMS -> AlbumGrid(vm, query)
                        LibTab.FOLDERS -> FolderTab(vm, query)
                        LibTab.PLAYLISTS -> PlaylistTab(vm, onImportPlaylist)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(vm: AppViewModel) {
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("No music found", style = MaterialTheme.typography.titleMedium)
        Text("Grant audio access, then rescan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ vm.library.rescan() }) { Text("Rescan") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(vm: AppViewModel, all: List<com.moise.laniakea.data.db.SongEntity>) {
    val scope = rememberCoroutineScope()
    var playlistDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf<Boolean?>(null) }   // null = hidden, true = copy, false = move
    val playlists by vm.playlists.collectAsState(emptyList())

    TopAppBar(
        title = { Text("${vm.selected.size} selected") },
        navigationIcon = { IconButton({ vm.clearSelection() }) { Icon(Icons.Default.Close, "Clear") } },
        actions = {
            IconButton({ vm.selectAll(all.map { it.id }) }) { Icon(Icons.Default.SelectAll, "Select all") }
            IconButton({ scope.launch { vm.player.playSongs(vm.selectedSongs()); vm.clearSelection() } }) { Icon(Icons.Default.PlayArrow, "Play") }
            IconButton({ scope.launch { vm.player.addToQueue(vm.selectedSongs()); vm.clearSelection() } }) { Icon(Icons.Default.QueueMusic, "Add to queue") }
            IconButton({ playlistDialog = true }) { Icon(Icons.Default.PlaylistAdd, "Add to playlist") }
            IconButton({ vm.open(Screen.TagEditor(vm.selected.toList())) }) { Icon(Icons.Default.Edit, "Edit tags") }
            var more by remember { mutableStateOf(false) }
            Box {
                IconButton({ more = true }) { Icon(Icons.Default.Favorite, "More") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem({ Text("Favorite") }, { scope.launch { vm.selectedSongs().forEach { vm.library.setFavorite(it.id, true) }; vm.clearSelection() }; more = false })
                    DropdownMenuItem({ Text("Move to…") }, { moveDialog = false; more = false })
                    DropdownMenuItem({ Text("Copy to…") }, { moveDialog = true; more = false })
                    DropdownMenuItem({ Text("Delete") }, { scope.launch { vm.deleteSongs(vm.selectedSongs()) }; more = false })
                }
            }
        },
    )

    if (playlistDialog) AlertDialog(
        onDismissRequest = { playlistDialog = false },
        title = { Text("Add to playlist") },
        text = {
            Column {
                playlists.filter { !it.isSmart }.forEach { p ->
                    Text(p.name, Modifier.fillMaxWidth().clickable { vm.addToPlaylist(p.id, vm.selected.toList()); playlistDialog = false }.padding(12.dp))
                }
                if (playlists.none { !it.isSmart }) Text("No playlists yet — create one in the Playlists tab.")
            }
        },
        confirmButton = { TextButton({ playlistDialog = false }) { Text("Close") } },
    )

    moveDialog?.let { copy ->
        var dir by remember { mutableStateOf("Music/") }
        AlertDialog(
            onDismissRequest = { moveDialog = null },
            title = { Text(if (copy) "Copy to folder" else "Move to folder") },
            text = { OutlinedTextField(dir, { dir = it }, label = { Text("Folder (relative to storage root)") }, singleLine = true) },
            confirmButton = {
                TextButton({ scope.launch { vm.moveOrCopy(vm.selectedSongs(), dir, copy) }; moveDialog = null }) { Text("OK") }
            },
            dismissButton = { TextButton({ moveDialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun MiniPlayer(vm: AppViewModel) {
    val np by vm.nowPlaying.collectAsState()
    val settings by vm.settings.collectAsState()
    if (np.songId == null) return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable { vm.open(Screen.NowPlaying) }) {
        Column {
            LinearProgressIndicator({ if (np.durationMs > 0) np.positionMs.toFloat() / np.durationMs else 0f }, Modifier.fillMaxWidth())
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val song by produceCurrentSong(vm, np.songId)
                Artwork(song, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), 96, settings.useFolderArt)
                Column(Modifier.weight(1f)) {
                    Text(np.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(np.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ vm.player.togglePlay() }) { Icon(if (np.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause") }
                IconButton({ vm.player.next() }) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}
