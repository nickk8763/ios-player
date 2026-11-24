package com.nickk.iosglassplayer.media

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationManagerCompat
import com.nickk.iosglassplayer.widget.PlayerWidgetProvider
import com.nickk.iosglassplayer.widget.WidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Single source of truth for media state. Event driven: no polling.
 *
 *  - MediaSessionManager.OnActiveSessionsChangedListener tells us when sessions appear/disappear.
 *  - A lightweight MediaController.Callback on every session lets us switch when another app starts
 *    playing, and refresh metadata/state of the selected one.
 *  - Everything runs on the main thread. attach() is idempotent.
 *
 * Commands return true when a command was handed to a real session. That means "sent", not
 * "the player obeyed" - Android gives no acknowledgement.
 */
object MediaSessionRepository {
    private const val TAG = "MediaSessionRepoDiag"
    private const val MAX_ARTWORK_PX = 1024
    private const val DEBOUNCE_MS = 80L
    private const val DEBOUNCE_PLAY_MS = 350L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var pendingPlayDebounceRunnable: Runnable? = null
    private var pendingDebounceIsPlaying: Boolean? = null

    private val _state = MutableStateFlow(TrackInfo())
    val state: StateFlow<TrackInfo> = _state.asStateFlow()

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var attached = false
    private var widgetJob: Job? = null

    private data class ActiveSession(
        val controller: MediaController,
        val callback: MediaController.Callback
    )

    private val activeSessions = LinkedHashMap<String, ActiveSession>()
    private var controllers: Map<String, MediaController> = emptyMap()
    private var selectedId: String? = null

    private var artKey: String? = null
    private var artCache: Bitmap? = null
    private val labelCache = HashMap<String, String?>()

