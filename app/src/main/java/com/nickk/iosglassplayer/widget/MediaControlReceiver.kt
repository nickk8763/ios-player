package com.nickk.iosglassplayer.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.nickk.iosglassplayer.R
import com.nickk.iosglassplayer.media.MediaSessionRepository

/** Forwards widget taps to the selected external media session. */
class MediaControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        PlayerWidgetProvider.setAppContext(context)
        // A widget tap can cold-start this process, so reattach and reload active sessions first.
        if (MediaSessionRepository.hasAccess(context)) MediaSessionRepository.attach(context)

        val sent = when (action) {
            ACTION_PREVIOUS -> MediaSessionRepository.skipToPrevious()
            ACTION_PLAY_PAUSE -> MediaSessionRepository.togglePlayPause()
            ACTION_NEXT -> MediaSessionRepository.skipToNext()
            ACTION_FAVORITE -> MediaSessionRepository.sendCustomAction()
            ACTION_OPEN_SOURCE -> MediaSessionRepository.openSessionApp(context)
            ACTION_SEEK -> {
                // Item D (W5): seek para uma fração 0.0–1.0 da duração total.
                // Extra EXTRA_SEEK_FRACTION = Float no intervalo [0.0, 1.0].
                // Converte para ms usando TrackInfo.durationMs do estado atual.
                val fraction = intent.getFloatExtra(EXTRA_SEEK_FRACTION, -1f)
                if (fraction < 0f || fraction > 1f) {
                    false
                } else {
                    val durationMs = MediaSessionRepository.state.value.durationMs
                    if (durationMs > 0L) {
                        val seekMs = (fraction * durationMs).toLong().coerceIn(0L, durationMs)
                        MediaSessionRepository.seekTo(seekMs)
                    } else {
                        false
                    }
                }
            }
            else -> return
        }

        if (!sent) {
            val message = if (action == ACTION_OPEN_SOURCE) {
                R.string.open_app_failed
            } else {
                R.string.no_session_toast
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_PREVIOUS = "com.nickk.iosglassplayer.action.PREVIOUS"
        const val ACTION_PLAY_PAUSE = "com.nickk.iosglassplayer.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.nickk.iosglassplayer.action.NEXT"
        const val ACTION_FAVORITE = "com.nickk.iosglassplayer.action.FAVORITE"
        const val ACTION_OPEN_SOURCE = "com.nickk.iosglassplayer.action.OPEN_SOURCE"
        /** Item D (W5): seek para fração da duração. Extra: EXTRA_SEEK_FRACTION (Float 0.0–1.0). */
        const val ACTION_SEEK = "com.nickk.iosglassplayer.action.SEEK"
        const val EXTRA_SEEK_FRACTION = "seek_fraction"
    }
}
