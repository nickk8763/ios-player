package com.nickk.iosglassplayer.media

import android.content.ComponentName
import android.service.notification.NotificationListenerService

/**
 * Exists only so the user can grant "Notification access" in Android settings, which is the
 * authorization MediaSessionManager.getActiveSessions() requires. All logic lives in
 * [MediaSessionRepository]; this service just keeps it attached while access is granted
 * (so the widget keeps working with the app UI closed). No root required.
 */
class MediaAccessService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaSessionRepository.attach(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaSessionRepository.detach()
        runCatching { requestRebind(ComponentName(this, MediaAccessService::class.java)) }
    }
}
