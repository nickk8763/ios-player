package com.nickk.iosglassplayer.ui.lockscreen

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object CardSpec {
    // Card geometry (iOS 26 Liquid Glass proportions)
    val CardHorizontalMargin: Dp = 16.dp
    val CardHeight: Dp = 144.dp
    val CardCornerRadius: Dp = 25.dp
    val CardHorizontalPadding: Dp = 22.dp
    val CardVerticalOffset: Dp = 40.dp

    // Title
    val TitleCenterY: Dp = 25.dp
    val TitleFontSize: TextUnit = 16.5.sp
    val TitleHorizontalPadding: Dp = 56.dp
    const val TitleAlpha: Float = 1.0f

    // Artist
    val ArtistCenterY: Dp = 46.dp
    val ArtistFontSize: TextUnit = 15.8.sp
    val ArtistHorizontalPadding: Dp = 56.dp
    const val ArtistAlpha: Float = 0.78f

    // Waveform (6 bars, iOS 26)
    val WaveformWidth: Dp = 21.dp
    val WaveformHeight: Dp = 16.dp
    val WaveformRightMargin: Dp = 24.dp
    val WaveformCenterY: Dp = 27.dp
    const val WaveformAlpha: Float = 0.95f

    // Progress
    val ProgressCenterY: Dp = 68.dp
    val ProgressHorizontalPadding: Dp = 22.dp
    val ProgressGap: Dp = 9.dp
    val TimeFontSize: TextUnit = 11.8.sp
    const val TimeAlpha: Float = 0.90f
    val BarHeight: Dp = 6.5.dp
    val BarDraggedHeight: Dp = 9.dp
    const val BarAnimationDurationMs: Int = 120
    const val BarTrackAlpha: Float = 0.30f
    const val BarFillAlpha: Float = 0.96f
    val BarTouchHeight: Dp = 32.dp

    // Controls (measured from iOS 26 Lock Screen player)
    val ControlsCenterY: Dp = 109.dp
    const val ControlFraction1: Float = 0.095f // 9.5% (Star)
    const val ControlFraction2: Float = 0.300f // 30.0% (Rewind)
    const val ControlFraction3: Float = 0.500f // 50.0% (Play/Pause)
    const val ControlFraction4: Float = 0.700f // 70.0% (Forward)
    const val ControlFraction5: Float = 0.905f // 90.5% (Output / AirPods)

    val StarIconSize: Dp = 24.dp
    val RewindWidth: Dp = 35.dp
    val RewindHeight: Dp = 19.dp
    val PauseWidth: Dp = 24.dp
    val PauseHeight: Dp = 30.dp
    val PlayWidth: Dp = 25.dp
    val PlayHeight: Dp = 29.dp
    val ForwardWidth: Dp = 35.dp
    val ForwardHeight: Dp = 19.dp
    val OutputIconSize: Dp = 22.dp

    val ControlTouchSize: Dp = 48.dp
    const val ControlPressedScale: Float = 0.90f
    const val ControlPressedAlpha: Float = 0.70f
    const val ControlDisabledAlpha: Float = 0.35f
    const val ControlNormalAlpha: Float = 1.0f

    // Glass V1
    const val GlassV1BackgroundAlpha: Float = 0.14f
    val GlassV1BorderWidth: Dp = 1.dp
    const val GlassV1BorderTopLeftAlpha: Float = 0.55f
    const val GlassV1BorderCenterAlpha: Float = 0.10f
    const val GlassV1BorderBottomRightAlpha: Float = 0.35f

    // Background
    const val ArtworkBackgroundScale: Float = 1.25f
    val ArtworkBlurRadius: Dp = 40.dp
    const val ArtworkBackgroundAlpha: Float = 0.90f
    const val VeilTopAlpha: Float = 0.15f
    const val VeilBottomAlpha: Float = 0.35f
}
