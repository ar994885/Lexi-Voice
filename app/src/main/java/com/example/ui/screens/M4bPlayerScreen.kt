package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.LexiVoiceApplication
import com.example.audiobook.M4bPlaybackService
import com.example.audiobook.M4bPlayerStateHolder
import com.example.data.prefs.ReaderSettings
import com.example.epub.EpubChapterSummary
import com.example.epub.EpubParser
import com.example.overlay.FloatingOverlayController
import com.example.tts.SleepTimerMode
import com.example.ui.components.EpubWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rewindSec = readerSettings.audiobookRewindSeconds
    val forwardSec = readerSettings.audiobookForwardSeconds


    var showChaptersSheet by remember { mutableStateOf(false) }
    var showSpeedSleepSheet by remember { mutableStateOf(false) }
    var readAlongEnabled by remember { mutableStateOf(true) }
    var epubChapters by remember { mutableStateOf<List<EpubChapterSummary>>(emptyList()) }
    var loadedWebPayload by remember { mutableStateOf<EpubParser.WebChapterPayload?>(null) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekSliderPos by remember { mutableFloatStateOf(0f) }

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

    // Auto-load matching EPUB chapter whenever ExoPlayer crosses into a synced chapter
    LaunchedEffect(
        playerState.activeSyncChapterIndex,
        epubChapters,
        readAlongEnabled,
        playerState.hasSyncedEpub
    ) {
        val epubKey = playerState.sourceEpubKey
        if (!readAlongEnabled || !playerState.hasSyncedEpub || epubKey.isNullOrBlank() || epubChapters.isEmpty()) {
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
                        val chLabel = playerState.chapters.getOrNull(playerState.currentChapterIndex)?.title
                            ?: playerState.author
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
                                contentDescription = if (showingReadAlong) "Show Cover View" else "Show Synced EPUB Text",
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
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    val durMs = playerState.durationMs.coerceAtLeast(1L)
                    val displayPosMs = if (isSeeking) seekSliderPos.toLong() else playerState.currentPositionMs
                    Slider(
                        value = displayPosMs.toFloat().coerceIn(0f, durMs.toFloat()),
                        onValueChange = { newVal ->
                            isSeeking = true
                            seekSliderPos = newVal
                        },
                        onValueChangeFinished = {
                            isSeeking = false
                            M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SEEK_TO) {
                                putExtra(M4bPlaybackService.EXTRA_POSITION_MS, seekSliderPos.toLong())
                            }
                        },
                        valueRange = 0f..durMs.toFloat(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("m4b_seek_slider")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(displayPosMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "-${formatDuration((durMs - displayPosMs).coerceAtLeast(0L))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showSpeedSleepSheet = true },
                            modifier = Modifier.testTag("m4b_speed_sleep_button")
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = "Playback Speed & Sleep Timer")
                        }
                        IconButton(
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_PREV_CHAPTER)
                            },
                            modifier = Modifier.testTag("m4b_prev_chapter_button")
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Chapter")
                        }
                        androidx.compose.material3.TextButton(
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SKIP_BY) {
                                    putExtra(M4bPlaybackService.EXTRA_DELTA_MS, -(rewindSec * 1000L))
                                }
                            },
                            modifier = Modifier.testTag("m4b_rewind_15_button")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Replay10, contentDescription = "Rewind $rewindSec seconds")
                                Text("-${rewindSec}s", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        FilledIconButton(
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("m4b_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playerState.isPlaying) "Pause" else "Play"
                            )
                        }
                        androidx.compose.material3.TextButton(
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SKIP_BY) {
                                    putExtra(M4bPlaybackService.EXTRA_DELTA_MS, forwardSec * 1000L)
                                }
                            },
                            modifier = Modifier.testTag("m4b_forward_30_button")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Forward30, contentDescription = "Forward $forwardSec seconds")
                                Text("+${forwardSec}s", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_NEXT_CHAPTER)
                            },
                            modifier = Modifier.testTag("m4b_next_chapter_button")
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter")
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
                val epubKey = playerState.sourceEpubKey
                val payload = loadedWebPayload
                if (epubKey != null && payload != null) {
                    val activeHighlightIdx = if (payload.chapterIndex == playerState.activeSyncChapterIndex) {
                        playerState.activeSyncSentenceIndex
                    } else {
                        -1
                    }
                    EpubWebView(
                        fileKey = epubKey,
                        isDirectPath = playerState.sourceEpubIsDirect,
                        chapterPayload = payload,
                        readerSettings = readerSettings,
                        activeSentenceIndex = activeHighlightIdx,
                        hasPrevChapter = false,
                        hasNextChapter = false,
                        onSentenceTapped = { tappedSentenceIdx ->
                            val match = M4bPlayerStateHolder.syncSegments.firstOrNull {
                                it.chapterIndex == payload.chapterIndex && it.sentenceIndex == tappedSentenceIdx
                            }
                            if (match != null) {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SEEK_TO) {
                                    putExtra(M4bPlaybackService.EXTRA_POSITION_MS, match.startMs)
                                }
                            }
                        },
                        onPullPrevChapter = {},
                        onPullNextChapter = {},
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                // Full Artwork Player View
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    BookCoverImage(
                        coverPath = playerState.coverPath,
                        title = playerState.title,
                        modifier = Modifier
                            .fillMaxWidth(0.68f)
                            .aspectRatio(0.75f)
                            .clip(RoundedCornerShape(20.dp))
                    )
                    Spacer(Modifier.height(22.dp))
                    Text(
                        text = playerState.title.ifBlank { "Loading..." },
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOf(playerState.author, playerState.narrator)
                            .filter { it.isNotBlank() }
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    val chapterTitle = playerState.chapters.getOrNull(playerState.currentChapterIndex)?.title
                    if (!chapterTitle.isNullOrBlank()) {
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
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = buildString {
                            append(String.format(Locale.US, "%.2fx speed", playerState.playbackSpeed))
                            if (playerState.sleepTimerMode == SleepTimerMode.END_OF_CHAPTER) {
                                append(" • Sleep at chapter end")
                            } else if (playerState.sleepTimerMode == SleepTimerMode.MINUTES) {
                                val mins = (playerState.sleepRemainingMs / 60000L) + 1
                                append(" • Sleep in ${mins}m")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    if (showChaptersSheet) {
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
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    items(playerState.chapters, key = { "${it.index}_${it.startMs}" }) { ch ->
                        val isCurrent = ch.index == playerState.currentChapterIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_JUMP_CHAPTER) {
                                        putExtra(M4bPlaybackService.EXTRA_CHAPTER_INDEX, ch.index)
                                    }
                                    showChaptersSheet = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${ch.index + 1}.",
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

    if (showSpeedSleepSheet) {
        var customMins by remember { mutableStateOf("20") }
        ModalBottomSheet(onDismissRequest = { showSpeedSleepSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Playback Speed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = String.format(Locale.US, "%.2fx", playerState.playbackSpeed),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = playerState.playbackSpeed,
                    onValueChange = { spd ->
                        M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SET_SPEED) {
                            putExtra(M4bPlaybackService.EXTRA_SPEED, spd)
                        }
                    },
                    valueRange = 0.5f..2.5f
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.8f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { preset ->
                        FilterChip(
                            selected = kotlin.math.abs(playerState.playbackSpeed - preset) < 0.05f,
                            onClick = {
                                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_SET_SPEED) {
                                    putExtra(M4bPlaybackService.EXTRA_SPEED, preset)
                                }
                            },
                            label = { Text("${preset}x") }
                        )
                    }
                }

                HorizontalDivider()

                Text("Skip Intervals (Rewind / Forward)", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Rewind:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                    listOf(5, 10, 15, 30).forEach { sec ->
                        FilterChip(
                            selected = rewindSec == sec,
                            onClick = {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Forward:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                    listOf(5, 10, 15, 30, 60).forEach { sec ->
                        FilterChip(
                            selected = forwardSec == sec,
                            onClick = {
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
                var customRewind by remember(rewindSec) { mutableStateOf(rewindSec.toString()) }
                var customForward by remember(forwardSec) { mutableStateOf(forwardSec.toString()) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customRewind,
                        onValueChange = {
                            customRewind = it.filter { c -> c.isDigit() }.take(3)
                            customRewind.toIntOrNull()?.coerceIn(1, 300)?.let { v ->
                                scope.launch {
                                    app.preferencesRepository.updateReaderSettings { it.copy(audiobookRewindSeconds = v) }
                                }
                            }
                        },
                        label = { Text("Rewind (s)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = customForward,
                        onValueChange = {
                            customForward = it.filter { c -> c.isDigit() }.take(3)
                            customForward.toIntOrNull()?.coerceIn(1, 300)?.let { v ->
                                scope.launch {
                                    app.preferencesRepository.updateReaderSettings { it.copy(audiobookForwardSeconds = v) }
                                }
                            }
                        },
                        label = { Text("Forward (s)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Sleep Timer", style = MaterialTheme.typography.titleMedium)
                }


                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    listOf(15, 30, 60).forEach { m ->
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
        }
    }
}
