package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.example.LexiVoiceApplication
import com.example.R
import com.example.audiobook.M4bPlaybackService
import com.example.audiobook.M4bPlaybackState
import com.example.audiobook.M4bPlayerStateHolder
import com.example.audiobook.SyncReconciler
import com.example.data.prefs.ReaderSettings
import com.example.epub.EpubChapterSummary
import com.example.epub.EpubParser
import com.example.overlay.FloatingOverlayController
import com.example.tts.SleepTimerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun M4bPlayerScreen(
    audiobookId: Long,
    onBack: () -> Unit,
    forceReadAlongTrigger: Long = 0L,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as LexiVoiceApplication
    val playerState by M4bPlayerStateHolder.state.collectAsStateWithLifecycle()
    val overlayState by FloatingOverlayController.state.collectAsStateWithLifecycle()
    val readerSettings by app.preferencesRepository.readerSettings.collectAsStateWithLifecycle(ReaderSettings())
    val scope = rememberCoroutineScope()
    val rewindSec = readerSettings.audiobookRewindSeconds
    val forwardSec = readerSettings.audiobookForwardSeconds

    var showChaptersSheet by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showSkipIntervalDialog by remember { mutableStateOf(false) }
    var readAlongEnabled by remember { mutableStateOf(true) }
    var epubChapters by remember { mutableStateOf<List<EpubChapterSummary>>(emptyList()) }
    var loadedWebPayload by remember { mutableStateOf<EpubParser.WebChapterPayload?>(null) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekSliderPos by remember { mutableFloatStateOf(0f) }
    var lastMatchedGlobalIndex by remember(playerState.audiobookId, playerState.activeSyncChapterIndex) {
        mutableStateOf(-1)
    }

    BackHandler { onBack() }

    LaunchedEffect(forceReadAlongTrigger) {
        if (forceReadAlongTrigger > 0L) {
            readAlongEnabled = true
        }
    }

    LaunchedEffect(audiobookId) {
        if (playerState.audiobookId != audiobookId || !playerState.isActive) {
            M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_LOAD_AND_PLAY) {
                putExtra(M4bPlaybackService.EXTRA_BOOK_ID, audiobookId)
                putExtra(M4bPlaybackService.EXTRA_AUTO_PLAY, true)
            }
        } else {
            M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SYNC_CHAPTERS)
        }
    }

    // Parse linked EPUB structure once when available
    LaunchedEffect(playerState.sourceEpubKey, playerState.sourceEpubIsDirect) {
        val epubKey = playerState.sourceEpubKey
        if (!epubKey.isNullOrBlank()) {
            val struct = withContext(Dispatchers.IO) {
                EpubParser.parseBookStructure(
                    context = context,
                    fileKey = epubKey,
                    isDirectPath = playerState.sourceEpubIsDirect,
                    fallbackTitle = playerState.title
                )
            }
            epubChapters = struct.chapters
        } else {
            epubChapters = emptyList()
            loadedWebPayload = null
        }
    }

    // Keep synced EPUB chapter loaded for both Lyric Read-Along & Cover Live Subtitle Box
    LaunchedEffect(
        playerState.activeSyncChapterIndex,
        epubChapters,
        playerState.hasSyncedEpub
    ) {
        val epubKey = playerState.sourceEpubKey
        if (!playerState.hasSyncedEpub || epubKey.isNullOrBlank() || epubChapters.isEmpty()) {
            return@LaunchedEffect
        }
        val targetCh = epubChapters.firstOrNull { it.index == playerState.activeSyncChapterIndex }
            ?: epubChapters.getOrNull(playerState.activeSyncChapterIndex)
            ?: return@LaunchedEffect
        if (loadedWebPayload?.chapterIndex != targetCh.index) {
            val payload = withContext(Dispatchers.IO) {
                EpubParser.loadChapterForWebView(
                    context = context,
                    fileKey = epubKey,
                    isDirectPath = playerState.sourceEpubIsDirect,
                    chapterIndex = targetCh.index,
                    chapterHref = targetCh.href,
                    chapterTitle = targetCh.title
                )
            }
            loadedWebPayload = payload
        }
    }

    val showingReadAlong = playerState.hasSyncedEpub && readAlongEnabled
    val currentChMeta = playerState.chapters.getOrNull(playerState.currentChapterIndex)
    val chStartMs = currentChMeta?.startMs ?: 0L
    val chEndMs = (currentChMeta?.endMs ?: playerState.durationMs).coerceAtLeast(chStartMs + 1L)
    val chDurMs = (chEndMs - chStartMs).coerceAtLeast(1L)
    val rawChPosMs = (playerState.currentPositionMs - chStartMs).coerceIn(0L, chDurMs)
    val displayChPosMs = if (isSeeking) seekSliderPos.toLong().coerceIn(0L, chDurMs) else rawChPosMs

    // The chapter's spoken segments, in spoken order.
    val chapterSyncSegments = remember(
        loadedWebPayload?.chapterIndex,
        playerState.activeSyncChapterIndex
    ) {
        val payload = loadedWebPayload
        if (payload == null || payload.chapterIndex != playerState.activeSyncChapterIndex) {
            emptyList()
        } else {
            M4bPlayerStateHolder.syncSegments.filter { it.chapterIndex == payload.chapterIndex }
        }
    }

    // Map each spoken segment onto the sentence the parser produces now. Computed
    // once per chapter rather than per frame, and independent of playback direction,
    // so seeking backwards cannot lose the alignment the way the old forward-only
    // cursor did.
    val segmentToSentence = remember(loadedWebPayload?.chapterIndex, chapterSyncSegments) {
        val payload = loadedWebPayload
        if (payload == null || payload.sentences.isEmpty() || chapterSyncSegments.isEmpty()) {
            IntArray(0)
        } else {
            SyncReconciler.align(
                segmentTexts = chapterSyncSegments.map { it.text },
                sentenceTexts = payload.sentences.map { it.text }
            )
        }
    }

    val activeHighlightIdx = remember(
        loadedWebPayload?.chapterIndex,
        segmentToSentence,
        playerState.activeSyncSentenceIndex
    ) {
        val payload = loadedWebPayload ?: return@remember -1
        if (payload.chapterIndex != playerState.activeSyncChapterIndex || payload.sentences.isEmpty()) {
            return@remember -1
        }
        val segmentPos = chapterSyncSegments.indexOfFirst {
            it.sentenceIndex == playerState.activeSyncSentenceIndex
        }
        val sentencePos = if (segmentPos >= 0) {
            segmentToSentence.getOrElse(segmentPos) { -1 }
        } else {
            -1
        }
        // Fall back to the raw index so behaviour degrades to the previous
        // behaviour rather than highlighting nothing.
        payload.sentences.getOrNull(sentencePos)?.globalIndex
            ?: payload.sentences.getOrNull(playerState.activeSyncSentenceIndex)?.globalIndex
            ?: playerState.activeSyncSentenceIndex
    }


    val activeParagraphIdx = remember(loadedWebPayload?.paragraphs, activeHighlightIdx) {
        val payload = loadedWebPayload ?: return@remember -1
        if (activeHighlightIdx < 0) return@remember -1
        payload.paragraphs.indexOfFirst { para ->
            para.sentences.any { it.globalIndex == activeHighlightIdx }
        }
    }

    val liveSubtitleText = remember(loadedWebPayload, activeParagraphIdx, playerState.activeSyncText) {
        val paraText = loadedWebPayload?.paragraphs?.getOrNull(activeParagraphIdx)?.text?.trim()
        paraText?.takeIf { it.isNotBlank() } ?: playerState.activeSyncText.trim()
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("m4b_player_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = playerState.title.ifBlank { "Audiobook Player" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val chLabel = currentChMeta?.title ?: playerState.author
                        val subtitleText = if (playerState.chapters.isNotEmpty()) {
                            "Ch ${playerState.currentChapterIndex + 1}/${playerState.chapters.size} • $chLabel"
                        } else {
                            chLabel
                        }
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val totalDur = playerState.durationMs.coerceAtLeast(1L)
                        val bookElapsed = playerState.currentPositionMs.coerceIn(0L, totalDur)
                        val bookLeft = (totalDur - bookElapsed).coerceAtLeast(0L)
                        Text(
                            text = "Book: ${formatDuration(bookElapsed)} / ${formatDuration(totalDur)} • ${formatDuration(bookLeft)} left",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("m4b_whole_book_progress_label")
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            FloatingOverlayController.setVisible(context, !overlayState.isVisible)
                        },
                        modifier = Modifier.testTag("m4b_floating_popup_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = if (overlayState.isVisible) "Hide Floating Overlay" else "Show Floating Overlay",
                            tint = if (overlayState.isVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (playerState.hasSyncedEpub) {
                        IconButton(
                            onClick = { readAlongEnabled = !readAlongEnabled },
                            modifier = Modifier.testTag("m4b_toggle_read_along_button")
                        ) {
                            Icon(
                                imageVector = if (showingReadAlong) Icons.Default.Headphones else Icons.Default.AutoStories,
                                contentDescription = if (showingReadAlong) "Show Cover View" else "Show Synced Read-Along",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = { showChaptersSheet = true },
                        modifier = Modifier.testTag("m4b_chapters_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Audiobook Chapters")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Slider(
                        value = displayChPosMs.toFloat().coerceIn(0f, chDurMs.toFloat()),
                        onValueChange = { newVal ->
                            isSeeking = true
                            seekSliderPos = newVal
                        },
                        onValueChangeFinished = {
                            isSeeking = false
                            lastMatchedGlobalIndex = -1
                            val targetGlobalPos = chStartMs + seekSliderPos.toLong().coerceIn(0L, chDurMs)
                            M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SEEK_TO) {
                                putExtra(M4bPlaybackService.EXTRA_POSITION_MS, targetGlobalPos)
                            }
                        },
                        valueRange = 0f..chDurMs.toFloat(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("m4b_seek_slider")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(displayChPosMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("m4b_chapter_elapsed_label")
                        )
                        Text(
                            text = "-${formatDuration((chDurMs - displayChPosMs).coerceAtLeast(0L))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("m4b_chapter_remaining_label")
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showSpeedDialog = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("m4b_speed_button")
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.2fx", playerState.playbackSpeed),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        IconButton(
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_PREV_CHAPTER)
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("m4b_prev_chapter_button")
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Chapter")
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .widthIn(min = 46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = {
                                        lastMatchedGlobalIndex = -1
                                        M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SKIP_BY) {
                                            putExtra(M4bPlaybackService.EXTRA_DELTA_MS, -(rewindSec * 1000L))
                                        }
                                    },
                                    onLongClick = { showSkipIntervalDialog = true }
                                )
                                .testTag("m4b_rewind_15_button")
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Replay10, contentDescription = "Rewind $rewindSec seconds")
                                Text("-${rewindSec}s", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        FilledIconButton(
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
                            },
                            modifier = Modifier
                                .size(54.dp)
                                .testTag("m4b_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playerState.isPlaying) "Pause" else "Play"
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .widthIn(min = 46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = {
                                        M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SKIP_BY) {
                                            putExtra(M4bPlaybackService.EXTRA_DELTA_MS, forwardSec * 1000L)
                                        }
                                    },
                                    onLongClick = { showSkipIntervalDialog = true }
                                )
                                .testTag("m4b_forward_30_button")
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Forward30, contentDescription = "Forward $forwardSec seconds")
                                Text("+${forwardSec}s", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_NEXT_CHAPTER)
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("m4b_next_chapter_button")
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter")
                        }
                        IconButton(
                            onClick = { showSleepDialog = true },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("m4b_sleep_timer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = "Sleep Timer",
                                tint = if (playerState.sleepTimerMode != SleepTimerMode.OFF) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (showingReadAlong) {
                val payload = loadedWebPayload
                if (payload != null) {
                    LyricReadAlongView(
                        payload = payload,
                        playerState = playerState,
                        activeParagraphIndex = activeParagraphIdx,
                        onSeekToParagraph = { paraIndex ->
                            val targetPara = payload.paragraphs.getOrNull(paraIndex) ?: return@LyricReadAlongView
                            val targetSentenceIdx = targetPara.sentences.firstOrNull()?.globalIndex ?: 0
                            lastMatchedGlobalIndex = targetSentenceIdx
                            val posMs = resolveParagraphStartMs(
                                payload = payload,
                                targetSentenceIdx = targetSentenceIdx,
                                playerState = playerState
                            )
                            if (posMs != null) {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SEEK_TO) {
                                    putExtra(M4bPlaybackService.EXTRA_POSITION_MS, posMs)
                                    putExtra(M4bPlaybackService.EXTRA_AUTO_PLAY, true)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                // Cover Mode: Dynamic Aspect-Ratio Cover + Title/Author + Live Current-Paragraph Subtitle Box
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(Modifier.height(14.dp))
                    DynamicAudiobookCover(
                        coverPath = playerState.coverPath,
                        title = playerState.title
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = playerState.title.ifBlank { "Loading..." },
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    val authorLine = listOf(playerState.author, playerState.narrator)
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                    if (authorLine.isNotBlank()) {
                        Text(
                            text = authorLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val chapterTitle = currentChMeta?.title
                    if (!chapterTitle.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape
                        ) {
                            Text(
                                text = chapterTitle,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }

                    if (liveSubtitleText.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("m4b_live_subtitle_box")
                        ) {
                            AnimatedContent(
                                targetState = liveSubtitleText,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "live_subtitle_crossfade"
                            ) { currentLine ->
                                Text(
                                    text = currentLine,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                                    textAlign = TextAlign.Center,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                )
                            }
                        }
                    }

                    if (playerState.sleepTimerMode != SleepTimerMode.OFF) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (playerState.sleepTimerMode == SleepTimerMode.END_OF_CHAPTER) {
                                "Sleep timer: End of chapter"
                            } else {
                                "Sleep timer: ${formatDuration(playerState.sleepRemainingMs)} left"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    if (showChaptersSheet) {
        val chapterListState = rememberLazyListState()
        LaunchedEffect(showChaptersSheet) {
            if (showChaptersSheet && playerState.chapters.isNotEmpty()) {
                val targetIdx = (playerState.currentChapterIndex - 2)
                    .coerceIn(0, playerState.chapters.lastIndex)
                chapterListState.scrollToItem(targetIdx)
            }
        }
        ModalBottomSheet(
            onDismissRequest = { showChaptersSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "Audiobook Chapters (${playerState.chapters.size})",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                LazyColumn(
                    state = chapterListState,
                    modifier = Modifier.height(400.dp)
                ) {
                    itemsIndexed(playerState.chapters, key = { idx, ch -> "${idx}_${ch.startMs}" }) { idx, ch ->
                        val isCurrent = idx == playerState.currentChapterIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_JUMP_CHAPTER) {
                                        putExtra(M4bPlaybackService.EXTRA_CHAPTER_INDEX, idx)
                                    }
                                    showChaptersSheet = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${idx + 1}.",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(34.dp)
                            )
                            Text(
                                text = ch.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatDuration(ch.startMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showSpeedDialog) {
        val updateSpeed: (Float) -> Unit = { spd ->
            val clamped = ((spd * 20f).let { kotlin.math.round(it) } / 20f).coerceIn(0.5f, 3.0f)
            M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SET_SPEED) {
                putExtra(M4bPlaybackService.EXTRA_SPEED, clamped)
            }
        }
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { updateSpeed(playerState.playbackSpeed - 0.05f) },
                            modifier = Modifier.testTag("m4b_speed_minus_button")
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease speed")
                        }
                        Text(
                            text = String.format(Locale.US, "%.2fx", playerState.playbackSpeed),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        IconButton(
                            onClick = { updateSpeed(playerState.playbackSpeed + 0.05f) },
                            modifier = Modifier.testTag("m4b_speed_plus_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase speed")
                        }
                    }
                    Slider(
                        value = playerState.playbackSpeed.coerceIn(0.5f, 3.0f),
                        onValueChange = { updateSpeed(it) },
                        valueRange = 0.5f..3.0f,
                        modifier = Modifier.testTag("m4b_speed_slider")
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.7f, 2.0f).forEach { preset ->
                            FilterChip(
                                selected = kotlin.math.abs(playerState.playbackSpeed - preset) < 0.03f,
                                onClick = { updateSpeed(preset) },
                                label = { Text("${preset}x") }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) { Text("Done") }
            }
        )
    }

    if (showSleepDialog) {
        var customMins by remember { mutableStateOf("20") }
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            icon = { Icon(Icons.Default.Bedtime, contentDescription = null) },
            title = { Text("Sleep Timer") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (playerState.sleepTimerMode != SleepTimerMode.OFF) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = if (playerState.sleepTimerMode == SleepTimerMode.END_OF_CHAPTER) {
                                    "Active: Pausing at end of current chapter"
                                } else {
                                    "Active: ${formatDuration(playerState.sleepRemainingMs)} remaining"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp)
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = playerState.sleepTimerMode == SleepTimerMode.OFF,
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SET_SLEEP_TIMER) {
                                    putExtra(M4bPlaybackService.EXTRA_SLEEP_MODE, SleepTimerMode.OFF.name)
                                }
                            },
                            label = { Text("Off") }
                        )
                        FilterChip(
                            selected = playerState.sleepTimerMode == SleepTimerMode.END_OF_CHAPTER,
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SET_SLEEP_TIMER) {
                                    putExtra(M4bPlaybackService.EXTRA_SLEEP_MODE, SleepTimerMode.END_OF_CHAPTER.name)
                                }
                            },
                            label = { Text("End of Chapter") }
                        )
                        listOf(5, 10, 15, 30, 45, 60, 90, 120).forEach { m ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    customMins = m.toString()
                                    M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SET_SLEEP_TIMER) {
                                        putExtra(M4bPlaybackService.EXTRA_SLEEP_MODE, SleepTimerMode.MINUTES.name)
                                        putExtra(M4bPlaybackService.EXTRA_SLEEP_MINUTES, m)
                                    }
                                },
                                label = { Text("${m}m") }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customMins,
                            onValueChange = { customMins = it.filter { c -> c.isDigit() }.take(3) },
                            label = { Text("Custom minutes") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val m = customMins.toIntOrNull()?.coerceIn(1, 600) ?: 20
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SET_SLEEP_TIMER) {
                                    putExtra(M4bPlaybackService.EXTRA_SLEEP_MODE, SleepTimerMode.MINUTES.name)
                                    putExtra(M4bPlaybackService.EXTRA_SLEEP_MINUTES, m)
                                }
                            }
                        ) {
                            Text("Set")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepDialog = false }) { Text("Done") }
            }
        )
    }

    if (showSkipIntervalDialog) {
        var customRewind by remember(rewindSec) { mutableStateOf(rewindSec.toString()) }
        var customForward by remember(forwardSec) { mutableStateOf(forwardSec.toString()) }
        AlertDialog(
            onDismissRequest = { showSkipIntervalDialog = false },
            title = { Text("Skip Intervals") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Rewind Interval", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(5, 10, 15, 30, 45, 60).forEach { sec ->
                            FilterChip(
                                selected = rewindSec == sec,
                                onClick = {
                                    customRewind = sec.toString()
                                    scope.launch {
                                        app.preferencesRepository.updateReaderSettings {
                                            it.copy(audiobookRewindSeconds = sec)
                                        }
                                    }
                                },
                                label = { Text("${sec}s") }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = customRewind,
                        onValueChange = {
                            customRewind = it.filter { c -> c.isDigit() }.take(3)
                            customRewind.toIntOrNull()?.coerceIn(1, 300)?.let { v ->
                                scope.launch {
                                    app.preferencesRepository.updateReaderSettings {
                                        it.copy(audiobookRewindSeconds = v)
                                    }
                                }
                            }
                        },
                        label = { Text("Custom Rewind (s)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider()

                    Text("Forward Interval", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(5, 10, 15, 30, 45, 60).forEach { sec ->
                            FilterChip(
                                selected = forwardSec == sec,
                                onClick = {
                                    customForward = sec.toString()
                                    scope.launch {
                                        app.preferencesRepository.updateReaderSettings {
                                            it.copy(audiobookForwardSeconds = sec)
                                        }
                                    }
                                },
                                label = { Text("${sec}s") }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = customForward,
                        onValueChange = {
                            customForward = it.filter { c -> c.isDigit() }.take(3)
                            customForward.toIntOrNull()?.coerceIn(1, 300)?.let { v ->
                                scope.launch {
                                    app.preferencesRepository.updateReaderSettings {
                                        it.copy(audiobookForwardSeconds = v)
                                    }
                                }
                            }
                        },
                        label = { Text("Custom Forward (s)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSkipIntervalDialog = false }) { Text("Done") }
            }
        )
    }
}

@Composable
private fun DynamicAudiobookCover(
    coverPath: String?,
    title: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverFile = remember(coverPath) {
        coverPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
    }
    val painter = if (coverFile != null) {
        rememberAsyncImagePainter(
            model = ImageRequest.Builder(context)
                .data(coverFile)
                .size(Size.ORIGINAL)
                .crossfade(true)
                .build()
        )
    } else {
        painterResource(id = R.drawable.img_default_cover)
    }
    val intrinsicSize = painter.intrinsicSize
    val aspectRatio = if (
        intrinsicSize.isSpecified &&
        intrinsicSize.width > 0f &&
        intrinsicSize.height > 0f
    ) {
        (intrinsicSize.width / intrinsicSize.height).coerceIn(0.55f, 1.8f)
    } else {
        0.72f // Safe fallback aspect ratio while loading, on error, or when size is unspecified
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth(0.78f)
            .testTag("m4b_cover_artwork"),
        contentAlignment = Alignment.Center
    ) {
        val maxW = maxWidth
        val maxH = 340.dp
        val minH = 180.dp
        val rawH = maxW / aspectRatio
        val (boxW, boxH) = when {
            rawH > maxH -> (maxH * aspectRatio) to maxH
            rawH < minH -> maxW to minH
            else -> maxW to rawH
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .width(boxW)
                .height(boxH)
                .heightIn(min = 180.dp, max = 340.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painter,
                    contentDescription = "$title cover",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                if (coverFile == null) {
                    Text(
                        text = title.take(32),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricReadAlongView(
    payload: EpubParser.WebChapterPayload,
    playerState: M4bPlaybackState,
    activeParagraphIndex: Int,
    onSeekToParagraph: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var selectedPreviewParaIndex by remember(payload.chapterIndex) { mutableStateOf<Int?>(null) }
    var isUserDragging by remember { mutableStateOf(false) }
    var lastUserInteractionMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isUserDragging = true
                    lastUserInteractionMs = System.currentTimeMillis()
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    isUserDragging = false
                    lastUserInteractionMs = System.currentTimeMillis()
                }
            }
        }
    }

    LaunchedEffect(activeParagraphIndex, payload.chapterIndex) {
        if (activeParagraphIndex !in payload.paragraphs.indices) return@LaunchedEffect
        val recentlyDragged = isUserDragging || (System.currentTimeMillis() - lastUserInteractionMs < 2500L)
        if (selectedPreviewParaIndex == null && !recentlyDragged) {
            val viewportH = listState.layoutInfo.viewportSize.height
            val centerOffset = if (viewportH > 0) -(viewportH / 3) else 0
            listState.animateScrollToItem(activeParagraphIndex, centerOffset)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("m4b_lyric_read_along_list")
        ) {
            itemsIndexed(
                items = payload.paragraphs,
                key = { _, para -> "${payload.chapterIndex}_${para.index}" }
            ) { idx, para ->
                val isActive = idx == activeParagraphIndex
                val isPreviewSelected = idx == selectedPreviewParaIndex

                val containerColor = when {
                    isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    isPreviewSelected -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                }
                val borderStroke = when {
                    isPreviewSelected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    else -> null
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = containerColor,
                    border = borderStroke,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            selectedPreviewParaIndex = if (selectedPreviewParaIndex == idx) null else idx
                        }
                        .testTag("m4b_lyric_paragraph_$idx")
                ) {
                    Text(
                        text = para.text,
                        fontSize = when {
                            isActive -> 19.sp
                            para.isHeading -> 17.sp
                            else -> 16.sp
                        },
                        lineHeight = if (isActive) 29.sp else 25.sp,
                        fontWeight = when {
                            isActive || para.isHeading -> FontWeight.SemiBold
                            else -> FontWeight.Normal
                        },
                        color = when {
                            isActive || isPreviewSelected -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        },
                        modifier = Modifier.padding(
                            horizontal = if (isActive || isPreviewSelected) 14.dp else 6.dp,
                            vertical = if (isActive || isPreviewSelected) 12.dp else 6.dp
                        )
                    )
                }
            }
        }

        // Left-side subtle chapter indicator pill + Right-side Play ▶ button when user taps a paragraph
        val currentChTitle = playerState.chapters.getOrNull(playerState.currentChapterIndex)?.title
            ?: payload.chapterTitle
        if (currentChTitle.isNotBlank()) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp)
                    .widthIn(max = 220.dp)
            ) {
                Text(
                    text = "Ch ${playerState.currentChapterIndex + 1} • $currentChTitle",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = selectedPreviewParaIndex != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 12.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    val targetIdx = selectedPreviewParaIndex ?: return@FloatingActionButton
                    selectedPreviewParaIndex = null
                    lastUserInteractionMs = 0L
                    onSeekToParagraph(targetIdx)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("m4b_lyric_play_selected_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play from selected paragraph"
                )
            }
        }
    }
}

private fun resolveParagraphStartMs(
    payload: EpubParser.WebChapterPayload,
    targetSentenceIdx: Int,
    playerState: M4bPlaybackState
): Long? {
    val exactMatch = M4bPlayerStateHolder.syncSegments.firstOrNull {
        it.chapterIndex == payload.chapterIndex && it.sentenceIndex == targetSentenceIdx
    } ?: payload.sentences.getOrNull(targetSentenceIdx)?.text?.trim()?.let { txt ->
        M4bPlayerStateHolder.syncSegments.firstOrNull {
            it.chapterIndex == payload.chapterIndex &&
                (it.text.trim() == txt || it.text.contains(txt) || txt.contains(it.text))
        }
    }
    if (exactMatch != null) return exactMatch.startMs

    val chapterSegs = M4bPlayerStateHolder.syncSegments.filter {
        it.chapterIndex == payload.chapterIndex
    }
    val targetChapterMeta = when {
        chapterSegs.isNotEmpty() -> {
            val segMid = chapterSegs.first().startMs
            playerState.chapters.firstOrNull { segMid in it.startMs..it.endMs }
        }
        else -> null
    } ?: playerState.chapters.firstOrNull {
        payload.chapterTitle.isNotBlank() &&
            it.title.equals(payload.chapterTitle, ignoreCase = true)
    } ?: if (playerState.activeSyncChapterIndex == payload.chapterIndex) {
        playerState.chapters.getOrNull(playerState.currentChapterIndex)
    } else {
        playerState.chapters.getOrNull(payload.chapterIndex)
    }
    val rangeStart = chapterSegs.firstOrNull()?.startMs ?: targetChapterMeta?.startMs ?: return null
    val rangeEnd = chapterSegs.lastOrNull()?.endMs ?: targetChapterMeta?.endMs ?: return null
    val totalSentences = payload.sentences.size.coerceAtLeast(1)
    val fraction = targetSentenceIdx.toFloat() / totalSentences.toFloat()
    val chDur = (rangeEnd - rangeStart).coerceAtLeast(0L)
    return rangeStart + (chDur * fraction).toLong()
}
