package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.LexiVoiceApplication
import com.example.data.prefs.ReaderMode
import com.example.export.ExportChaptersActivity
import com.example.export.ExportStateHolder
import com.example.overlay.FloatingOverlayController
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsSourceType
import com.example.tts.TtsStateHolder
import com.example.ui.ReaderViewModel
import com.example.ui.components.isSafeExternalUrl
import com.example.ui.components.BookmarksAndHighlightsSheet
import com.example.ui.components.CustomCssEditorDialog
import com.example.ui.components.CustomThemeCreatorDialog
import com.example.ui.components.EpubWebView
import com.example.ui.components.InBookSearchSheet
import com.example.ui.components.ReaderAppearanceBottomBar
import com.example.ui.components.ReaderAudioPlayerDockedBar
import com.example.ui.components.ReaderTypographySheet
import com.example.ui.components.ReaderControlSheet
import com.example.ui.components.ReaderEpubCheckPromptDialog
import com.example.ui.components.ReaderMetadataDraft
import com.example.ui.components.ReaderMetadataDialog
import com.example.ui.components.ReaderMiscSheet
import com.example.ui.components.ReaderSettingsHubSheet
import com.example.ui.components.ReaderSettingsPanel
import com.example.ui.components.TextSelectionActionBar
import com.example.ui.components.TextSelectionRectInfo
import com.example.ui.components.TtsControlsModalSheet
import com.example.ui.theme.ReaderThemeMode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenPronunciationRules: (Long) -> Unit = {},
    onOpenAudiobook: (Long) -> Unit = {},
    onRegisterVolumeKeyHandler: (((Int) -> Boolean)?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = context.applicationContext as LexiVoiceApplication
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val readerSettings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val importedCustomFonts by viewModel.importedCustomFonts.collectAsStateWithLifecycle()
    val allBookmarksAndHighlights by viewModel.bookmarksAndHighlights.collectAsStateWithLifecycle(initialValue = emptyList())
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

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()

    var showTocSheet by remember { mutableStateOf(false) }
    var showSearchSheet by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }
    var showReaderSettingsHub by remember { mutableStateOf(false) }
    var showReaderControlSheet by remember { mutableStateOf(false) }
    var showReaderMiscSheet by remember { mutableStateOf(false) }
    var showReaderMetadataDialog by remember { mutableStateOf(false) }
    // Metadata for the next export only. Never written back to the library.
    var pendingExportMetadata by remember { mutableStateOf<com.example.export.EpubMetadataOverride?>(null) }
    var metadataDraft by remember { mutableStateOf(ReaderMetadataDraft()) }
    var pickedCoverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pickedCoverMime by remember { mutableStateOf<String?>(null) }
    var validationReport by remember { mutableStateOf<com.example.export.EpubValidationReport?>(null) }
    var noteTargetId by remember { mutableStateOf<Long?>(null) }
    var noteDraftOpen by remember { mutableStateOf(false) }
    var noteDraftImage by remember { mutableStateOf("") }
    var noteImageBase64 by remember { mutableStateOf("") }
    // EPUBCheck is optional: 21 MB, downloaded only if the reader asks for it.
    var epubCheckPrompt by remember { mutableStateOf(false) }
    var epubCheckBusy by remember { mutableStateOf(false) }
    var epubCheckProgress by remember { mutableStateOf("") }
    var pendingValidationUri by remember { mutableStateOf<Uri?>(null) }
    var showCustomCssDialog by remember { mutableStateOf(false) }
    var showCustomThemeDialog by remember { mutableStateOf(false) }
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importCustomFont(context, it) }
    }
    val noteImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val encoded = withContext(Dispatchers.IO) {
                    com.example.ui.components.NoteImages.encode(context, uri)
                }
                if (encoded.isNotBlank()) noteImageBase64 = encoded
            }
        }
    }
    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null) mime to bytes else null
                    }.getOrNull()
                }
                if (loaded != null) {
                    pickedCoverMime = loaded.first
                    pickedCoverBytes = loaded.second
                }
            }
        }
    }
    val exportEditedEpubLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        if (uri != null) {
            scope.launch { snackbarHostState.showSnackbar("Exporting edited EPUB...") }
            viewModel.exportEditedEpub(uri, pendingExportMetadata) { result ->
                scope.launch {
                    if (result.success) {
                        // Check what we just wrote, not what we started from.
                        // The real EPUBCheck is offered as an optional download;
                        // without it the built-in structural checker runs instead.
                        pendingValidationUri = uri
                        if (com.example.export.EpubCheckRunner.isAvailable(context)) {
                            val real = com.example.export.EpubCheckRunner.validate(context, uri)
                            validationReport = real?.asReport()
                                ?: com.example.export.EpubValidator.validate(context, uri)
                        } else {
                            epubCheckPrompt = true
                        }
                        val snackResult = snackbarHostState.showSnackbar(
                            message = "Saved edited EPUB (${result.processedChapters} chapter(s) updated)",
                            actionLabel = "Import Copy to Library",
                            duration = SnackbarDuration.Long
                        )
                        if (snackResult == SnackbarResult.ActionPerformed) {
                            val imported = app.libraryRepository.importSingleEpubUri(uri)
                            snackbarHostState.showSnackbar(
                                if (imported != null) "Imported \"${imported.title}\" to Library"
                                else "Saved EPUB; could not auto-import copy"
                            )
                        }
                    } else {
                        snackbarHostState.showSnackbar(result.errorMessage ?: "EPUB export failed.")
                    }
                }
            }
        }
    }
    var showTtsSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showAudioPlayerBar by remember { mutableStateOf(false) }
    var showReaderChrome by remember { mutableStateOf(true) }
    var currentPage by remember(uiState.currentChapterIndex) { mutableIntStateOf(0) }
    var totalPages by remember(uiState.currentChapterIndex) { mutableIntStateOf(1) }
    var targetPageTrigger by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var pageFlipTrigger by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var webActionTrigger by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var targetAnchorTrigger by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var selectedWordForRule by remember(bookId, uiState.currentChapterIndex) { mutableStateOf("") }
    var selectedSentenceForAction by remember(bookId, uiState.currentChapterIndex) { mutableIntStateOf(-1) }
    var selectionRectInfo by remember(bookId, uiState.currentChapterIndex) { mutableStateOf(TextSelectionRectInfo()) }
    var webViewContainerSize by remember { mutableStateOf(IntSize.Zero) }
    var selectionPillSize by remember { mutableStateOf(IntSize.Zero) }
    var showQuickRuleDialog by remember { mutableStateOf(false) }
    var latestScrollY by remember { mutableIntStateOf(0) }
    var showAudiobookAvailablePrompt by remember(bookId) { mutableStateOf(false) }
    var promptedAudiobookForSession by remember(bookId) { mutableStateOf(false) }
    var pendingTtsActionDuringExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingExternalUrl by remember { mutableStateOf<String?>(null) }
    var showFinishBookDialog by remember { mutableStateOf(false) }

    val clearTextSelection: () -> Unit = {
        selectedWordForRule = ""
        selectedSentenceForAction = -1
        selectionRectInfo = TextSelectionRectInfo()
        webActionTrigger = "CLEAR_SELECTION" to System.nanoTime()
    }

    var wasLifecyclePaused by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> wasLifecyclePaused = true
                Lifecycle.Event.ON_RESUME -> {
                    if (wasLifecyclePaused) {
                        wasLifecyclePaused = false
                        viewModel.reloadCurrentChapterForRules()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val chapterHighlights = remember(allBookmarksAndHighlights, uiState.currentChapterIndex) {
        allBookmarksAndHighlights.filter {
            it.chapterIndex == uiState.currentChapterIndex && it.type == "HIGHLIGHT"
        }
    }
    val chapterHasBookmark = remember(allBookmarksAndHighlights, uiState.currentChapterIndex) {
        allBookmarksAndHighlights.any {
            it.chapterIndex == uiState.currentChapterIndex && it.type == "BOOKMARK"
        }
    }

    // Apply per-reader brightness preference and restore system brightness when leaving ReaderScreen
    val brightnessPref = readerSettings.brightnessLevel
    DisposableEffect(view, brightnessPref) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val lp = window.attributes
            lp.screenBrightness = if (brightnessPref < 0f) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                brightnessPref.coerceIn(0.05f, 1.0f)
            }
            window.attributes = lp
        }
        onDispose { }
    }
    DisposableEffect(view) {
        onDispose {
            val window = view.context.findActivity()?.window
            if (window != null) {
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    // Volume Key Page Turning
    DisposableEffect(readerSettings.volumeKeysTurnPages) {
        if (readerSettings.volumeKeysTurnPages) {
            onRegisterVolumeKeyHandler { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        pageFlipTrigger = 1 to System.nanoTime()
                        true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        pageFlipTrigger = -1 to System.nanoTime()
                        true
                    }
                    else -> false
                }
            }
        } else {
            onRegisterVolumeKeyHandler(null)
        }
        onDispose {
            onRegisterVolumeKeyHandler(null)
        }
    }

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

    // Coming back into a book that is already being read aloud should show the
    // player bar straight away instead of making the user hunt for the headphones
    // button. When nothing is being read for this book the reader stays clean.
    LaunchedEffect(Unit) {
        if (isTtsPlayingThisBook) showAudioPlayerBar = true
    }

    val hostActivity = context.findActivity()

    // Keep Screen Awake is a window flag, not a view flag: a view flag is dropped
    // every time the reader's view is re-attached, which is why the toggle looked
    // like it did nothing.
    DisposableEffect(readerSettings.keepScreenAwake, hostActivity) {
        val window = hostActivity?.window
        if (readerSettings.keepScreenAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Show System Notification Bar hides only the status bar, only while a book is
    // open. Two mechanisms on purpose: the insets controller is the modern path but
    // is a no-op on some builds, and FLAG_FULLSCREEN is the old path that still works
    // there. Every call is guarded, so a window that is going away cannot crash it.
    DisposableEffect(readerSettings.showSystemStatusBar, hostActivity) {
        val window = hostActivity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (window != null) {
            runCatching {
                if (readerSettings.showSystemStatusBar) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    controller?.show(WindowInsetsCompat.Type.statusBars())
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    controller?.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller?.hide(WindowInsetsCompat.Type.statusBars())
                }
            }
        }
        onDispose {
            runCatching {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                controller?.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    LaunchedEffect(ttsState.chapterIndex, isTtsPlayingThisBook) {
        if (isTtsPlayingThisBook && ttsState.chapterIndex != uiState.currentChapterIndex) {
            viewModel.syncChapterFromTts(ttsState.chapterIndex)
        }
    }

    val triggerReadAloudAction: () -> Unit = {
        val action: () -> Unit = {
            showAudioPlayerBar = true
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

    val theme = readerSettings.themeMode
    val isCustomTheme = theme == ReaderThemeMode.CUSTOM
    val effectiveBg = remember(theme, readerSettings.customThemeBgHex) {
        if (isCustomTheme) {
            runCatching { Color(android.graphics.Color.parseColor(readerSettings.customThemeBgHex)) }
                .getOrDefault(theme.background)
        } else theme.background
    }
    val effectiveText = remember(theme, readerSettings.customThemeTextHex) {
        if (isCustomTheme) {
            runCatching { Color(android.graphics.Color.parseColor(readerSettings.customThemeTextHex)) }
                .getOrDefault(theme.text)
        } else theme.text
    }
    val effectiveChromeBg = remember(theme, readerSettings.customThemeBgHex) {
        if (isCustomTheme) effectiveBg else theme.chromeBg
    }
    val effectiveSecondaryText = remember(theme, effectiveText) {
        if (isCustomTheme) effectiveText.copy(alpha = 0.7f) else theme.secondaryText
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(effectiveBg)
    ) {
        // Full-Screen Book Content & Quiet Running Footer (edge-to-edge)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
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
                    .onGloballyPositioned { webViewContainerSize = it.size }
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    uiState.errorMessage != null -> {
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            color = effectiveText,
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
                            chapterHighlights = chapterHighlights,
                            enableSentenceTap = false,
                            onSentenceTapped = { _ -> },
                            onPullPrevChapter = {
                                if (uiState.currentChapterIndex > 0) {
                                    val jumpEnd = readerSettings.readingMode == ReaderMode.PAGED
                                    viewModel.selectChapter(uiState.currentChapterIndex - 1, jumpToEnd = jumpEnd)
                                }
                            },
                            onPullNextChapter = {
                                if (uiState.currentChapterIndex < uiState.chapters.lastIndex) {
                                    viewModel.selectChapter(uiState.currentChapterIndex + 1)
                                } else if (!book.isFinished) {
                                    showFinishBookDialog = true
                                }
                            },
                            onEndOfBookReached = {
                                if (uiState.currentChapterIndex >= uiState.chapters.lastIndex && !book.isFinished) {
                                    showFinishBookDialog = true
                                }
                            },
                            onScrollChanged = { y -> latestScrollY = y },
                            onTextSelectedWithSentence = { selected, sentIdx ->
                                selectedWordForRule = selected.trim()
                                selectedSentenceForAction = sentIdx
                            },
                            onHighlightTapped = { id -> noteTargetId = id },
                            onTextSelectedWithRect = { rectInfo ->
                                selectionRectInfo = rectInfo
                                if (rectInfo.text.isNotBlank()) {
                                    selectedWordForRule = rectInfo.text.trim()
                                } else {
                                    selectedWordForRule = ""
                                    selectedSentenceForAction = -1
                                }
                            },
                            initialPageOnLoad = uiState.initialPageOnLoad,
                            initialAnchorOnLoad = uiState.initialAnchorOnLoad,
                            targetPageTrigger = targetPageTrigger,
                            pageFlipTrigger = pageFlipTrigger,
                            webActionTrigger = webActionTrigger,
                            targetAnchorTrigger = targetAnchorTrigger,
                            onPageStateChanged = { cur, tot ->
                                currentPage = cur
                                totalPages = tot
                            },
                            onCenterTapped = {
                                if (selectedWordForRule.isNotBlank()) {
                                    clearTextSelection()
                                } else {
                                    showReaderChrome = !showReaderChrome
                                }
                            },
                            onExternalLinkClicked = { url ->
                                pendingExternalUrl = url
                            },
                            onInternalLinkClicked = { rawHref ->
                                viewModel.navigateToInternalLink(rawHref) { anchor ->
                                    targetAnchorTrigger = anchor to System.nanoTime()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Floating Selection Action Pill anchored directly above (or below near top chrome) selected text
                val showSelectionPill = selectedWordForRule.isNotBlank() && uiState.book != null
                if (showSelectionPill) {
                    val offsetPx = remember(
                        selectionRectInfo,
                        selectionPillSize,
                        webViewContainerSize,
                        showReaderChrome
                    ) {
                        val containerW = webViewContainerSize.width
                        val containerH = webViewContainerSize.height
                        val gapPx = with(density) { 10.dp.roundToPx() }
                        val edgeMarginPx = with(density) { 8.dp.roundToPx() }
                        val topChromePx = if (showReaderChrome) {
                            with(density) { 64.dp.roundToPx() }
                        } else {
                            edgeMarginPx
                        }
                        val pillW = selectionPillSize.width
                        val pillH = selectionPillSize.height
                        if (containerW <= 0 || containerH <= 0) {
                            IntOffset(edgeMarginPx, topChromePx)
                        } else if (selectionRectInfo.text.isNotBlank() && selectionRectInfo.viewportWidthCssPx > 1f) {
                            val scaleX = containerW.toFloat() / selectionRectInfo.viewportWidthCssPx
                            val scaleY = if (selectionRectInfo.viewportHeightCssPx > 1f) {
                                containerH.toFloat() / selectionRectInfo.viewportHeightCssPx
                            } else {
                                scaleX
                            }
                            val selTopPx = selectionRectInfo.topCssPx * scaleY
                            val selBottomPx = selectionRectInfo.bottomCssPx * scaleY
                            val selCenterX = ((selectionRectInfo.leftCssPx + selectionRectInfo.rightCssPx) / 2f) * scaleX

                            val maxX = (containerW - pillW - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                            val x = (selCenterX - pillW / 2f).roundToInt().coerceIn(edgeMarginPx, maxX)

                            val aboveY = (selTopPx - pillH - gapPx).roundToInt()
                            val isFullViewportSelection = selTopPx <= topChromePx && selBottomPx >= containerH - gapPx
                            val y = when {
                                isFullViewportSelection -> topChromePx + gapPx
                                aboveY >= topChromePx -> aboveY
                                else -> {
                                    val maxY = (containerH - pillH - gapPx).coerceAtLeast(topChromePx)
                                    (selBottomPx + gapPx).roundToInt().coerceIn(topChromePx, maxY)
                                }
                            }
                            IntOffset(x, y)
                        } else {
                            val maxX = (containerW - pillW - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                            IntOffset(((containerW - pillW) / 2).coerceIn(edgeMarginPx, maxX), topChromePx + gapPx)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .offset { offsetPx }
                            .onGloballyPositioned { selectionPillSize = it.size }
                    ) {
                        TextSelectionActionBar(
                            selectedText = selectedWordForRule,
                            canReadFromHere = selectedSentenceForAction >= 0,
                            onAddNote = { noteDraftOpen = true },
                            onHighlightColor = { hex ->
                                viewModel.saveHighlight(
                                    sentenceIndex = selectedSentenceForAction.coerceAtLeast(0),
                                    selectedText = selectedWordForRule,
                                    colorHex = hex,
                                    startOffset = selectionRectInfo.startOffset,
                                    endOffset = selectionRectInfo.endOffset,
                                    highlightAll = false
                                )
                                clearTextSelection()
                            },
                            onReadAloudFromHere = {
                                val idx = selectedSentenceForAction
                                clearTextSelection()
                                if (idx >= 0) {
                                    val action: () -> Unit = {
                                        showAudioPlayerBar = true
                                        viewModel.playFromSentence(idx)
                                    }
                                    if (exportProgress.isRunning) {
                                        pendingTtsActionDuringExport = action
                                    } else {
                                        action()
                                    }
                                }
                            },
                            onDefineSelection = {
                                val query = Uri.encode("define $selectedWordForRule")
                                clearTextSelection()
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query"))
                                    )
                                }
                            },
                            onAddPronunciationRule = {
                                showQuickRuleDialog = true
                            },
                            onCopySelection = {
                                clipboardManager.setText(AnnotatedString(selectedWordForRule))
                                clearTextSelection()
                                scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                            },
                            onSelectAll = {
                                webActionTrigger = "SELECT_ALL" to System.nanoTime()
                            },
                            onShareSelection = {
                                val shareText = selectedWordForRule
                                clearTextSelection()
                                runCatching {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share excerpt"))
                                }
                            },
                            onDismiss = { clearTextSelection() }
                        )
                    }
                }
            }

            // Quiet 24.dp Running Footer
            if (!uiState.isLoading && uiState.errorMessage == null && uiState.chapters.isNotEmpty()) {
                val chapterTitle = uiState.webChapterPayload?.chapterTitle
                    ?.ifBlank { uiState.chapters.getOrNull(uiState.currentChapterIndex)?.title.orEmpty() }
                    .orEmpty()
                val bookPercent = (((uiState.currentChapterIndex + 1).toFloat() / uiState.chapters.size.coerceAtLeast(1)) * 100f)
                    .roundToInt().coerceIn(0, 100)
                val rightLabel = if (readerSettings.readingMode == ReaderMode.PAGED) {
                    "Page ${currentPage + 1} / ${totalPages.coerceAtLeast(1)}  •  $bookPercent%"
                } else {
                    "Ch ${uiState.currentChapterIndex + 1} / ${uiState.chapters.size}  •  $bookPercent%"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(effectiveBg)
                        .padding(horizontal = 16.dp)
                        .testTag("reader_running_footer"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = chapterTitle,
                        fontSize = 11.sp,
                        color = effectiveSecondaryText.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Text(
                        text = rightLabel,
                        fontSize = 11.sp,
                        color = effectiveSecondaryText.copy(alpha = 0.65f),
                        maxLines = 1
                    )
                }
            }
        }

        // Floating Top Bar Overlay
        AnimatedVisibility(
            visible = showReaderChrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = effectiveChromeBg.copy(alpha = 0.96f),
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = effectiveText,
                        navigationIconContentColor = effectiveText,
                        actionIconContentColor = effectiveText
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
                                val pagePart = if (readerSettings.readingMode == ReaderMode.PAGED) {
                                    " • Pg ${currentPage + 1}/${totalPages.coerceAtLeast(1)}"
                                } else ""
                                "Ch ${uiState.currentChapterIndex + 1}/${uiState.chapters.size}$pagePart • ${uiState.webChapterPayload?.chapterTitle.orEmpty()}"
                            } else {
                                uiState.webChapterPayload?.chapterTitle.orEmpty()
                            }
                            Text(
                                text = chNum,
                                style = MaterialTheme.typography.bodySmall,
                                color = effectiveSecondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showSearchSheet = true },
                            modifier = Modifier
                                .size(width = 38.dp, height = 48.dp)
                                .testTag("reader_search_button")
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search in Book")
                        }
                        IconButton(
                            onClick = {
                                val sentIdx = if (isTtsPlayingThisBook) ttsState.sentenceIndex else 0
                                viewModel.toggleBookmarkAtCurrentPosition(sentIdx)
                            },
                            modifier = Modifier
                                .size(width = 38.dp, height = 48.dp)
                                .testTag("reader_bookmark_button")
                        ) {
                            Icon(
                                imageVector = if (chapterHasBookmark) Icons.Default.Bookmark else Icons.Default.BookmarkAdd,
                                contentDescription = "Bookmark This Page",
                                tint = if (chapterHasBookmark) MaterialTheme.colorScheme.primary else effectiveText
                            )
                        }
                        IconButton(
                            onClick = { showBookmarksSheet = true },
                            modifier = Modifier
                                .size(width = 38.dp, height = 48.dp)
                                .testTag("reader_bookmarks_button")
                        ) {
                            Icon(Icons.Default.Bookmarks, contentDescription = "Bookmarks & Highlights")
                        }
                        IconButton(
                            onClick = { showTocSheet = true },
                            modifier = Modifier
                                .size(width = 38.dp, height = 48.dp)
                                .testTag("reader_toc_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Table of Contents")
                        }
                        IconButton(
                            onClick = {
                                FloatingOverlayController.setVisible(context, !overlayState.isVisible)
                            },
                            modifier = Modifier
                                .size(width = 38.dp, height = 48.dp)
                                .testTag("reader_floating_popup_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Toggle Floating Playback Popup",
                                tint = if (overlayState.isVisible) MaterialTheme.colorScheme.primary else effectiveText
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { showMoreMenu = true },
                                modifier = Modifier
                                    .size(width = 38.dp, height = 48.dp)
                                    .testTag("reader_more_menu_button")
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Reader Options")
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Reader Settings") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Tune, contentDescription = null)
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        showReaderSettingsHub = true
                                    },
                                    modifier = Modifier.testTag("reader_menu_settings")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(if (isTtsPlayingThisBook && ttsState.isPlaying) "Pause Read Aloud" else "Read Aloud")
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isTtsPlayingThisBook && ttsState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = null
                                        )
                                    },
                                    enabled = uiState.book != null && uiState.webChapterPayload != null,
                                    onClick = {
                                        showMoreMenu = false
                                        triggerReadAloudAction()
                                    },
                                    modifier = Modifier.testTag("reader_menu_read_aloud")
                                )
                                DropdownMenuItem(
                                    text = { Text("Bookmarks & Highlights (${allBookmarksAndHighlights.size})") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bookmarks, contentDescription = null)
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        showBookmarksSheet = true
                                    },
                                    modifier = Modifier.testTag("reader_menu_bookmarks")
                                )
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
                                DropdownMenuItem(
                                    text = { Text("Export Edited EPUB") },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                                    },
                                    enabled = uiState.book != null,
                                    onClick = {
                                        showMoreMenu = false
                                        if (uiState.book == null) return@DropdownMenuItem
                                        // Metadata first: the dialog feeds the export, and
                                        // cancelling it leaves the original untouched.
                                        pendingExportMetadata = null
                                        val openBook = uiState.book
                                        if (openBook != null) {
                                            metadataDraft = ReaderMetadataDraft(
                                                title = openBook.title,
                                                author = openBook.author,
                                                language = openBook.language
                                            )
                                        }
                                        pickedCoverBytes = null
                                        pickedCoverMime = null
                                        showReaderMetadataDialog = true
                                    },
                                    modifier = Modifier.testTag("reader_export_edited_epub_button")
                                )
                            }
                        }
                    }
                )
            }
        }

        // Floating Bottom Appearance Capsule or Docked TTS Bar
        AnimatedVisibility(
            visible = showReaderChrome || showAudioPlayerBar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 18.dp)
        ) {
            if (showAudioPlayerBar) {
                ReaderAudioPlayerDockedBar(
                    ttsState = ttsState,
                    onOpenControlsSheet = { showTtsSheet = true },
                    onPlayClick = triggerReadAloudAction,
                    onCollapseToAppearance = { showAudioPlayerBar = false }
                )
            } else {
                ReaderAppearanceBottomBar(
                    readerSettings = readerSettings,
                    currentChapterIndex = uiState.currentChapterIndex,
                    totalChapters = uiState.chapters.size,
                    currentPage = currentPage,
                    totalPages = totalPages,
                    isTtsActive = isTtsPlayingThisBook,
                    onSelectChapter = { chIdx -> viewModel.selectChapter(chIdx) },
                    onJumpToPage = { pg -> targetPageTrigger = pg to System.nanoTime() },
                    onUpdateSettings = { transform -> viewModel.updateReaderSettings(transform) },
                    onOpenTypographySheet = { showAppearanceSheet = true },
                    onOpenCustomThemeDialog = { showCustomThemeDialog = true },
                    onSwitchToAudioBar = { showAudioPlayerBar = true },
                    onOpenReaderSettings = { showReaderSettingsHub = true }
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 86.dp)
        )
    }

    // Auto-scrolling Table of Contents Sheet
    if (showTocSheet) {
        val tocListState = rememberLazyListState()
        LaunchedEffect(showTocSheet, uiState.currentChapterIndex) {
            val target = uiState.currentChapterIndex.coerceAtLeast(0)
            if (target < uiState.chapters.size) {
                tocListState.scrollToItem(target)
            }
        }
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
                    state = tocListState,
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

    if (showSearchSheet) {
        InBookSearchSheet(
            query = searchQuery,
            isSearching = isSearching,
            results = searchResults,
            onQueryChange = { viewModel.updateSearchQuery(it) },
            onSearchSubmit = { viewModel.executeInBookSearch() },
            onJumpToResult = { hit ->
                showSearchSheet = false
                viewModel.requestJumpToLocation(bookId, hit.chapterIndex, hit.sentenceIndex)
            },
            onDismiss = { showSearchSheet = false }
        )
    }

    if (showBookmarksSheet) {
        BookmarksAndHighlightsSheet(
            items = allBookmarksAndHighlights,
            onJumpToItem = { item ->
                showBookmarksSheet = false
                viewModel.requestJumpToLocation(bookId, item.chapterIndex, item.sentenceIndex)
            },
            onDeleteItem = { id -> viewModel.deleteBookmarkOrHighlight(id) },
            onDismiss = { showBookmarksSheet = false }
        )
    }

    if (showAppearanceSheet) {
        ReaderTypographySheet(
            readerSettings = readerSettings,
            onUpdateSettings = { transform -> viewModel.updateReaderSettings(transform) },
            onOpenCustomCssDialog = { showCustomCssDialog = true },
            onPickCustomFont = { fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/font-sfnt", "*/*")) },
            importedFonts = importedCustomFonts,
            onSelectImportedFont = { name -> viewModel.selectCustomFont(name) },
            onDeleteImportedFont = { name -> viewModel.deleteCustomFont(name) },
            onDismiss = { showAppearanceSheet = false }
        )
    }

    if (showCustomCssDialog) {
        CustomCssEditorDialog(
            initialCss = readerSettings.customCss,
            onDismiss = { showCustomCssDialog = false },
            onSave = { newCss ->
                viewModel.updateReaderSettings { it.copy(customCss = newCss) }
                showCustomCssDialog = false
            }
        )
    }

    if (showCustomThemeDialog) {
        CustomThemeCreatorDialog(
            initialBg = readerSettings.customThemeBgHex,
            initialText = readerSettings.customThemeTextHex,
            initialLink = readerSettings.customThemeLinkHex,
            onDismiss = { showCustomThemeDialog = false },
            onSave = { bg, text, link ->
                viewModel.updateReaderSettings {
                    it.copy(
                        themeMode = ReaderThemeMode.CUSTOM,
                        customThemeBgHex = bg,
                        customThemeTextHex = text,
                        customThemeLinkHex = link
                    )
                }
                showCustomThemeDialog = false
            }
        )
    }

    if (showReaderSettingsHub) {
        ReaderSettingsHubSheet(
            onDismiss = { showReaderSettingsHub = false },
            onOpenPanel = { panel ->
                showReaderSettingsHub = false
                when (panel) {
                    ReaderSettingsPanel.VISUAL -> showAppearanceSheet = true
                    ReaderSettingsPanel.CONTROL -> showReaderControlSheet = true
                    ReaderSettingsPanel.METADATA -> showReaderMetadataDialog = true
                    ReaderSettingsPanel.MISCELLANEOUS -> showReaderMiscSheet = true
                }
            }
        )
    }

    if (showReaderControlSheet) {
        ReaderControlSheet(
            readerSettings = readerSettings,
            onUpdateSettings = { transform -> viewModel.updateReaderSettings(transform) },
            onDismiss = { showReaderControlSheet = false }
        )
    }

    if (showReaderMiscSheet) {
        ReaderMiscSheet(
            readerSettings = readerSettings,
            onUpdateSettings = { transform -> viewModel.updateReaderSettings(transform) },
            onDismiss = { showReaderMiscSheet = false }
        )
    }

    if (showReaderMetadataDialog && uiState.book != null) {
        val currentBook = uiState.book!!
        ReaderMetadataDialog(
            initial = metadataDraft,
            coverPreviewModel = pickedCoverBytes ?: currentBook.coverPath,
            hasPickedCover = pickedCoverBytes != null,
            onPickCover = { coverPickerLauncher.launch("image/*") },
            onClearCover = {
                pickedCoverBytes = null
                pickedCoverMime = null
            },
            onDismiss = { showReaderMetadataDialog = false },
            onConfirm = { draft ->
                showReaderMetadataDialog = false
                metadataDraft = draft
                pendingExportMetadata = com.example.export.EpubMetadataOverride(
                    title = draft.title.trim().ifBlank { null },
                    subtitle = draft.subtitle.trim().ifBlank { null },
                    author = draft.author.trim().ifBlank { null },
                    publisher = draft.publisher.trim().ifBlank { null },
                    language = draft.language.trim().ifBlank { null },
                    date = draft.date.trim().ifBlank { null },
                    identifier = draft.identifier.trim().ifBlank { null },
                    rights = draft.rights.trim().ifBlank { null },
                    series = draft.series.trim().ifBlank { null },
                    seriesIndex = draft.seriesIndex.trim().ifBlank { null },
                    subjects = draft.subjects.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                    synopsis = draft.synopsis.trim().ifBlank { null },
                    coverBytes = pickedCoverBytes,
                    coverFileName = when (pickedCoverMime) {
                        "image/png" -> "lexi-cover.png"
                        "image/webp" -> "lexi-cover.webp"
                        "image/gif" -> "lexi-cover.gif"
                        else -> "lexi-cover.jpg"
                    },
                    coverMimeType = pickedCoverMime
                )
                val baseName = draft.title.trim().ifBlank { currentBook.title }
                exportEditedEpubLauncher.launch("$baseName (Edited).epub")
            }
        )
    }

    if (epubCheckPrompt) {
        ReaderEpubCheckPromptDialog(
            sizeLabel = com.example.export.EpubCheckRunner.downloadSizeLabel(),
            busy = epubCheckBusy,
            progressText = epubCheckProgress,
            onDownloadAndCheck = {
                val uri = pendingValidationUri
                if (uri == null) {
                    epubCheckPrompt = false
                } else {
                    epubCheckBusy = true
                    epubCheckProgress = "Starting download…"
                    scope.launch {
                        val installed = com.example.export.EpubCheckRunner.download(context) { fraction ->
                            scope.launch {
                                epubCheckProgress = if (fraction >= 1f) {
                                    "Installed. Validating…"
                                } else {
                                    "Downloading EPUBCheck… " + (fraction * 100).toInt() + "%"
                                }
                            }
                        }
                        epubCheckBusy = false
                        epubCheckPrompt = false
                        epubCheckProgress = ""
                        validationReport = if (installed) {
                            com.example.export.EpubCheckRunner.validate(context, uri)?.asReport()
                                ?: com.example.export.EpubValidator.validate(context, uri)
                        } else {
                            // The download is optional; a failure must not block the export.
                            com.example.export.EpubValidator.validate(context, uri)
                        }
                        pendingValidationUri = null
                    }
                }
            },
            onUseBuiltIn = {
                val uri = pendingValidationUri
                epubCheckPrompt = false
                if (uri != null) {
                    scope.launch {
                        validationReport = com.example.export.EpubValidator.validate(context, uri)
                        pendingValidationUri = null
                    }
                }
            },
            onSkip = {
                epubCheckPrompt = false
                pendingValidationUri = null
            }
        )
    }

    if (noteDraftOpen) {
        com.example.ui.components.ReaderNoteDialog(
            quotedText = selectedWordForRule,
            initialNote = "",
            initialHighlightAll = false,
            imageBase64 = noteDraftImage,
            isNew = true,
            onPickImage = { noteImagePicker.launch("image/*") },
            onClearImage = { noteDraftImage = "" },
            onDelete = { noteDraftOpen = false },
            onDismiss = {
                noteDraftOpen = false
                noteDraftImage = ""
            },
            onConfirm = { note, highlightAll ->
                viewModel.saveHighlight(
                    sentenceIndex = selectedSentenceForAction.coerceAtLeast(0),
                    selectedText = selectedWordForRule,
                    colorHex = "#FFEB3B",
                    startOffset = selectionRectInfo.startOffset,
                    endOffset = selectionRectInfo.endOffset,
                    highlightAll = highlightAll,
                    note = note,
                    noteImage = noteDraftImage
                )
                noteDraftOpen = false
                noteDraftImage = ""
                clearTextSelection()
            }
        )
    }

    val noteTarget = noteTargetId?.let { id -> allBookmarksAndHighlights.firstOrNull { it.id == id } }
    if (noteTarget != null) {
        com.example.ui.components.ReaderNoteDialog(
            quotedText = noteTarget.selectedText,
            initialNote = noteTarget.note,
            initialHighlightAll = noteTarget.highlightAll,
            imageBase64 = noteImageBase64.ifBlank { noteTarget.noteImage },
            onPickImage = { noteImagePicker.launch("image/*") },
            onClearImage = { noteImageBase64 = "" },
            onDelete = {
                viewModel.deleteBookmarkOrHighlight(noteTarget.id)
                noteTargetId = null
                noteImageBase64 = ""
            },
            onDismiss = {
                noteTargetId = null
                noteImageBase64 = ""
            },
            onConfirm = { note, highlightAll ->
                viewModel.updateHighlightNote(
                    id = noteTarget.id,
                    note = note,
                    noteImage = noteImageBase64.ifBlank { noteTarget.noteImage },
                    highlightAll = highlightAll
                )
                noteTargetId = null
                noteImageBase64 = ""
            }
        )
    }

    validationReport?.let { report ->
        com.example.ui.components.ReaderValidationDialog(
            report = report,
            onDismiss = { validationReport = null }
        )
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
            onSave = { findText, replaceWith, ruleScope, ruleBookId, matchCase, wholeWord, applyToAudio, applyToReaderText ->
                scope.launch {
                    app.pronunciationRepository.saveRule(
                        findText = findText,
                        replaceWith = replaceWith,
                        scope = ruleScope,
                        bookId = ruleBookId,
                        matchCase = matchCase,
                        wholeWordOnly = wholeWord,
                        isEnabled = true,
                        applyToAudio = applyToAudio,
                        applyToReaderText = applyToReaderText
                    )
                    showQuickRuleDialog = false
                    clearTextSelection()
                    viewModel.reloadCurrentChapterForRules()
                }
            }
        )
    }

    val externalUrl = pendingExternalUrl
    if (externalUrl != null) {
        AlertDialog(
            onDismissRequest = { pendingExternalUrl = null },
            title = { Text("Open External Link?") },
            text = {
                Text("Do you want to open this external web link in your browser?\n\n$externalUrl")
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingExternalUrl = null
                        runCatching {
                            // Second line of defence: only http(s) is launched, so a
                            // crafted EPUB cannot drive an intent:// or custom scheme.
                            if (isSafeExternalUrl(externalUrl)) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl)))
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm_open_external_link")
                ) {
                    Text("Open Browser")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingExternalUrl = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFinishBookDialog) {
        AlertDialog(
            onDismissRequest = { showFinishBookDialog = false },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
            title = { Text("Reached End of Book") },
            text = {
                Text("You've reached the end of \"${uiState.book?.title ?: "this book"}\". Mark this book as Finished?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.markCurrentBookFinished(true)
                        showFinishBookDialog = false
                    },
                    modifier = Modifier.testTag("confirm_finish_book_button")
                ) {
                    Text("Mark as Finished")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishBookDialog = false }) {
                    Text("Keep Reading")
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
