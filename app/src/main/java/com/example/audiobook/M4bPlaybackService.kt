package com.example.audiobook

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.LexiVoiceApplication
import com.example.MainActivity
import com.example.data.db.AudiobookEntity
import com.example.tts.SleepTimerMode
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class M4bPlaybackState(
    val audiobookId: Long = -1L,
    val title: String = "",
    val author: String = "",
    val narrator: String = "",
    val coverPath: String? = null,
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val chapters: List<AudiobookChapterMeta> = emptyList(),
    val currentChapterIndex: Int = 0,
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val sleepRemainingMs: Long = 0L,
    val sourceEpubKey: String? = null,
    val sourceEpubIsDirect: Boolean = false,
    val hasSyncedEpub: Boolean = false,
    val activeSyncChapterIndex: Int = 0,
    val activeSyncSentenceIndex: Int = -1
)

object M4bPlayerStateHolder {
    private val _state = MutableStateFlow(M4bPlaybackState())
    val state: StateFlow<M4bPlaybackState> = _state.asStateFlow()

    @Volatile
    var syncSegments: List<SyncAlignmentSegment> = emptyList()
        private set

    fun setSyncSegments(segments: List<SyncAlignmentSegment>) {
        syncSegments = segments
    }

    fun updateState(transform: (M4bPlaybackState) -> M4bPlaybackState) {
        _state.update(transform)
    }
}

