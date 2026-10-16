package com.moise.laniakea.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Wraps ExoPlayer so that play()/pause() ramp the volume instead of cutting abruptly.
 * Fade durations are adjustable at runtime. Gapless playback is native to ExoPlayer; the
 * fade only affects explicit play/pause actions, never track transitions.
 */
class FadingPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {

    @Volatile var fadeInMs: Int = 400
    @Volatile var fadeOutMs: Int = 400

    private val handler = Handler(Looper.getMainLooper())
    private var ramp: Runnable? = null
    private var pendingPause = false

    override fun play() {
        cancelRamp()
        pendingPause = false
        if (fadeInMs <= 0) {
            exo.volume = 1f
            super.play()
            return
        }
        exo.volume = if (exo.isPlaying) exo.volume else 0f
        super.play()
        rampTo(1f, fadeInMs, onDone = null)
    }

    override fun pause() {
        cancelRamp()
        if (fadeOutMs <= 0 || !exo.isPlaying) {
            super.pause()
            exo.volume = 1f
            return
        }
        pendingPause = true
        rampTo(0f, fadeOutMs) {
            if (pendingPause) {
                super.pause()
                exo.volume = 1f   // restore so the next play() starts from a known state
                pendingPause = false
            }
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    /** Stop-style transitions (e.g. sleep timer) can reuse the fade-out. */
    fun fadeOutThen(durationMs: Int, action: () -> Unit) {
        cancelRamp()
        rampTo(0f, durationMs.coerceAtLeast(1)) {
            action()
            exo.volume = 1f
        }
    }

    private fun rampTo(target: Float, durationMs: Int, onDone: (() -> Unit)?) {
        val start = exo.volume
        val t0 = android.os.SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val f = ((android.os.SystemClock.uptimeMillis() - t0).toFloat() / durationMs).coerceIn(0f, 1f)
                exo.volume = start + (target - start) * f
                if (f < 1f) handler.postDelayed(this, 16) else { ramp = null; onDone?.invoke() }
            }
        }
        ramp = r
        handler.post(r)
    }

    private fun cancelRamp() {
        ramp?.let { handler.removeCallbacks(it) }
        ramp = null
    }

    /** Not named release(): that would clash with Player.release(). */
    fun cancelFades() = cancelRamp()
}
