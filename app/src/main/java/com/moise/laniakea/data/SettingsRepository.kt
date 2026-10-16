package com.moise.laniakea.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.store by preferencesDataStore("moise_settings")

enum class NowPlayingTheme { CLASSIC, FULL_ART, MINIMAL, VINYL }
enum class FolderMode { LINEAR, HIERARCHICAL }
enum class SortMode { NATURAL, TITLE, ARTIST, ALBUM, DATE_ADDED, DURATION, PLAY_COUNT, CUSTOM }

data class Settings(
    val fadeInMs: Int = 400,
    val fadeOutMs: Int = 400,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val skipSilence: Boolean = false,
    val autoPauseMinutes: Int = 10,           // battery saving: stop service after paused this long (0 = off)
    val artistDelimiters: String = ";,/&",    // customizable artist/genre separators
    val genreDelimiters: String = ";,/",
    val folderWhitelist: Set<String> = emptySet(),   // empty = whole library
    val folderExclusions: Set<String> = emptySet(),
    val folderMode: FolderMode = FolderMode.HIERARCHICAL,
    val gridView: Boolean = false,
    val sortMode: SortMode = SortMode.NATURAL,
    val sortDescending: Boolean = false,
    val nowPlayingTheme: NowPlayingTheme = NowPlayingTheme.CLASSIC,
    val dynamicColor: Boolean = true,
    val artworkColors: Boolean = true,
    val useFolderArt: Boolean = true,         // "enhanced album covers via storage access"
)

class SettingsRepository(private val context: Context, private val scope: CoroutineScope) {
    private object K {
        val fadeIn = intPreferencesKey("fade_in")
        val fadeOut = intPreferencesKey("fade_out")
        val speed = floatPreferencesKey("speed")
        val pitch = floatPreferencesKey("pitch")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val autoPause = intPreferencesKey("auto_pause")
        val artistDelims = stringPreferencesKey("artist_delims")
        val genreDelims = stringPreferencesKey("genre_delims")
        val whitelist = stringSetPreferencesKey("whitelist")
        val exclusions = stringSetPreferencesKey("exclusions")
        val folderMode = stringPreferencesKey("folder_mode")
        val grid = booleanPreferencesKey("grid")
        val sort = stringPreferencesKey("sort")
        val sortDesc = booleanPreferencesKey("sort_desc")
        val npTheme = stringPreferencesKey("np_theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val artColors = booleanPreferencesKey("art_colors")
        val folderArt = booleanPreferencesKey("folder_art")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    val settings: StateFlow<Settings> = context.store.data.map { p ->
        val d = Settings()
        Settings(
            fadeInMs = p[K.fadeIn] ?: d.fadeInMs,
            fadeOutMs = p[K.fadeOut] ?: d.fadeOutMs,
            speed = p[K.speed] ?: d.speed,
            pitch = p[K.pitch] ?: d.pitch,
            skipSilence = p[K.skipSilence] ?: d.skipSilence,
            autoPauseMinutes = p[K.autoPause] ?: d.autoPauseMinutes,
            artistDelimiters = p[K.artistDelims] ?: d.artistDelimiters,
            genreDelimiters = p[K.genreDelims] ?: d.genreDelimiters,
            folderWhitelist = p[K.whitelist] ?: d.folderWhitelist,
            folderExclusions = p[K.exclusions] ?: d.folderExclusions,
            folderMode = p.enum(K.folderMode, d.folderMode),
            gridView = p[K.grid] ?: d.gridView,
            sortMode = p.enum(K.sort, d.sortMode),
            sortDescending = p[K.sortDesc] ?: d.sortDescending,
            nowPlayingTheme = p.enum(K.npTheme, d.nowPlayingTheme),
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            artworkColors = p[K.artColors] ?: d.artworkColors,
            useFolderArt = p[K.folderArt] ?: d.useFolderArt,
        )
    }.stateIn(scope, SharingStarted.Eagerly, Settings())

    fun update(block: (MutablePrefs) -> Unit) {
        scope.launch {
            context.store.edit { prefs -> block(MutablePrefs(prefs)) }
        }
    }

    class MutablePrefs(private val p: androidx.datastore.preferences.core.MutablePreferences) {
        fun fadeIn(ms: Int) { p[K.fadeIn] = ms.coerceIn(0, 10_000) }
        fun fadeOut(ms: Int) { p[K.fadeOut] = ms.coerceIn(0, 10_000) }
        fun speed(v: Float) { p[K.speed] = v.coerceIn(0.25f, 4f) }
        fun pitch(v: Float) { p[K.pitch] = v.coerceIn(0.5f, 2f) }
        fun skipSilence(v: Boolean) { p[K.skipSilence] = v }
        fun autoPause(min: Int) { p[K.autoPause] = min }
        fun artistDelimiters(s: String) { p[K.artistDelims] = s }
        fun genreDelimiters(s: String) { p[K.genreDelims] = s }
        fun whitelist(s: Set<String>) { p[K.whitelist] = s }
        fun exclusions(s: Set<String>) { p[K.exclusions] = s }
        fun folderMode(m: FolderMode) { p[K.folderMode] = m.name }
        fun grid(v: Boolean) { p[K.grid] = v }
        fun sort(m: SortMode, desc: Boolean) { p[K.sort] = m.name; p[K.sortDesc] = desc }
        fun nowPlayingTheme(t: NowPlayingTheme) { p[K.npTheme] = t.name }
        fun dynamicColor(v: Boolean) { p[K.dynamic] = v }
        fun artworkColors(v: Boolean) { p[K.artColors] = v }
        fun folderArt(v: Boolean) { p[K.folderArt] = v }
    }
}
