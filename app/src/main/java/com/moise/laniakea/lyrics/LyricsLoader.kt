package com.moise.laniakea.lyrics

import android.content.Context
import android.net.Uri
import com.moise.laniakea.data.db.SongEntity
import com.moise.laniakea.data.tags.TagIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds lyrics for a song, best source first:
 *  1. Sidecar file next to the audio (.lrc, .ttml, .srt, .ass, .ssa, .sbv, .txt)
 *  2. Embedded ID3 SYLT frame (synced, possibly word-level)
 *  3. Embedded LYRICS/USLT tag (parsed as LRC if it has timestamps, otherwise plain)
 */
object LyricsLoader {

    suspend fun load(context: Context, song: SongEntity): Lyrics? = withContext(Dispatchers.IO) {
        sidecar(song)?.let { return@withContext it }
        val uri = Uri.parse(song.uri)

        if (song.mimeType.contains("mpeg") || song.extension in setOf("mp3", "mp2", "aac")) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { Id3SyltParser.parse(it) }
            }.getOrNull()?.let { return@withContext it }
        }

        val embedded = TagIO.read(context, uri)?.lyrics.orEmpty()
        if (embedded.isNotBlank()) LyricParsers.sniff(embedded)?.normalized() else null
    }

    private fun sidecar(song: SongEntity): Lyrics? {
        val base = File(song.path).let { File(it.parentFile, it.nameWithoutExtension) }
        for (ext in LyricParsers.EXTENSIONS) {
            val f = File("${base.path}.$ext")
            if (f.isFile && f.canRead()) {
                val parsed = runCatching { LyricParsers.parse(f.readText(), ext) }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return null
    }

    /** Persist lyrics next to the song as .lrc (used by the tag editor's lyrics tab). */
    fun saveSidecarLrc(song: SongEntity, text: String): Boolean = runCatching {
        val f = File(File(song.path).let { File(it.parentFile, it.nameWithoutExtension) }.path + ".lrc")
        f.writeText(text); true
    }.getOrDefault(false)
}
