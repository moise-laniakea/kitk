package com.moise.laniakea.lyrics

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Parsers for LRC (plain + enhanced), TTML, SRT, SBV, SSA/ASS and plain text. */
object LyricParsers {

    val EXTENSIONS = listOf("lrc", "ttml", "xml", "srt", "sbv", "ass", "ssa", "txt")

    fun parse(text: String, extension: String): Lyrics? {
        val clean = text.removePrefix("\uFEFF")
        val result = when (extension.lowercase()) {
            "lrc" -> parseLrc(clean)
            "ttml", "xml", "dfxp" -> parseTtml(clean)
            "srt" -> parseSrt(clean)
            "sbv" -> parseSbv(clean)
            "ass", "ssa" -> parseAss(clean)
            else -> sniff(clean)
        }
        return result?.takeIf { !it.isEmpty }?.normalized()
    }

    /** Used for embedded lyrics tags where there is no file extension to go on. */
    fun sniff(text: String): Lyrics? = when {
        text.contains("<tt", true) && text.contains("<p", true) -> parseTtml(text)
        Regex("""\[\d+:\d+(?:[.:]\d+)?\]""").containsMatchIn(text) -> parseLrc(text)
        text.contains("-->") -> parseSrt(text)
        text.contains("[Events]", true) -> parseAss(text)
        else -> parsePlain(text)
    }

    // ---- Plain ---------------------------------------------------------------------------------

    fun parsePlain(text: String): Lyrics? {
        val lines = text.lines().map { it.trimEnd() }
        if (lines.all { it.isBlank() }) return null
        return Lyrics(lines.map { LyricLine(-1, -1, it) }, LyricsKind.PLAIN, "plain")
    }

    // ---- LRC (incl. enhanced word tags <mm:ss.xx>) ------------------------------------------------

    private val lrcLineTs = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val lrcWordTs = Regex("""<(\d+):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val lrcOffset = Regex("""^\[offset:\s*(-?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    private fun ts(m: String, s: String, frac: String?): Long {
        val fracMs = when (frac?.length) {
            null, 0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return m.toLong() * 60_000 + s.toLong() * 1000 + fracMs
    }

    fun parseLrc(text: String): Lyrics? {
        var offset = 0L
        val out = ArrayList<LyricLine>()
        var anyWords = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            lrcOffset.find(line)?.let { offset = it.groupValues[1].toLong(); continue }
            val stamps = ArrayList<Long>()
            var idx = 0
            while (true) {
                val m = lrcLineTs.find(line, idx)
                if (m == null || m.range.first != idx) break
                stamps += ts(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifEmpty { null })
                idx = m.range.last + 1
            }
            if (stamps.isEmpty()) continue            // metadata tag ([ar:], [ti:] …) or stray text
            val body = line.substring(idx)
            val words = parseEnhancedWords(body)
            val textOnly = lrcWordTs.replace(body, "").trim()
            if (words.isNotEmpty()) anyWords = true
            for (t in stamps) {
                val shift = offset
                out += LyricLine(
                    startMs = (t - shift).coerceAtLeast(0), endMs = -1, text = textOnly,
                    words = words.map { it.copy(startMs = (it.startMs - shift).coerceAtLeast(0), endMs = (it.endMs - shift).coerceAtLeast(0)) },
                )
            }
        }
        if (out.isEmpty()) return parsePlain(text)
        return Lyrics(out.sortedBy { it.startMs }, if (anyWords) LyricsKind.WORD_SYNCED else LyricsKind.SYNCED, "lrc")
    }

    private fun parseEnhancedWords(body: String): List<LyricWord> {
        val marks = lrcWordTs.findAll(body).toList()
        if (marks.isEmpty()) return emptyList()
        val words = ArrayList<LyricWord>()
        for ((i, m) in marks.withIndex()) {
            val start = ts(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifEmpty { null })
            val textStart = m.range.last + 1
            val textEnd = marks.getOrNull(i + 1)?.range?.first ?: body.length
            val seg = body.substring(textStart, textEnd)
            val end = marks.getOrNull(i + 1)?.let { n -> ts(n.groupValues[1], n.groupValues[2], n.groupValues[3].ifEmpty { null }) } ?: -1L
            if (seg.isNotEmpty()) words += LyricWord(start, end, seg)
        }
        return words
    }

    // ---- SRT / SBV -------------------------------------------------------------------------------------

    private val srtTime = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{1,3})""")

    private fun hmsMs(h: String, m: String, s: String, ms: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + ms.padEnd(3, '0').take(3).toLong()

    fun parseSrt(text: String): Lyrics? {
        val out = ArrayList<LyricLine>()
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).forEach { block ->
            val lines = block.trim().lines()
            val ti = lines.indexOfFirst { srtTime.containsMatchIn(it) }
            if (ti < 0) return@forEach
            val m = srtTime.find(lines[ti])!!.groupValues
            val body = lines.drop(ti + 1).joinToString("\n") { it.replace(Regex("<[^>]+>"), "") }.trim()
            if (body.isNotEmpty()) out += LyricLine(hmsMs(m[1], m[2], m[3], m[4]), hmsMs(m[5], m[6], m[7], m[8]), body)
        }
        return if (out.isEmpty()) null else Lyrics(out, LyricsKind.SYNCED, "srt")
    }

    private val sbvTime = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{1,3}),(\d+):(\d{2}):(\d{2})\.(\d{1,3})""")

    fun parseSbv(text: String): Lyrics? {
        val out = ArrayList<LyricLine>()
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).forEach { block ->
            val lines = block.trim().lines()
            val m = lines.firstOrNull()?.let { sbvTime.find(it) }?.groupValues ?: return@forEach
            val body = lines.drop(1).joinToString("\n").trim()
            if (body.isNotEmpty()) out += LyricLine(hmsMs(m[1], m[2], m[3], m[4]), hmsMs(m[5], m[6], m[7], m[8]), body)
        }
        return if (out.isEmpty()) null else Lyrics(out, LyricsKind.SYNCED, "sbv")
    }

