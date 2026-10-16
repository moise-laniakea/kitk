package com.moise.laniakea.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.moise.laniakea.App
import com.moise.laniakea.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground playback service.
 *
 * - Media3 ExoPlayer: gapless by design; extension renderers (FFmpeg) preferred when the AAR is present,
 *   which is how ALAC/APE/TAK/DSD/etc. get decoded.
 * - No equalizer inside the app: the audio session id is exposed so the system or any third-party EQ
 *   can attach (see ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL in the UI layer).
 * - Notification drawer controls come free from MediaSessionService + the default notification provider.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private lateinit var fading: FadingPlayer
    private lateinit var sleepTimer: SleepTimer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { stopIfIdle() }

    override fun onCreate() {
        super.onCreate()

        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        exo = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)      // auto-pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        fading = FadingPlayer(exo)
        sleepTimer = SleepTimer(fading) { /* UI polls state via GET_SLEEP_STATE */ }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, fading)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        // Apply persisted settings and keep them live.
        scope.launch {
            App.instance.settings.settings.collectLatest { s ->
                fading.fadeInMs = s.fadeInMs
                fading.fadeOutMs = s.fadeOutMs
                exo.playbackParameters = PlaybackParameters(s.speed, s.pitch)
                exo.skipSilenceEnabled = s.skipSilence
            }
        }

        // Battery saving: if paused/idle for N minutes, release the foreground service.
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(idleStop)
                if (!isPlaying) {
                    val min = App.instance.settings.settings.value.autoPauseMinutes
                    if (min > 0) handler.postDelayed(idleStop, min * 60_000L)
                }
            }
        })
    }

    private fun stopIfIdle() {
        if (!exo.isPlaying) {
            exo.stop()
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleStop)
        scope.cancel()
        sleepTimer.release()
        fading.cancelFades()
        session?.run { release(); session = null }
        exo.release()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val cmds = SessionCommands.Builder()
                .addSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.commands)
                .also { b -> PlaybackCommands.all.forEach { b.add(it) } }
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(cmds)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val out = Bundle()
            when (customCommand.customAction) {
                PlaybackCommands.SET_SPEED_PITCH -> {
                    val speed = args.getFloat(PlaybackCommands.K_SPEED, 1f)
                    val pitch = args.getFloat(PlaybackCommands.K_PITCH, 1f)
                    exo.playbackParameters = PlaybackParameters(speed, pitch)
                    App.instance.settings.update { it.speed(speed); it.pitch(pitch) }
                }
                PlaybackCommands.SET_FADES -> {
                    val fi = args.getInt(PlaybackCommands.K_FADE_IN, fading.fadeInMs)
                    val fo = args.getInt(PlaybackCommands.K_FADE_OUT, fading.fadeOutMs)
                    fading.fadeInMs = fi; fading.fadeOutMs = fo
                    App.instance.settings.update { it.fadeIn(fi); it.fadeOut(fo) }
                }
                PlaybackCommands.SET_SKIP_SILENCE -> {
                    val on = args.getBoolean(PlaybackCommands.K_ENABLED)
                    exo.skipSilenceEnabled = on
                    App.instance.settings.update { it.skipSilence(on) }
                }
                PlaybackCommands.SLEEP_DURATION -> sleepTimer.startDuration(
                    args.getInt(PlaybackCommands.K_MINUTES), args.getInt(PlaybackCommands.K_SECONDS),
                )
                PlaybackCommands.SLEEP_SONGS -> sleepTimer.startSongs(args.getInt(PlaybackCommands.K_SONGS))
                PlaybackCommands.SLEEP_CANCEL -> sleepTimer.cancel()
                PlaybackCommands.GET_AUDIO_SESSION ->
                    out.putInt(PlaybackCommands.K_AUDIO_SESSION, exo.audioSessionId)
                PlaybackCommands.GET_SLEEP_STATE -> sleepTimer.state.value.let {
                    out.putBoolean(PlaybackCommands.K_SLEEP_ACTIVE, it.active)
                    out.putLong(PlaybackCommands.K_SLEEP_END, it.endsAtMs)
                    out.putInt(PlaybackCommands.K_SLEEP_SONGS, it.songsLeft)
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, out))
        }
    }
}