    private var lastCommand: String? = null
    private var lastCommandAt = 0L

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list.orEmpty()) }

    // ---- authorization / lifecycle -------------------------------------------------------------

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    @MainThread
    fun attach(context: Context) {
        val app = context.applicationContext
        if (!attached) {
            val m = app.getSystemService(MediaSessionManager::class.java)
            val c = ComponentName(app, MediaAccessService::class.java)
            try {
                m.addOnActiveSessionsChangedListener(sessionsListener, c, mainHandler)
            } catch (e: SecurityException) {
                // Notification access not granted (or revoked).
                resetState()
                return
            }
            appContext = app
            manager = m
            component = c
            attached = true
            if (widgetJob == null) {
                widgetJob = scope.launch {
                    // Position is deliberately not part of the key: the widget must not refresh on progress.
                    state.map { WidgetSnapshot.from(it) }
                        .distinctUntilChanged { old, new ->
                            val eq = old == new
                            if (!eq && PlayerWidgetProvider.DEBUG_DIAG) {
                                Log.i("WIDGETDIAG", "SNAPSHOT_DIFF: old(title='${old.title}', artKey='${old.artworkKey}', play=${old.isPlaying}, adv=${old.isAdvancing}) != new(title='${new.title}', artKey='${new.artworkKey}', play=${new.isPlaying}, adv=${new.isAdvancing})")
                            }
                            eq
                        }
                        .collect {
                            if (PlayerWidgetProvider.DEBUG_DIAG) {
                                Log.i("WIDGETDIAG", "COLLECT snapshot: title='${it.title}' isPlaying=${it.isPlaying} isAdvancing=${it.isAdvancing}")
                            }
                            PlayerWidgetProvider.refreshAll(app, it)
                        }
                }
            }
            refreshFromManager()
        }
    }

    @MainThread
    fun detach() {
        manager?.let { runCatching { it.removeOnActiveSessionsChangedListener(sessionsListener) } }
        resetState()
        attached = false
    }

    private fun resetState() {
        if (selectedId != null) {
            Log.i(TAG, "selectedId cleared: '$selectedId' -> null [reason=resetState]")
        }
        for ((id, session) in activeSessions) {
            Log.i(TAG, "callback removed: id='$id' controller=${System.identityHashCode(session.controller)} [reason=resetState]")
            runCatching { session.controller.unregisterCallback(session.callback) }
        }
        activeSessions.clear()
        controllers = emptyMap()
        selectedId = null
        artKey = null
        artCache = null
        _state.value = TrackInfo()
    }

    private fun refreshFromManager() {
        val m = manager ?: return
        val c = component ?: return
        val list = try {
            m.getActiveSessions(c)
        } catch (e: SecurityException) {
            detach()
            return
        }
        onSessions(list)
    }

    // ---- session tracking -------------------------------------------------------------------------

    private fun idOf(c: MediaController) = "${c.packageName}#${c.sessionToken.hashCode()}"

    private fun onSessions(list: List<MediaController>) {
        val newMap = LinkedHashMap<String, ActiveSession>()
        for (c in list) {
            val id = idOf(c)
            val existing = activeSessions[id]
            if (existing != null) {
                newMap[id] = existing
            } else {
                val cb = SessionCallback(id)
                Log.i(TAG, "callback registered: id='$id' pkg='${c.packageName}' controller=${System.identityHashCode(c)}")
                c.registerCallback(cb, mainHandler)
                newMap[id] = ActiveSession(controller = c, callback = cb)
            }
        }
        for ((id, session) in activeSessions) {
            if (id !in newMap) {
                Log.i(TAG, "callback removed: id='$id' controller=${System.identityHashCode(session.controller)} [reason=session_gone]")
                runCatching { session.controller.unregisterCallback(session.callback) }
            }
        }
        activeSessions.clear()
        activeSessions.putAll(newMap)
        controllers = activeSessions.mapValues { it.value.controller }
        reevaluate("active_sessions_changed:count=${activeSessions.size}")
    }

    private class SessionCallback(private val id: String) : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val pkg = id.split("#").firstOrNull() ?: id
            Log.i(TAG, "onPlaybackStateChanged: id='$id' pkg='$pkg' state=${state?.state} at=${SystemClock.elapsedRealtime()}")
            reevaluate("playback_state_changed:$id:state=${state?.state}")
        }

        // Re-run the selection (not just publish): metadata arriving on another session can make it
        // the better pick (SessionSelector abandons a session that has none while another does).
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val pkg = id.split("#").firstOrNull() ?: id
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            Log.i(TAG, "onMetadataChanged: id='$id' pkg='$pkg' metadataNull=${metadata == null} title='$title' at=${SystemClock.elapsedRealtime()}")
            reevaluate("metadata_changed:$id:hasMetadata=${metadata != null}")
        }

        override fun onSessionDestroyed() {
            Log.i(TAG, "onSessionDestroyed: id='$id'")
            refreshFromManager()
        }
    }

    private fun reevaluate(reason: String = "unspecified") {
        val previousSelectedId = selectedId
        val candidates = controllers.map { (id, c) ->
            SessionCandidate(id, statusOf(c.playbackState), c.metadata != null)
        }
        val newSelectedId = SessionSelector.select(candidates, previousSelectedId)
        selectedId = newSelectedId

        if (previousSelectedId != newSelectedId) {
            Log.i(TAG, "selectedId changed: '$previousSelectedId' -> '$newSelectedId' [reason=$reason]")
        } else {
            Log.d(TAG, "selectedId retained: '$selectedId' [reason=$reason]")
        }
        publish()
    }

    private fun publish() {
        val controller = selectedId?.let { controllers[it] }
        if (controller == null) {
            // Sem sessão: cancela qualquer debounce pendente e publica estado vazio imediatamente.
            pendingPlayDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingPlayDebounceRunnable = null
            pendingDebounceIsPlaying = null
            Log.d(TAG, "publish: no session, clearing state immediately")
            _state.value = TrackInfo()
            return
        }

        val newTrack = buildTrack(controller)
        val newIsPlaying = newTrack.isPlaying
        val currentIsPlaying = _state.value.isPlaying
        val isPlayingChanged = newIsPlaying != currentIsPlaying

        // Se a mudança é só em isPlaying (title/artist/session continuam iguais), debounça.
        // Outros campos (título, artista, sessão) publicam imediatamente.
        val newTitle = newTrack.title
        val currentTitle = _state.value.title
        val newHasSession = newTrack.hasSession
        val currentHasSession = _state.value.hasSession

        val onlyPlayChanged = isPlayingChanged
                && newTitle == currentTitle
                && newHasSession == currentHasSession

        if (onlyPlayChanged) {
            // BitChord tende a oscilar PLAYING→PAUSED→PLAYING em <400ms.
            // Guardamos o estado pendente e só publicamos após DEBOUNCE_PLAY_MS de estabilidade.
            pendingDebounceIsPlaying = newIsPlaying
            pendingPlayDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable {
                pendingPlayDebounceRunnable = null
                val latestController = selectedId?.let { controllers[it] }
                if (latestController != null) {
                    val settled = buildTrack(latestController)
                    Log.i(TAG, "publish(debounced): isPlaying settled to ${settled.isPlaying} after ${DEBOUNCE_PLAY_MS}ms")
                    _state.value = settled
                } else {
                    _state.value = TrackInfo()
                }
                pendingDebounceIsPlaying = null
            }
            pendingPlayDebounceRunnable = r
            mainHandler.postDelayed(r, DEBOUNCE_PLAY_MS)
            Log.d(TAG, "publish: isPlaying change $currentIsPlaying→$newIsPlaying debounced for ${DEBOUNCE_PLAY_MS}ms")
        } else {
            // Mudança estrutural (título, sessão, etc.) — publica imediatamente,
            // descartando qualquer debounce de play pendente (o novo estado é mais recente).
            pendingPlayDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingPlayDebounceRunnable = null
            pendingDebounceIsPlaying = null
            Log.d(TAG, "publish: immediate (title='$newTitle' hasSession=$newHasSession isPlaying=$newIsPlaying)")
            _state.value = newTrack
        }
    }

    // ---- mapping --------------------------------------------------------------------------------------

    private fun statusOf(pb: PlaybackState?): PlaybackStatus = when (pb?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> PlaybackStatus.PLAYING
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> PlaybackStatus.BUFFERING
        PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED
        PlaybackState.STATE_STOPPED -> PlaybackStatus.STOPPED
        PlaybackState.STATE_ERROR -> PlaybackStatus.ERROR
        else -> PlaybackStatus.NONE
    }

    private fun buildTrack(c: MediaController): TrackInfo {
        val md = c.metadata
        val pb = c.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        // Some apps never fill `actions`; treat an empty mask as "unknown" and allow the command
        // instead of disabling controls that would actually work.
        val actions = pb?.actions ?: 0L
        fun can(mask: Long) = actions == 0L || (actions and mask) != 0L

        val customActionId = pb?.customActions?.firstOrNull { ca ->
            val action = ca.action?.lowercase().orEmpty()
            val name = ca.name?.toString()?.lowercase().orEmpty()
            listOf("like", "favorite", "favourite", "heart").any { key ->
                action.contains(key) || name.contains(key)
            }
        }?.action

        return TrackInfo(
            title = title,
            artist = artist,
            album = album,
            artwork = artworkFor(md, c.packageName, title, artist, album),
            status = statusOf(pb),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = pb?.position?.takeIf { it >= 0L } ?: 0L,
            positionUpdateElapsedMs = pb?.lastPositionUpdateTime ?: 0L,
            speed = (pb?.playbackSpeed ?: 1f).coerceAtLeast(0f),
            packageName = c.packageName,
            appLabel = labelFor(c.packageName),
            hasSession = true,
            canPlayPause = can(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE),
            canSkipPrevious = can(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            canSkipNext = can(PlaybackState.ACTION_SKIP_TO_NEXT),
            canSeek = actions != 0L && (actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            customActionId = customActionId
        )
    }

    /**
     * Cached by (package, title, artist, album, size) so a playback-state update does not re-scale
     * the bitmap. Limitation: art delivered only as METADATA_KEY_*_URI is not loaded yet.
     */
    private fun artworkFor(md: MediaMetadata?, pkg: String, title: String, artist: String, album: String): Bitmap? {
        val src = md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (src == null) {
            artKey = null
            artCache = null
            return null
        }
        val key = "$pkg|$title|$artist|$album|${src.width}x${src.height}"
        if (key == artKey) artCache?.let { return it }
        val scaled = src.limitedTo(MAX_ARTWORK_PX)
        artKey = key
        artCache = scaled
        return scaled
    }

    private fun Bitmap.limitedTo(maxPx: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= maxPx) return this
        val s = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            this, (width * s).roundToInt().coerceAtLeast(1), (height * s).roundToInt().coerceAtLeast(1), true
        )
    }

    private fun labelFor(pkg: String): String? = labelCache.getOrPut(pkg) {
        val ctx = appContext ?: return@getOrPut null
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

    // ---- commands ---------------------------------------------------------------------------------------

    fun togglePlayPause(): Boolean = send("play_pause") { c ->
        val currentIsPlaying = _state.value.isPlaying
        val newStatus = if (currentIsPlaying) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING
        if (currentIsPlaying) c.transportControls.pause() else c.transportControls.play()
        val now = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            status = newStatus,
            positionUpdateElapsedMs = if (newStatus == PlaybackStatus.PLAYING) now else 0L
        )
    }

    fun skipToPrevious(): Boolean = send("previous") { it.transportControls.skipToPrevious() }

    fun skipToNext(): Boolean = send("next") { it.transportControls.skipToNext() }

    fun seekTo(positionMs: Long): Boolean =
        send("seek", debounce = false) { it.transportControls.seekTo(positionMs.coerceAtLeast(0L)) }

    fun sendCustomAction(action: String? = _state.value.customActionId): Boolean {
        val act = action ?: return false
        return send("custom_action") { it.transportControls.sendCustomAction(act, null) }
    }

    private fun send(name: String, debounce: Boolean = true, block: (MediaController) -> Unit): Boolean {
        val targetId = selectedId
        val controller = targetId?.let { controllers[it] }
        if (controller == null) {
            Log.w(TAG, "send command='$name' failed: no controller available (selectedId='$targetId')")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        // A duplicate inside the debounce window counts as handled (the first one was real).
        if (debounce && name == lastCommand && now - lastCommandAt < DEBOUNCE_MS) {
            Log.d(TAG, "command='$name' to target='$targetId' debounced (elapsed=${now - lastCommandAt}ms < ${DEBOUNCE_MS}ms)")
            return true
        }
        lastCommand = name
        lastCommandAt = now
        Log.i(TAG, "dispatching command='$name' to target='$targetId'")
        return try {
            block(controller)
            Log.d(TAG, "command='$name' successfully sent to target='$targetId'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "command='$name' to target='$targetId' threw exception: ${e.javaClass.simpleName}: ${e.message}")
            false // dead binder, revoked access, ...
        }
    }

    /** Opens the app that owns the selected session. Not every app exposes a session activity. */
    fun openSessionApp(context: Context): Boolean {
        val controller = selectedId?.let { controllers[it] } ?: return false
        controller.sessionActivity?.let { pi ->
            try {
                val options = if (Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                } else null
                pi.send(context, 0, null, null, null, null, options)
                return true
            } catch (e: PendingIntent.CanceledException) {
                // fall through to the launcher intent
            }
        }
        val launch = context.packageManager.getLaunchIntentForPackage(controller.packageName) ?: return false
        return try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
