package com.nickk.iosglassplayer.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSelectorTest {
    private fun c(id: String, s: PlaybackStatus, md: Boolean = true) = SessionCandidate(id, s, md)

    @Test fun emptyListSelectsNothing() = assertNull(SessionSelector.select(emptyList(), "a"))

    @Test fun keepsCurrentWhenItIsStillPlaying() {
        val list = listOf(c("spotify", PlaybackStatus.PLAYING), c("ytm", PlaybackStatus.PLAYING))
        assertEquals("ytm", SessionSelector.select(list, "ytm"))
    }

    @Test fun switchesToTheSessionThatStartedPlaying() {
        val list = listOf(c("spotify", PlaybackStatus.PAUSED), c("ytm", PlaybackStatus.PLAYING))
        assertEquals("ytm", SessionSelector.select(list, "spotify"))
    }

    @Test fun staysOnPausedCurrentInsteadOfFlipFlopping() {
        val list = listOf(c("a", PlaybackStatus.PAUSED), c("b", PlaybackStatus.PAUSED))
        assertEquals("b", SessionSelector.select(list, "b"))
    }

    @Test fun leavesCurrentIfItHasNoMetadataButAnotherDoes() {
        val list = listOf(c("a", PlaybackStatus.STOPPED, md = false), c("b", PlaybackStatus.PAUSED))
        assertEquals("b", SessionSelector.select(list, "a"))
    }

    @Test fun fallsBackToFirstWithMetadataWhenCurrentDisappeared() {
        val list = listOf(c("a", PlaybackStatus.NONE, md = false), c("b", PlaybackStatus.PAUSED))
        assertEquals("b", SessionSelector.select(list, "gone"))
    }
}
