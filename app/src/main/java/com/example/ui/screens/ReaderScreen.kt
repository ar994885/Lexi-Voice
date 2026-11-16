package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.export.ExportChaptersActivity
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsSourceType
import com.example.tts.TtsStateHolder
import com.example.ui.ReaderViewModel
import com.example.ui.components.EpubWebView
import com.example.ui.components.TtsControlsModalSheet
import com.example.ui.components.TtsDockedBar
import com.example.ui.theme.ReaderThemeMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val readerSettings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val ttsState by TtsStateHolder.state.collectAsStateWithLifecycle()

    var showTocSheet by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }
    var showTtsSheet by remember { mutableStateOf(false) }
    var latestScrollY by remember { mutableIntStateOf(0) }

    BackHandler {
        viewModel.saveScrollProgress(latestScrollY)
        onBack()
    }

    LaunchedEffect(bookId) {
        viewModel.openBook(bookId)
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
                            TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_FLOATING_POPUP) {
                                putExtra(TtsPlaybackService.EXTRA_BOOL, !ttsState.floatingPopupVisible)
                            }
                        },
                        modifier = Modifier.testTag("reader_floating_popup_button")
                    ) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Toggle Floating TTS Popup")
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
                    IconButton(
                        onClick = {
                            val book = uiState.book ?: return@IconButton
                            ExportChaptersActivity.launchForBook(
                                context = context,
                                title = book.title,
                                author = book.author,
                                fileKey = book.fileKey,
                                isDirectPath = book.isDirectPath,
                                coverPath = book.coverPath
                            )
                        },
                        enabled = uiState.book != null,
                        modifier = Modifier.testTag("reader_export_audio_button")
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = "Export Audio (MP3 / M4B)")
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
                        if (isTtsPlayingThisBook) {
                            TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
                        } else {
                            val startIdx = (uiState.book?.currentSentenceIndex ?: 0).coerceAtLeast(0)
                            viewModel.playFromSentence(startIdx)
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.background)
                .padding(paddingValues)
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
                        -1
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
                            viewModel.playFromSentence(sentenceIdx)
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
                        modifier = Modifier.fillMaxSize()
                    )
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
                    Text(String.format("%.2fx", readerSettings.lineSpacing), style = MaterialTheme.typography.labelLarge)
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
}
