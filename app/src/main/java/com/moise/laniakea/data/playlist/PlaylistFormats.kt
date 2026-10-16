package com.moise.laniakea.data.playlist

import com.moise.laniakea.data.db.SongEntity

data class PlaylistEntry(
    val path: String,
    val title: String? = null,
    val startMs: Long = 0,      // cue sheets: track start inside the file
)

object PlaylistFormats {

    val EXTENSIONS = setOf("m3u", "m3u8", "pls", "wpl", "cue")

    fun parse(text: String, extension: String): List<PlaylistEntry> = when (extension.lowercase()) {
        "m3u", "m3u8" -> parseM3u(text)
        "pls" -> parsePls(text)
        "wpl" -> parseWpl(text)
        "cue" -> parseCue(text)
        else -> emptyList()
    }

    // ---- M3U / M3U8 --------------------------------------------------------------------------

    private fun parseM3u(text: String): List<PlaylistEntry> {
        val out = ArrayList<PlaylistEntry>()
        var pendingTitle: String? = null
        text.removePrefix("﻿").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line.startsWith("#EXTINF", true) ->
                    pendingTitle = line.substringAfter(',', "").trim().ifEmpty { null }
                line.startsWith("#") -> Unit
                else -> { out += PlaylistEntry(line.cleanPath(), pendingTitle); pendingTitle = null }
            }
        }
        return out
    }

    fun exportM3u8(songs: List<SongEntity>): String = buildString {
        appendLine("#EXTM3U")
        songs.forEach { s ->
            appendLine("#EXTINF:${s.durationMs / 1000},${s.artist.ifBlank { "Unknown" }} - ${s.title}")
            appendLine(s.path)
        }
    }

    // ---- PLS ---------------------------------------------------------------------------------------

    private fun parsePls(text: String): List<PlaylistEntry> {
        val files = sortedMapOf<Int, String>()
        val titles = HashMap<Int, String>()
        text.lineSequence().map { it.trim() }.forEach { line ->
            val eq = line.indexOf('=')
            if (eq < 0) return@forEach
            val key = line.substring(0, eq).lowercase()
            val value = line.substring(eq + 1).trim()
            when {
                key.startsWith("file") -> key.removePrefix("file").toIntOrNull()?.let { files[it] = value }
                key.startsWith("title") -> key.removePrefix("title").toIntOrNull()?.let { titles[it] = value }
            }
        }
        return files.map { (i, p) -> PlaylistEntry(p.cleanPath(), titles[i]) }
    }

    fun exportPls(songs: List<SongEntity>): String = buildString {
        appendLine("[playlist]")
        songs.forEachIndexed { i, s ->
            appendLine("File${i + 1}=${s.path}")
            appendLine("Title${i + 1}=${s.title}")
            appendLine("Length${i + 1}=${s.durationMs / 1000}")
        }
        appendLine("NumberOfEntries=${songs.size}")
        appendLine("Version=2")
    }

    // ---- WPL (Windows Media Player, XML) -------------------------------------------------------

    private val wplSrc = Regex("""<media\b[^>]*?\bsrc\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

    private fun parseWpl(text: String): List<PlaylistEntry> =
        wplSrc.findAll(text).map { PlaylistEntry(it.groupValues[1].xmlUnescape().cleanPath()) }.toList()

    private fun String.xmlUnescape() = replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

    // ---- CUE sheets ---------------------------------------------------------------------------------

    private val cueFile = Regex("""^FILE\s+"?(.+?)"?\s+\w+\s*$""", RegexOption.IGNORE_CASE)
    private val cueTitle = Regex("""^TITLE\s+"?(.*?)"?\s*$""", RegexOption.IGNORE_CASE)
    private val cueIndex = Regex("""^INDEX\s+01\s+(\d+):(\d+):(\d+)""", RegexOption.IGNORE_CASE)

    /** Returns one entry per CUE TRACK. Entries sharing a file carry their start offset in the file. */
    private fun parseCue(text: String): List<PlaylistEntry> {
        val out = ArrayList<PlaylistEntry>()
        var file: String? = null
        var title: String? = null
        var inTrack = false
        text.removePrefix("﻿").lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("FILE", true) -> { file = cueFile.find(line)?.groupValues?.get(1); inTrack = false }
                line.startsWith("TRACK", true) -> { inTrack = true; title = null }
                inTrack && line.startsWith("TITLE", true) -> title = cueTitle.find(line)?.groupValues?.get(1)
                inTrack && line.startsWith("INDEX 01", true) -> {
                    val m = cueIndex.find(line) ?: return@forEach
                    val (mm, ss, ff) = m.destructured
                    val ms = (mm.toLong() * 60 + ss.toLong()) * 1000 + ff.toLong() * 1000 / 75
                    file?.let { out += PlaylistEntry(it.cleanPath(), title, ms) }
                }
            }
        }
        return out
    }

    private fun String.cleanPath(): String = trim().removePrefix("file://").replace('\\', '/')
}
