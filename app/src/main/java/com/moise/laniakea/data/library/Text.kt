package com.moise.laniakea.data.library

/** Splits "A; B / C" style tag strings with a user-configurable delimiter set. Handles "dual-artist" tags. */
fun splitMulti(raw: String, delimiters: String): List<String> {
    if (raw.isBlank()) return emptyList()
    if (delimiters.isEmpty()) return listOf(raw.trim())
    val set = delimiters.toSet()
    val out = ArrayList<String>()
    val sb = StringBuilder()
    for (ch in raw) {
        if (ch in set) { sb.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add); sb.clear() }
        else sb.append(ch)
    }
    sb.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
    return out.distinct()
}

/** Natural ("Track 2" before "Track 10") case-insensitive comparator. */
val NaturalComparator: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var si = i; while (si < a.length && a[si] == '0') si++
            var sj = j; while (sj < b.length && b[sj] == '0') sj++
            var ei = si; while (ei < a.length && a[ei].isDigit()) ei++
            var ej = sj; while (ej < b.length && b[ej].isDigit()) ej++
            val la = ei - si; val lb = ej - sj
            if (la != lb) return la.compareTo(lb)
            val c = a.substring(si, ei).compareTo(b.substring(sj, ej))
            if (c != 0) return c
            i = ei; j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++; j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}
