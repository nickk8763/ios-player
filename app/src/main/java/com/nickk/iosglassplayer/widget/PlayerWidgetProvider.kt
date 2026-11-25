package com.nickk.iosglassplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.nickk.iosglassplayer.MainActivity
import com.nickk.iosglassplayer.R
import com.nickk.iosglassplayer.media.MediaSessionRepository
import com.nickk.iosglassplayer.media.PlaybackStatus
import com.nickk.iosglassplayer.media.TrackInfo
import com.nickk.iosglassplayer.ui.lockscreen.CardSpec
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Snapshot required by the home-screen widget.
 * [equals] and [hashCode] omit routine position updates so that MediaSessionRepository's
 * distinctUntilChanged() does not trigger full RemoteViews inflation on every tick.
 */
data class WidgetSnapshot(
    val hasSession: Boolean = false,
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val isAdvancing: Boolean = false,
    val artwork: Bitmap? = null,
    val artworkKey: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val positionAnchorElapsedMs: Long = 0L,
    val speed: Float = 1f,
    val canPlayPause: Boolean = false,
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,
    val canFavorite: Boolean = false,
    val canOpenSource: Boolean = false
) {
    fun positionAt(nowElapsedMs: Long): Long {
        val live = if (isAdvancing && positionAnchorElapsedMs > 0L) {
            positionMs + ((nowElapsedMs - positionAnchorElapsedMs) * speed).toLong()
        } else {
            positionMs
        }
        return if (durationMs > 0L) live.coerceIn(0L, durationMs) else live.coerceAtLeast(0L)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WidgetSnapshot) return false
        if (hasSession != other.hasSession) return false
        if (trackId != other.trackId) return false
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (isPlaying != other.isPlaying) return false
        if (isAdvancing != other.isAdvancing) return false
        if (durationMs != other.durationMs) return false
        if (speed != other.speed) return false
        if (canPlayPause != other.canPlayPause) return false
        if (canSkipPrevious != other.canSkipPrevious) return false
        if (canSkipNext != other.canSkipNext) return false
        if (canFavorite != other.canFavorite) return false
        if (canOpenSource != other.canOpenSource) return false
        if (artworkKey != other.artworkKey) return false
        return true
    }

    override fun hashCode(): Int {
        var result = hasSession.hashCode()
        result = 31 * result + trackId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + isAdvancing.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + speed.hashCode()
        result = 31 * result + canPlayPause.hashCode()
        result = 31 * result + canSkipPrevious.hashCode()
        result = 31 * result + canSkipNext.hashCode()
        result = 31 * result + canFavorite.hashCode()
        result = 31 * result + canOpenSource.hashCode()
        result = 31 * result + artworkKey.hashCode()
        return result
    }

    companion object {
        fun from(track: TrackInfo): WidgetSnapshot {
            val now = SystemClock.elapsedRealtime()
            val artKey = if (track.artwork != null) {
                "${track.packageName}|${track.title}|${track.artist}|${track.album}|${track.artwork.width}x${track.artwork.height}"
            } else ""
            val tId = "${track.packageName}|${track.title}|${track.artist}"
            return WidgetSnapshot(
                hasSession = track.hasSession,
                trackId = tId,
                title = track.title,
                artist = track.artist,
                isPlaying = track.isPlaying,
                isAdvancing = track.status == PlaybackStatus.PLAYING && track.positionUpdateElapsedMs > 0L,
                artwork = track.artwork,
                artworkKey = artKey,
                durationMs = track.durationMs,
                positionMs = track.positionAt(now),
                positionAnchorElapsedMs = now,
                speed = track.speed,
                canPlayPause = track.hasSession && track.canPlayPause,
                canSkipPrevious = track.hasSession && track.canSkipPrevious,
                canSkipNext = track.hasSession && track.canSkipNext,
                canFavorite = track.hasSession && track.customActionId != null,
                canOpenSource = track.hasSession && !track.packageName.isNullOrBlank()
            )
        }
    }
}

/**
 * RemoteViews provider for iOS Glass Player.
 * Reescrito do zero na Etapa W4 com:
 *  - MODO_FUNDO = B (vidro 14% e borda em gradiente sobre o fundo da música recortado a 22dp)
 *  - Posicionamento percentual dos 5 botões via setViewLayoutMargin (9 / 29.5 / 50 / 70.5 / 91 %)
 *  - Atualização de tempo ao vivo SEM Chronometer via partiallyUpdateAppWidget (TICK_MS = 1000)
 */
