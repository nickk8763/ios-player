package com.nickk.iosglassplayer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nickk.iosglassplayer.media.MediaSessionRepository
import com.nickk.iosglassplayer.ui.PlayerScreen

class MainActivity : ComponentActivity() {
    private var hasAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent { PlayerScreen(hasAccess = hasAccess) }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just come back from the Notification access settings screen.
        hasAccess = MediaSessionRepository.hasAccess(this)
        if (hasAccess) MediaSessionRepository.attach(this)
    }
}
