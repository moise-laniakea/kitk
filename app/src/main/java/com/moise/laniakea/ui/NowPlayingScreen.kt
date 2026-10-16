package com.moise.laniakea.ui

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.moise.laniakea.data.NowPlayingTheme
import com.moise.laniakea.data.db.SongEntity

@Composable
fun NowPlayingScreen(vm: AppViewModel) {
    val np by vm.nowPlaying.collectAsState()
    val settings by vm.settings.collectAsState()
    val lyrics by vm.lyrics.collectAsState()
    val song by produceCurrentSong(vm, np.songId)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showLyrics by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(false) }

    val theme = settings.nowPlayingTheme
    val bg = when (theme) {
        NowPlayingTheme.MINIMAL -> MaterialTheme.colorScheme.surface
        NowPlayingTheme.FULL_ART -> Color.Black
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    }

    val art: @Composable (Modifier) -> Unit = { m ->
        when (theme) {
            NowPlayingTheme.VINYL -> VinylArt(song, np.isPlaying, settings.useFolderArt, m.clickableNoRipple { zoom = true })
            NowPlayingTheme.MINIMAL -> Box(m)
            else -> Artwork(song, m.clip(RoundedCornerShape(24.dp)).clickableNoRipple { zoom = true }, 800, settings.useFolderArt)
        }
    }

    val lyricsOrArt: @Composable () -> Unit = {
        val l = lyrics
        if (showLyrics && l != null) LyricsView(l, np.positionMs, { vm.player.seekTo(it) })
        else art(Modifier.fillMaxWidth().aspectRatioSquare())
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        if (theme == NowPlayingTheme.FULL_ART) {
            Artwork(song, Modifier.fillMaxSize(), 800, settings.useFolderArt)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.85f)))))
        }
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { lyricsOrArt() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Controls(vm, song, showLyrics, lyrics != null, { showLyrics = !showLyrics }, { showSpeed = true }, { showSleep = true })
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ vm.back() }) { Icon(Icons.Default.ExpandMore, "Close") }
                    Text("Now playing", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                    IconButton({ vm.open(Screen.Queue) }) { Icon(Icons.Default.QueueMusic, "Queue") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { lyricsOrArt() }
                Controls(vm, song, showLyrics, lyrics != null, { showLyrics = !showLyrics }, { showSpeed = true }, { showSleep = true })
            }
        }
    }

    if (showSpeed) SpeedPitchSheet(vm) { showSpeed = false }
    if (showSleep) SleepTimerSheet(vm) { showSleep = false }
    if (zoom) ZoomableArtDialog(song, settings.useFolderArt) { zoom = false }
}

@Composable
private fun VinylArt(song: SongEntity?, playing: Boolean, useFolderArt: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "vinyl")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(modifier.clip(CircleShape).background(Color(0xFF111111)).padding(10.dp)) {
        Artwork(song, Modifier.fillMaxSize().clip(CircleShape).rotate(if (playing) angle else 0f), 600, useFolderArt)
        Box(Modifier.align(Alignment.Center).size(18.dp).clip(CircleShape).background(Color(0xFF111111)))
    }
}

@Composable
private fun Controls(
    vm: AppViewModel,
    song: SongEntity?,
    showLyrics: Boolean,
    hasLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
) {
    val np by vm.nowPlaying.collectAsState()
    val context = LocalContext.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val progress = dragging ?: if (np.durationMs > 0) np.positionMs.toFloat() / np.durationMs else 0f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(np.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(np.artist, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Slider(
            progress,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { vm.player.seekTo((it * np.durationMs).toLong()) }; dragging = null },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((progress * np.durationMs).toLong()), style = MaterialTheme.typography.labelMedium)
            Text(formatTime(np.durationMs), style = MaterialTheme.typography.labelMedium)
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.player.toggleShuffle() }) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (np.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ vm.player.previous() }) { Icon(Icons.Default.SkipPrevious, "Previous") }
            FilledIconButton({ vm.player.togglePlay() }, Modifier.size(72.dp)) {
                Icon(if (np.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", Modifier.size(36.dp))
            }
            IconButton({ vm.player.next() }) { Icon(Icons.Default.SkipNext, "Next") }
            IconButton({ vm.player.cycleRepeat() }) {
                Icon(
                    if (np.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat",
                    tint = if (np.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton({ song?.let { vm.toggleFavorite(it) } }) {
                Icon(if (song?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite")
            }
            IconButton(onToggleLyrics, enabled = hasLyrics) {
                Icon(Icons.Default.Lyrics, "Lyrics", tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onSpeed) { Icon(Icons.Default.Speed, "Speed and pitch") }
            IconButton(onSleep) { Icon(Icons.Default.Timer, "Sleep timer") }
            IconButton({
                // No built-in equalizer: hand off to the system or any installed third-party EQ.
                vm.player.openSystemEqualizer { Toast.makeText(context, "No system equalizer found. Install a third-party EQ.", Toast.LENGTH_LONG).show() }
            }) { Icon(Icons.Default.Equalizer, "System equalizer") }
            IconButton({ song?.let { vm.open(Screen.TagEditor(listOf(it.id))) } }) { Icon(Icons.Default.Edit, "Edit tags") }
            IconButton({ song?.let { vm.open(Screen.Cutter(it.id)) } }) { Icon(Icons.Default.ContentCut, "Cut / ringtone") }
        }
    }
}
