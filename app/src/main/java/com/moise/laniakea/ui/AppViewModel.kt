package com.moise.laniakea.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moise.laniakea.App
import com.moise.laniakea.data.Settings
import com.moise.laniakea.data.db.PlaylistEntity
import com.moise.laniakea.data.db.SongEntity
import com.moise.laniakea.data.files.FileOps
import com.moise.laniakea.data.library.SmartRules
import com.moise.laniakea.data.playlist.PlaylistFormats
import com.moise.laniakea.lyrics.Lyrics
import com.moise.laniakea.lyrics.LyricsLoader
import com.moise.laniakea.playback.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Library : Screen
    data object NowPlaying : Screen
    data object Queue : Screen
    data object Settings : Screen
    data class Artist(val name: String) : Screen
    data class Album(val key: String) : Screen
    data class Folder(val path: String) : Screen
    data class Playlist(val id: Long) : Screen
    data class TagEditor(val songIds: List<Long>) : Screen
    data class Cutter(val songId: Long) : Screen
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val library = app.library
    val settingsRepo = app.settings
    val settings: StateFlow<Settings> = app.settings.settings
    val player = PlayerController(application, viewModelScope)
    val nowPlaying = player.state

    // ---- Navigation (simple back stack, no extra nav dependency) ------------------------------------
    val stack = mutableStateListOf<Screen>(Screen.Library)
    val current: Screen get() = stack.last()
    fun open(s: Screen) { if (stack.lastOrNull() != s) stack.add(s) }
    fun back(): Boolean { if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }; return false }

    // ---- Multi-select ---------------------------------------------------------------------------------------
    val selected = mutableStateListOf<Long>()
    val selecting get() = selected.isNotEmpty()
    fun toggleSelect(id: Long) { if (!selected.remove(id)) selected.add(id) }
    fun clearSelection() = selected.clear()
    fun selectAll(ids: List<Long>) { selected.clear(); selected.addAll(ids) }
    suspend fun selectedSongs(): List<SongEntity> = library.songsByIds(selected.toList())

    // ---- System approval flow (scoped storage delete/write requests) --------------------------------------
    val approvalRequest = MutableStateFlow<IntentSender?>(null)
    private var afterApproval: (() -> Unit)? = null
    val toast = MutableStateFlow<String?>(null)

    fun needApproval(sender: IntentSender, then: () -> Unit) { afterApproval = then; approvalRequest.value = sender }
    fun approvalFinished(ok: Boolean) {
        approvalRequest.value = null
        val a = afterApproval; afterApproval = null
        if (ok) a?.invoke()
    }

    // ---- Lyrics for the current song ---------------------------------------------------------------------------
    val lyrics = MutableStateFlow<Lyrics?>(null)

    init {
        player.connect()
        app.library.rescan()
        viewModelScope.launch {
            nowPlaying.distinctUntilChangedBy { it.songId }.collect { np ->
                lyrics.value = null
                val id = np.songId ?: return@collect
                library.songsByIds(listOf(id)).firstOrNull()?.let { song ->
                    lyrics.value = LyricsLoader.load(app, song)
                    library.recordPlay(song.id)
                }
            }
        }
    }

    // ---- Actions -----------------------------------------------------------------------------------------------------
    fun play(songs: List<SongEntity>, index: Int = 0) { player.playSongs(songs, index); }
    fun shuffleAll(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        player.playSongs(songs.shuffled(), 0)
    }
    fun toggleFavorite(song: SongEntity) = viewModelScope.launch { library.setFavorite(song.id, !song.favorite) }

    fun deleteSongs(songs: List<SongEntity>) {
        when (val r = FileOps.requestDelete(app, songs)) {
            is FileOps.Result.Done -> viewModelScope.launch { library.removeSongs(songs.map { it.id }); toast.value = r.message }
            is FileOps.Result.NeedsApproval -> needApproval(r.sender) {
                viewModelScope.launch { library.removeSongs(songs.map { it.id }); toast.value = "Deleted ${songs.size}" }
            }
            is FileOps.Result.Failed -> toast.value = r.reason
        }
        clearSelection()
    }

    fun renameSong(song: SongEntity, name: String) {
        when (val r = FileOps.rename(app, song, name)) {
            is FileOps.Result.Done -> app.library.rescan()
            is FileOps.Result.NeedsApproval -> needApproval(r.sender) { renameSong(song, name) }
            is FileOps.Result.Failed -> toast.value = r.reason
        }
    }

    fun moveOrCopy(songs: List<SongEntity>, targetDir: String, copy: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            for (s in songs) {
                when (val r = FileOps.moveOrCopy(app, s, targetDir, copy)) {
                    is FileOps.Result.NeedsApproval -> { withContext(Dispatchers.Main) { needApproval(r.sender) { moveOrCopy(songs, targetDir, copy) } }; return@launch }
                    is FileOps.Result.Failed -> { toast.value = r.reason; return@launch }
                    else -> Unit
                }
            }
            toast.value = if (copy) "Copied ${songs.size}" else "Moved ${songs.size}"
            library.rescan()
        }
        clearSelection()
    }

    // Playlists
    val playlists = library.playlists
    fun createPlaylist(name: String, rules: SmartRules? = null, parent: Long? = null, songs: List<Long> = emptyList()) =
        viewModelScope.launch {
            val id = library.createPlaylist(name, parent, rules)
            if (songs.isNotEmpty()) library.addToPlaylist(id, songs)
        }
    fun addToPlaylist(playlistId: Long, songs: List<Long>) = viewModelScope.launch { library.addToPlaylist(playlistId, songs); clearSelection() }
    fun deletePlaylist(p: PlaylistEntity) = viewModelScope.launch { library.deletePlaylist(p) }

    fun importPlaylist(uri: Uri, fileName: String) = viewModelScope.launch(Dispatchers.IO) {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val text = runCatching { app.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull() ?: return@launch
        val entries = PlaylistFormats.parse(text, ext)
        val ids = entries.mapNotNull { library.matchPath(it.path, null)?.id }.distinct()
        library.createPlaylist(fileName.substringBeforeLast('.')).also { library.addToPlaylist(it, ids) }
        toast.value = "Imported ${ids.size} of ${entries.size} tracks"
    }

    fun exportPlaylist(id: Long, format: String, onText: (String) -> Unit) = viewModelScope.launch {
        val songs = library.resolvePlaylist(id)
        onText(if (format == "pls") PlaylistFormats.exportPls(songs) else PlaylistFormats.exportM3u8(songs))
    }

    override fun onCleared() { player.release() }
}
