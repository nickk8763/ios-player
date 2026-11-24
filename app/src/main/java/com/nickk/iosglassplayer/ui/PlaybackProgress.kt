package com.nickk.iosglassplayer.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nickk.iosglassplayer.media.PlaybackStatus
import com.nickk.iosglassplayer.media.TrackInfo
import kotlinx.coroutines.delay

/**
 * Real progress: the live position is extrapolated from PlaybackState while playing and ticks
 * inside this composable only, so the rest of the screen does not recompose every 250 ms.
 * Seeking is enabled only when the session advertises ACTION_SEEK_TO and the duration is known.
 */
@Composable
fun PlaybackProgress(track: TrackInfo, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var positionMs by remember { mutableLongStateOf(0L) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(track.status, track.positionMs, track.positionUpdateElapsedMs, track.speed, track.durationMs) {
        while (true) {
            positionMs = track.positionAt(SystemClock.elapsedRealtime())
            if (track.status != PlaybackStatus.PLAYING) break
            delay(250L)
        }
    }

    val duration = track.durationMs
    val durationKnown = duration > 0L
    val seekable = track.hasSession && track.canSeek && durationKnown
    val fraction = dragFraction ?: if (durationKnown) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownMs = dragFraction?.let { (it * duration).toLong() } ?: positionMs

    val textColor = Color(0xFFCBCDD4).copy(alpha = 0.9f)

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(seekable, duration) {
                    if (!seekable) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { f -> onSeek((f * duration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null }
                    )
                }
                .pointerInput(seekable, duration) {
                    if (!seekable) return@pointerInput
                    detectTapGestures { onSeek(((it.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxWidth().height(if (dragFraction != null) 9.dp else 6.dp)) {
                val corner = CornerRadius(size.height / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.28f), size = size, cornerRadius = corner)
                drawRoundRect(Color.White.copy(alpha = 0.92f), size = Size(size.width * fraction, size.height), cornerRadius = corner)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownMs), color = textColor, fontSize = 12.sp)
            Text(
                if (durationKnown) "-${formatTime((duration - shownMs).coerceAtLeast(0L))}" else "--:--",
                color = textColor,
                fontSize = 12.sp
            )
        }
    }
}

internal fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