    // ---- TTML (line and span/word timing) -------------------------------------------------------------

    private fun ttmlTime(v: String?): Long {
        if (v.isNullOrBlank()) return -1
        val t = v.trim()
        return when {
            t.endsWith("ms") -> t.removeSuffix("ms").toDoubleOrNull()?.toLong() ?: -1
            t.endsWith("s") -> ((t.removeSuffix("s").toDoubleOrNull() ?: return -1) * 1000).toLong()
            t.contains(':') -> {
                val p = t.split(':')
                val sec = p.last().toDoubleOrNull() ?: return -1
                val min = p.getOrNull(p.size - 2)?.toLongOrNull() ?: 0
                val h = p.getOrNull(p.size - 3)?.toLongOrNull() ?: 0
                h * 3_600_000 + min * 60_000 + (sec * 1000).toLong()
            }
            else -> t.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1
        }
    }

    fun parseTtml(text: String): Lyrics? = runCatching {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(text))
        val lines = ArrayList<LyricLine>()
        var pStart = -1L; var pEnd = -1L
        val pText = StringBuilder()
        val words = ArrayList<LyricWord>()
        var inP = false
        var spanStart = -1L; var spanEnd = -1L
        val spanText = StringBuilder()
        var inSpan = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "p" -> {
                        inP = true; pText.clear(); words.clear()
                        pStart = ttmlTime(parser.getAttributeValue(null, "begin"))
                        pEnd = ttmlTime(parser.getAttributeValue(null, "end"))
                    }
                    "span" -> if (inP) {
                        inSpan = true; spanText.clear()
                        spanStart = ttmlTime(parser.getAttributeValue(null, "begin"))
                        spanEnd = ttmlTime(parser.getAttributeValue(null, "end"))
                    }
                    "br" -> if (inP) pText.append('\n')
                }
                XmlPullParser.TEXT -> if (inP) {
                    val t = parser.text
                    if (inSpan) spanText.append(t)
                    pText.append(t)
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "span" -> if (inP && inSpan) {
                        if (spanStart >= 0 && spanText.isNotEmpty()) words += LyricWord(spanStart, spanEnd, spanText.toString())
                        inSpan = false
                    }
                    "p" -> if (inP) {
                        val body = pText.toString().replace(Regex("[ \t]+"), " ").trim()
                        if (body.isNotEmpty() && pStart >= 0) {
                            val start = if (words.isNotEmpty()) minOf(pStart, words.first().startMs) else pStart
                            lines += LyricLine(start, pEnd, body, words.toList())
                        }
                        inP = false
                    }
                }
            }
            event = parser.next()
        }
        if (lines.isEmpty()) null
        else Lyrics(lines, if (lines.any { it.words.isNotEmpty() }) LyricsKind.WORD_SYNCED else LyricsKind.SYNCED, "ttml")
    }.getOrNull()

    // ---- SSA / ASS (with \k karaoke syllable timing) -------------------------------------------------

    private val assTime = Regex("""(\d+):(\d{2}):(\d{2})[.](\d{1,2})""")
    private val assTag = Regex("""\{[^}]*\}""")
    private val assK = Regex("""\\[kK][fo]?(\d+)""")

    private fun assMs(s: String): Long {
        val m = assTime.find(s.trim()) ?: return -1
        val (h, mi, se, cs) = m.destructured
        return h.toLong() * 3_600_000 + mi.toLong() * 60_000 + se.toLong() * 1000 + cs.padEnd(2, '0').take(2).toLong() * 10
    }

    fun parseAss(text: String): Lyrics? {
        var format: List<String> = emptyList()
        var inEvents = false
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) { inEvents = line.equals("[Events]", true); continue }
            if (!inEvents) continue
            if (line.startsWith("Format:", true)) {
                format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            } else if (line.startsWith("Dialogue:", true)) {
                if (format.isEmpty()) continue
                val parts = line.substringAfter(':').trimStart().split(',', limit = format.size)
                if (parts.size < format.size) continue
                val start = assMs(parts[format.indexOf("start")])
                val end = assMs(parts[format.indexOf("end")])
                val body = parts[format.indexOf("text")]
                if (start < 0) continue
                val words = ArrayList<LyricWord>()
                // Split on override blocks; each block that carries \k starts a new syllable.
                var cursor = start
                val blocks = Regex("""\{([^}]*)\}([^{]*)""").findAll(body).toList()
                for (b in blocks) {
                    val k = assK.find(b.groupValues[1]) ?: continue
                    val dur = k.groupValues[1].toLong() * 10
                    val syl = b.groupValues[2].replace("\\N", " ").replace("\\n", " ")
                    if (syl.isNotEmpty()) words += LyricWord(cursor, cursor + dur, syl)
                    cursor += dur
                }
                val plain = assTag.replace(body, "").replace("\\N", "\n").replace("\\n", "\n").trim()
                if (plain.isNotEmpty()) out += LyricLine(start, end, plain, words)
            }
        }
        return if (out.isEmpty()) null
        else Lyrics(out, if (out.any { it.words.isNotEmpty() }) LyricsKind.WORD_SYNCED else LyricsKind.SYNCED, "ass")
    }
}
