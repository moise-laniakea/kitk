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

    private val extraExtensions = listOf("ape", "tak", "mka", "dsf", "dff", "opus", "alac", "m4a", "wv", "flac", "ogg")

    fun rescan() {
        if (scanning.value) return
        scope.launch(Dispatchers.IO) {
            scanning.value = true
            try { indexNow() } finally { scanning.value = false }
        }
    }

    private suspend fun indexNow() {
        val s = settingsRepo.settings.value
        val knownPaths = songDao.allPaths().toHashSet()
        val knownKeys = songDao.allDedupKeys().toHashSet()
        val seenPaths = HashSet<String>()
        val batch = ArrayList<SongEntity>()

        val uri = MediaStore.Files.getContentUri("external")
        val cols = buildList {
            add(MediaStore.Files.FileColumns._ID); add(MediaStore.Files.FileColumns.DATA)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME); add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.YEAR); add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DURATION); add(MediaStore.Files.FileColumns.MIME_TYPE)
            add(MediaStore.Files.FileColumns.SIZE); add(MediaStore.Files.FileColumns.DATE_ADDED)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= 30) { add(MediaStore.Audio.Media.GENRE); add(MediaStore.Audio.Media.ALBUM_ARTIST) }
        }.toTypedArray()

        val extClause = extraExtensions.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.$it'" }
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO} OR $extClause)"

        context.contentResolver.query(uri, cols, selection, null, null)?.use { c ->
            scanProgress.value = 0 to c.count
            val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iData = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val iGenre = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
            val iAA = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1

            var n = 0
            while (c.moveToNext()) {
                n++
                if (n % 50 == 0) scanProgress.value = n to c.count
                val path = c.getString(iData) ?: continue
                val folder = path.substringBeforeLast('/', "")
                if (s.folderExclusions.any { folder.startsWith(it) }) continue
                if (s.folderWhitelist.isNotEmpty() && s.folderWhitelist.none { folder.startsWith(it) }) continue
                seenPaths += path
                if (path in knownPaths) continue          // already indexed; incremental scan

                val id = c.getLong(iId)
                val itemUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val name = c.getString(iName).orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase()

                var title = c.getString(iTitle).orEmpty().ifBlank { name.substringBeforeLast('.') }
                var artist = c.getString(iArtist).orEmpty().takeUnless { it == "<unknown>" }.orEmpty()
                var albumArtist = if (iAA >= 0) c.getString(iAA).orEmpty() else ""
                var album = c.getString(iAlbum).orEmpty().takeUnless { it == "<unknown>" }.orEmpty()
                var genre = if (iGenre >= 0) c.getString(iGenre).orEmpty() else ""
                var year = c.getInt(iYear)
                var track = c.getInt(iTrack)
                var disc = 0
                var lyrics = false

                // TagLib enrichment: catches formats MediaStore reads poorly (APE, TAK, MKA, DSD …).
                TagIO.read(context, itemUri)?.let { t ->
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
                val durationMs = c.getLong(iDur)
                val key = "${title.lowercase()}|${artist.lowercase()}|${album.lowercase()}|${durationMs / 1000}"
                if (!knownKeys.add(key)) continue

                batch += SongEntity(
                    uri = itemUri.toString(), path = path, folder = folder, title = title, artist = artist,
                    albumArtist = albumArtist, album = album, genre = genre, year = year, trackNumber = track,
                    discNumber = disc, durationMs = durationMs, mimeType = c.getString(iMime).orEmpty(),
                    extension = ext, size = c.getLong(iSize), dateAdded = c.getLong(iAdded),
                    dateModified = c.getLong(iMod), dedupKey = key, hasEmbeddedLyrics = lyrics,
                )
                if (batch.size >= 200) { songDao.upsertAll(batch.toList()); batch.clear() }
            }
        }
        if (batch.isNotEmpty()) songDao.upsertAll(batch)

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
