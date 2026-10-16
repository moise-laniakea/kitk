package com.moise.laniakea.lyrics

data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

data class LyricLine(
    val startMs: Long,            // -1 for plain (unsynced) lyrics
    val endMs: Long,              // -1 when unknown; filled in by [Lyrics.normalized]
    val text: String,
    val words: List<LyricWord> = emptyList(),   // non-empty => word/syllable (karaoke) sync
)

enum class LyricsKind { PLAIN, SYNCED, WORD_SYNCED }

data class Lyrics(val lines: List<LyricLine>, val kind: LyricsKind, val source: String = "") {

    val isEmpty get() = lines.isEmpty()

    /** Index of the line that is active at [positionMs], or -1 before the first line. */
    fun activeIndex(positionMs: Long): Int {
        if (kind == LyricsKind.PLAIN) return -1
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** Fills missing end times so each line lasts until the next one starts. */
    fun normalized(durationMs: Long = Long.MAX_VALUE): Lyrics {
        if (kind == LyricsKind.PLAIN) return this
        val sorted = lines.sortedBy { it.startMs }
        val fixed = sorted.mapIndexed { i, l ->
            if (l.endMs > l.startMs) l
            else l.copy(endMs = sorted.getOrNull(i + 1)?.startMs ?: minOf(durationMs, l.startMs + 5000))
        }
        return copy(lines = fixed)
    }

    fun asPlainText(): String = lines.joinToString("\n") { it.text }

    companion object {
        val Empty = Lyrics(emptyList(), LyricsKind.PLAIN)
    }
}
