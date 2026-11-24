package com.nickk.iosglassplayer.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackInfoTest {
    private fun track(
        status: PlaybackStatus,
        position: Long = 10_000L,
        updatedAt: Long = 1_000L,
        speed: Float = 1f,
        duration: Long = 200_000L
    ) = TrackInfo(
        status = status,
        positionMs = position,
        positionUpdateElapsedMs = updatedAt,
        speed = speed,
        durationMs = duration,
        hasSession = true
    )

    @Test fun pausedPositionDoesNotMove() =
        assertEquals(10_000L, track(PlaybackStatus.PAUSED).positionAt(99_000L))

    @Test fun playingPositionExtrapolatesFromTheSnapshot() =
        assertEquals(15_000L, track(PlaybackStatus.PLAYING).positionAt(6_000L))

    @Test fun playingPositionRespectsPlaybackSpeed() =
        assertEquals(20_000L, track(PlaybackStatus.PLAYING, speed = 2f).positionAt(6_000L))

    @Test fun positionIsClampedToDuration() =
        assertEquals(200_000L, track(PlaybackStatus.PLAYING).positionAt(1_000_000L))

    @Test fun unknownDurationHasNoUpperClampButNeverGoesNegative() {
        val t = track(PlaybackStatus.PLAYING, duration = 0L)
        assertEquals(510_000L, t.positionAt(501_000L))
        assertEquals(0L, track(PlaybackStatus.PAUSED, position = -5L, duration = 0L).positionAt(0L))
    }

    @Test fun bufferingCountsAsPlayingForTheIconButDoesNotAdvanceTheClock() {
        val t = track(PlaybackStatus.BUFFERING)
        assertTrue(t.isPlaying)
        assertEquals(10_000L, t.positionAt(50_000L))
    }

    @Test fun missingUpdateTimestampDisablesExtrapolation() =
        assertEquals(10_000L, track(PlaybackStatus.PLAYING, updatedAt = 0L).positionAt(900_000L))

    @Test fun pausedAndStoppedAreNotPlaying() {
        assertFalse(track(PlaybackStatus.PAUSED).isPlaying)
        assertFalse(track(PlaybackStatus.STOPPED).isPlaying)
    }
}
