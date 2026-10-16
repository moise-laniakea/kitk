package com.moise.laniakea.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Custom MediaSession commands shared between the service and UI controller. */
object PlaybackCommands {
    const val SET_SPEED_PITCH = "moise.SET_SPEED_PITCH"
    const val SET_FADES = "moise.SET_FADES"
    const val SLEEP_DURATION = "moise.SLEEP_DURATION"
    const val SLEEP_SONGS = "moise.SLEEP_SONGS"
    const val SLEEP_CANCEL = "moise.SLEEP_CANCEL"
    const val SET_SKIP_SILENCE = "moise.SET_SKIP_SILENCE"
    const val GET_AUDIO_SESSION = "moise.GET_AUDIO_SESSION"
    const val GET_SLEEP_STATE = "moise.GET_SLEEP_STATE"

    val all: List<SessionCommand> = listOf(
        SET_SPEED_PITCH, SET_FADES, SLEEP_DURATION, SLEEP_SONGS, SLEEP_CANCEL,
        SET_SKIP_SILENCE, GET_AUDIO_SESSION, GET_SLEEP_STATE,
    ).map { SessionCommand(it, Bundle.EMPTY) }

    // Bundle keys
    const val K_SPEED = "speed"
    const val K_PITCH = "pitch"
    const val K_FADE_IN = "fade_in"
    const val K_FADE_OUT = "fade_out"
    const val K_MINUTES = "minutes"
    const val K_SECONDS = "seconds"
    const val K_SONGS = "songs"
    const val K_ENABLED = "enabled"
    const val K_AUDIO_SESSION = "audio_session"
    const val K_SLEEP_ACTIVE = "sleep_active"
    const val K_SLEEP_END = "sleep_end"
    const val K_SLEEP_SONGS = "sleep_songs"
}