class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (MediaSessionRepository.hasAccess(context)) MediaSessionRepository.attach(context)
        val snapshot = WidgetSnapshot.from(MediaSessionRepository.state.value)
        currentSnapshot = snapshot
        applicationContext = context.applicationContext
        needsFlipperInit = true  // updateAppWidget re-infla do zero: precisa de setDisplayedChild
        Log.i("WIDGETDIAG", "PATH=full REASON=system_onUpdate ids=${ids.contentToString()} actions=[buildViews, updateAppWidget]")
        for (id in ids) {
            val widthDp = getWidgetWidthDp(manager, id)
            manager.updateAppWidget(id, buildViews(context, snapshot, widthDp))
        }
        checkTicker()
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        val snapshot = currentSnapshot ?: WidgetSnapshot.from(MediaSessionRepository.state.value)
        val widthDp = getWidgetWidthDp(manager, appWidgetId)
        needsFlipperInit = true  // updateAppWidget re-infla do zero: precisa de setDisplayedChild
        Log.i("WIDGETDIAG", "PATH=full REASON=options_changed id=$appWidgetId actions=[buildViews, updateAppWidget]")
        manager.updateAppWidget(appWidgetId, buildViews(context, snapshot, widthDp))
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        applicationContext = context.applicationContext
        registerScreenReceiver(context.applicationContext)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        stopTicker()
        unregisterScreenReceiver(context.applicationContext)
        textSharpRunnable?.let { mainHandler.removeCallbacks(it) }
        textSharpRunnable = null
        playMidRunnable?.let { mainHandler.removeCallbacks(it) }
        playMidRunnable = null
        playSharpRunnable?.let { mainHandler.removeCallbacks(it) }
        playSharpRunnable = null
        isTextTransitioning = false
        currentActiveTitleBmp = null
        currentActiveArtistBmp = null
        cachedBackdrop = null
        cachedGlassOverlay = null
        cachedArtworkSource = null
        cachedSharpPlay = null
        cachedMidBlurPlay = null
        cachedBlurPlay = null
        cachedSharpPause = null
        cachedMidBlurPause = null
        cachedBlurPause = null
    }

    companion object {
        private const val TAG = "PlayerWidgetProvider"
        const val DEBUG_DIAG: Boolean = true

        /**
         * MODO_FUNDO:
         *   "A" = vidro puro (preenchimento 14% branco + borda em gradiente, mostrando o papel de parede)
         *   "B" = vidro sobre a cor da música (camada derivada da capa + véu, recortada a 22dp, com o vidro e a borda POR CIMA)
         */
        const val MODO_FUNDO: String = "A"

        const val TICK_MS: Long = 1000L
        private const val PROGRESS_MAX = 1000

        private const val BACKDROP_WIDTH = 352
        private const val BACKDROP_HEIGHT = 144

        private var applicationContext: Context? = null
        private var currentSnapshot: WidgetSnapshot? = null

        private var cachedArtworkSource: Bitmap? = null
        private var cachedBackdrop: Bitmap? = null
        @Volatile private var cachedGlassOverlay: Pair<Float, Bitmap>? = null

        /**
         * W6: Plano B de fonte — Typeface Inter carregado de assets uma vez.
         * O launcher OneUI substitui android:fontFamily de apps de terceiros (confirmado empiricamente).
         * Renderizar título/artista como bitmap garante Inter independente do sistema.
         */
        @Volatile private var typefaceSemiBold: Typeface? = null
        @Volatile private var typefaceRegular: Typeface? = null
        @Volatile private var cachedTitleBitmap: Pair<String, Bitmap>? = null  // (text, bitmap)
        @Volatile private var cachedArtistBitmap: Pair<String, Bitmap>? = null

        /**
         * Item A/B (W5): rastreia qual filho do ViewFlipper está visível atualmente.
         * 0 = filho A (índice 0), 1 = filho B (índice 1).
         * Título/Artista alternam A/B; Play/Pause: 0=play, 1=pause.
         *
         * W6 FIX LOOP: buildViews() NUNCA chama setDisplayedChild nos flippers de texto.
         * A alternância (com animação) é feita SOMENTE via applyTextFlip() com partiallyUpdateAppWidget.
         * buildViews() apenas escreve o texto no filho já visível (textFlipperChild).
         */
        private var backdropFlipperChild: Int = 0  // 0=A, 1=B
        private const val PREFS_NAME = "widget_state_prefs"
        private const val KEY_LAST_PLAY_CHILD = "last_play_child"
        private const val KEY_LAST_TITLE = "last_title"
        private const val KEY_LAST_ARTIST = "last_artist"
        private const val KEY_TEXT_FLIPPER_CHILD = "text_flipper_child"

        private var cachedTextFlipperChild: Int = -1
        private var textFlipperChild: Int
            get() {
                if (cachedTextFlipperChild < 0) {
                    val ctx = applicationContext
                    cachedTextFlipperChild = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.getInt(KEY_TEXT_FLIPPER_CHILD, 0) ?: 0
                }
                return cachedTextFlipperChild
            }
            set(value) {
                cachedTextFlipperChild = value
                val ctx = applicationContext
                ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putInt(KEY_TEXT_FLIPPER_CHILD, value)
                    ?.apply()
            }

        private var cachedLastTitle: String? = null
        private var lastTitle: String
            get() {
                if (cachedLastTitle == null) {
                    val ctx = applicationContext
                    cachedLastTitle = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.getString(KEY_LAST_TITLE, "") ?: ""
                }
                return cachedLastTitle ?: ""
            }
            set(value) {
                cachedLastTitle = value
                val ctx = applicationContext
                ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putString(KEY_LAST_TITLE, value)
                    ?.apply()
            }

        private var cachedLastArtist: String? = null
        private var lastArtist: String
            get() {
                if (cachedLastArtist == null) {
                    val ctx = applicationContext
                    cachedLastArtist = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.getString(KEY_LAST_ARTIST, "") ?: ""
                }
                return cachedLastArtist ?: ""
            }
            set(value) {
                cachedLastArtist = value
                val ctx = applicationContext
                ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putString(KEY_LAST_ARTIST, value)
                    ?.apply()
            }

        private var cachedLastPlayChild: Int = -1
        // Flag: força setDisplayedChild no próximo buildViews (cold-start após re-inflate).
        // Setado por onUpdate/onAppWidgetOptionsChanged antes de updateAppWidget().
        var needsFlipperInit: Boolean = true

        // Estado em memória aplicado no widget (evita re-envio de animações)
        private var lastAppliedTrackId: String? = null
        private var lastAppliedIsPlaying: Boolean? = null
        private var lastAppliedHasSession: Boolean? = null

        private var lastPlayChild: Int
            get() {
                if (cachedLastPlayChild < 0) {
                    val ctx = applicationContext
                    if (ctx != null) {
                        cachedLastPlayChild = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .getInt(KEY_LAST_PLAY_CHILD, -1)
                    }
                }
                return cachedLastPlayChild
            }
            set(value) {
                cachedLastPlayChild = value
                val ctx = applicationContext
                if (ctx != null) {
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_LAST_PLAY_CHILD, value)
                        .apply()
                }
            }

        fun setAppContext(context: Context) {
            applicationContext = context.applicationContext
        }

        // Item 3: estado de transição ótica de desfoque (blur 16dp -> 0dp)
        @Volatile private var isTextTransitioning: Boolean = false
        @Volatile private var currentActiveTitleBmp: Bitmap? = null
        @Volatile private var currentActiveArtistBmp: Bitmap? = null
        private var textSharpRunnable: Runnable? = null
        private var lastTextTransitionTime: Long = 0L

        // Cache de ícones de controle (Play/Pause) sharp (0dp), mid-blur (4dp) e full-blur (10dp)
        // Referência: LockScreenPlayerCard.kt:705-764 & MotionSpec.IconTransitionBlur (10.dp)
        @Volatile private var cachedSharpPlay: Bitmap? = null
        @Volatile private var cachedMidBlurPlay: Bitmap? = null
        @Volatile private var cachedBlurPlay: Bitmap? = null
        @Volatile private var cachedSharpPause: Bitmap? = null
        @Volatile private var cachedMidBlurPause: Bitmap? = null
        @Volatile private var cachedBlurPause: Bitmap? = null
        private var playMidRunnable: Runnable? = null
        private var playSharpRunnable: Runnable? = null

        private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val mainHandler = Handler(Looper.getMainLooper())

        private var isTickerRunning = false
        private var tickerRunnable: Runnable? = null

        private var isScreenReceiverRegistered = false
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> checkTicker()
                    Intent.ACTION_SCREEN_OFF -> stopTicker()
                }
            }
        }

        fun refreshAll(context: Context, snapshot: WidgetSnapshot) {
            applicationContext = context.applicationContext
            currentSnapshot = snapshot
            registerScreenReceiver(context.applicationContext)

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
            if (ids.isEmpty()) {
                stopTicker()
                return
            }

            // W6: Detectar mudança de título/artista ANTES do update completo.
            // Se mudou → applyTextFlip() via partiallyUpdateAppWidget (anima o flipper),
            // depois applyFullUpdate() atualiza o resto sem reanimar o flipper.
            // Correção de reversão/instabilidade: ignora estado vazio temporário emitido durante o salto de faixa.
            val newTitleText = if (snapshot.hasSession) {
                if (snapshot.title.isNotBlank()) {
                    snapshot.title
                } else if (lastTitle.isNotBlank() && lastTitle != context.getString(R.string.nothing_playing)) {
                    lastTitle
                } else {
                    context.getString(R.string.nothing_playing)
                }
            } else {
                context.getString(R.string.nothing_playing)
            }

            val newArtistText = if (snapshot.hasSession) {
                if (snapshot.artist.isNotBlank()) {
                    snapshot.artist
                } else if (lastArtist.isNotBlank() && lastArtist != context.getString(R.string.start_music)) {
                    lastArtist
                } else {
                    context.getString(R.string.start_music)
                }
            } else {
                context.getString(R.string.start_music)
            }

            val isInitialTextLoad = lastTitle.isEmpty() && lastArtist.isEmpty()
            val titleChanged = newTitleText != lastTitle
            val artistChanged = newArtistText != lastArtist

            if (titleChanged || artistChanged) {
                val widthDp = run {
                    val id = ids.firstOrNull() ?: ids[0]
                    getWidgetWidthDp(manager, id)
                }
                lastTitle = newTitleText
                lastArtist = newArtistText

                textSharpRunnable?.let { mainHandler.removeCallbacks(it) }

                if (!isInitialTextLoad) {
                    // Transição ótica Apple SwiftUI: Blur dissolve suave (blur 4dp -> sharp 0dp)
                    // Simula a transição .transition(.blurFade) de open-swiftui-animations
                    isTextTransitioning = true
                    val blurTitleBmp = renderTitleBitmap(context, newTitleText, widthDp, blurRadiusDp = 3.5f)
                    val blurArtistBmp = renderArtistBitmap(context, newArtistText, widthDp, blurRadiusDp = 3.0f)
                    currentActiveTitleBmp = blurTitleBmp
                    currentActiveArtistBmp = blurArtistBmp

                    val blurViews = RemoteViews(context.packageName, R.layout.widget_player)
                    blurViews.setImageViewBitmap(R.id.widget_title, blurTitleBmp)
                    blurViews.setImageViewBitmap(R.id.widget_artist, blurArtistBmp)
                    for (id in ids) { manager.partiallyUpdateAppWidget(id, blurViews) }

                    // Em 100ms, foca perfeitamente no sharp bitmap
                    textSharpRunnable = Runnable {
                        val sharpTitle = renderTitleBitmap(context, newTitleText, widthDp, blurRadiusDp = 0f)
                        val sharpArtist = renderArtistBitmap(context, newArtistText, widthDp, blurRadiusDp = 0f)
                        currentActiveTitleBmp = sharpTitle
                        currentActiveArtistBmp = sharpArtist
                        isTextTransitioning = false

                        val sharpViews = RemoteViews(context.packageName, R.layout.widget_player)
                        sharpViews.setImageViewBitmap(R.id.widget_title, sharpTitle)
                        sharpViews.setImageViewBitmap(R.id.widget_artist, sharpArtist)
                        for (id in ids) { manager.partiallyUpdateAppWidget(id, sharpViews) }
                        textSharpRunnable = null
                    }
                    mainHandler.postDelayed(textSharpRunnable!!, 100L)
                } else {
                    val sharpTitleBmp = renderTitleBitmap(context, newTitleText, widthDp, blurRadiusDp = 0f)
                    val sharpArtistBmp = renderArtistBitmap(context, newArtistText, widthDp, blurRadiusDp = 0f)
                    currentActiveTitleBmp = sharpTitleBmp
                    currentActiveArtistBmp = sharpArtistBmp
                    isTextTransitioning = false

                    val textViews = RemoteViews(context.packageName, R.layout.widget_player)
                    textViews.setImageViewBitmap(R.id.widget_title, sharpTitleBmp)
                    textViews.setImageViewBitmap(R.id.widget_artist, sharpArtistBmp)
                    for (id in ids) { manager.partiallyUpdateAppWidget(id, textViews) }
                }
            }

            // Atualiza ícone Play/Pause com transição ótica fluida (blur 3dp -> 1.2dp -> 0dp)
            // Inspirado nos padrões Apple SwiftUI transitions (blur fade micro-interaction)
            val playChild = if (snapshot.isPlaying) 1 else 0
            val playChanged = lastPlayChild >= 0 && playChild != lastPlayChild
            if (playChanged || lastPlayChild < 0) {
                lastPlayChild = playChild
                playMidRunnable?.let { mainHandler.removeCallbacks(it) }
                playSharpRunnable?.let { mainHandler.removeCallbacks(it) }
                playMidRunnable = null
                playSharpRunnable = null

                if (playChanged) {
                    // Passo 1: Inicia com blur ótico (fade + blur rápido 3.0dp)
                    val blurBmp = if (snapshot.isPlaying) getBlurPauseBitmap(context) else getBlurPlayBitmap(context)
                    val blurViews = RemoteViews(context.packageName, R.layout.widget_player)
                    blurViews.setImageViewBitmap(R.id.widget_play, blurBmp)
                    for (id in ids) { manager.partiallyUpdateAppWidget(id, blurViews) }

                    // Passo 2: Após 50ms, refina para mid-blur (1.2dp)
                    val midRunnable = Runnable {
                        val midBmp = if (snapshot.isPlaying) getMidBlurPauseBitmap(context) else getMidBlurPlayBitmap(context)
                        val midViews = RemoteViews(context.packageName, R.layout.widget_player)
                        midViews.setImageViewBitmap(R.id.widget_play, midBmp)
                        for (id in ids) { manager.partiallyUpdateAppWidget(id, midViews) }
                    }
                    playMidRunnable = midRunnable
                    mainHandler.postDelayed(midRunnable, 50L)

                    // Passo 3: Após 120ms total, foca perfeitamente no sharp (0dp)
                    val sharpRunnable = Runnable {
                        val sharpBmp = if (snapshot.isPlaying) getSharpPauseBitmap(context) else getSharpPlayBitmap(context)
                        val sharpViews = RemoteViews(context.packageName, R.layout.widget_player)
                        sharpViews.setImageViewBitmap(R.id.widget_play, sharpBmp)
                        for (id in ids) { manager.partiallyUpdateAppWidget(id, sharpViews) }
                        playSharpRunnable = null
                        playMidRunnable = null
                    }
                    playSharpRunnable = sharpRunnable
                    mainHandler.postDelayed(sharpRunnable, 120L)
                } else {
                    val playBmp = if (snapshot.isPlaying) getSharpPauseBitmap(context) else getSharpPlayBitmap(context)
                    val playViews = RemoteViews(context.packageName, R.layout.widget_player)
                    playViews.setImageViewBitmap(R.id.widget_play, playBmp)
                    for (id in ids) { manager.partiallyUpdateAppWidget(id, playViews) }
                }
            }

            // Geração do backdrop fora da main thread se necessário
            if (MODO_FUNDO == "B") {
                val source = snapshot.artwork
                if (source != null && source !== cachedArtworkSource) {
                    providerScope.launch {
                        val backdrop = renderBackdrop(source)
                        val oldArtwork = cachedArtworkSource
                        cachedArtworkSource = source
                        // Item B (W5): crossfade do backdrop ao trocar de música.
                        // Carrega o novo backdrop no filho OCULTO e alterna o ViewFlipper.
                        if (oldArtwork != null) {
                            // Já havia um backdrop: faz crossfade
                            val nextChild = 1 - backdropFlipperChild
                            val nextBackdropId = if (nextChild == 0) R.id.widget_backdrop_a else R.id.widget_backdrop_b
                            // O novo backdrop vai para o filho oculto; depois alternamos
                            cachedBackdrop = backdrop
                            withContext(Dispatchers.Main) {
                                val crossfadeViews = RemoteViews(context.packageName, R.layout.widget_player)
                                crossfadeViews.setImageViewBitmap(nextBackdropId, backdrop)
                                crossfadeViews.setInt(R.id.widget_backdrop_flipper, "setDisplayedChild", nextChild)
                                crossfadeViews.setViewVisibility(R.id.widget_backdrop_flipper, View.VISIBLE)
                                for (id in ids) { manager.partiallyUpdateAppWidget(id, crossfadeViews) }
                                backdropFlipperChild = nextChild
                                applyFullUpdate(context, manager, ids, snapshot)
                                lastAppliedTrackId = snapshot.trackId
                                lastAppliedIsPlaying = snapshot.isPlaying
                                lastAppliedHasSession = snapshot.hasSession
                                checkTicker()
                            }
                        } else {
                            // Primeira vez: carrega sem crossfade
                            cachedBackdrop = backdrop
                            withContext(Dispatchers.Main) {
                                applyFullUpdate(context, manager, ids, snapshot)
                                lastAppliedTrackId = snapshot.trackId
                                lastAppliedIsPlaying = snapshot.isPlaying
                                lastAppliedHasSession = snapshot.hasSession
                                checkTicker()
                            }
                        }
                    }
                    return
                } else if (source == null && cachedArtworkSource != null) {
                    cachedArtworkSource = null
                    cachedBackdrop = null
                }
            }

            if (!playChanged) {
                applyFullUpdate(context, manager, ids, snapshot)
            } else {
                // Durante a transição do play/pause (220ms), atualiza apenas a waveform
                // sem enviar a hierarquia inteira de botões para não sobrescrever o ViewFlipper no OneUI.
                val waveViews = RemoteViews(context.packageName, R.layout.widget_player)
                if (snapshot.hasSession) {
                    if (snapshot.isPlaying) {
                        waveViews.setViewVisibility(R.id.widget_waveform_flipper, View.VISIBLE)
                        waveViews.setViewVisibility(R.id.widget_waveform, View.GONE)
                    } else {
                        waveViews.setViewVisibility(R.id.widget_waveform_flipper, View.GONE)
                        waveViews.setViewVisibility(R.id.widget_waveform, View.VISIBLE)
                        waveViews.setInt(R.id.widget_waveform, "setImageAlpha", 242)
                    }
                }
                for (id in ids) { manager.partiallyUpdateAppWidget(id, waveViews) }
            }
            lastAppliedTrackId = snapshot.trackId
            lastAppliedIsPlaying = snapshot.isPlaying
            lastAppliedHasSession = snapshot.hasSession
            checkTicker()
        }

        private fun applyFullUpdate(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            snapshot: WidgetSnapshot
        ) {
            // Se o botão de reprodução estiver no meio da transição de blur (220ms),
            // suprime o full update para não resetar ou interromper a animação no launcher.
            if (playSharpRunnable != null) return

            Log.i("WIDGETDIAG", "PATH=full REASON=applyFullUpdate ids=${ids.contentToString()} isPlaying=${snapshot.isPlaying} actions=[buildViews(backdrop_setDisplayedChild=$backdropFlipperChild, title_bitmaps, play_bitmaps)]")
            for (id in ids) {
                val widthDp = getWidgetWidthDp(manager, id)
                val views = buildViews(context, snapshot, widthDp)
                manager.partiallyUpdateAppWidget(id, views)
            }
        }

        fun buildViews(context: Context, snapshot: WidgetSnapshot, widthDp: Float): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player)

            // Clip perimétrico dos cantos arredondados a 25dp (CardSpec.CardCornerRadius)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                views.setBoolean(R.id.widget_root, "setClipToOutline", true)
            }

            // ── 0. Clear Liquid Glass overlay (bitmap) ───────────────────────────────
            // MODO A: wallpaper visível através do vidro translúcido (sem backdrop opaco).
            // O bitmap de vidro é desenhado sobre o wallpaper pelo sistema de composição.
            val glassBmp = renderGlassOverlay(context, widthDp)
            views.setImageViewBitmap(R.id.widget_glass_overlay, glassBmp)

            // ── 1. Fundo do cartão (MODO_FUNDO A/B) ──────────────────────────────────────
            // Item B (W5): backdrop usa ViewFlipper com 2 ImageViews para crossfade.
            // O filho atual (backdropFlipperChild) já está visível; atualiza-se diretamente sem animar.
            // A troca de backdrop (crossfade) ocorre em refreshAll quando a artwork muda.
            if (MODO_FUNDO == "B") {
                val backdrop = cachedBackdrop ?: renderFallbackBackdrop()
                // Carrega o backdrop no filho ATUAL (sem animar na primeira exibição)
                val currentBackdropId = if (backdropFlipperChild == 0) R.id.widget_backdrop_a else R.id.widget_backdrop_b
                views.setImageViewBitmap(currentBackdropId, backdrop)
                if (needsFlipperInit) {
                    views.setInt(R.id.widget_backdrop_flipper, "setDisplayedChild", backdropFlipperChild)
                }
                views.setViewVisibility(R.id.widget_backdrop_flipper, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_backdrop_flipper, View.GONE)
            }

            // ── 2. Título e Artista — ViewFlipper A/B ────────────────────────────────────
            // Referência: LockScreenPlayerCard.kt:120 — track.title.ifBlank { nothing_playing }
            // W6 FIX LOOP: buildViews() NUNCA chama setDisplayedChild nos flippers de texto.
            // A alternância com animação ocorre SOMENTE via applyTextFlip() com partiallyUpdateAppWidget.
            // buildViews() escreve APENAS no filho JÁ VISÍVEL (textFlipperChild), sem mudar o filho exibido.
            // Motivo: quando updateAppWidget() completo re-infla o layout, qualquer setDisplayedChild
            // faz o ViewFlipper tratar como transição inicial e anima — mesmo sem mudança de música.
            val titleText = if (snapshot.hasSession && snapshot.title.isNotBlank()) {
                snapshot.title
            } else {
                context.getString(R.string.nothing_playing)
            }
            val artistText = if (snapshot.hasSession) snapshot.artist else context.getString(R.string.start_music)

            // W6 Plano B — bitmap de texto via Canvas/Typeface Inter (launcher OneUI substitui fontFamily)
            // Se houver transição de texto em andamento, NÃO sobrescrevemos os ImageViews aqui
            // para evitar interrupções de layout (requestLayout) no meio da animação de subida do ViewFlipper.
            val titleBmp = currentActiveTitleBmp ?: renderTitleBitmap(context, titleText, widthDp, blurRadiusDp = 0f)
            val artistBmp = currentActiveArtistBmp ?: renderArtistBitmap(context, artistText, widthDp, blurRadiusDp = 0f)

            views.setImageViewBitmap(R.id.widget_title, titleBmp)
            views.setImageViewBitmap(R.id.widget_artist, artistBmp)
            // Acessibilidade: contentDescription para leitores de tela (bitmap não carrega label automático)
            views.setContentDescription(R.id.widget_title, titleText)
            views.setContentDescription(R.id.widget_artist, artistText)
            // NÃO chamar setDisplayedChild aqui — evita animação espúria a cada updateAppWidget() completo.

            // ── 3. Waveform ───────────────────────────────────────────────────────────────
            // Referência: LockScreenPlayerCard.kt:234-235 & 351-493
            // alpha=0.95 (242). Visível se hasSession:
            // - isPlaying: widget_waveform_flipper dançando (250ms flip entre 6 quadros)
            // - paused/buffering: widget_waveform em repouso (ic_waveform)
            if (snapshot.hasSession) {
                if (snapshot.isPlaying) {
                    views.setViewVisibility(R.id.widget_waveform_flipper, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_waveform, View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_waveform_flipper, View.GONE)
                    views.setViewVisibility(R.id.widget_waveform, View.VISIBLE)
                    views.setInt(R.id.widget_waveform, "setImageAlpha", 242)
                }
            } else {
                views.setViewVisibility(R.id.widget_waveform_flipper, View.GONE)
                views.setViewVisibility(R.id.widget_waveform, View.GONE)
            }

            // ── 4. Progresso e tempos ─────────────────────────────────────────────────────
            val now = SystemClock.elapsedRealtime()
            val position = snapshot.positionAt(now)
            val duration = snapshot.durationMs

            views.setTextViewText(R.id.widget_elapsed, formatTime(position))
            if (duration > 0L) {
                val remaining = (duration - position).coerceAtLeast(0L)
                views.setTextViewText(R.id.widget_remaining, "\u2212" + formatTime(remaining))
                val progress = ((position.toDouble() / duration.toDouble()) * PROGRESS_MAX)
                    .roundToInt()
                    .coerceIn(0, PROGRESS_MAX)
                views.setImageViewBitmap(R.id.widget_progress, renderProgressBitmap(context, widthDp, progress))
            } else {
                views.setTextViewText(R.id.widget_remaining, "--:--")
                views.setImageViewBitmap(R.id.widget_progress, renderProgressBitmap(context, widthDp, 0))
            }

            // ── 5. Botões — posicionamento percentual ─────────────────────────────────────
            // Referência: CardSpec.kt:50-54 (9%, 29.5%, 50%, 70.5%, 91%)
            positionControlButtons(views, widthDp)

            // ── 6. Play/Pause — ViewFlipper (Item A W5) ──────────────────────────────────
            // Filho 0 = ic_play (pausado/sem sessão), Filho 1 = ic_pause (tocando)
            // Referência: LockScreenPlayerCard.kt:289-301
            //
            // REGRA ANTI-PISCAR:
            // setDisplayedChild SOMENTE em cold-start (lastPlayChild < 0).
            // Após inicializado, NÃO inclui setDisplayedChild no RemoteViews — mesmo via
            // partiallyUpdateAppWidget o OneUI re-aplica a inAnimation se o campo estiver presente,
            // causando o piscar/flash a cada applyFullUpdate (1×/seg pelo ticker de posição).
            // A troca do filho é feita EXCLUSIVAMENTE pelo bloco playChanged abaixo.
            val playChild = if (snapshot.isPlaying) 1 else 0
            if (lastPlayChild < 0) {
                lastPlayChild = playChild
            }
            if (needsFlipperInit) {
                needsFlipperInit = false
            }
            views.setImageViewBitmap(
                R.id.widget_play,
                if (snapshot.isPlaying) getSharpPauseBitmap(context) else getSharpPlayBitmap(context)
            )
            views.setContentDescription(
                R.id.widget_play,
                context.getString(if (snapshot.isPlaying) R.string.pause else R.string.play)
            )

            // ── 7. Estados de habilitação ─────────────────────────────────────────────────
            updateButton(views, R.id.widget_star, snapshot.canFavorite)
            updateButton(views, R.id.widget_previous, snapshot.canSkipPrevious)
            updateButton(views, R.id.widget_play, snapshot.canPlayPause)
            updateButton(views, R.id.widget_next, snapshot.canSkipNext)
            updateButton(views, R.id.widget_open_source, snapshot.canOpenSource)

            // ── 8. Seek overlay — toque para pular (Item D W5) ───────────────────────────
            // 5 regiões invisíveis na barra de progresso, cada uma com ACTION_SEEK e fração central.
            // Frações: 0.10, 0.30, 0.50, 0.70, 0.90
            // DECLARAÇÃO: arrastar a barra é IMPOSSÍVEL em RemoteViews — sem MotionEvent.ACTION_MOVE.
            val seekIds = intArrayOf(
                R.id.widget_seek_0, R.id.widget_seek_1, R.id.widget_seek_2,
                R.id.widget_seek_3, R.id.widget_seek_4
            )
            val seekFractions = floatArrayOf(0.10f, 0.30f, 0.50f, 0.70f, 0.90f)
            val seekRequestCodes = intArrayOf(
                REQUEST_SEEK_0, REQUEST_SEEK_1, REQUEST_SEEK_2, REQUEST_SEEK_3, REQUEST_SEEK_4
            )
            val seekLabels = arrayOf("Seek 10%", "Seek 30%", "Seek 50%", "Seek 70%", "Seek 90%")
            for (i in seekIds.indices) {
                views.setOnClickPendingIntent(
                    seekIds[i],
                    seekIntent(context, seekFractions[i], seekRequestCodes[i])
                )
                // Habilitar seek apenas se houver sessão e duração conhecida
                views.setBoolean(seekIds[i], "setEnabled", snapshot.hasSession && snapshot.durationMs > 0L)
                views.setContentDescription(seekIds[i], seekLabels[i])
            }

            // ── 9. PendingIntents dos controles ──────────────────────────────────────────
            val openApp = PendingIntent.getActivity(
                context,
                REQUEST_OPEN_APP,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openApp)
            views.setOnClickPendingIntent(R.id.widget_progress_row, openApp)

            views.setOnClickPendingIntent(
                R.id.widget_star,
                commandIntent(context, MediaControlReceiver.ACTION_FAVORITE, REQUEST_FAVORITE)
            )
            views.setOnClickPendingIntent(
                R.id.widget_previous,
                commandIntent(context, MediaControlReceiver.ACTION_PREVIOUS, REQUEST_PREVIOUS)
            )
            val playPauseIntent = commandIntent(context, MediaControlReceiver.ACTION_PLAY_PAUSE, REQUEST_PLAY_PAUSE)
            views.setOnClickPendingIntent(R.id.widget_play, playPauseIntent)
            views.setOnClickPendingIntent(
                R.id.widget_next,
                commandIntent(context, MediaControlReceiver.ACTION_NEXT, REQUEST_NEXT)
            )
            views.setOnClickPendingIntent(
                R.id.widget_open_source,
                commandIntent(context, MediaControlReceiver.ACTION_OPEN_SOURCE, REQUEST_OPEN_SOURCE)
            )

            return views
        }

        private fun positionControlButtons(views: RemoteViews, widthDp: Float) {
            // Posiciona os 5 botões centralizados em suas porcentagens nominais do CardSpec:
            //   Star:       widthDp * 0.090 - 24dp
            //   Previous:   widthDp * 0.295 - 24dp
            //   Play:       widthDp * 0.500 - 24dp (ViewFlipper widget_play_flipper)
            //   Next:       widthDp * 0.705 - 24dp
            //   OpenSource: widthDp * 0.910 - 24dp
            val starMargin = (widthDp * CardSpec.ControlFraction1) - 24f
            val prevMargin = (widthDp * CardSpec.ControlFraction2) - 24f
            val playMargin = (widthDp * CardSpec.ControlFraction3) - 24f
            val nextMargin = (widthDp * CardSpec.ControlFraction4) - 24f
            val openMargin = (widthDp * CardSpec.ControlFraction5) - 24f

            views.setViewLayoutMargin(R.id.widget_star, RemoteViews.MARGIN_START, starMargin, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(R.id.widget_previous, RemoteViews.MARGIN_START, prevMargin, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(R.id.widget_play, RemoteViews.MARGIN_START, playMargin, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(R.id.widget_next, RemoteViews.MARGIN_START, nextMargin, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(R.id.widget_open_source, RemoteViews.MARGIN_START, openMargin, TypedValue.COMPLEX_UNIT_DIP)
        }

        private fun updateButton(views: RemoteViews, id: Int, enabled: Boolean) {
            views.setBoolean(id, "setEnabled", enabled)
            // Alfa: 1.0 (255) se habilitado; 0.35 (89) se desabilitado (CardSpec.kt:70-71)
            views.setInt(id, "setImageAlpha", if (enabled) 255 else 89)
        }

        private fun getWidgetWidthDp(manager: AppWidgetManager, appWidgetId: Int): Float {
            val options = manager.getAppWidgetOptions(appWidgetId)
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            return if (minW > 0) minW.toFloat() else 334.58f
        }

        private fun formatTime(milliseconds: Long): String {
            val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
            return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
        }

        // =========================================================================
        // TEMPO AO VIVO — TICKER DE 1000ms SEM CHRONOMETER
        // =========================================================================

        private fun checkTicker() {
            val context = applicationContext ?: return
            val snapshot = currentSnapshot ?: return

            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = pm?.isInteractive ?: true

            val shouldTick = snapshot.hasSession && snapshot.isAdvancing && isInteractive

            if (shouldTick && !isTickerRunning) {
                startTicker()
            } else if (!shouldTick && isTickerRunning) {
                stopTicker()
            }
        }

        private fun startTicker() {
            if (isTickerRunning) return
            isTickerRunning = true

            tickerRunnable = object : Runnable {
                override fun run() {
                    if (!isTickerRunning) return
                    tickProgress()
                    mainHandler.postDelayed(this, TICK_MS)
                }
            }
            mainHandler.postDelayed(tickerRunnable!!, TICK_MS)
        }

        private fun stopTicker() {
            isTickerRunning = false
            tickerRunnable?.let { mainHandler.removeCallbacks(it) }
            tickerRunnable = null
        }

        fun buildFullViews(context: Context, snapshot: WidgetSnapshot, widthDp: Float): RemoteViews =
            buildViews(context, snapshot, widthDp)

        fun buildTickViews(
            context: Context,
            position: Long,
            duration: Long,
            widthDp: Float = 334.58f
        ): RemoteViews {
            val partialViews = RemoteViews(context.packageName, R.layout.widget_player)
            partialViews.setTextViewText(R.id.widget_elapsed, formatTime(position))
            if (duration > 0L) {
                val remaining = (duration - position).coerceAtLeast(0L)
                partialViews.setTextViewText(R.id.widget_remaining, "\u2212" + formatTime(remaining))
                val progress = ((position.toDouble() / duration.toDouble()) * PROGRESS_MAX)
                    .roundToInt()
                    .coerceIn(0, PROGRESS_MAX)
                partialViews.setImageViewBitmap(R.id.widget_progress, renderProgressBitmap(context, widthDp, progress))
            } else {
                partialViews.setTextViewText(R.id.widget_remaining, "--:--")
                partialViews.setImageViewBitmap(R.id.widget_progress, renderProgressBitmap(context, widthDp, 0))
            }
            return partialViews
        }

        private fun tickProgress() {
            val context = applicationContext ?: return
            val snapshot = currentSnapshot ?: return
            if (!snapshot.hasSession || !snapshot.isAdvancing) {
                stopTicker()
                return
            }

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
            if (ids.isEmpty()) {
                stopTicker()
                return
            }

            val now = SystemClock.elapsedRealtime()
            val position = snapshot.positionAt(now)
            val duration = snapshot.durationMs

            if (DEBUG_DIAG) {
                val elapsedStr = formatTime(position)
                val remStr = if (duration > 0L) "\u2212" + formatTime((duration - position).coerceAtLeast(0L)) else "--:--"
                Log.i("WIDGETDIAG", "PATH=tick REASON=tickProgress pos=$position elapsed='$elapsedStr' rem='$remStr' ids=${ids.contentToString()} actions=[setTextViewText(elapsed), setTextViewText(remaining), setImageViewBitmap(progress)]")
            }
            for (id in ids) {
                val widthDp = getWidgetWidthDp(manager, id)
                val partialViews = buildTickViews(context, position, duration, widthDp)
                manager.partiallyUpdateAppWidget(id, partialViews)
            }
        }

        private fun registerScreenReceiver(context: Context) {
            if (isScreenReceiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            context.registerReceiver(screenReceiver, filter)
            isScreenReceiverRegistered = true
        }

        private fun unregisterScreenReceiver(context: Context) {
            if (!isScreenReceiverRegistered) return
            try {
                context.unregisterReceiver(screenReceiver)
            } catch (_: Exception) {}
            isScreenReceiverRegistered = false
        }

        // =========================================================================
        // W6 PLANO B — RENDERIZAÇÃO DE TEXTO COMO BITMAP (fonte Inter garantida)
        // =========================================================================
        // O launcher OneUI substitui android:fontFamily de apps de terceiros (confirmado empiricamente).
        // Solução: renderizar título e artista como Bitmap via Canvas + Typeface Inter (res/font ou assets/font).
        // Tamanho compacto (apenas os bounds do texto medido): consome < 70KB por bitmap no Binder IPC,
        // evitando o erro TransactionTooLargeException no RemoteViews.
        // O ImageView com android:scaleType="center" centraliza perfeitamente no ViewFlipper.

        private fun getTypefaceSemiBold(context: Context): Typeface =
            typefaceSemiBold ?: run {
                val tf = try {
                    androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)
                } catch (_: Exception) { null }
                    ?: try {
                        Typeface.createFromAsset(context.assets, "font/inter_semibold.ttf")
                    } catch (_: Exception) { null }
                    ?: Typeface.DEFAULT_BOLD
                typefaceSemiBold = tf
                tf
            }

        private fun getTypefaceRegular(context: Context): Typeface =
            typefaceRegular ?: run {
                val tf = try {
                    androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_regular)
                } catch (_: Exception) { null }
                    ?: try {
                        Typeface.createFromAsset(context.assets, "font/inter_regular.ttf")
                    } catch (_: Exception) { null }
                    ?: Typeface.DEFAULT
                typefaceRegular = tf
                tf
            }

        private fun getSharpPlayBitmap(context: Context): Bitmap =
            cachedSharpPlay ?: renderControlIconBitmap(context, R.drawable.ic_play, 25f, 29f, 0f).also { cachedSharpPlay = it }

        private fun getMidBlurPlayBitmap(context: Context): Bitmap =
            cachedMidBlurPlay ?: renderControlIconBitmap(context, R.drawable.ic_play, 25f, 29f, 1.2f).also { cachedMidBlurPlay = it }

        private fun getBlurPlayBitmap(context: Context): Bitmap =
            cachedBlurPlay ?: renderControlIconBitmap(context, R.drawable.ic_play, 25f, 29f, 3.0f).also { cachedBlurPlay = it }

        private fun getSharpPauseBitmap(context: Context): Bitmap =
            cachedSharpPause ?: renderControlIconBitmap(context, R.drawable.ic_pause, 24f, 30f, 0f).also { cachedSharpPause = it }

        private fun getMidBlurPauseBitmap(context: Context): Bitmap =
            cachedMidBlurPause ?: renderControlIconBitmap(context, R.drawable.ic_pause, 24f, 30f, 1.2f).also { cachedMidBlurPause = it }

        private fun getBlurPauseBitmap(context: Context): Bitmap =
            cachedBlurPause ?: renderControlIconBitmap(context, R.drawable.ic_pause, 24f, 30f, 3.0f).also { cachedBlurPause = it }

        /**
         * Renderiza ícone de controle (Play/Pause) como Bitmap em um canvas fixo de 48dp x 48dp.
         * Dimensões idênticas garantem que ImageView.setImageBitmap() NUNCA dispare requestLayout(),
         * permitindo transições óticas de foco (blur 4dp -> 1.5dp -> 0dp) perfeitamente suaves, leves e rápidas.
         * Intensidade e velocidade calibradas para reproduzir com exatidão a física do player dentro do app.
         */
        private fun renderControlIconBitmap(
            context: Context,
            @DrawableRes drawableId: Int,
            widthDp: Float,
            heightDp: Float,
            blurRadiusDp: Float
        ): Bitmap {
            val density = context.resources.displayMetrics.density
            val containerSizePx = (48f * density).roundToInt().coerceAtLeast(1)
            val wPx = (widthDp * density).roundToInt().coerceAtLeast(1)
            val hPx = (heightDp * density).roundToInt().coerceAtLeast(1)

            val drawable = ContextCompat.getDrawable(context, drawableId)?.mutate()
                ?: return Bitmap.createBitmap(containerSizePx, containerSizePx, Bitmap.Config.ARGB_8888)

            val left = (containerSizePx - wPx) / 2
            val top = (containerSizePx - hPx) / 2

            if (blurRadiusDp <= 0f) {
                val bmp = Bitmap.createBitmap(containerSizePx, containerSizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(left, top, left + wPx, top + hPx)
                drawable.draw(canvas)
                return bmp
            }

            // Desenha o vetor na posição exata central do container
            val baseBmp = Bitmap.createBitmap(containerSizePx, containerSizePx, Bitmap.Config.ARGB_8888)
            val baseCanvas = Canvas(baseBmp)
            drawable.setBounds(left, top, left + wPx, top + hPx)
            drawable.draw(baseCanvas)

            val radiusPx = blurRadiusDp * density
            val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(radiusPx.coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
            }
            val offset = IntArray(2)
            val alphaBmp = baseBmp.extractAlpha(blurPaint, offset)
            baseBmp.recycle()

            val blurred = Bitmap.createBitmap(containerSizePx, containerSizePx, Bitmap.Config.ARGB_8888)
            val blurCanvas = Canvas(blurred)
            val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
            }
            blurCanvas.drawBitmap(alphaBmp, offset[0].toFloat(), offset[1].toFloat(), whitePaint)
            alphaBmp.recycle()
            return blurred
        }

        /**
         * Renderiza o título como Bitmap compacto com Inter SemiBold 16.5sp branco (#FFFFFFFF).
         * blurRadiusDp > 0 aplica BlurMaskFilter para simular a animação BlurEffect do LockScreenPlayerCard.
         * Referência: CardSpec.kt:17-20, LockScreenPlayerCard.kt:120, 145-173, MotionSpec.kt:57.
         */
        private fun renderTitleBitmap(
            context: Context,
            text: String,
            widthDp: Float,
            blurRadiusDp: Float = 0f
        ): Bitmap {
            if (blurRadiusDp == 0f) {
                cachedTitleBitmap?.let { (t, b) -> if (t == text && !b.isRecycled) return b }
            }
            val density = context.resources.displayMetrics.density
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = getTypefaceSemiBold(context)
                textSize = 16.5f * density  // 16.5sp
                color = Color.WHITE
                letterSpacing = -0.02f
                if (blurRadiusDp > 0f) {
                    maskFilter = BlurMaskFilter(
                        (blurRadiusDp * density).coerceAtLeast(0.1f),
                        BlurMaskFilter.Blur.NORMAL
                    )
                }
            }
            // Margem horizontal nominal do CardSpec.kt:19 (56dp de cada lado)
            val availW = ((widthDp - 112f) * density).coerceAtLeast(10f)
            val ellipsized = TextUtils.ellipsize(text, paint, availW, TextUtils.TruncateAt.END).toString()
            val textW = paint.measureText(ellipsized).roundToInt().coerceAtLeast(2)
            val textH = (paint.descent() - paint.ascent()).roundToInt().coerceAtLeast(2)

            val radiusPx = blurRadiusDp * density
            val padX = if (blurRadiusDp > 0f) (radiusPx * 1.5f).roundToInt() else 0
            val maxPadY = if (blurRadiusDp > 0f) {
                ((44f * density - textH) / 2f).roundToInt().coerceAtLeast(0)
            } else 0
            val padY = if (blurRadiusDp > 0f) {
                (radiusPx * 1.2f).roundToInt().coerceAtMost(maxPadY)
            } else 0

            val bmpW = textW + padX * 2
            val bmpH = textH + padY * 2
            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawText(ellipsized, padX.toFloat(), padY.toFloat() - paint.ascent(), paint)
            if (blurRadiusDp == 0f) {
                cachedTitleBitmap = text to bmp
            }
            return bmp
        }

        /**
         * Renderiza o artista como Bitmap compacto com Inter Regular 14sp branco alfa 0.60 (#99FFFFFF).
         * blurRadiusDp > 0 aplica BlurMaskFilter para simular a animação BlurEffect do LockScreenPlayerCard.
         * Referência: CardSpec.kt:23-26, LockScreenPlayerCard.kt:176-230, MotionSpec.kt:57.
         */
        private fun renderArtistBitmap(
            context: Context,
            text: String,
            widthDp: Float,
            blurRadiusDp: Float = 0f
        ): Bitmap {
            if (blurRadiusDp == 0f) {
                cachedArtistBitmap?.let { (t, b) -> if (t == text && !b.isRecycled) return b }
            }
            val density = context.resources.displayMetrics.density
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = getTypefaceRegular(context)
                textSize = 15.8f * density   // 15.8sp — iOS 26 reference: ArtistFontSize
                color = Color.argb((0.78f * 255).roundToInt(), 255, 255, 255)  // alpha 0.78
                letterSpacing = -0.02f
                if (blurRadiusDp > 0f) {
                    maskFilter = BlurMaskFilter(
                        (blurRadiusDp * density).coerceAtLeast(0.1f),
                        BlurMaskFilter.Blur.NORMAL
                    )
                }
            }
            val availW = ((widthDp - 112f) * density).coerceAtLeast(10f)
            val ellipsized = TextUtils.ellipsize(text, paint, availW, TextUtils.TruncateAt.END).toString()
            val textW = paint.measureText(ellipsized).roundToInt().coerceAtLeast(2)
            val textH = (paint.descent() - paint.ascent()).roundToInt().coerceAtLeast(2)

            val radiusPx = blurRadiusDp * density
            val padX = if (blurRadiusDp > 0f) (radiusPx * 1.5f).roundToInt() else 0
            val maxPadY = if (blurRadiusDp > 0f) {
                ((40f * density - textH) / 2f).roundToInt().coerceAtLeast(0)
            } else 0
            val padY = if (blurRadiusDp > 0f) {
                (radiusPx * 1.2f).roundToInt().coerceAtMost(maxPadY)
            } else 0

            val bmpW = textW + padX * 2
            val bmpH = textH + padY * 2
            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawText(ellipsized, padX.toFloat(), padY.toFloat() - paint.ascent(), paint)
            if (blurRadiusDp == 0f) {
                cachedArtistBitmap = text to bmp
            }
            return bmp
        }

        // =========================================================================
        // MODO_FUNDO B — GERAÇÃO DA CAMADA DE FUNDO (<= 400 KB)
        // =========================================================================

        /**
         * Renderiza o overlay de Clear Liquid Glass como Bitmap com bordas arredondadas a 25dp.
         * Camadas (de baixo para cima):
         *   1. Preenchimento semi-transparente: 16% branco #29FFFFFF (top) → escuro #1A0E1218 (bottom).
         *   2. Borda especular 1px: LinearGradient top-left peak #8CFFFFFF → #1AFFFFFF → bottom-right #59FFFFFF.
         * Sem blur real (impossível em RemoteViews de terceiros — apenas o sistema usa blurRadius).
         * Referência: GlassSurfaceV1.kt, CardSpec.CardCornerRadius=25dp.
         */
        private fun renderGlassOverlay(context: Context, widthDp: Float): Bitmap {
            cachedGlassOverlay?.let { (cachedW, bmp) ->
                if (cachedW == widthDp && !bmp.isRecycled) return bmp
            }
            val density = context.resources.displayMetrics.density
            val w = (widthDp * density).roundToInt().coerceAtLeast(1)
            val h = (144f * density).roundToInt().coerceAtLeast(1)
            val r = 25f * density

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // Camada 1: preenchimento gradiente (topo claro → fundo escuro)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    intArrayOf(
                        Color.argb(42, 255, 255, 255),   // #2AFFFFFF top ~16%
                        Color.argb(26, 14, 18, 24),       // #1A0E1218 bottom
                        Color.argb(41, 8, 10, 14)         // #29080A0E very bottom
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            canvas.drawRoundRect(rect, r, r, fillPaint)

            // Camada 2: borda especular fina (0.75dp)
            val strokeW = (0.75f * density).coerceAtLeast(1f)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(
                        Color.argb(140, 255, 255, 255),  // #8CFFFFFF top-left peak
                        Color.argb(26, 255, 255, 255),   // #1AFFFFFF center fade
                        Color.argb(89, 255, 255, 255)    // #59FFFFFF bottom-right gleam
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            val inset = strokeW / 2f
            canvas.drawRoundRect(
                RectF(inset, inset, w - inset, h - inset),
                r - inset, r - inset, borderPaint
            )

            cachedGlassOverlay = widthDp to bmp
            return bmp
        }

        /**
         * Renderiza a barra de progresso como bitmap pill (extremidades arredondadas = raio pleno).
         * Trilho: 30% branco (#4DFFFFFF). Preenchimento: 96% branco (#F5FFFFFF).
         * Referência: CardSpec.BarHeight=6.5dp, PlaybackProgress.kt.
         */
        private fun renderProgressBitmap(context: Context, widthDp: Float, progress: Int): Bitmap {
            val density = context.resources.displayMetrics.density
            // Descontar margem horizontal da progress row: 22dp cada lado + 38dp elapsed + 9dp gap + 42dp remaining + 9dp gap
            val barWidthDp = widthDp - 22f - 22f - 38f - 9f - 42f - 9f
            val w = (barWidthDp * density).roundToInt().coerceAtLeast(2)
            val h = (6.5f * density).roundToInt().coerceAtLeast(1)
            val r = h / 2f

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // Trilho: 30% branco
            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(77, 255, 255, 255)  // #4DFFFFFF 30%
            }
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, trackPaint)

            // Preenchimento: 96% branco
            if (progress > 0) {
                val fillW = (w.toFloat() * progress / PROGRESS_MAX).coerceAtLeast(h.toFloat())
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(245, 255, 255, 255)  // #F5FFFFFF 96%
                }
                canvas.drawRoundRect(RectF(0f, 0f, fillW, h.toFloat()), r, r, fillPaint)
            }

            return bmp
        }        /**
         * Gera a camada de fundo derivada da capa (MODO B):
         *   1. Camada base sólida #0B0C10 (BackgroundInk, PlayerScreen.kt:44, 62).
         *   2. Capa com escala 1.25x (CardSpec.ArtworkBackgroundScale, PlayerScreen.kt:80-81),
         *      blur 40dp (CardSpec.ArtworkBlurRadius, PlayerScreen.kt:76-77),
         *      alfa 0.90f (CardSpec.ArtworkBackgroundAlpha, PlayerScreen.kt:74).
         *   3. Véu vertical preto de 0.15f a 0.35f (CardSpec.kt:84-85, PlayerScreen.kt:96-107).
         *   4. Recortada com cantos arredondados de 22dp (CardSpec.CardCornerRadius).
         */
        private fun renderBackdrop(source: Bitmap): Bitmap {
            val width = BACKDROP_WIDTH
            val height = BACKDROP_HEIGHT

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            // Camada 1: Cor sólida #0B0C10 (PlayerScreen.kt:44, 62)
            canvas.drawColor(0xFF0B0C10.toInt())

            // Camada 2: Capa em escala 1.25, recortada centralizada com blur profundo
            val scale = CardSpec.ArtworkBackgroundScale
            val sourceRatio = source.width.toFloat() / source.height.toFloat()
            val targetRatio = width.toFloat() / height.toFloat()

            val (cropW, cropH) = if (sourceRatio > targetRatio) {
                val baseW = (source.height * targetRatio).roundToInt().coerceAtLeast(1)
                val sW = (baseW / scale).roundToInt().coerceAtLeast(1)
                val sH = (source.height / scale).roundToInt().coerceAtLeast(1)
                sW to sH
            } else {
                val baseH = (source.width / targetRatio).roundToInt().coerceAtLeast(1)
                val sH = (baseH / scale).roundToInt().coerceAtLeast(1)
                val sW = (source.width / scale).roundToInt().coerceAtLeast(1)
                sW to sH
            }

            val cropLeft = ((source.width - cropW) / 2).coerceAtLeast(0)
            val cropTop = ((source.height - cropH) / 2).coerceAtLeast(0)
            val srcRect = Rect(cropLeft, cropTop, cropLeft + cropW, cropTop + cropH)

            // Downsample intermediário para dissolução de detalhes e desfoque profundo
            val lowW = 44
            val lowH = 18
            val lowRes = Bitmap.createBitmap(lowW, lowH, Bitmap.Config.ARGB_8888)
            val lowCanvas = Canvas(lowRes)
            lowCanvas.drawBitmap(
                source,
                srcRect,
                Rect(0, 0, lowW, lowH),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            )

            // 2 passagens de blur horizontal e vertical
            var pixels = IntArray(lowW * lowH)
            lowRes.getPixels(pixels, 0, lowW, 0, 0, lowW, lowH)
            repeat(2) {
                pixels = blurPass(pixels, lowW, lowH, 4, horizontal = true)
                pixels = blurPass(pixels, lowW, lowH, 4, horizontal = false)
            }
            lowRes.setPixels(pixels, 0, lowW, 0, 0, lowW, lowH)

            // Desenho da camada difusa com opacidade 0.90f (CardSpec.ArtworkBackgroundAlpha)
            val artworkPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (CardSpec.ArtworkBackgroundAlpha * 255).roundToInt()
            }
            canvas.drawBitmap(lowRes, Rect(0, 0, lowW, lowH), Rect(0, 0, width, height), artworkPaint)
            lowRes.recycle()

            // Camada 3: Véu vertical preto de 0.15f a 0.35f (PlayerScreen.kt:96-107)
            val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    Color.argb((CardSpec.VeilTopAlpha * 255).roundToInt(), 0, 0, 0),
                    Color.argb((CardSpec.VeilBottomAlpha * 255).roundToInt(), 0, 0, 0),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veilPaint)

            // Camada 4: Máscara arredondada de 22dp
            val masked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(masked)
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radiusPx = (25f * (width / 352f))
            maskCanvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radiusPx, radiusPx, maskPaint)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            maskCanvas.drawBitmap(output, 0f, 0f, maskPaint)
            output.recycle()

            return masked
        }

        /**
         * Fundo reserva sem capa: gradiente vertical #414A5D -> #171923 -> #0B0C10
         * com o mesmo véu preto por cima e cantos arredondados a 22dp (PlayerScreen.kt:44-46, 88-93).
         */
        private fun renderFallbackBackdrop(): Bitmap {
            val width = BACKDROP_WIDTH
            val height = BACKDROP_HEIGHT

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            // Fundo reserva: gradiente vertical
            val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0xFF414A5D.toInt(), 0xFF171923.toInt(), 0xFF0B0C10.toInt()),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradPaint)

            // Véu vertical preto de 0.15f a 0.35f
            val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    Color.argb((CardSpec.VeilTopAlpha * 255).roundToInt(), 0, 0, 0),
                    Color.argb((CardSpec.VeilBottomAlpha * 255).roundToInt(), 0, 0, 0),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veilPaint)

            // Máscara arredondada de 22dp
            val masked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(masked)
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radiusPx = (25f * (width / 352f))
            maskCanvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radiusPx, radiusPx, maskPaint)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            maskCanvas.drawBitmap(output, 0f, 0f, maskPaint)
            output.recycle()

            return masked
        }

        private fun blurPass(
            source: IntArray,
            width: Int,
            height: Int,
            radius: Int,
            horizontal: Boolean
        ): IntArray {
            val result = IntArray(source.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var a = 0
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0

                    for (delta in -radius..radius) {
                        val sx = if (horizontal) x + delta else x
                        val sy = if (horizontal) y else y + delta
                        if (sx !in 0 until width || sy !in 0 until height) continue
                        val c = source[sy * width + sx]
                        a += (c ushr 24) and 0xFF
                        r += (c ushr 16) and 0xFF
                        g += (c ushr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }

                    result[y * width + x] =
                        ((a / count) shl 24) or
                            ((r / count) shl 16) or
                            ((g / count) shl 8) or
                            (b / count)
                }
            }
            return result
        }

        private fun commandIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MediaControlReceiver::class.java)
                .setAction(action)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Item D (W5): cria PendingIntent de seek para uma fração [0.0, 1.0] da duração.
         * Referência: MediaControlReceiver.ACTION_SEEK + EXTRA_SEEK_FRACTION.
         */
        private fun seekIntent(context: Context, fraction: Float, requestCode: Int): PendingIntent {
            val intent = Intent(context, MediaControlReceiver::class.java)
                .setAction(MediaControlReceiver.ACTION_SEEK)
                .putExtra(MediaControlReceiver.EXTRA_SEEK_FRACTION, fraction)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private const val REQUEST_FAVORITE = 10
        private const val REQUEST_PREVIOUS = 11
        private const val REQUEST_PLAY_PAUSE = 12
        private const val REQUEST_NEXT = 13
        private const val REQUEST_OPEN_SOURCE = 14
        private const val REQUEST_OPEN_APP = 15
        // Item D (W5): seek overlay — 5 regiões da barra de progresso (0–20%, 20–40%, 40–60%, 60–80%, 80–100%)
        private const val REQUEST_SEEK_0 = 20
        private const val REQUEST_SEEK_1 = 21
        private const val REQUEST_SEEK_2 = 22
        private const val REQUEST_SEEK_3 = 23
        private const val REQUEST_SEEK_4 = 24
    }
}
