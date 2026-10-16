package com.moise.laniakea.data.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.moise.laniakea.data.Settings
import com.moise.laniakea.data.SettingsRepository
import com.moise.laniakea.data.SortMode
import com.moise.laniakea.data.db.AppDatabase
import com.moise.laniakea.data.db.PlaylistEntity
import com.moise.laniakea.data.db.SongEntity
import com.moise.laniakea.data.tags.TagIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val songDao = db.songs()
    private val playlistDao = db.playlists()

    val scanning = MutableStateFlow(false)
    val scanError = MutableStateFlow<String?>(null)
    /** Human-readable summary of the last scan, shown on the empty-library screen to help diagnose problems. */
    val scanInfo = MutableStateFlow("Not scanned yet")
    val scanProgress = MutableStateFlow(0 to 0)

    // ---- Observable library (whitelist / exclusion filtered, sorted) ---------------------------

    val songs: StateFlow<List<SongEntity>> =
        combine(songDao.observeAll(), settingsRepo.settings) { all, s -> applyFilters(all, s) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val artists: StateFlow<List<ArtistItem>> =
        combine(songs, settingsRepo.settings) { list, s -> buildArtists(list, s) }
            .stateIn(scope, SharingStarted.Lazily, emptyList())

    val albums: StateFlow<List<AlbumItem>> =
        combine(songs, settingsRepo.settings) { list, s -> buildAlbums(list, s) }
            .stateIn(scope, SharingStarted.Lazily, emptyList())

    val folders: StateFlow<List<FolderItem>> = songs.let { flow ->
        combine(flow, settingsRepo.settings) { list, _ ->
            list.groupBy { it.folder }
                .map { (path, items) -> FolderItem(path, path.substringAfterLast('/'), items.size) }
                .sortedWith(compareBy(NaturalComparator) { it.name })
        }.stateIn(scope, SharingStarted.Lazily, emptyList())
    }

    val folderTree: StateFlow<FolderNode> = songs.let { flow ->
        combine(flow, settingsRepo.settings) { list, _ -> buildTree(list) }
            .stateIn(scope, SharingStarted.Lazily, FolderNode("", "/", emptyList(), emptyList(), 0))
    }

    val playlists: Flow<List<PlaylistEntity>> = playlistDao.observePlaylists()

    val favorites: StateFlow<List<SongEntity>> =
        songs.let { f -> combine(f, settingsRepo.settings) { l, _ -> l.filter { it.favorite } } }
            .stateIn(scope, SharingStarted.Lazily, emptyList())

    // ---- Filtering / sorting ---------------------------------------------------------------

    private fun applyFilters(all: List<SongEntity>, s: Settings): List<SongEntity> {
        val white = s.folderWhitelist
        val excl = s.folderExclusions
        val filtered = all.filter { song ->
            (white.isEmpty() || white.any { song.folder.startsWith(it) }) &&
                excl.none { song.folder.startsWith(it) }
        }
        val cmp: Comparator<SongEntity> = when (s.sortMode) {
            SortMode.NATURAL -> compareBy(NaturalComparator) { it.title }
            SortMode.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            SortMode.ARTIST -> compareBy<SongEntity, String>(NaturalComparator) { it.artist }.thenBy(NaturalComparator) { it.title }
            SortMode.ALBUM -> compareBy<SongEntity, String>(NaturalComparator) { it.album }
                .thenBy { it.discNumber }.thenBy { it.trackNumber }
            SortMode.DATE_ADDED -> compareBy { it.dateAdded }
            SortMode.DURATION -> compareBy { it.durationMs }
            SortMode.PLAY_COUNT -> compareBy { it.playCount }
            SortMode.CUSTOM -> compareBy { it.id }
        }
        return filtered.sortedWith(if (s.sortDescending) cmp.reversed() else cmp)
    }

    private fun buildArtists(list: List<SongEntity>, s: Settings): List<ArtistItem> {
        val bySong = HashMap<String, MutableList<SongEntity>>()
        list.forEach { song ->
            splitMulti(song.artist.ifBlank { song.albumArtist }, s.artistDelimiters).ifEmpty { listOf("Unknown artist") }
                .forEach { bySong.getOrPut(it) { ArrayList() }.add(song) }
        }
        return bySong.map { (name, songs) ->
            ArtistItem(name, songs.size, songs.map { it.album }.distinct().size)
        }.sortedWith(compareBy(NaturalComparator) { it.name })
    }

    private fun buildAlbums(list: List<SongEntity>, s: Settings): List<AlbumItem> =
        list.groupBy { albumKey(it) }.map { (key, songs) ->
            val first = songs.first()
            AlbumItem(
                key = key,
                title = first.album.ifBlank { "Unknown album" },
                artist = first.albumArtist.ifBlank { splitMulti(first.artist, s.artistDelimiters).firstOrNull() ?: "Unknown artist" },
                year = songs.maxOf { it.year },
                songs = songs.sortedWith(compareBy({ it.discNumber }, { it.trackNumber })),
            )
        }.sortedWith(compareBy(NaturalComparator) { it.title })

    private fun albumKey(song: SongEntity) = "${song.albumArtist.ifBlank { song.artist }}\u0000${song.album}"

    private fun buildTree(list: List<SongEntity>): FolderNode {
        class Builder(val path: String, val name: String) {
            val kids = sortedMapOf<String, Builder>()
            val songs = ArrayList<SongEntity>()
        }
        val root = Builder("", "/")
        list.forEach { song ->
            var node = root
            var acc = ""
            song.folder.split('/').filter { it.isNotEmpty() }.forEach { part ->
                acc += "/$part"
                node = node.kids.getOrPut(part) { Builder(acc, part) }
            }
            node.songs.add(song)
        }
        fun Builder.build(): FolderNode {
            val ch = kids.values.map { it.build() }
            return FolderNode(path, name, ch, songs, songs.size + ch.sumOf { it.totalSongs })
        }
        // Collapse single-child chains from the root so users don't start inside /storage/emulated/0.
        var top = root.build()
        while (top.songs.isEmpty() && top.children.size == 1) top = top.children.first()
        return top
    }

    fun artistHub(artist: String, s: Settings = settingsRepo.settings.value): ArtistHub {
        val list = songs.value
        val delim = s.artistDelimiters
        val mine = list.filter { song ->
            artist in splitMulti(song.artist, delim) || artist == song.albumArtist
        }
        val groups = mine.groupBy { albumKey(it) }
        fun item(songs: List<SongEntity>): AlbumItem {
            val f = songs.first()
            return AlbumItem(albumKey(f), f.album.ifBlank { "Unknown album" }, f.albumArtist.ifBlank { f.artist },
                songs.maxOf { it.year }, songs.sortedWith(compareBy({ it.discNumber }, { it.trackNumber })))
        }
        val own = groups.filter { (_, v) -> v.any { it.albumArtist == artist || (it.albumArtist.isBlank() && artist in splitMulti(it.artist, delim)) } }
        val others = groups.filterKeys { it !in own.keys }
        // A release counts as a single when the full release (not just this artist's tracks) has one track.
        val fullCount = list.groupingBy { albumKey(it) }.eachCount()
        val (singles, albums) = own.values.map(::item).partition { (fullCount[it.key] ?: 0) <= 1 }
        return ArtistHub(
            artist = artist,
            albums = albums.sortedByDescending { it.year },
            singles = singles.sortedByDescending { it.year },
            appearsIn = others.values.map(::item).sortedByDescending { it.year },
            songs = mine,
        )
    }

    // ---- Indexing (MediaStore direct access + TagLib enrichment + dedup) -------------------------

    fun rescan() {
        if (scanning.value) return
        scope.launch(Dispatchers.IO) {
            scanning.value = true
            scanError.value = null
            try { indexNow() }
            catch (e: Throwable) {
                android.util.Log.e("MoiseScan", "scan failed", e)
                scanError.value = "Library scan failed: ${e.javaClass.simpleName}: ${e.message}"
                scanInfo.value = scanError.value!!
            }
            finally { scanning.value = false }
        }
    }

    /** One file as reported by MediaStore, before TagLib enrichment. */
    private class Raw(
        val uri: Uri, val path: String, val name: String, val title: String, val artist: String, val album: String,
        val year: Int, val track: Int, val durationMs: Long, val mime: String, val size: Long, val added: Long, val modified: Long,
    )

    private fun queryAudio(): List<Raw> {
        // The audio view has every audio column. Ringtones, alarms and notifications are not music.
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND ${MediaStore.Audio.Media.IS_ALARM} = 0"
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<Raw>()
        context.contentResolver.query(base, cols, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                out += Raw(
                    ContentUris.withAppendedId(base, c.getLong(0)), path, c.getString(2).orEmpty(), c.getString(3).orEmpty(),
                    c.getString(4).orEmpty().takeUnless { it == "<unknown>" }.orEmpty(),
                    c.getString(5).orEmpty().takeUnless { it == "<unknown>" }.orEmpty(),
                    c.getInt(6), c.getInt(7) % 1000, c.getLong(8), c.getString(9).orEmpty(), c.getLong(10), c.getLong(11), c.getLong(12),
                )
            }
        }
        return out
    }

    /** Formats Android does not file as audio (APE, TAK, MKA, DSD ...): ask the general files table, minimal columns only. */
    private fun queryExtraFormats(): List<Raw> {
        val base = MediaStore.Files.getContentUri("external")
        val cols = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val ext = listOf("ape", "tak", "mka", "dsf", "dff", "wv", "alac").joinToString(" OR ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.$it'" }
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO} AND ($ext)"
        val out = ArrayList<Raw>()
        context.contentResolver.query(base, cols, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                val name = c.getString(2).orEmpty()
                val uri = ContentUris.withAppendedId(base, c.getLong(0))
                out += Raw(uri, path, name, name.substringBeforeLast('.'), "", "", 0, 0, probeDuration(uri), c.getString(3).orEmpty(), c.getLong(4), c.getLong(5), c.getLong(6))
            }
        }
        return out
    }

    private fun probeDuration(uri: Uri): Long {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { runCatching { r.release() } }
    }

    private suspend fun indexNow() {
        val s = settingsRepo.settings.value
        val knownPaths = songDao.allPaths().toHashSet()
        val knownKeys = songDao.allDedupKeys().toHashSet()
        val seenPaths = HashSet<String>()
        val batch = ArrayList<SongEntity>()
        var alreadyKnown = 0; var duplicates = 0; var inserted = 0

        val rows = queryAudio() + queryExtraFormats()
        scanProgress.value = 0 to rows.size

        for ((n, r) in rows.withIndex()) {
            if (n % 50 == 0) scanProgress.value = n to rows.size
            val path = r.path
            val folder = path.substringBeforeLast('/', "")
            if (s.folderExclusions.any { folder.startsWith(it) }) continue
            if (s.folderWhitelist.isNotEmpty() && s.folderWhitelist.none { folder.startsWith(it) }) continue
            seenPaths += path
            if (path in knownPaths) { alreadyKnown++; continue }          // already indexed; incremental scan

            val ext = r.name.substringAfterLast('.', "").lowercase()
            var title = r.title.ifBlank { r.name.substringBeforeLast('.') }
            var artist = r.artist
            var albumArtist = ""
            var album = r.album
            var genre = ""
            var year = r.year
            var track = r.track
            var disc = 0
            var lyrics = false

            // TagLib enrichment: genre, album artist, disc, multi-value artists, and formats MediaStore reads poorly.
            TagIO.read(context, r.uri)?.let { t ->
                if (t.title.isNotBlank()) title = t.title
                if (t.artists.isNotEmpty()) artist = t.artists.joinToString("; ")
                if (t.albumArtists.isNotEmpty()) albumArtist = t.albumArtists.joinToString("; ")
                if (t.album.isNotBlank()) album = t.album
                if (t.genres.isNotEmpty()) genre = t.genres.joinToString("; ")
                if (t.year > 0) year = t.year
                if (t.track > 0) track = t.track
                disc = t.disc
                lyrics = t.lyrics.isNotBlank()
            }

            // Deduplicate by tag identity + whole-second duration (same song copied to two folders).
            val key = "${title.lowercase()}|${artist.lowercase()}|${album.lowercase()}|${r.durationMs / 1000}"
            if (!knownKeys.add(key)) { duplicates++; continue }

            batch += SongEntity(
                uri = r.uri.toString(), path = path, folder = folder, title = title, artist = artist,
                albumArtist = albumArtist, album = album, genre = genre, year = year, trackNumber = track,
                discNumber = disc, durationMs = r.durationMs, mimeType = r.mime,
                extension = ext, size = r.size, dateAdded = r.added,
                dateModified = r.modified, dedupKey = key, hasEmbeddedLyrics = lyrics,
            )
            inserted++
            if (batch.size >= 200) { songDao.upsertAll(batch.toList()); batch.clear() }
        }
        if (batch.isNotEmpty()) songDao.upsertAll(batch)
        val info = "Scan: MediaStore rows=${rows.size}, new=$inserted, already indexed=$alreadyKnown, duplicates=$duplicates"
        android.util.Log.i("MoiseScan", info)
        scanInfo.value = info

        // Remove rows for files that vanished.
        val gone = knownPaths.filter { it !in seenPaths }
        gone.chunked(500).forEach { songDao.deleteByPaths(it) }
    }

    // ---- Song mutation helpers -------------------------------------------------------------------

    suspend fun recordPlay(songId: Long) = songDao.recordPlay(songId, System.currentTimeMillis())
    suspend fun setFavorite(songId: Long, fav: Boolean) = songDao.setFavorite(songId, fav)
    suspend fun songsByIds(ids: List<Long>): List<SongEntity> {
        val map = songDao.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull { map[it] }          // preserve order
    }

    /** Re-reads tags for a song after the tag editor wrote to the file. */
    suspend fun refreshFromFile(song: SongEntity) = withContext(Dispatchers.IO) {
        val t = TagIO.read(context, Uri.parse(song.uri)) ?: return@withContext
        songDao.update(
            song.copy(
                title = t.title.ifBlank { song.title }, artist = t.artists.joinToString("; "),
                albumArtist = t.albumArtists.joinToString("; "), album = t.album,
                genre = t.genres.joinToString("; "), year = t.year, trackNumber = t.track,
                discNumber = t.disc, hasEmbeddedLyrics = t.lyrics.isNotBlank(),
            ),
        )
    }

    suspend fun removeSongs(ids: List<Long>) = songDao.deleteByIds(ids)
    suspend fun updatePath(song: SongEntity, newPath: String, newUri: String? = null) {
        songDao.update(song.copy(path = newPath, folder = newPath.substringBeforeLast('/'), uri = newUri ?: song.uri))
    }

    // ---- Playlists ----------------------------------------------------------------------------------

    suspend fun createPlaylist(name: String, parentId: Long? = null, rules: SmartRules? = null): Long =
        playlistDao.insert(PlaylistEntity(name = name, parentId = parentId, isSmart = rules != null, rulesJson = rules?.toJson()))

    suspend fun renamePlaylist(p: PlaylistEntity, name: String) = playlistDao.update(p.copy(name = name))
    suspend fun deletePlaylist(p: PlaylistEntity) {
        playlistDao.children(p.id).forEach { playlistDao.setParent(it.id, p.parentId) }
        playlistDao.clearSongs(p.id); playlistDao.delete(p)
    }
    suspend fun nestPlaylist(id: Long, parentId: Long?) {
        // Prevent cycles: walk up from the target parent.
        var cur = parentId
        while (cur != null) { if (cur == id) return; cur = playlistDao.get(cur)?.parentId }
        playlistDao.setParent(id, parentId)
    }
    suspend fun addToPlaylist(id: Long, songIds: List<Long>) =
        playlistDao.setSongs(id, playlistDao.songIds(id) + songIds)
    suspend fun removeFromPlaylist(id: Long, positions: Set<Int>) =
        playlistDao.setSongs(id, playlistDao.songIds(id).filterIndexed { i, _ -> i !in positions })
    suspend fun movePlaylistSong(id: Long, from: Int, to: Int) {
        val l = playlistDao.songIds(id).toMutableList()
        if (from !in l.indices || to !in l.indices) return
        l.add(to, l.removeAt(from)); playlistDao.setSongs(id, l)
    }
    suspend fun replacePlaylistSongs(id: Long, songIds: List<Long>) = playlistDao.setSongs(id, songIds)

    /** Resolve a playlist (manual, smart, or nested) into a flat, de-duplicated song list. */
    suspend fun resolvePlaylist(id: Long, visited: MutableSet<Long> = HashSet()): List<SongEntity> {
        if (!visited.add(id)) return emptyList()
        val p = playlistDao.get(id) ?: return emptyList()
        val own = if (p.isSmart) SmartRules.fromJson(p.rulesJson).evaluate(songs.value)
        else songsByIds(playlistDao.songIds(id))
        val nested = playlistDao.children(id).flatMap { resolvePlaylist(it.id, visited) }
        return (own + nested).distinctBy { it.id }
    }

    suspend fun playlistParent(id: Long) = playlistDao.get(id)?.parentId

    /** Resolve a file path from an imported playlist to a library song. */
    suspend fun matchPath(path: String, baseDir: String?): SongEntity? {
        songDao.getByPath(path)?.let { return it }
        if (baseDir != null && !path.startsWith("/")) songDao.getByPath(File(baseDir, path).canonicalPath)?.let { return it }
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return songs.value.firstOrNull { it.path.endsWith("/$name") }
    }

    val playlistDaoInternal get() = playlistDao
}