class M4bPlaybackService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var exoPlayer: ExoPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollerJob: Job? = null
    private var sleepEndTimeMs: Long = 0L
    private var lastChapterForSleep: Int = -1
    private var lastSavedTick: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LexiVoice::M4bPlaybackWakeLock")

        mediaSession = MediaSessionCompat(this, "LexiVoiceM4bSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = jumpRelativeChapter(1)
                override fun onSkipToPrevious() = jumpRelativeChapter(-1)
                override fun onFastForward() = skipBy(30_000L)
                override fun onRewind() = skipBy(-15_000L)
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stopAndDismiss()
            })
            isActive = true
        }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        M4bPlayerStateHolder.updateState { it.copy(isPlaying = isPlaying) }
                        if (isPlaying) {
                            acquireWakeLock()
                            startPositionPoller()
                        } else {
                            releaseWakeLock()
                            persistProgressNow()
                        }
                        updateMediaSessionAndNotification()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            val dur = duration.coerceAtLeast(0L)
                            if (dur > 0L) {
                                M4bPlayerStateHolder.updateState { it.copy(durationMs = dur) }
                            }
                            updateMediaSessionAndNotification()
                        } else if (playbackState == Player.STATE_ENDED) {
                            M4bPlayerStateHolder.updateState { it.copy(isPlaying = false) }
                            persistProgressNow()
                            updateMediaSessionAndNotification()
                        }
                    }
                })
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        val action = intent?.action
        if (action != null && action != ACTION_STOP) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        when (action) {
            ACTION_LOAD_AND_PLAY -> {
                val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                val autoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY, true)
                if (bookId > 0L) {
                    loadAudiobook(bookId, autoPlay)
                }
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                if (exoPlayer?.isPlaying == true) pausePlayback() else resumePlayback()
            }
            ACTION_SEEK_TO -> {
                val pos = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                seekTo(pos)
            }
            ACTION_SKIP_BY -> {
                val delta = intent.getLongExtra(EXTRA_DELTA_MS, 0L)
                skipBy(delta)
            }
            ACTION_JUMP_CHAPTER -> {
                val idx = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0)
                jumpToChapter(idx)
            }
            ACTION_SET_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f).coerceIn(0.5f, 2.5f)
                exoPlayer?.playbackParameters = PlaybackParameters(speed)
                M4bPlayerStateHolder.updateState { it.copy(playbackSpeed = speed) }
            }
            ACTION_SET_SLEEP_TIMER -> {
                val modeName = intent.getStringExtra(EXTRA_SLEEP_MODE) ?: SleepTimerMode.OFF.name
                val mode = runCatching { SleepTimerMode.valueOf(modeName) }.getOrDefault(SleepTimerMode.OFF)
                val minutes = intent.getIntExtra(EXTRA_SLEEP_MINUTES, 15)
                configureSleepTimer(mode, minutes)
            }
            ACTION_STOP -> stopAndDismiss()
        }
        return START_NOT_STICKY
    }

    private fun loadAudiobook(bookId: Long, autoPlay: Boolean) {
        val currentId = M4bPlayerStateHolder.state.value.audiobookId
        if (currentId == bookId && exoPlayer?.mediaItemCount ?: 0 > 0) {
            if (autoPlay && exoPlayer?.isPlaying == false) resumePlayback()
            return
        }

        // Pause TTS if it was active so voices never overlap
        if (TtsStateHolder.state.value.isPlaying) {
            TtsPlaybackService.sendAction(this, TtsPlaybackService.ACTION_PAUSE)
        }

        val app = applicationContext as LexiVoiceApplication
        serviceScope.launch {
            val entity: AudiobookEntity? = withContext(Dispatchers.IO) {
                app.audiobookRepository.getAudiobookById(bookId)
            }
            if (entity == null) {
                stopSelf()
                return@launch
            }

            val chapters = withContext(Dispatchers.IO) {
                SyncAlignmentSerializer.decodeChapters(entity.chaptersJson)
            }
            val segments = withContext(Dispatchers.IO) {
                entity.syncJsonPath?.let { path ->
                    SyncAlignmentSerializer.loadSegments(File(path))
                }.orEmpty()
            }
            M4bPlayerStateHolder.setSyncSegments(segments)

            val uri = if (entity.isDirectPath) {
                Uri.fromFile(File(entity.fileUri))
            } else {
                Uri.parse(entity.fileUri)
            }

            val startPos = entity.lastPositionMs.coerceAtLeast(0L)
            val initialSeg = SyncAlignmentSerializer.findSegmentAtPosition(segments, startPos)
            val chIdx = findChapterIndex(chapters, startPos)
            lastChapterForSleep = chIdx

            M4bPlayerStateHolder.updateState {
                M4bPlaybackState(
                    audiobookId = entity.id,
                    title = entity.title,
                    author = entity.author,
                    narrator = entity.narrator,
                    coverPath = entity.coverPath,
                    isActive = true,
                    isPlaying = autoPlay,
                    currentPositionMs = startPos,
                    durationMs = entity.durationMs,
                    playbackSpeed = it.playbackSpeed,
                    chapters = chapters,
                    currentChapterIndex = chIdx,
                    sleepTimerMode = it.sleepTimerMode,
                    sleepRemainingMs = it.sleepRemainingMs,
                    sourceEpubKey = entity.sourceEpubKey,
                    sourceEpubIsDirect = entity.sourceEpubIsDirect,
                    hasSyncedEpub = !entity.sourceEpubKey.isNullOrBlank() && segments.isNotEmpty(),
                    activeSyncChapterIndex = initialSeg?.chapterIndex ?: 0,
                    activeSyncSentenceIndex = initialSeg?.sentenceIndex ?: -1
                )
            }

            exoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(uri))
                playbackParameters = PlaybackParameters(M4bPlayerStateHolder.state.value.playbackSpeed)
                prepare()
                if (startPos > 0L) seekTo(startPos)
                playWhenReady = autoPlay
            }

            startForeground(NOTIFICATION_ID, buildNotification())
            updateMediaSessionAndNotification()
            if (autoPlay) startPositionPoller()
        }
    }

    private fun resumePlayback() {
        if (TtsStateHolder.state.value.isPlaying) {
            TtsPlaybackService.sendAction(this, TtsPlaybackService.ACTION_PAUSE)
        }
        exoPlayer?.play()
        startForeground(NOTIFICATION_ID, buildNotification())
        startPositionPoller()
    }

    private fun pausePlayback() {
        exoPlayer?.pause()
        persistProgressNow()
        updateMediaSessionAndNotification()
    }

    private fun seekTo(posMs: Long) {
        val dur = exoPlayer?.duration?.takeIf { it > 0L } ?: M4bPlayerStateHolder.state.value.durationMs
        val clamped = posMs.coerceIn(0L, dur.coerceAtLeast(posMs))
        exoPlayer?.seekTo(clamped)
        refreshPositionAndSync(clamped)
        persistProgressNow()
    }

    private fun skipBy(deltaMs: Long) {
        val current = exoPlayer?.currentPosition ?: M4bPlayerStateHolder.state.value.currentPositionMs
        seekTo(current + deltaMs)
    }

    private fun jumpToChapter(chapterIndex: Int) {
        val chapters = M4bPlayerStateHolder.state.value.chapters
        val target = chapters.getOrNull(chapterIndex) ?: return
        seekTo(target.startMs)
    }

    private fun jumpRelativeChapter(delta: Int) {
        val st = M4bPlayerStateHolder.state.value
        val targetIdx = (st.currentChapterIndex + delta).coerceIn(0, (st.chapters.size - 1).coerceAtLeast(0))
        jumpToChapter(targetIdx)
    }

    private fun configureSleepTimer(mode: SleepTimerMode, minutes: Int) {
        sleepEndTimeMs = if (mode == SleepTimerMode.MINUTES) {
            System.currentTimeMillis() + (minutes.coerceAtLeast(1) * 60_000L)
        } else {
            0L
        }
        lastChapterForSleep = M4bPlayerStateHolder.state.value.currentChapterIndex
        M4bPlayerStateHolder.updateState {
            it.copy(
                sleepTimerMode = mode,
                sleepRemainingMs = if (mode == SleepTimerMode.MINUTES) minutes * 60_000L else 0L
            )
        }
    }

    private fun startPositionPoller() {
        if (pollerJob?.isActive == true) return
        pollerJob = serviceScope.launch {
            while (isActive) {
                val player = exoPlayer
                if (player != null) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    refreshPositionAndSync(pos)

                    val now = System.currentTimeMillis()
                    if (now - lastSavedTick > 3000L) {
                        lastSavedTick = now
                        persistProgressNow()
                    }

                    // Sleep Timer checks
                    val st = M4bPlayerStateHolder.state.value
                    if (st.sleepTimerMode == SleepTimerMode.MINUTES && sleepEndTimeMs > 0L) {
                        val rem = sleepEndTimeMs - now
                        if (rem <= 0L) {
                            pausePlayback()
                            configureSleepTimer(SleepTimerMode.OFF, 0)
                        } else {
                            M4bPlayerStateHolder.updateState { it.copy(sleepRemainingMs = rem) }
                        }
                    } else if (st.sleepTimerMode == SleepTimerMode.END_OF_CHAPTER) {
                        if (st.currentChapterIndex > lastChapterForSleep && player.isPlaying) {
                            pausePlayback()
                            configureSleepTimer(SleepTimerMode.OFF, 0)
                        }
                    }
                }
                delay(150L)
            }
        }
    }

    private fun refreshPositionAndSync(posMs: Long) {
        val st = M4bPlayerStateHolder.state.value
        val chIdx = findChapterIndex(st.chapters, posMs)
        val segments = M4bPlayerStateHolder.syncSegments
        val seg = SyncAlignmentSerializer.findSegmentAtPosition(segments, posMs)

        M4bPlayerStateHolder.updateState {
            it.copy(
                currentPositionMs = posMs,
                currentChapterIndex = chIdx,
                activeSyncChapterIndex = seg?.chapterIndex ?: it.activeSyncChapterIndex,
                activeSyncSentenceIndex = seg?.sentenceIndex ?: it.activeSyncSentenceIndex
            )
        }
    }

    private fun findChapterIndex(chapters: List<AudiobookChapterMeta>, posMs: Long): Int {
        if (chapters.isEmpty()) return 0
        val idx = chapters.indexOfLast { posMs >= it.startMs }
        return idx.coerceAtLeast(0)
    }

    private fun persistProgressNow() {
        val id = M4bPlayerStateHolder.state.value.audiobookId
        val pos = exoPlayer?.currentPosition ?: M4bPlayerStateHolder.state.value.currentPositionMs
        if (id <= 0L) return
        val app = applicationContext as LexiVoiceApplication
        serviceScope.launch(Dispatchers.IO) {
            app.audiobookRepository.updatePlaybackProgress(id, pos)
        }
    }

    private fun stopAndDismiss() {
        persistProgressNow()
        pollerJob?.cancel()
        exoPlayer?.stop()
        M4bPlayerStateHolder.updateState { it.copy(isActive = false, isPlaying = false) }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateMediaSessionAndNotification() {
        val st = M4bPlayerStateHolder.state.value
        val stateCode = if (st.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val pbState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(stateCode, st.currentPositionMs, st.playbackSpeed)
            .build()
        mediaSession.setPlaybackState(pbState)

        val chapterName = st.chapters.getOrNull(st.currentChapterIndex)?.title ?: st.author
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, st.title.ifBlank { "LexiVoice Audiobook" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, chapterName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, st.durationMs)
            .build()
        mediaSession.setMetadata(meta)

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val st = M4bPlayerStateHolder.state.value
        val openIntent = PendingIntent.getActivity(
            this,
            301,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val rewindIntent = PendingIntent.getService(
            this,
            302,
            Intent(this, M4bPlaybackService::class.java)
                .setAction(ACTION_SKIP_BY)
                .putExtra(EXTRA_DELTA_MS, -15_000L),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPauseIntent = PendingIntent.getService(
            this,
            303,
            Intent(this, M4bPlaybackService::class.java).setAction(ACTION_TOGGLE_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val forwardIntent = PendingIntent.getService(
            this,
            304,
            Intent(this, M4bPlaybackService::class.java)
                .setAction(ACTION_SKIP_BY)
                .putExtra(EXTRA_DELTA_MS, 30_000L),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val chTitle = st.chapters.getOrNull(st.currentChapterIndex)?.title ?: st.author
        val coverBmp = st.coverPath?.let { path ->
            runCatching { File(path).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) } }.getOrNull()
        }

        return NotificationCompat.Builder(this, LexiVoiceApplication.CHANNEL_M4B_PLAYBACK)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(coverBmp)
            .setContentTitle(st.title.ifBlank { "Audiobook" })
            .setContentText(chTitle)
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(st.isPlaying)
            .addAction(android.R.drawable.ic_media_rew, "-15s", rewindIntent)
            .addAction(
                if (st.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (st.isPlaying) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_ff, "+30s", forwardIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun acquireWakeLock() {
        wakeLock?.let { if (!it.isHeld) it.acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onDestroy() {
        persistProgressNow()
        pollerJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        mediaSession.release()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4301

        const val ACTION_LOAD_AND_PLAY = "com.example.m4b.LOAD_AND_PLAY"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.example.m4b.TOGGLE_PLAY_PAUSE"
        const val ACTION_SEEK_TO = "com.example.m4b.SEEK_TO"
        const val ACTION_SKIP_BY = "com.example.m4b.SKIP_BY"
        const val ACTION_JUMP_CHAPTER = "com.example.m4b.JUMP_CHAPTER"
        const val ACTION_SET_SPEED = "com.example.m4b.SET_SPEED"
        const val ACTION_SET_SLEEP_TIMER = "com.example.m4b.SET_SLEEP_TIMER"
        const val ACTION_STOP = "com.example.m4b.STOP"

        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_AUTO_PLAY = "extra_auto_play"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_DELTA_MS = "extra_delta_ms"
        const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_SLEEP_MODE = "extra_sleep_mode"
        const val EXTRA_SLEEP_MINUTES = "extra_sleep_minutes"

        fun sendAction(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, M4bPlaybackService::class.java).setAction(action).apply(extras)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
