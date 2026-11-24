package com.nickk.iosglassplayer.media

import android.graphics.Bitmap

/** Immutable snapshot of the selected media session. Empty strings mean "not provided by the app". */
data class TrackInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artwork: Bitmap? = null,
    val status: PlaybackStatus = PlaybackStatus.NONE,
    /** <= 0 means unknown. */
    val durationMs: Long = 0L,
    /** Position as reported at [positionUpdateElapsedMs]; use [positionAt] for the live value. */
    val positionMs: Long = 0L,
    val positionUpdateElapsedMs: Long = 0L,
    val speed: Float = 1f,
    val packageName: String? = null,
    val appLabel: String? = null,
    val hasSession: Boolean = false,
    val canPlayPause: Boolean = false,
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSeek: Boolean = false,
    val customActionId: String? = null
) {
    /** Play/pause icon state: buffering counts as "playing" (the user pressed play). */
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING

    /**
     * PlaybackState.position is a snapshot taken at lastPositionUpdateTime, not a live value.
     * Extrapolate while playing, otherwise the progress bar would freeze.
     */
    fun positionAt(nowElapsedMs: Long): Long {
        // An app that never stamps lastPositionUpdateTime leaves it at 0; extrapolating from "boot"
        // would pin the bar at the end of the track, so only extrapolate from a real timestamp.
        val live = if (status == PlaybackStatus.PLAYING && positionUpdateElapsedMs > 0L) {
            positionMs + ((nowElapsedMs - positionUpdateElapsedMs) * speed).toLong()
        } else positionMs
        return if (durationMs > 0L) live.coerceIn(0L, durationMs) else live.coerceAtLeast(0L)
    }
}
