package com.moise.laniakea.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.moise.laniakea.data.db.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val songId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
)

data class QueueItem(val songId: Long, val title: String, val artist: String, val uri: Uri)

/** UI-side facade over the MediaController. All calls must be made on the main thread. */
class PlayerController(private val context: Context, private val scope: CoroutineScope) {

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            publish()
            while (pending.isNotEmpty()) pending.removeFirst()(c)
            startPositionTicker()
        }, MoreExecutors.directExecutor())
        // Make sure the service is alive for background playback.
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending.addLast(block)
    }

    private fun startPositionTicker() {
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                controller?.let { c ->
                    if (c.isPlaying) _state.value = _state.value.copy(positionMs = c.currentPosition)
                }
                delay(250)
            }
        }
    }

    private fun publish() {
        val c = controller ?: return
        val md = c.mediaMetadata
        val queue = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            QueueItem(
                songId = item.mediaId.toLongOrNull() ?: -1,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                uri = item.localConfiguration?.uri ?: Uri.EMPTY,
            )
        }
        _state.value = NowPlaying(
            songId = c.currentMediaItem?.mediaId?.toLongOrNull(),
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            artworkUri = md.artworkUri,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = c.playbackParameters.speed,
            pitch = c.playbackParameters.pitch,
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
        )
    }

    // ---- Transport ------------------------------------------------------------------------

    fun playSongs(songs: List<SongEntity>, startIndex: Int = 0) = withController { c ->
        c.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0L)
        c.prepare(); c.play()
    }

    /** Play a file opened from another app ("Open with"); it is not part of the library. */
    fun playUri(uri: Uri, title: String) = withController { c ->
        c.setMediaItem(
            MediaItem.Builder().setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build(),
        )
        c.prepare(); c.play()
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController {
        if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem()
    }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ---- Dynamic queue --------------------------------------------------------------------

    fun playNext(song: SongEntity) = withController { c ->
        c.addMediaItem((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), song.toMediaItem())
        if (c.mediaItemCount == 1) { c.prepare(); c.play() }
    }
    fun addToQueue(songs: List<SongEntity>) = withController { c ->
        c.addMediaItems(songs.map { it.toMediaItem() })
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
    }
    fun removeFromQueue(index: Int) = withController { it.removeMediaItem(index) }
    fun moveInQueue(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun jumpTo(index: Int) = withController { it.seekToDefaultPosition(index); it.play() }
    fun clearQueue() = withController { it.clearMediaItems() }

    // ---- Speed / pitch / fades / sleep / EQ -----------------------------------------------

    fun setSpeedPitch(speed: Float, pitch: Float) = custom(PlaybackCommands.SET_SPEED_PITCH) {
        putFloat(PlaybackCommands.K_SPEED, speed); putFloat(PlaybackCommands.K_PITCH, pitch)
    }
    fun setFades(inMs: Int, outMs: Int) = custom(PlaybackCommands.SET_FADES) {
        putInt(PlaybackCommands.K_FADE_IN, inMs); putInt(PlaybackCommands.K_FADE_OUT, outMs)
    }
    fun setSkipSilence(on: Boolean) = custom(PlaybackCommands.SET_SKIP_SILENCE) {
        putBoolean(PlaybackCommands.K_ENABLED, on)
    }
    fun sleepAfter(minutes: Int, seconds: Int = 0) = custom(PlaybackCommands.SLEEP_DURATION) {
        putInt(PlaybackCommands.K_MINUTES, minutes); putInt(PlaybackCommands.K_SECONDS, seconds)
    }
    fun sleepAfterSongs(count: Int) = custom(PlaybackCommands.SLEEP_SONGS) {
        putInt(PlaybackCommands.K_SONGS, count)
    }
    fun cancelSleep() = custom(PlaybackCommands.SLEEP_CANCEL) {}

    fun querySleepState(onResult: (active: Boolean, endsAtElapsedMs: Long, songsLeft: Int) -> Unit) =
        withController { c ->
            val f = c.sendCustomCommand(SessionCommand(PlaybackCommands.GET_SLEEP_STATE, Bundle.EMPTY), Bundle.EMPTY)
            f.addListener({
                val b = f.get().extras
                onResult(
                    b.getBoolean(PlaybackCommands.K_SLEEP_ACTIVE),
                    b.getLong(PlaybackCommands.K_SLEEP_END),
                    b.getInt(PlaybackCommands.K_SLEEP_SONGS),
                )
            }, MoreExecutors.directExecutor())
        }

    /**
     * No built-in equalizer by design. Hands the audio session to the system EQ or whatever
     * third-party equalizer (Wavelet, Poweramp EQ, etc.) the user has installed.
     */
    fun openSystemEqualizer(onMissing: () -> Unit) = withController { c ->
        val f = c.sendCustomCommand(SessionCommand(PlaybackCommands.GET_AUDIO_SESSION, Bundle.EMPTY), Bundle.EMPTY)
        f.addListener({
            val session = f.get().extras.getInt(PlaybackCommands.K_AUDIO_SESSION)
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent) else onMissing()
        }, MoreExecutors.directExecutor())
    }

    private fun custom(action: String, fill: Bundle.() -> Unit) = withController { c ->
        c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle().apply(fill))
    }

    companion object {
        fun SongEntity.toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setAlbumArtist(albumArtist)
                    .setArtworkUri(artworkUri?.let(Uri::parse))
                    .setTrackNumber(trackNumber.takeIf { it > 0 })
                    .build(),
            )
            .build()
    }
}
