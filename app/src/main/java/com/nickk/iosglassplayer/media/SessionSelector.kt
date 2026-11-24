package com.nickk.iosglassplayer.media

data class SessionCandidate(val id: String, val status: PlaybackStatus, val hasMetadata: Boolean)

/**
 * Pure selection policy (no Android types) so it can be unit-tested.
 * Candidates are expected in the order MediaSessionManager returns them (system priority).
 *
 *  1. Something is playing/buffering -> keep the current session if it is one of them, else the first.
 *  2. Nothing playing -> stick with the current session (no flip-flopping between paused apps),
 *     unless it has no metadata while another session does.
 *  3. No current session -> first session with metadata, else first.
 */
object SessionSelector {
    fun select(candidates: List<SessionCandidate>, currentId: String?): String? {
        if (candidates.isEmpty()) return null

        val active = candidates.filter {
            it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.BUFFERING
        }
        if (active.isNotEmpty()) return (active.firstOrNull { it.id == currentId } ?: active.first()).id

        val current = candidates.firstOrNull { it.id == currentId }
        if (current != null && (current.hasMetadata || candidates.none { it.hasMetadata })) return current.id

        return (candidates.firstOrNull { it.hasMetadata } ?: candidates.first()).id
    }
}
