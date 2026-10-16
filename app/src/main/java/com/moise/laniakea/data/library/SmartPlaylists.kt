package com.moise.laniakea.data.library

import com.moise.laniakea.data.db.SongEntity
import org.json.JSONObject

/**
 * Rule-based smart playlists. "Most played" is just a preset: minPlays = 1, sortBy = PLAY_COUNT, limit = 50.
 * All set rules are ANDed together.
 */
data class SmartRules(
    val minPlays: Int = 0,
    val favoritesOnly: Boolean = false,
    val artistContains: String = "",
    val albumContains: String = "",
    val genreContains: String = "",
    val yearFrom: Int = 0,
    val yearTo: Int = 0,
    val addedWithinDays: Int = 0,
    val playedWithinDays: Int = 0,
    val extensions: Set<String> = emptySet(),
    val sortBy: Sort = Sort.PLAY_COUNT,
    val limit: Int = 100,
) {
    enum class Sort { PLAY_COUNT, LAST_PLAYED, DATE_ADDED, TITLE, RANDOM }

    fun toJson(): String = JSONObject().apply {
        put("minPlays", minPlays); put("fav", favoritesOnly)
        put("artist", artistContains); put("album", albumContains); put("genre", genreContains)
        put("yearFrom", yearFrom); put("yearTo", yearTo)
        put("added", addedWithinDays); put("played", playedWithinDays)
        put("ext", extensions.joinToString(",")); put("sort", sortBy.name); put("limit", limit)
    }.toString()

    companion object {
        val MostPlayed = SmartRules(minPlays = 1, sortBy = Sort.PLAY_COUNT, limit = 50)
        val RecentlyAdded = SmartRules(addedWithinDays = 30, sortBy = Sort.DATE_ADDED, limit = 100)

        fun fromJson(json: String?): SmartRules {
            if (json.isNullOrBlank()) return SmartRules()
            return runCatching {
                val o = JSONObject(json)
                SmartRules(
                    minPlays = o.optInt("minPlays"), favoritesOnly = o.optBoolean("fav"),
                    artistContains = o.optString("artist"), albumContains = o.optString("album"),
                    genreContains = o.optString("genre"),
                    yearFrom = o.optInt("yearFrom"), yearTo = o.optInt("yearTo"),
                    addedWithinDays = o.optInt("added"), playedWithinDays = o.optInt("played"),
                    extensions = o.optString("ext").split(',').filter { it.isNotBlank() }.toSet(),
                    sortBy = runCatching { Sort.valueOf(o.optString("sort")) }.getOrDefault(Sort.PLAY_COUNT),
                    limit = o.optInt("limit", 100),
                )
            }.getOrDefault(SmartRules())
        }
    }
}

fun SmartRules.evaluate(all: List<SongEntity>, nowMs: Long = System.currentTimeMillis()): List<SongEntity> {
    val day = 86_400_000L
    val filtered = all.filter { s ->
        s.playCount >= minPlays &&
            (!favoritesOnly || s.favorite) &&
            (artistContains.isBlank() || s.artist.contains(artistContains, true) || s.albumArtist.contains(artistContains, true)) &&
            (albumContains.isBlank() || s.album.contains(albumContains, true)) &&
            (genreContains.isBlank() || s.genre.contains(genreContains, true)) &&
            (yearFrom == 0 || s.year >= yearFrom) &&
            (yearTo == 0 || s.year in 1..yearTo) &&
            (addedWithinDays == 0 || s.dateAdded * 1000 >= nowMs - addedWithinDays * day) &&
            (playedWithinDays == 0 || (s.lastPlayed > 0 && s.lastPlayed >= nowMs - playedWithinDays * day)) &&
            (extensions.isEmpty() || s.extension.lowercase() in extensions)
    }
    val sorted = when (sortBy) {
        SmartRules.Sort.PLAY_COUNT -> filtered.sortedByDescending { it.playCount }
        SmartRules.Sort.LAST_PLAYED -> filtered.sortedByDescending { it.lastPlayed }
        SmartRules.Sort.DATE_ADDED -> filtered.sortedByDescending { it.dateAdded }
        SmartRules.Sort.TITLE -> filtered.sortedWith(compareBy(NaturalComparator) { it.title })
        SmartRules.Sort.RANDOM -> filtered.shuffled()
    }
    return if (limit > 0) sorted.take(limit) else sorted
}
