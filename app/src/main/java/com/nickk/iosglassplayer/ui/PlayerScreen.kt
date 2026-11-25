package com.nickk.iosglassplayer.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nickk.iosglassplayer.R
import com.nickk.iosglassplayer.media.MediaSessionRepository

private val Ink = Color(0xFF08090D)
private val Milk = Color(0xFFF7F7FA)
private val Muted = Color(0xFFCBCDD4)

/**
 * PlayerScreen inspirado nos padrões fluidos de animação do iOS e open-swiftui-animations:
 * - Album artwork scaling responsivo ao playback (playing: 1.0f escala plena, paused: 0.85f escala retraída)
 * - Transição fluida de play/pause com escala elástica + fade
 * - Transição fluida de títulos e artistas com AnimatedContent
 * - Feedback táctil orgânico com mola amortecida Apple HIG (dampingRatio 0.72f, stiffness MediumLow)
 */
@Composable
fun PlayerScreen(hasAccess: Boolean) {
    val context = LocalContext.current
    val track by MediaSessionRepository.state.collectAsStateWithLifecycle()
    val artwork = track.artwork // local val so smart cast works (delegated `track` is not stable)
    val noSession = stringResource(R.string.no_session_toast)
    val openFailed = stringResource(R.string.open_app_failed)

    fun report(sent: Boolean) {
        if (!sent) Toast.makeText(context, noSession, Toast.LENGTH_SHORT).show()
    }

    // Escala da capa ao tocar / pausar (física Apple: 1.0f quando tocando, 0.85f recolhido quando pausado)
    val artworkScale by animateFloatAsState(
        targetValue = if (track.isPlaying) 1.0f else 0.86f,
        animationSpec = spring(
            dampingRatio = 0.76f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "album-art-scale"
    )

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (artwork != null) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = 0.54f
                    renderEffect = BlurEffect(68f, 68f, TileMode.Clamp)
                    scaleX = 1.18f
                    scaleY = 1.18f
                }
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF414A5D), Color(0xFF171923), Ink))))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x6606070B), Color(0x2206070B), Color(0xDD07080C)))))

        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 27.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.now_playing),
                color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp, letterSpacing = 3.2.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(24.dp))

            // Capa com transição de escala de mola Apple
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = artworkScale
                        scaleY = artworkScale
                    }
                    .shadow(30.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.26f), RoundedCornerShape(28.dp))
            ) {
                AnimatedContent(
                    targetState = artwork,
                    transitionSpec = {
                        (fadeIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)) +
                                scaleIn(initialScale = 0.94f, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)))
                            .togetherWith(
                                fadeOut(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)) +
                                        scaleOut(targetScale = 1.04f, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow))
                            )
                    },
                    label = "artwork-crossfade"
                ) { currentArt ->
                    if (currentArt != null) {
                        Image(
                            currentArt.asImageBitmap(), stringResource(R.string.album_artwork),
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Image(
                            painterResource(R.drawable.ic_music_note), null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Spacer(Modifier.height(25.dp))
            Column(Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = track.title.ifBlank { stringResource(R.string.nothing_playing) },
                    transitionSpec = {
                        (fadeIn(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) +
                                scaleIn(initialScale = 0.96f, animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)))
                            .togetherWith(
                                fadeOut(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                            )
                    },
                    label = "title-animation"
                ) { titleText ->
                    Text(
                        titleText,
                        color = Milk, fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(5.dp))
                AnimatedContent(
                    targetState = if (track.hasSession) track.artist else stringResource(R.string.start_music),
                    transitionSpec = {
                        fadeIn(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                            .togetherWith(fadeOut(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)))
                    },
                    label = "artist-animation"
                ) { artistText ->
                    Text(
                        artistText,
                        color = Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(19.dp))
            PlaybackProgress(track = track, onSeek = { report(MediaSessionRepository.seekTo(it)) })

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                PlayerControl(
                    R.drawable.ic_previous, stringResource(R.string.previous), 31.dp, primary = false,
                    enabled = track.hasSession && track.canSkipPrevious
                ) { report(MediaSessionRepository.skipToPrevious()) }
                PlayerControl(
                    if (track.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    stringResource(if (track.isPlaying) R.string.pause else R.string.play), 35.dp, primary = true,
                    enabled = track.hasSession && track.canPlayPause
                ) { report(MediaSessionRepository.togglePlayPause()) }
                PlayerControl(
                    R.drawable.ic_next, stringResource(R.string.next), 31.dp, primary = false,
                    enabled = track.hasSession && track.canSkipNext
                ) { report(MediaSessionRepository.skipToNext()) }
            }

            Spacer(Modifier.weight(1f))
            when {
                !hasAccess -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.connect_music), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text(stringResource(R.string.connect_music_body), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.17f), contentColor = Color.White)
                    ) { Text(stringResource(R.string.enable_media_access)) }
                }
                track.hasSession -> Text(
                    (track.appLabel ?: track.packageName.orEmpty()).uppercase(),
                    color = Color.White.copy(alpha = 0.52f), fontSize = 10.sp, letterSpacing = 1.3.sp, maxLines = 1,
                    modifier = Modifier.clickable(role = Role.Button) {
                        if (!MediaSessionRepository.openSessionApp(context)) {
                            Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
                        }
                    }.padding(8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlayerControl(
    @DrawableRes icon: Int,
    label: String,
    iconSize: Dp,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Curva de mola tátil Apple (dampingRatio 0.72f, stiffness MediumLow)
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "control-press"
    )
    Box(
        modifier = Modifier
            .size(if (primary) 76.dp else 58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .clip(CircleShape)
            .background(if (primary) Color.White.copy(alpha = 0.96f) else Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = if (primary) 0.85f else 0.19f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (fadeIn(spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.75f, animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)))
                    .togetherWith(
                        fadeOut(spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMedium)) +
                                scaleOut(targetScale = 0.75f, animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium))
                    )
            },
            label = "control-icon-switch"
        ) { currentIcon ->
            Icon(
                painterResource(currentIcon),
                contentDescription = null,
                tint = if (primary) Ink else Color.White,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
