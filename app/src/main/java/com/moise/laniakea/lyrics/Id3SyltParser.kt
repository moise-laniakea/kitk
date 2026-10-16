package com.moise.laniakea.lyrics

import java.io.InputStream
import java.nio.charset.Charset

/**
 * Minimal ID3v2.3/2.4 reader that extracts the SYLT (synchronised lyrics/text) frame.
 *
 * SYLT stores (text, timestamp) pairs. When the producer wrote one pair per syllable the result is
 * word/syllable "karaoke" lyrics: a text beginning with a newline starts a new line.
 */
object Id3SyltParser {

    fun parse(input: InputStream): Lyrics? = runCatching {
        val header = ByteArray(10)
        if (input.readFully(header) < 10 || String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
        val major = header[3].toInt()
        if (major != 3 && major != 4) return null
        val flags = header[5].toInt()
        val size = synchsafe(header, 6)
        if (size <= 0 || size > 16 * 1024 * 1024) return null

        var tag = ByteArray(size)
        if (input.readFully(tag) < size) return null
        if (flags and 0x80 != 0) tag = removeUnsync(tag)         // tag-wide unsynchronisation
        // Skip extended header.
        var pos = 0
        if (flags and 0x40 != 0 && tag.size >= 4) {
            pos = if (major == 4) synchsafe(tag, 0) else (be32(tag, 0) + 4)
        }

        while (pos + 10 <= tag.size) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val fsize = if (major == 4) synchsafe(tag, pos + 4) else be32(tag, pos + 4)
            val start = pos + 10
            if (fsize <= 0 || start + fsize > tag.size) break
            if (id == "SYLT") {
                return decode(tag.copyOfRange(start, start + fsize))
            }
            pos = start + fsize
        }
        null
    }.getOrNull()

    private fun decode(f: ByteArray): Lyrics? {
        if (f.size < 7) return null
        val encoding = f[0].toInt()
        // f[1..3] = language, f[4] = timestamp format (1 = MPEG frames, 2 = milliseconds), f[5] = content type
        val tsFormat = f[4].toInt()
        if (tsFormat != 2) return null                       // frame-based timestamps need the bitrate; unsupported
        val charset = charsetFor(encoding)
        val wide = encoding == 1 || encoding == 2
        var pos = 6
        pos = skipTerminated(f, pos, wide)                   // content descriptor
        val items = ArrayList<Pair<String, Long>>()
        while (pos < f.size) {
            val end = findTerminator(f, pos, wide)
            if (end < 0 || end + (if (wide) 2 else 1) + 4 > f.size) break
            val text = String(f, pos, end - pos, charset).removePrefix("﻿")
            val tsPos = end + if (wide) 2 else 1
            val time = be32(f, tsPos).toLong() and 0xFFFFFFFFL
            items += text to time
            pos = tsPos + 4
        }
        if (items.isEmpty()) return null

        // Group syllables into lines. A syllable starting with '\n' (or containing one) begins a new line.
        val lines = ArrayList<LyricLine>()
        var words = ArrayList<LyricWord>()
        var lineStart = -1L
        val sb = StringBuilder()
        fun flush() {
            val t = sb.toString().trim()
            if (t.isNotEmpty() && lineStart >= 0) lines += LyricLine(lineStart, -1, t, words.toList())
            sb.clear(); words = ArrayList(); lineStart = -1
        }
        items.forEachIndexed { i, (rawText, time) ->
            val startsNew = rawText.startsWith("\n") || rawText.startsWith("\r")
            if (startsNew) flush()
            val syl = rawText.trimStart('\n', '\r').replace("\n", " ")
            if (lineStart < 0) lineStart = time
            val next = items.getOrNull(i + 1)?.second ?: (time + 500)
            if (syl.isNotEmpty()) { words += LyricWord(time, next, syl); sb.append(syl) }
        }
        flush()
        if (lines.isEmpty()) return null

        // If every line has exactly one "word", it is really line-synced, not karaoke.
        val karaoke = lines.any { it.words.size > 1 }
        val finalLines = if (karaoke) lines else lines.map { it.copy(words = emptyList()) }
        return Lyrics(finalLines, if (karaoke) LyricsKind.WORD_SYNCED else LyricsKind.SYNCED, "sylt").normalized()
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private fun charsetFor(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun findTerminator(b: ByteArray, from: Int, wide: Boolean): Int {
        var i = from
        if (!wide) { while (i < b.size && b[i].toInt() != 0) i++; return if (i < b.size) i else -1 }
        while (i + 1 < b.size) { if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && (i - from) % 2 == 0) return i; i += 2 }
        return -1
    }

    private fun skipTerminated(b: ByteArray, from: Int, wide: Boolean): Int {
        val e = findTerminator(b, from, wide)
        return if (e < 0) b.size else e + if (wide) 2 else 1
    }

    private fun synchsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun removeUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun InputStream.readFully(buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val r = read(buf, n, buf.size - n); if (r < 0) break; n += r }
        return n
    }
}
