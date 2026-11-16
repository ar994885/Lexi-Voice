package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.LexiVoiceApplication
import com.example.export.ExportChaptersActivity
import com.example.export.ExportStateHolder
import com.example.overlay.FloatingOverlayController
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsSourceType
import com.example.tts.TtsStateHolder
import com.example.ui.ReaderViewModel
import com.example.ui.components.EpubWebView
import com.example.ui.components.TtsControlsModalSheet
import com.example.ui.components.TtsDockedBar
import com.example.ui.theme.ReaderThemeMode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenPronunciationRules: (Long) -> Unit = {},
    onOpenAudiobook: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as LexiVoiceApplication
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val readerSettings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val ttsState by TtsStateHolder.state.collectAsStateWithLifecycle()
    val overlayState by FloatingOverlayController.state.collectAsStateWithLifecycle()
    val exportProgress by ExportStateHolder.state.collectAsStateWithLifecycle()

    val bookFileKey = uiState.book?.fileKey.orEmpty()
    val linkedAudiobooks by remember(bookFileKey) {
        if (bookFileKey.isNotBlank()) {
            app.audiobookRepository.observeAudiobooksForEpub(bookFileKey)
        } else {
            flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val primaryAudiobook = linkedAudiobooks.firstOrNull()

    var showTocSheet by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }
    var showTtsSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var selectedWordForRule by remember(bookId, uiState.currentChapterIndex) { mutableStateOf("") }
    var showQuickRuleDialog by remember { mutableStateOf(false) }
    var latestScrollY by remember { mutableIntStateOf(0) }
    var showAudiobookAvailablePrompt by remember(bookId) { mutableStateOf(false) }
    var promptedAudiobookForSession by remember(bookId) { mutableStateOf(false) }
    var pendingTtsActionDuringExport by remember { mutableStateOf<(() -> Unit)?>(null) }

    BackHandler {
        viewModel.saveScrollProgress(latestScrollY)
        onBack()
    }

    LaunchedEffect(bookId) {
        viewModel.openBook(bookId)
    }

    LaunchedEffect(primaryAudiobook?.id, uiState.isLoading) {
        if (primaryAudiobook != null && !uiState.isLoading && !promptedAudiobookForSession) {
            promptedAudiobookForSession = true
            showAudiobookAvailablePrompt = true
        }
    }

    val isTtsPlayingThisBook = ttsState.isActive &&
        ttsState.sourceType == TtsSourceType.BOOK &&
        ttsState.bookId == bookId

    // Auto-switch chapter when TTS advances into the next/previous chapter
    LaunchedEffect(ttsState.chapterIndex, isTtsPlayingThisBook) {
        if (isTtsPlayingThisBook && ttsState.chapterIndex != uiState.currentChapterIndex) {
            viewModel.syncChapterFromTts(ttsState.chapterIndex)
        }
    }

    val theme = readerSettings.themeMode

    Scaffold(
        modifier = modifier,
        containerColor = theme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.chromeBg,
                    titleContentColor = theme.text,
                    navigationIconContentColor = theme.text,
                    actionIconContentColor = theme.text
                ),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.saveScrollProgress(latestScrollY)
                            onBack()
                        },
                        modifier = Modifier.testTag("reader_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Library")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = uiState.book?.title ?: "Reader",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val chNum = if (uiState.chapters.isNotEmpty()) {
                            "Ch ${uiState.currentChapterIndex + 1}/${uiState.chapters.size} • ${uiState.webChapterPayload?.chapterTitle.orEmpty()}"
                        } else {
                            uiState.webChapterPayload?.chapterTitle.orEmpty()
                        }
                        Text(
                            text = chNum,
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.secondaryText,
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
                        modifier = Modifier.testTag("reader_floating_popup_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = "Toggle Floating Playback Popup",
                            tint = if (overlayState.isVisible) MaterialTheme.colorScheme.primary else theme.text
                        )
                    }
                    IconButton(
                        onClick = { showTocSheet = true },
                        modifier = Modifier.testTag("reader_toc_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Table of Contents")
                    }
                    IconButton(
                        onClick = { showAppearanceSheet = true },
                        modifier = Modifier.testTag("reader_appearance_button")
                    ) {
                        Icon(Icons.Default.FormatSize, contentDescription = "Reader Appearance")
                    }
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.testTag("reader_more_menu_button")
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More Reader Options")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            if (primaryAudiobook != null) {
                                DropdownMenuItem(
                                    text = { Text("Open Synced Audiobook") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Headphones, contentDescription = null)
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.syncCurrentProgressToAudiobook(primaryAudiobook) { aid ->
                                            onOpenAudiobook(aid)
                                        }
                                    },
                                    modifier = Modifier.testTag("reader_menu_open_audiobook")
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Pronunciation Rules") },
                                leadingIcon = {
                                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    onOpenPronunciationRules(bookId)
                                },
                                modifier = Modifier.testTag("reader_menu_pronunciation_rules")
                            )
                            DropdownMenuItem(
                                text = { Text("Export Audio (MP3 / M4B)") },
                                leadingIcon = {
                                    Icon(Icons.Default.AudioFile, contentDescription = null)
                                },
                                enabled = uiState.book != null,
                                onClick = {
                                    showMoreMenu = false
                                    val book = uiState.book ?: return@DropdownMenuItem
                                    ExportChaptersActivity.launchForBook(
                                        context = context,
                                        title = book.title,
                                        author = book.author,
                                        fileKey = book.fileKey,
                                        isDirectPath = book.isDirectPath,
                                        coverPath = book.coverPath
                                    )
                                },
                                modifier = Modifier.testTag("reader_export_audio_button")
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Box(modifier = Modifier.navigationBarsPadding()) {
                TtsDockedBar(
                    ttsState = ttsState,
                    onOpenControlsSheet = { showTtsSheet = true },
                    onPlayClick = {
                        val action: () -> Unit = {
                            if (isTtsPlayingThisBook) {
                                TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
                            } else {
                                val startIdx = (uiState.book?.currentSentenceIndex ?: 0).coerceAtLeast(0)
                                viewModel.playFromSentence(startIdx)
                            }
                        }
                        if (exportProgress.isRunning && !ttsState.isPlaying) {
                            pendingTtsActionDuringExport = action
                        } else {
                            action()
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.background)
                .padding(paddingValues)
        ) {
            if (exportProgress.isRunning) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reader_export_tts_warning_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Export active warning",
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Audio export running (${exportProgress.batchProgressPercent}%) — avoid TTS playback until finished so exported audio is clean.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.errorMessage != null -> {
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        color = theme.text,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
                uiState.webChapterPayload != null && uiState.book != null -> {
                    val book = uiState.book!!
                    val payload = uiState.webChapterPayload!!
                    val activeGlobalIdx = if (isTtsPlayingThisBook && ttsState.chapterIndex == uiState.currentChapterIndex) {
                        ttsState.sentenceIndex
                    } else {
                        uiState.jumpHighlightSentenceIndex
                    }

                    EpubWebView(
                        fileKey = book.fileKey,
                        isDirectPath = book.isDirectPath,
                        chapterPayload = payload,
                        readerSettings = readerSettings,
                        activeSentenceIndex = activeGlobalIdx,
                        hasPrevChapter = uiState.currentChapterIndex > 0,
                        hasNextChapter = uiState.currentChapterIndex < uiState.chapters.lastIndex,
                        onSentenceTapped = { sentenceIdx ->
                            val action: () -> Unit = { viewModel.playFromSentence(sentenceIdx) }
                            if (exportProgress.isRunning) {
                                pendingTtsActionDuringExport = action
                            } else {
                                action()
                            }
                        },
                        onPullPrevChapter = {
                            if (uiState.currentChapterIndex > 0) {
                                viewModel.selectChapter(uiState.currentChapterIndex - 1)
                            }
                        },
                        onPullNextChapter = {
                            if (uiState.currentChapterIndex < uiState.chapters.lastIndex) {
                                viewModel.selectChapter(uiState.currentChapterIndex + 1)
                            }
                        },
                        onScrollChanged = { y -> latestScrollY = y },
                        onTextSelected = { selected ->
                            selectedWordForRule = selected.trim()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = selectedWordForRule.length in 1..120 && uiState.book != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { showQuickRuleDialog = true }
                                .padding(vertical = 8.dp)
                                .testTag("reader_add_rule_from_selection_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Add Rule: \"${selectedWordForRule.take(24)}\"",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { selectedWordForRule = "" },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss selection action",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            }
        }
    }

    if (showTocSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTocSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "Table of Contents (${uiState.chapters.size})",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LazyColumn(
                    modifier = Modifier.height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.chapters, key = { it.index }) { ch ->
                        val isCurrent = ch.index == uiState.currentChapterIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectChapter(ch.index)
                                    showTocSheet = false
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${ch.index + 1}.",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(36.dp)
                            )
                            Text(
                                text = ch.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAppearanceSheet) {
        ModalBottomSheet(onDismissRequest = { showAppearanceSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Reader Appearance", style = MaterialTheme.typography.titleLarge)

                Text("Theme", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReaderThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = readerSettings.themeMode == mode,
                            onClick = { viewModel.updateReaderSettings { it.copy(themeMode = mode) } },
                            label = { Text(mode.label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Font Size", style = MaterialTheme.typography.titleSmall)
                    Text("${readerSettings.fontSizeSp.roundToInt()} sp", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = readerSettings.fontSizeSp,
                    onValueChange = { sz -> viewModel.updateReaderSettings { it.copy(fontSizeSp = sz) } },
                    valueRange = 14f..30f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Line Spacing", style = MaterialTheme.typography.titleSmall)
                    Text(String.format(Locale.US, "%.2fx", readerSettings.lineSpacing), style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = readerSettings.lineSpacing,
                    onValueChange = { sp -> viewModel.updateReaderSettings { it.copy(lineSpacing = sp) } },
                    valueRange = 1.15f..2.0f
                )

                Text("Margins", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Compact" to 12, "Normal" to 20, "Wide" to 32).forEach { (label, dpVal) ->
                        FilterChip(
                            selected = readerSettings.horizontalMarginDp == dpVal,
                            onClick = { viewModel.updateReaderSettings { it.copy(horizontalMarginDp = dpVal) } },
                            label = { Text(label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Serif Book Font (Literata)", style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = readerSettings.useSerifFont,
                        onCheckedChange = { serif ->
                            viewModel.updateReaderSettings { it.copy(useSerifFont = serif) }
                        }
                    )
                }
            }
        }
    }

    if (showTtsSheet) {
        TtsControlsModalSheet(
            ttsState = ttsState,
            onDismiss = { showTtsSheet = false }
        )
    }

    if (showQuickRuleDialog && uiState.book != null) {
        val currentBook = uiState.book!!
        PronunciationRuleEditorDialog(
            existingRule = null,
            defaultFindText = selectedWordForRule,
            defaultBookId = currentBook.id,
            availableBooks = listOf(currentBook),
            onDismiss = { showQuickRuleDialog = false },
            onSave = { findText, replaceWith, ruleScope, ruleBookId, matchCase, wholeWord ->
                scope.launch {
                    app.pronunciationRepository.saveRule(
                        findText = findText,
                        replaceWith = replaceWith,
                        scope = ruleScope,
                        bookId = ruleBookId,
                        matchCase = matchCase,
                        wholeWordOnly = wholeWord,
                        isEnabled = true
                    )
                    showQuickRuleDialog = false
                    selectedWordForRule = ""
                }
            }
        )
    }

    if (showAudiobookAvailablePrompt && primaryAudiobook != null) {
        val syncedCh = (uiState.book?.currentChapterIndex ?: uiState.currentChapterIndex) + 1
        AlertDialog(
            onDismissRequest = { showAudiobookAvailablePrompt = false },
            icon = { Icon(Icons.Default.Headphones, contentDescription = null) },
            title = { Text("Audiobook Audio Available") },
            text = {
                Text(
                    "This book has exported audiobook audio (${primaryAudiobook.chapterCount} chapter(s)). " +
                        "Switch to the Audiobook Player synced around Chapter $syncedCh or continue reading here?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAudiobookAvailablePrompt = false
                        viewModel.syncCurrentProgressToAudiobook(primaryAudiobook) { aid ->
                            onOpenAudiobook(aid)
                        }
                    },
                    modifier = Modifier.testTag("reader_prompt_open_audiobook_button")
                ) {
                    Text("Listen in Audiobook Player")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAudiobookAvailablePrompt = false },
                    modifier = Modifier.testTag("reader_prompt_continue_reading_button")
                ) {
                    Text("Continue Reading")
                }
            }
        )
    }

    val queuedTtsAction = pendingTtsActionDuringExport
    if (queuedTtsAction != null) {
        AlertDialog(
            onDismissRequest = { pendingTtsActionDuringExport = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Audio Export in Progress") },
            text = {
                Text(
                    "Live TTS playback and background audio export share the same speech engine. " +
                        "Playing TTS right now can degrade audio quality or cause exported chapters to skip sentences."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { pendingTtsActionDuringExport = null },
                    modifier = Modifier.testTag("reader_wait_for_export_button")
                ) {
                    Text("Wait for Export")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingTtsActionDuringExport = null
                        queuedTtsAction()
                    },
                    modifier = Modifier.testTag("reader_play_tts_anyway_button")
                ) {
                    Text("Play Anyway")
                }
            }
        )
    }
}
