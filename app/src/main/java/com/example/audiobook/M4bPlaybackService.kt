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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.LexiVoiceApplication
import com.example.MainActivity
import com.example.data.db.AudiobookEntity
import com.example.overlay.FloatingOverlayController
import com.example.overlay.UnifiedOverlaySource
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val isFolderAudiobook: Boolean = false,
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
    val activeSyncSentenceIndex: Int = -1,
    val activeSyncText: String = ""
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
    private var bookObserverJob: Job? = null
    private val refreshMutex = Mutex()
    private var sleepEndTimeMs: Long = 0L
    private var lastChapterForSleep: Int = -1
    private var sleepTimerMinutes: Int = 0
    private var lastSavedTick: Long = 0L
    private var wasPausedByUser: Boolean = false

    private var loadedBookId: Long = -1L
    private var isPlaylistMode: Boolean = false
    private var playlistChapters: List<AudiobookChapterMeta> = emptyList()
    private var playlistChapterFiles: List<Mp3ChapterFileMeta> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LexiVoice::M4bPlaybackWakeLock")

        mediaSession = MediaSessionCompat(this, "LexiVoiceM4bSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = jumpRelativeChapterAsync(1)
                override fun onSkipToPrevious() = jumpRelativeChapterAsync(-1)
                override fun onFastForward() = skipBy(30_000L)
                override fun onRewind() = skipBy(-15_000L)
                override fun onSeekTo(pos: Long) = seekToGlobal(pos)
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
                            FloatingOverlayController.markActiveSource(UnifiedOverlaySource.AUDIOBOOK)
                            acquireWakeLock()
                            startPositionPoller()
                        } else {
                            releaseWakeLock()
                            persistProgressNow()
                        }
                        updateMediaSessionAndNotification()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (isPlaylistMode) {
                            refreshPositionAndSync(computeGlobalPositionMs(this@apply))
                            persistProgressNow()
                            updateMediaSessionAndNotification()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            if (!isPlaylistMode) {
                                val dur = duration.coerceAtLeast(0L)
                                if (dur > 0L) {
                                    M4bPlayerStateHolder.updateState { it.copy(durationMs = dur) }
                                }
                            }
                            updateMediaSessionAndNotification()
                        } else if (playbackState == Player.STATE_ENDED) {
                            M4bPlayerStateHolder.updateState { it.copy(isPlaying = false) }
                            persistProgressNow()
                            updateMediaSessionAndNotification()
                            serviceScope.launch {
                                syncLatestFromDatabase(autoPlayOnNewAfterEnd = true)
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("M4bPlaybackService", "ExoPlayer playback error: ${error.message}", error)
                        M4bPlayerStateHolder.updateState { it.copy(isPlaying = false) }
                        releaseWakeLock()
                        persistProgressNow()
                        updateMediaSessionAndNotification()
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
                val forceReload = intent.getBooleanExtra(EXTRA_FORCE_RELOAD, false)
                if (bookId > 0L) {
                    loadAudiobook(bookId, autoPlay, forceReload)
                }
            }
            ACTION_SYNC_CHAPTERS -> {
                serviceScope.launch {
                    syncLatestFromDatabase(autoPlayOnNewAfterEnd = false)
                }
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                if (exoPlayer?.isPlaying == true) pausePlayback() else resumePlayback()
            }
            ACTION_SEEK_TO -> {
                val pos = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                val autoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY, true)
                serviceScope.launch {
                    syncLatestFromDatabase(autoPlayOnNewAfterEnd = false)
                    seekToGlobal(pos)
                    if (autoPlay || exoPlayer?.isPlaying == true) {
                        resumePlayback()
                    }
                }
            }
            ACTION_SKIP_BY -> {
                val delta = intent.getLongExtra(EXTRA_DELTA_MS, 0L)
                skipBy(delta)
            }
            ACTION_NEXT_SENTENCE -> jumpRelativeSentence(1)
            ACTION_PREV_SENTENCE -> jumpRelativeSentence(-1)
            ACTION_NEXT_CHAPTER -> jumpRelativeChapterAsync(1)
            ACTION_PREV_CHAPTER -> jumpRelativeChapterAsync(-1)
            ACTION_JUMP_CHAPTER -> {
                val idx = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0)
                jumpToChapterAsync(idx)
            }
            ACTION_SET_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f).coerceIn(0.5f, 3.0f)
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

    private fun buildMediaItem(cf: Mp3ChapterFileMeta): MediaItem {
        val itemUri = if (cf.isDirectPath) Uri.fromFile(File(cf.fileUri)) else Uri.parse(cf.fileUri)
        return MediaItem.fromUri(itemUri)
    }

    private fun loadAudiobook(bookId: Long, autoPlay: Boolean, forceReload: Boolean) {
        val currentId = M4bPlayerStateHolder.state.value.audiobookId
        if (!forceReload && currentId == bookId && loadedBookId == bookId && (exoPlayer?.mediaItemCount ?: 0) > 0) {
            startObservingAudiobook(bookId)
            serviceScope.launch {
                syncLatestFromDatabase(autoPlayOnNewAfterEnd = autoPlay)
                if (autoPlay && exoPlayer?.isPlaying == false) {
                    resumePlayback()
                }
            }
            return
        }

        if (TtsStateHolder.state.value.isPlaying) {
            TtsPlaybackService.sendAction(this, TtsPlaybackService.ACTION_PAUSE)
        }
        FloatingOverlayController.markActiveSource(UnifiedOverlaySource.AUDIOBOOK)

        bookObserverJob?.cancel()
        loadedBookId = bookId

        val app = applicationContext as LexiVoiceApplication
        serviceScope.launch {
            val entity: AudiobookEntity? = withContext(Dispatchers.IO) {
                app.audiobookRepository.getAudiobookById(bookId)
            }
            if (entity == null) {
                stopSelf()
                return@launch
            }
            applyEntityUpdate(
                entity = entity,
                isInitialLoad = true,
                autoPlayOnInitial = autoPlay,
                autoPlayOnNewAfterEnd = autoPlay
            )
            startObservingAudiobook(bookId)
        }
    }

    private fun startObservingAudiobook(bookId: Long) {
        if (bookObserverJob?.isActive == true && loadedBookId == bookId) return
        bookObserverJob?.cancel()
        val app = applicationContext as LexiVoiceApplication
        bookObserverJob = serviceScope.launch {
            app.audiobookRepository.observeAudiobook(bookId)
                .distinctUntilChanged()
                .collect { entity ->
                    if (entity != null && entity.id == loadedBookId) {
                        applyEntityUpdate(
                            entity = entity,
                            isInitialLoad = false,
                            autoPlayOnNewAfterEnd = false
                        )
                    }
                }
        }
    }

    private suspend fun syncLatestFromDatabase(autoPlayOnNewAfterEnd: Boolean) {
        val bookId = loadedBookId
        if (bookId <= 0L) return
        val app = applicationContext as LexiVoiceApplication
        val latest = withContext(Dispatchers.IO) {
            app.audiobookRepository.getAudiobookById(bookId)
        } ?: return
        applyEntityUpdate(
            entity = latest,
            isInitialLoad = false,
            autoPlayOnNewAfterEnd = autoPlayOnNewAfterEnd
        )
    }

    private suspend fun applyEntityUpdate(
        entity: AudiobookEntity,
        isInitialLoad: Boolean,
        autoPlayOnInitial: Boolean = true,
        autoPlayOnNewAfterEnd: Boolean = false
    ) = refreshMutex.withLock {
        if (loadedBookId != entity.id) return@withLock

        val (chapters, chapterFiles, segments) = withContext(Dispatchers.IO) {
            val chs = SyncAlignmentSerializer.decodeChapters(entity.chaptersJson)
            val files = if (entity.isFolderAudiobook) {
                SyncAlignmentSerializer.decodeChapterFiles(entity.chapterFilesJson)
            } else {
                emptyList()
            }
            val segs = (entity.syncJsonPath?.takeIf { it.isNotBlank() }?.let { path ->
                SyncAlignmentSerializer.loadSegments(File(path)).takeIf { it.isNotEmpty() }
            } ?: run {
                val folder = entity.folderUri ?: entity.fileUri.removePrefix("folder://")
                val candidate1 = File(folder, "sync_map.json")
                if (candidate1.exists()) SyncAlignmentSerializer.loadSegments(candidate1)
                else null
            }).orEmpty()
            Triple(chs, files, segs)
        }

        val usePlaylist = entity.isFolderAudiobook && chapterFiles.isNotEmpty()
        val rawChapters = if (usePlaylist) {
            if (chapters.size == chapterFiles.size) chapters
            else chapterFiles.mapIndexed { idx, cf ->
                AudiobookChapterMeta(idx, cf.title, cf.startMs, cf.endMs)
            }
        } else {
            chapters
        }
        val resolvedChapters = SyncAlignmentSerializer.normalizeChapterOrdinals(rawChapters)
        val totalDur = if (usePlaylist) {
            resolvedChapters.lastOrNull()?.endMs ?: entity.durationMs
        } else {
            entity.durationMs
        }

        if (!isInitialLoad) {
            val filesUnchanged = !usePlaylist ||
                (chapterFiles == playlistChapterFiles && resolvedChapters == playlistChapters)
            val singleM4bUnchanged = usePlaylist ||
                (resolvedChapters == playlistChapters && totalDur == M4bPlayerStateHolder.state.value.durationMs)
            val segsUnchanged = segments.size == M4bPlayerStateHolder.syncSegments.size
            val mediaSynced = if (usePlaylist) {
                exoPlayer?.mediaItemCount == chapterFiles.size
            } else {
                true
            }
            if (filesUnchanged && singleM4bUnchanged && segsUnchanged && mediaSynced) {
                return@withLock
            }
        }

        M4bPlayerStateHolder.setSyncSegments(segments)
        val previousFiles = playlistChapterFiles
        isPlaylistMode = usePlaylist
        playlistChapters = resolvedChapters
        playlistChapterFiles = chapterFiles

        val player = exoPlayer
        if (isInitialLoad) {
            var startPos = entity.lastPositionMs.coerceIn(0L, totalDur.coerceAtLeast(entity.lastPositionMs))
            var inheritedFromLibrary = false
            // Resolve the EPUB on THIS device. The map's key names the recording
            // device's path, so an exact match normally fails; the reader also has
            // to open a file that actually exists here.
            val linkedBook = if (!entity.sourceEpubKey.isNullOrBlank()) {
                val app = applicationContext as LexiVoiceApplication
                withContext(Dispatchers.IO) {
                    app.libraryRepository.getBookByFileKeyOrName(entity.sourceEpubKey)
                }
            } else {
                null
            }
            val localEpubKey = linkedBook?.fileKey ?: entity.sourceEpubKey
            val localEpubIsDirect = linkedBook?.isDirectPath ?: entity.sourceEpubIsDirect

            if (!localEpubKey.isNullOrBlank() && segments.isNotEmpty()) {
                if (linkedBook != null &&
                    (entity.lastPlayedAt == 0L || linkedBook.lastOpenedAt > entity.lastPlayedAt) &&
                    (linkedBook.currentChapterIndex > 0 || linkedBook.currentSentenceIndex > 0)
                ) {
                    val exactSeg = segments.firstOrNull {
                        it.chapterIndex == linkedBook.currentChapterIndex &&
                            it.sentenceIndex == linkedBook.currentSentenceIndex
                    } ?: segments.firstOrNull {
                        it.chapterIndex == linkedBook.currentChapterIndex
                    }
                    if (exactSeg != null) {
                        startPos = exactSeg.startMs.coerceIn(0L, totalDur.coerceAtLeast(exactSeg.startMs))
                        inheritedFromLibrary = true
                    }
                }
            }
            val initialSeg = SyncAlignmentSerializer.findSegmentAtPosition(segments, startPos)
            val chIdx = findChapterIndex(resolvedChapters, startPos)
            lastChapterForSleep = chIdx

            M4bPlayerStateHolder.updateState {
                M4bPlaybackState(
                    audiobookId = entity.id,
                    title = entity.title,
                    author = entity.author,
                    narrator = entity.narrator,
                    coverPath = entity.coverPath ?: it.coverPath,
                    isActive = true,
                    isPlaying = autoPlayOnInitial,
                    isFolderAudiobook = usePlaylist,
                    currentPositionMs = startPos,
                    durationMs = totalDur,
                    playbackSpeed = it.playbackSpeed,
                    chapters = resolvedChapters,
                    currentChapterIndex = chIdx,
                    sleepTimerMode = it.sleepTimerMode,
                    sleepRemainingMs = it.sleepRemainingMs,
                    sourceEpubKey = localEpubKey,
                    sourceEpubIsDirect = localEpubIsDirect,
                    hasSyncedEpub = !localEpubKey.isNullOrBlank() && segments.isNotEmpty(),
                    activeSyncChapterIndex = initialSeg?.chapterIndex ?: 0,
                    activeSyncSentenceIndex = initialSeg?.sentenceIndex ?: -1,
                    activeSyncText = initialSeg?.text.orEmpty()
                )
            }

            player?.apply {
                if (usePlaylist) {
                    val mediaItems = chapterFiles.map { buildMediaItem(it) }
                    val startItemIdx = chIdx.coerceIn(0, mediaItems.lastIndex)
                    val chStart = resolvedChapters.getOrNull(startItemIdx)?.startMs ?: 0L
                    val offsetInItem = (startPos - chStart).coerceAtLeast(0L)
                    setMediaItems(mediaItems, startItemIdx, offsetInItem)
                } else {
                    val uri = if (entity.isDirectPath) {
                        Uri.fromFile(File(entity.fileUri))
                    } else {
                        Uri.parse(entity.fileUri)
                    }
                    setMediaItem(MediaItem.fromUri(uri))
                    if (startPos > 0L) seekTo(startPos)
                }
                playbackParameters = PlaybackParameters(M4bPlayerStateHolder.state.value.playbackSpeed)
                prepare()
                playWhenReady = autoPlayOnInitial
            }

            startForeground(NOTIFICATION_ID, buildNotification())
            if (inheritedFromLibrary) {
                persistProgressNow(overridePosMs = startPos)
            }
            updateMediaSessionAndNotification()
            if (autoPlayOnInitial) startPositionPoller()
            return@withLock
        }

        if (player != null && usePlaylist) {
            val wasEnded = player.playbackState == Player.STATE_ENDED
            val wasTryingToPlay = player.isPlaying || player.playWhenReady

            val isPureAppend = previousFiles.isNotEmpty() &&
                chapterFiles.size > previousFiles.size &&
                player.mediaItemCount == previousFiles.size &&
                (0 until previousFiles.size).all { previousFiles[it].fileUri == chapterFiles[it].fileUri }

            if (isPureAppend) {
                val addedItems = chapterFiles.subList(previousFiles.size, chapterFiles.size).map { buildMediaItem(it) }
                player.addMediaItems(addedItems)
                if (wasEnded) {
                    val nextIdx = previousFiles.size.coerceIn(0, (chapterFiles.size - 1).coerceAtLeast(0))
                    player.seekTo(nextIdx, 0L)
                    player.prepare()
                    val shouldPlay = autoPlayOnNewAfterEnd || wasTryingToPlay
                    player.playWhenReady = shouldPlay
                    if (shouldPlay) startPositionPoller()
                }
            } else if (chapterFiles != previousFiles || player.mediaItemCount != chapterFiles.size) {
                val curIdx = player.currentMediaItemIndex
                val activeFileUri = previousFiles.getOrNull(curIdx)?.fileUri
                val curItemOffset = player.currentPosition.coerceAtLeast(0L)
                val globalPosBefore = computeGlobalPositionMs(player)

                val matchedIdx = activeFileUri?.let { uri ->
                    chapterFiles.indexOfFirst { it.fileUri == uri }
                }?.takeIf { it >= 0 }

                val targetIdx: Int
                val targetOffset: Long
                if (matchedIdx != null) {
                    if (wasEnded && matchedIdx + 1 < chapterFiles.size) {
                        targetIdx = matchedIdx + 1
                        targetOffset = 0L
                    } else {
                        targetIdx = matchedIdx
                        targetOffset = curItemOffset
                    }
                } else {
                    val fallbackIdx = findChapterIndex(resolvedChapters, globalPosBefore)
                        .coerceIn(0, (chapterFiles.size - 1).coerceAtLeast(0))
                    val chStart = resolvedChapters.getOrNull(fallbackIdx)?.startMs ?: 0L
                    targetIdx = fallbackIdx
                    targetOffset = (globalPosBefore - chStart).coerceAtLeast(0L)
                }

                val allItems = chapterFiles.map { buildMediaItem(it) }
                player.setMediaItems(allItems, targetIdx, targetOffset)
                player.playbackParameters = PlaybackParameters(M4bPlayerStateHolder.state.value.playbackSpeed)
                player.prepare()
                val shouldPlay = autoPlayOnNewAfterEnd || wasTryingToPlay
                player.playWhenReady = shouldPlay
                if (shouldPlay) startPositionPoller()
            }
        }

        val livePos = player?.let { computeGlobalPositionMs(it) } ?: M4bPlayerStateHolder.state.value.currentPositionMs
        val chIdx = if (usePlaylist && player != null) {
            player.currentMediaItemIndex.coerceIn(0, (resolvedChapters.size - 1).coerceAtLeast(0))
        } else {
            findChapterIndex(resolvedChapters, livePos)
        }
        val activeSeg = SyncAlignmentSerializer.findSegmentAtPosition(segments, livePos)

        M4bPlayerStateHolder.updateState {
            it.copy(
                title = entity.title.ifBlank { it.title },
                author = entity.author.ifBlank { it.author },
                narrator = entity.narrator.ifBlank { it.narrator },
                coverPath = entity.coverPath ?: it.coverPath,
                isFolderAudiobook = usePlaylist,
                currentPositionMs = livePos,
                durationMs = totalDur,
                chapters = resolvedChapters,
                currentChapterIndex = chIdx,
                sourceEpubKey = entity.sourceEpubKey ?: it.sourceEpubKey,
                sourceEpubIsDirect = if (entity.sourceEpubKey != null) entity.sourceEpubIsDirect else it.sourceEpubIsDirect,
                hasSyncedEpub = !(entity.sourceEpubKey ?: it.sourceEpubKey).isNullOrBlank() && segments.isNotEmpty(),
                activeSyncChapterIndex = activeSeg?.chapterIndex ?: it.activeSyncChapterIndex,
                activeSyncSentenceIndex = activeSeg?.sentenceIndex ?: it.activeSyncSentenceIndex,
                activeSyncText = activeSeg?.text ?: it.activeSyncText
            )
        }
        updateMediaSessionAndNotification()
    }

    private fun resumePlayback() {
        if (TtsStateHolder.state.value.isPlaying) {
            TtsPlaybackService.sendAction(this, TtsPlaybackService.ACTION_PAUSE)
        }
        FloatingOverlayController.markActiveSource(UnifiedOverlaySource.AUDIOBOOK)
        serviceScope.launch {
            val prevCount = exoPlayer?.mediaItemCount ?: 0
            syncLatestFromDatabase(autoPlayOnNewAfterEnd = true)
            val player = exoPlayer ?: return@launch
            if (player.playbackState == Player.STATE_ENDED) {
                val grew = player.mediaItemCount > prevCount
                if (!grew) {
                    if (isPlaylistMode) {
                        player.seekTo(0, 0L)
                    } else {
                        player.seekTo(0L)
                    }
                }
                player.prepare()
            } else {
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                if (wasPausedByUser) {
                    val currentGlobal = computeGlobalPositionMs(player)
                    val chIdx = findChapterIndex(playlistChapters, currentGlobal)
                    val chStart = playlistChapters.getOrNull(chIdx)?.startMs ?: 0L
                    if (currentGlobal - chStart > 1_200L) {
                        seekToGlobal(currentGlobal - 1_000L)
                    }
                }
            }
            wasPausedByUser = false
            player.play()
            startForeground(NOTIFICATION_ID, buildNotification())
            startPositionPoller()
        }
    }

    private fun pausePlayback() {
        wasPausedByUser = true
        exoPlayer?.pause()
        persistProgressNow()
        updateMediaSessionAndNotification()
    }

    private fun computeGlobalPositionMs(player: ExoPlayer): Long {
        if (!isPlaylistMode) return player.currentPosition.coerceAtLeast(0L)
        val idx = player.currentMediaItemIndex.coerceIn(0, (playlistChapters.size - 1).coerceAtLeast(0))
        val chStart = playlistChapters.getOrNull(idx)?.startMs ?: 0L
        return chStart + player.currentPosition.coerceAtLeast(0L)
    }

    private fun seekToGlobal(posMs: Long) {
        wasPausedByUser = false
        val player = exoPlayer ?: return
        val dur = if (isPlaylistMode) {
            M4bPlayerStateHolder.state.value.durationMs
        } else {
            player.duration.takeIf { it > 0L } ?: M4bPlayerStateHolder.state.value.durationMs
        }
        val clamped = posMs.coerceIn(0L, dur.coerceAtLeast(posMs))
        if (isPlaylistMode && playlistChapters.isNotEmpty()) {
            val maxItemIdx = (player.mediaItemCount - 1).coerceAtLeast(0)
            val targetIdx = findChapterIndex(playlistChapters, clamped).coerceIn(0, maxItemIdx)
            val chStart = playlistChapters.getOrNull(targetIdx)?.startMs ?: 0L
            val offsetInItem = (clamped - chStart).coerceAtLeast(0L)
            player.seekTo(targetIdx, offsetInItem)
        } else {
            player.seekTo(clamped)
        }
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            player.prepare()
        }
        refreshPositionAndSync(clamped)
        persistProgressNow()
    }

    private fun skipBy(deltaMs: Long) {
        val player = exoPlayer
        val current = if (player != null) computeGlobalPositionMs(player) else M4bPlayerStateHolder.state.value.currentPositionMs
        seekToGlobal(current + deltaMs)
    }

    private fun jumpRelativeSentence(delta: Int) {
        serviceScope.launch {
            syncLatestFromDatabase(autoPlayOnNewAfterEnd = false)
            val segments = M4bPlayerStateHolder.syncSegments
            if (segments.isEmpty()) {
                skipBy(if (delta > 0) 30_000L else -15_000L)
                return@launch
            }
            val st = M4bPlayerStateHolder.state.value
            val curIdx = segments.indexOfFirst {
                it.chapterIndex == st.activeSyncChapterIndex && it.sentenceIndex == st.activeSyncSentenceIndex
            }.takeIf { it >= 0 } ?: 0
            val target = (curIdx + delta).coerceIn(0, segments.lastIndex)
            seekToGlobal(segments[target].startMs)
        }
    }

    private fun jumpToChapterAsync(chapterIndex: Int) {
        serviceScope.launch {
            syncLatestFromDatabase(autoPlayOnNewAfterEnd = false)
            jumpToChapterInternal(chapterIndex, startPlayback = false)
        }
    }

    private fun jumpRelativeChapterAsync(delta: Int) {
        val oldIdx = M4bPlayerStateHolder.state.value.currentChapterIndex
        val wasEnded = exoPlayer?.playbackState == Player.STATE_ENDED
        serviceScope.launch {
            syncLatestFromDatabase(autoPlayOnNewAfterEnd = false)
            val chapters = M4bPlayerStateHolder.state.value.chapters
            if (chapters.isEmpty()) return@launch
            val targetIdx = (oldIdx + delta).coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            jumpToChapterInternal(targetIdx, startPlayback = wasEnded)
        }
    }

    private fun jumpToChapterInternal(chapterIndex: Int, startPlayback: Boolean) {
        wasPausedByUser = false
        val chapters = M4bPlayerStateHolder.state.value.chapters
        if (chapters.isEmpty()) return
        val clampedIdx = chapterIndex.coerceIn(0, chapters.lastIndex)
        val target = chapters[clampedIdx]
        val player = exoPlayer
        if (isPlaylistMode) {
            val itemIdx = clampedIdx.coerceIn(0, ((player?.mediaItemCount ?: 1) - 1).coerceAtLeast(0))
            player?.seekTo(itemIdx, 0L)
            if (player != null && (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED)) {
                player.prepare()
            }
            if (startPlayback) {
                player?.play()
                startPositionPoller()
            }
            refreshPositionAndSync(target.startMs)
            persistProgressNow()
            updateMediaSessionAndNotification()
        } else {
            seekToGlobal(target.startMs)
            if (startPlayback) {
                player?.play()
                startPositionPoller()
            }
        }
    }

    private fun configureSleepTimer(mode: SleepTimerMode, minutes: Int) {
        if (mode == SleepTimerMode.MINUTES) sleepTimerMinutes = minutes.coerceIn(1, 600)
        sleepEndTimeMs = if (mode == SleepTimerMode.MINUTES) {
            System.currentTimeMillis() + (sleepTimerMinutes * 60_000L)
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
                    val pos = computeGlobalPositionMs(player)
                    refreshPositionAndSync(pos)

                    val now = System.currentTimeMillis()
                    if (now - lastSavedTick > 3000L) {
                        lastSavedTick = now
                        persistProgressNow()
                    }

                    val st = M4bPlayerStateHolder.state.value
                    if (st.sleepTimerMode == SleepTimerMode.MINUTES) {
                        // The timer stays armed until the user switches it off, so a
                        // fresh countdown begins as soon as playback resumes.
                        if (sleepEndTimeMs <= 0L && sleepTimerMinutes > 0 && player.isPlaying) {
                            sleepEndTimeMs = now + sleepTimerMinutes * 60_000L
                            M4bPlayerStateHolder.updateState {
                                it.copy(sleepRemainingMs = sleepTimerMinutes * 60_000L)
                            }
                        }
                        if (sleepEndTimeMs > 0L) {
                            val rem = sleepEndTimeMs - now
                            if (rem <= 0L) {
                                pausePlayback()
                                sleepEndTimeMs = 0L
                                M4bPlayerStateHolder.updateState {
                                    it.copy(sleepRemainingMs = sleepTimerMinutes * 60_000L)
                                }
                            } else {
                                M4bPlayerStateHolder.updateState { it.copy(sleepRemainingMs = rem) }
                            }
                        }
                    } else if (st.sleepTimerMode == SleepTimerMode.END_OF_CHAPTER) {
                        if (st.currentChapterIndex > lastChapterForSleep && player.isPlaying) {
                            pausePlayback()
                            // Stay armed: re-anchor to this chapter so the end of the
                            // next chapter stops playback again.
                            lastChapterForSleep = st.currentChapterIndex
                        }
                    }
                }
                delay(150L)
            }
        }
    }

    private fun refreshPositionAndSync(posMs: Long) {
        val st = M4bPlayerStateHolder.state.value
        val chIdx = if (isPlaylistMode) {
            exoPlayer?.currentMediaItemIndex?.coerceIn(0, (st.chapters.size - 1).coerceAtLeast(0))
                ?: findChapterIndex(st.chapters, posMs)
        } else {
            findChapterIndex(st.chapters, posMs)
        }
        val segments = M4bPlayerStateHolder.syncSegments
        val seg = SyncAlignmentSerializer.findSegmentAtPosition(segments, posMs)

        M4bPlayerStateHolder.updateState {
            it.copy(
                currentPositionMs = posMs,
                currentChapterIndex = chIdx,
                activeSyncChapterIndex = seg?.chapterIndex ?: it.activeSyncChapterIndex,
                activeSyncSentenceIndex = seg?.sentenceIndex ?: it.activeSyncSentenceIndex,
                activeSyncText = seg?.text ?: it.activeSyncText
            )
        }
    }

    private fun findChapterIndex(chapters: List<AudiobookChapterMeta>, posMs: Long): Int {
        if (chapters.isEmpty()) return 0
        val idx = chapters.indexOfLast { posMs >= it.startMs }
        return idx.coerceAtLeast(0)
    }

    private fun persistProgressNow(overridePosMs: Long? = null) {
        val st = M4bPlayerStateHolder.state.value
        val id = st.audiobookId
        val pos = overridePosMs ?: exoPlayer?.let { computeGlobalPositionMs(it) } ?: st.currentPositionMs
        if (id <= 0L) return
        val syncTs = System.currentTimeMillis()
        val epubKey = st.sourceEpubKey
        val activeSeg = SyncAlignmentSerializer.findSegmentAtPosition(M4bPlayerStateHolder.syncSegments, pos)
        val targetCh = activeSeg?.chapterIndex ?: st.currentChapterIndex
        val targetSent = activeSeg?.sentenceIndex ?: st.activeSyncSentenceIndex.coerceAtLeast(0)
        val fallbackTotalChapters = st.chapters.size

        val app = applicationContext as LexiVoiceApplication
        serviceScope.launch(Dispatchers.IO) {
            app.audiobookRepository.updatePlaybackProgress(id, pos, syncTs)
            if (!epubKey.isNullOrBlank()) {
                val book = app.libraryRepository.getBookByFileKey(epubKey)
                if (book != null) {
                    app.libraryRepository.updateReadingProgress(
                        bookId = book.id,
                        chapterIndex = targetCh,
                        sentenceIndex = targetSent,
                        scrollOffset = 0,
                        totalChapters = book.chapterCount.coerceAtLeast(fallbackTotalChapters).coerceAtLeast(1),
                        syncedAt = syncTs
                    )
                }
            }
        }
    }

    private fun stopAndDismiss() {
        persistProgressNow()
        pollerJob?.cancel()
        bookObserverJob?.cancel()
        loadedBookId = -1L
        playlistChapterFiles = emptyList()
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
        bookObserverJob?.cancel()
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
        const val ACTION_SYNC_CHAPTERS = "com.example.m4b.SYNC_CHAPTERS"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.example.m4b.TOGGLE_PLAY_PAUSE"
        const val ACTION_SEEK_TO = "com.example.m4b.SEEK_TO"
        const val ACTION_SKIP_BY = "com.example.m4b.SKIP_BY"
        const val ACTION_NEXT_SENTENCE = "com.example.m4b.NEXT_SENTENCE"
        const val ACTION_PREV_SENTENCE = "com.example.m4b.PREV_SENTENCE"
        const val ACTION_NEXT_CHAPTER = "com.example.m4b.NEXT_CHAPTER"
        const val ACTION_PREV_CHAPTER = "com.example.m4b.PREV_CHAPTER"
        const val ACTION_JUMP_CHAPTER = "com.example.m4b.JUMP_CHAPTER"
        const val ACTION_SET_SPEED = "com.example.m4b.SET_SPEED"
        const val ACTION_SET_SLEEP_TIMER = "com.example.m4b.SET_SLEEP_TIMER"
        const val ACTION_STOP = "com.example.m4b.STOP"

        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_AUTO_PLAY = "extra_auto_play"
        const val EXTRA_FORCE_RELOAD = "extra_force_reload"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_DELTA_MS = "extra_delta_ms"
        const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_SLEEP_MODE = "extra_sleep_mode"
        const val EXTRA_SLEEP_MINUTES = "extra_sleep_minutes"

        fun sendAction(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, M4bPlaybackService::class.java).setAction(action).apply(extras)
            if (action == ACTION_STOP) {
                runCatching { context.startService(intent) }
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
