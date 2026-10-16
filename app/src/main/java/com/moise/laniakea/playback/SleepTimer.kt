package com.moise.laniakea.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Sleep timer by precise duration OR by number of songs. */
class SleepTimer(
    private val player: FadingPlayer,
    private val onFinished: () -> Unit,
) : Player.Listener {

    data class State(
        val active: Boolean = false,
        val endsAtMs: Long = 0L,       // SystemClock.elapsedRealtime based; 0 when song-count mode
        val songsLeft: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable { finish() }

    init { player.addListener(this) }

    fun startDuration(minutes: Int, seconds: Int = 0) {
        cancel()
        val ms = (minutes * 60L + seconds) * 1000L
        if (ms <= 0) return
        handler.postDelayed(fire, ms)
        _state.value = State(true, android.os.SystemClock.elapsedRealtime() + ms, 0)
    }

    fun startSongs(count: Int) {
        cancel()
        if (count <= 0) return
        _state.value = State(true, 0L, count)
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        _state.value = State()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val s = _state.value
        if (!s.active || s.songsLeft <= 0) return
        // A song finished (auto transition) or was skipped; count either as consumed.
        val left = s.songsLeft - 1
        if (left <= 0) {
            // We already moved on to the next song; pause immediately and rewind to its start.
            player.pause()
            _state.value = State()
            onFinished()
        } else {
            _state.value = s.copy(songsLeft = left)
        }
    }

    private fun finish() {
        player.fadeOutThen(1500) { player.pause() }
        _state.value = State()
        onFinished()
    }

    fun release() {
        handler.removeCallbacks(fire)
        player.removeListener(this)
    }
}
