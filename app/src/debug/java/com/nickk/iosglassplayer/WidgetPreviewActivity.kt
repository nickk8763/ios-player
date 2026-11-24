package com.nickk.iosglassplayer

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nickk.iosglassplayer.media.PlaybackStatus
import com.nickk.iosglassplayer.media.TrackInfo
import com.nickk.iosglassplayer.widget.WidgetSnapshot

/**
 * Tela de comparação — somente build DEBUG.
 * Exibe (a) o LockScreenPlayerCard real e (b) o RemoteViews do widget inflado com apply(),
 * ambos com a mesma largura. Suporta o parâmetro width_dp via Intent extra:
 *   adb shell am start -n com.nickk.iosglassplayer/.WidgetPreviewActivity --ei width_dp 352
 */
class WidgetPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widthExtra = intent.getIntExtra("width_dp", 0)
        val targetWidth = if (widthExtra > 0) widthExtra else null
        setContent {
            PreviewScreen(targetWidthDp = targetWidth)
        }
    }
}

/** TrackInfo de exemplo — dados equivalentes ao layout estático (F1). */
private val SAMPLE_TRACK = TrackInfo(
    title = "De Janeiro A Janeiro",
    artist = "Roberta Campos, Nando Reis",
    album = "",
    artwork = null,
    status = PlaybackStatus.PLAYING,      // reproduzindo → ícone pause, waveform visível
    durationMs = 190_000L,                // 3:10 de duração
    positionMs = 8_000L,                  // 0:08 elapsed → ~3% de progresso
    positionUpdateElapsedMs = 0L,
    speed = 1f,
    packageName = "com.music.bitchord",
    appLabel = "BitChord",
    hasSession = true,
    canPlayPause = true,
    canSkipPrevious = true,
    canSkipNext = true,
    canSeek = true,
    customActionId = "favorite"
)

/** WidgetSnapshot equivalente ao SAMPLE_TRACK, com positionAnchorElapsedMs fixo. */
private val SAMPLE_SNAPSHOT = WidgetSnapshot(
    hasSession = true,
    title = "De Janeiro A Janeiro",
    artist = "Roberta Campos, Nando Reis",
    isPlaying = true,
    isAdvancing = false,
    artwork = null,
    durationMs = 190_000L,
    positionMs = 8_000L,
    positionAnchorElapsedMs = android.os.SystemClock.elapsedRealtime(),
    speed = 1f,
    canPlayPause = true,
    canSkipPrevious = true,
    canSkipNext = true,
    canFavorite = true,
    canOpenSource = true
)

@Composable
private fun PreviewScreen(targetWidthDp: Int?) {
    val displayWidthLabel = targetWidthDp?.let { "${it}dp" } ?: "352dp (fillMaxWidth)"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C10))
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Rótulo A
        Text(
            text = "A — LockScreenPlayerCard (Compose) [$displayWidthLabel]",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        // (a) Placeholder para LockScreenPlayerCard (omitido no build do widget)
        Box(
            modifier = if (targetWidthDp != null) Modifier.width(targetWidthDp.dp) else Modifier.fillMaxWidth()
        ) {
            Text(
                text = "LockScreenPlayerCard omitido no build focado em widget.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp)
            )
        }

        // Separador
        Box(modifier = Modifier.padding(vertical = 16.dp))

        // Rótulo B
        Text(
            text = "B — Widget RemoteViews (apply()) [$displayWidthLabel]",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        // (b) RemoteViews do widget inflado via apply()
        Box(
            modifier = if (targetWidthDp != null) Modifier.width(targetWidthDp.dp) else Modifier.fillMaxWidth()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    val container = FrameLayout(ctx)

                    val width = targetWidthDp?.toFloat() ?: 352f
                    val rv = com.nickk.iosglassplayer.widget.PlayerWidgetProvider.buildViews(ctx, SAMPLE_SNAPSHOT, width)
                    val inflated = rv.apply(ctx, container)
                    container.addView(inflated)
                    container
                }
            )
        }
    }
}
