package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LexiVoiceApplication
import com.example.audiobook.M4bPlaybackService
import com.example.audiobook.SyncAlignmentSerializer
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.prefs.ReaderFontFamily
import com.example.data.prefs.ReaderSettings
import com.example.epub.EpubChapterSummary
import com.example.epub.EpubParser
import com.example.export.EpubExportResult
import com.example.export.EpubExporter
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsSessionContent
import com.example.tts.TtsSourceType
import com.example.tts.TtsStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ReaderUiState(
    val isLoading: Boolean = true,
    val book: BookEntity? = null,
    val chapters: List<EpubChapterSummary> = emptyList(),
    val currentChapterIndex: Int = 0,
    val webChapterPayload: EpubParser.WebChapterPayload? = null,
    val jumpHighlightSentenceIndex: Int = -1,
    val initialPageOnLoad: Int = 0,
    val initialAnchorOnLoad: String? = null,
    val errorMessage: String? = null
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LexiVoiceApplication
    private val libraryRepo = app.libraryRepository
    private val prefsRepo = app.preferencesRepository
    private val bookmarkDao = app.database.bookmarkDao()
    private val bookDao = app.database.bookDao()

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val readerSettings: StateFlow<ReaderSettings> = prefsRepo.readerSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())

    private val _importedCustomFonts = MutableStateFlow<List<String>>(emptyList())
    val importedCustomFonts: StateFlow<List<String>> = _importedCustomFonts.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<EpubParser.InBookSearchResult>>(emptyList())
    val searchResults: StateFlow<List<EpubParser.InBookSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val bookmarksAndHighlights: Flow<List<BookmarkEntity>> = _uiState
        .flatMapLatest { state ->
            val id = state.book?.id ?: return@flatMapLatest flowOf(emptyList())
            bookmarkDao.observeBookmarksForBook(id)
        }

    private var pendingJumpBookId: Long = -1L
    private var pendingJumpChapter: Int = -1
    private var pendingJumpSentence: Int = -1

    init {
        refreshCustomFonts(application)
    }

    fun refreshCustomFonts(context: Context = app) {
        val dir = File(context.filesDir, "custom_fonts")
        val names = dir.listFiles()
            ?.filter { it.isFile && (it.extension.equals("ttf", true) || it.extension.equals("otf", true)) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()
        _importedCustomFonts.value = names
    }

    fun selectCustomFont(fontNameWithoutExt: String) {
        updateReaderSettings {
            it.copy(
                fontFamily = ReaderFontFamily.CUSTOM,
                customFontName = fontNameWithoutExt
            )
        }
    }

    fun deleteCustomFont(fontNameWithoutExt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(app.filesDir, "custom_fonts")
            dir.listFiles()?.filter { it.nameWithoutExtension == fontNameWithoutExt }?.forEach { it.delete() }
            refreshCustomFonts(app)
            val current = readerSettings.value
            if (current.fontFamily == ReaderFontFamily.CUSTOM && current.customFontName == fontNameWithoutExt) {
                prefsRepo.updateReaderSettings {
                    it.copy(fontFamily = ReaderFontFamily.ORIGINAL, customFontName = "")
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.trim().length < 2) {
            _searchResults.value = emptyList()
        }
    }

    fun executeInBookSearch() {
        val current = _uiState.value
        val book = current.book ?: return
        val q = _searchQuery.value.trim()
        if (q.length < 2 || current.chapters.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            val hits = withContext(Dispatchers.IO) {
                EpubParser.searchBookChapters(
                    context = app,
                    fileKey = book.fileKey,
                    isDirectPath = book.isDirectPath,
                    chapters = current.chapters,
                    query = q
                )
            }
            _searchResults.value = hits
            _isSearching.value = false
        }
    }

    fun toggleBookmarkAtCurrentPosition(sentenceIndex: Int) {
        val current = _uiState.value
        val book = current.book ?: return
        val chIdx = current.currentChapterIndex
        val safeSent = sentenceIndex.coerceAtLeast(0)
        val chTitle = current.chapters.getOrNull(chIdx)?.title ?: "Chapter ${chIdx + 1}"
        val previewSnippet = current.webChapterPayload?.sentences?.getOrNull(safeSent)?.text?.take(90)
            ?: chTitle

        viewModelScope.launch {
            if (bookmarkDao.hasBookmarkForChapter(book.id, chIdx)) {
                bookmarkDao.deleteBookmarkForChapter(book.id, chIdx)
            } else {
                bookmarkDao.insertBookmark(
                    BookmarkEntity(
                        bookId = book.id,
                        chapterIndex = chIdx,
                        sentenceIndex = safeSent,
                        selectedText = previewSnippet,
                        note = chTitle,
                        colorHex = "#90CAF9",
                        type = "BOOKMARK"
                    )
                )
            }
            app.backupSyncRepository.scheduleAutoMirror()
        }
    }

    fun saveHighlight(sentenceIndex: Int, selectedText: String, colorHex: String) {
        val current = _uiState.value
        val book = current.book ?: return
        val chIdx = current.currentChapterIndex
        val chTitle = current.chapters.getOrNull(chIdx)?.title ?: "Chapter ${chIdx + 1}"
        viewModelScope.launch {
            bookmarkDao.insertBookmark(
                BookmarkEntity(
                    bookId = book.id,
                    chapterIndex = chIdx,
                    sentenceIndex = sentenceIndex.coerceAtLeast(0),
                    selectedText = selectedText.trim(),
                    note = chTitle,
                    colorHex = colorHex,
                    type = "HIGHLIGHT"
                )
            )
            app.backupSyncRepository.scheduleAutoMirror()
        }
    }

    fun deleteBookmarkOrHighlight(id: Long) {
        viewModelScope.launch {
            bookmarkDao.deleteBookmark(id)
            app.backupSyncRepository.scheduleAutoMirror()
        }
    }

    fun markCurrentBookFinished(finished: Boolean = true) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            bookDao.updateBookFinished(book.id, finished)
            _uiState.value = _uiState.value.copy(book = book.copy(isFinished = finished))
        }
    }

    fun requestJumpToLocation(bookId: Long, chapterIndex: Int, sentenceIndex: Int) {
        val current = _uiState.value
        if (current.book?.id == bookId && !current.isLoading && current.chapters.isNotEmpty()) {
            val targetCh = chapterIndex.coerceIn(0, current.chapters.lastIndex)
            val safeSent = sentenceIndex.coerceAtLeast(0)
            if (targetCh == current.currentChapterIndex) {
                _uiState.value = current.copy(jumpHighlightSentenceIndex = safeSent)
            } else {
                viewModelScope.launch {
                    val summary = current.chapters[targetCh]
                    val payload = withContext(Dispatchers.IO) {
                        val visualRules = app.pronunciationRepository.getEnabledRulesForReaderDisplay(current.book.id)
                        EpubParser.loadChapterForWebView(
                            context = app,
                            fileKey = current.book.fileKey,
                            isDirectPath = current.book.isDirectPath,
                            chapterIndex = targetCh,
                            chapterHref = summary.href,
                            chapterTitle = summary.title,
                            visualRules = visualRules,
                            bookId = current.book.id
                        )
                    }
                    _uiState.value = _uiState.value.copy(
                        currentChapterIndex = targetCh,
                        webChapterPayload = payload,
                        jumpHighlightSentenceIndex = safeSent
                    )
                    if (!TtsStateHolder.state.value.isPlaying || TtsStateHolder.state.value.bookId == current.book.id) {
                        pushSessionToTts(current.book, current.chapters, targetCh, payload)
                    }
                }
            }
        } else {
            pendingJumpBookId = bookId
            pendingJumpChapter = chapterIndex
            pendingJumpSentence = sentenceIndex
        }
    }

    fun openBook(bookId: Long) {
        viewModelScope.launch {
            _uiState.value = ReaderUiState(isLoading = true)
            val book = libraryRepo.getBookById(bookId)
            if (book == null) {
                _uiState.value = ReaderUiState(isLoading = false, errorMessage = "Book not found")
                return@launch
            }

            val structure = withContext(Dispatchers.IO) {
                EpubParser.parseBookStructure(
                    context = app,
                    fileKey = book.fileKey,
                    isDirectPath = book.isDirectPath,
                    fallbackTitle = book.title
                )
            }

            val chapters = structure.chapters
            if (chapters.isEmpty()) {
                _uiState.value = ReaderUiState(
                    isLoading = false,
                    book = book,
                    errorMessage = "Could not parse chapters in this EPUB file."
                )
                return@launch
            }

            val hasPendingJump = pendingJumpBookId == bookId && pendingJumpChapter >= 0
            val targetChapterIdx = if (hasPendingJump) {
                pendingJumpChapter.coerceIn(0, chapters.lastIndex)
            } else {
                book.currentChapterIndex.coerceIn(0, chapters.lastIndex)
                }
                val jumpSentence = if (hasPendingJump) {
                    pendingJumpSentence.coerceAtLeast(0)
                    } else if (book.currentSentenceIndex > 0) {
book.currentSentenceIndex
} else {
-1
}
if (hasPendingJump) {
pendingJumpBookId = -1L
pendingJumpChapter = -1
pendingJumpSentence = -1
}
val payload = withContext(Dispatchers.IO) {
val visualRules = app.pronunciationRepository.getEnabledRulesForReaderDisplay(book.id)
EpubParser.loadChapterForWebView(
context = app,
fileKey = book.fileKey,
isDirectPath = book.isDirectPath,
chapterIndex = targetChapterIdx,
chapterHref = chapters[targetChapterIdx].href,
chapterTitle = chapters[targetChapterIdx].title,
visualRules = visualRules,
bookId = book.id
)
}
_uiState.value = ReaderUiState(
isLoading = false,
book = book,
chapters = chapters,
currentChapterIndex = targetChapterIdx,
webChapterPayload = payload,
jumpHighlightSentenceIndex = jumpSentence
)
libraryRepo.updateReadingProgress(
bookId = book.id,
chapterIndex = targetChapterIdx,
sentenceIndex = if (jumpSentence >= 0) jumpSentence else book.currentSentenceIndex,
scrollOffset = book.scrollOffset,
totalChapters = chapters.size
)
val ttsState = TtsStateHolder.state.value
if (!ttsState.isPlaying || ttsState.bookId == book.id) {
pushSessionToTts(book, chapters, targetChapterIdx, payload)
}
}
}
fun selectChapter(
chapterIndex: Int,
startPlayingFromSentence: Int? = null,
jumpToEnd: Boolean = false,
initialAnchor: String? = null
) {
val current = _uiState.value
val book = current.book ?: return
if (chapterIndex !in current.chapters.indices) return
val ttsState = TtsStateHolder.state.value
val wasPlayingThisBook = ttsState.isPlaying && ttsState.sourceType == TtsSourceType.BOOK && ttsState.bookId == book.id
val autoSpeakSentence = startPlayingFromSentence ?: if (wasPlayingThisBook) 0 else null
viewModelScope.launch {
val summary = current.chapters[chapterIndex]
val payload = withContext(Dispatchers.IO) {
val visualRules = app.pronunciationRepository.getEnabledRulesForReaderDisplay(book.id)
EpubParser.loadChapterForWebView(
context = app,
fileKey = book.fileKey,
isDirectPath = book.isDirectPath,
chapterIndex = chapterIndex,
chapterHref = summary.href,
chapterTitle = summary.title,
visualRules = visualRules,
bookId = book.id
)
}
_uiState.value = _uiState.value.copy(
currentChapterIndex = chapterIndex,
webChapterPayload = payload,
jumpHighlightSentenceIndex = -1,
initialPageOnLoad = if (jumpToEnd) -1 else 0,
initialAnchorOnLoad = initialAnchor
)
libraryRepo.updateReadingProgress(
bookId = book.id,
chapterIndex = chapterIndex,
sentenceIndex = autoSpeakSentence ?: 0,
scrollOffset = 0,
totalChapters = current.chapters.size
)
pushSessionToTts(book, current.chapters, chapterIndex, payload)
if (autoSpeakSentence != null) {
TtsPlaybackService.sendAction(app, TtsPlaybackService.ACTION_PLAY_BOOK) {
putExtra(TtsPlaybackService.EXTRA_SENTENCE_INDEX, autoSpeakSentence)
}
}
}
}
fun syncChapterFromTts(ttsChapterIndex: Int) {
val current = _uiState.value
if (current.book == null || ttsChapterIndex == current.currentChapterIndex) return
if (ttsChapterIndex !in current.chapters.indices) return
viewModelScope.launch {
val summary = current.chapters[ttsChapterIndex]
val payload = withContext(Dispatchers.IO) {
val visualRules = app.pronunciationRepository.getEnabledRulesForReaderDisplay(current.book.id)
EpubParser.loadChapterForWebView(
context = app,
fileKey = current.book.fileKey,
isDirectPath = current.book.isDirectPath,
chapterIndex = ttsChapterIndex,
chapterHref = summary.href,
chapterTitle = summary.title,
visualRules = visualRules,
bookId = current.book.id
)
}
_uiState.value = _uiState.value.copy(
currentChapterIndex = ttsChapterIndex,
webChapterPayload = payload,
initialAnchorOnLoad = null
)
}
}
fun reloadCurrentChapterForRules() {
val current = _uiState.value
val book = current.book ?: return
val summary = current.chapters.getOrNull(current.currentChapterIndex) ?: return
viewModelScope.launch {
val payload = withContext(Dispatchers.IO) {
val visualRules = app.pronunciationRepository.getEnabledRulesForReaderDisplay(book.id)
EpubParser.loadChapterForWebView(
context = app,
fileKey = book.fileKey,
isDirectPath = book.isDirectPath,
chapterIndex = current.currentChapterIndex,
chapterHref = summary.href,
chapterTitle = summary.title,
visualRules = visualRules,
bookId = book.id
)
}
_uiState.value = _uiState.value.copy(
webChapterPayload = payload,
initialAnchorOnLoad = null
)
}
}
fun exportEditedEpub(
destinationUri: Uri,
onResult: (EpubExportResult) -> Unit
) {
val book = _uiState.value.book ?: run {
onResult(EpubExportResult(success = false, errorMessage = "Book not loaded"))
return
}
viewModelScope.launch {
val visualRules = withContext(Dispatchers.IO) {
app.pronunciationRepository.getEnabledRulesForReaderDisplay(book.id)
}
val result = EpubExporter.exportEditedEpubToUri(
context = app,
sourceFileKey = book.fileKey,
isDirectPath = book.isDirectPath,
bookId = book.id,
visualRules = visualRules,
destinationUri = destinationUri
)
onResult(result)
}
}
fun playFromSentence(sentenceIndex: Int) {
val current = _uiState.value
val book = current.book ?: return
val payload = current.webChapterPayload ?: return
_uiState.value = current.copy(jumpHighlightSentenceIndex = -1)
pushSessionToTts(book, current.chapters, current.currentChapterIndex, payload)
TtsPlaybackService.sendAction(app, TtsPlaybackService.ACTION_PLAY_BOOK) {
putExtra(TtsPlaybackService.EXTRA_SENTENCE_INDEX, sentenceIndex)
}
}
fun saveScrollProgress(scrollOffset: Int) {
val current = _uiState.value
val book = current.book ?: return
if (current.chapters.isEmpty()) return
viewModelScope.launch {
libraryRepo.updateReadingProgress(
bookId = book.id,
chapterIndex = current.currentChapterIndex,
sentenceIndex = TtsStateHolder.state.value.takeIf { it.bookId == book.id }?.sentenceIndex
?: book.currentSentenceIndex,
scrollOffset = scrollOffset,
totalChapters = current.chapters.size
)
}
}
fun syncCurrentProgressToAudiobook(audiobook: AudiobookEntity, onReady: (Long) -> Unit) {
val current = _uiState.value
val curChapter = current.currentChapterIndex
val ttsState = TtsStateHolder.state.value
val curSentence = if (ttsState.isActive && ttsState.bookId == current.book?.id && ttsState.chapterIndex == curChapter) {
ttsState.sentenceIndex
} else {
current.jumpHighlightSentenceIndex.takeIf { it >= 0 }
?: current.book?.currentSentenceIndex
?: 0
}.coerceAtLeast(0)
if (ttsState.isPlaying) {
TtsPlaybackService.sendAction(app, TtsPlaybackService.ACTION_PAUSE)
}
viewModelScope.launch {
val targetMs = withContext(Dispatchers.IO) {
val segments = audiobook.syncJsonPath?.let { path ->
SyncAlignmentSerializer.loadSegments(File(path))
}.orEmpty()
val matched = segments.firstOrNull {
it.chapterIndex == curChapter && it.sentenceIndex == curSentence
} ?: segments.firstOrNull {
it.chapterIndex == curChapter
}
if (matched != null) {
matched.startMs
} else {
val epubChTitle = current.chapters.getOrNull(curChapter)?.title.orEmpty()
val chapters = SyncAlignmentSerializer.decodeChapters(audiobook.chaptersJson)
val titleMatched = if (epubChTitle.isNotBlank()) {
chapters.firstOrNull { it.title.equals(epubChTitle, ignoreCase = true) }
} else null
(titleMatched ?: chapters.getOrNull(curChapter))?.startMs ?: audiobook.lastPositionMs
}
}.coerceIn(0L, audiobook.durationMs.coerceAtLeast(0L))
val syncTs = System.currentTimeMillis()
app.audiobookRepository.updatePlaybackProgress(audiobook.id, targetMs, syncTs)
current.book?.let { b ->
libraryRepo.updateReadingProgress(
bookId = b.id,
chapterIndex = curChapter,
sentenceIndex = curSentence,
scrollOffset = b.scrollOffset,
totalChapters = current.chapters.size.coerceAtLeast(1),
syncedAt = syncTs
)
}
M4bPlaybackService.sendAction(app, M4bPlaybackService.ACTION_LOAD_AND_PLAY) {
putExtra(M4bPlaybackService.EXTRA_BOOK_ID, audiobook.id)
putExtra(M4bPlaybackService.EXTRA_AUTO_PLAY, true)
putExtra(M4bPlaybackService.EXTRA_FORCE_RELOAD, true)
}
onReady(audiobook.id)
}
}
    private fun pushSessionToTts(
        book: BookEntity,
        chapters: List<EpubChapterSummary>,
        chapterIdx: Int,
        payload: EpubParser.WebChapterPayload
    ) {
TtsStateHolder.setSessionContent(
TtsSessionContent(
bookId = book.id,
bookTitle = book.title,
bookAuthor = book.author,
fileKey = book.fileKey,
isDirectPath = book.isDirectPath,
chapters = chapters,
currentChapterIndex = chapterIdx,
paragraphs = payload.paragraphs,
sentences = payload.sentences
)
)
if (TtsStateHolder.state.value.sourceType == TtsSourceType.NONE) {
TtsPlaybackService.sendAction(app, TtsPlaybackService.ACTION_INIT_ENGINES)
}
}
fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
viewModelScope.launch {
prefsRepo.updateReaderSettings(transform)
}
}
fun importCustomFont(context: Context, uri: Uri) {
viewModelScope.launch {
withContext(Dispatchers.IO) {
runCatching {
val fontDir = File(context.filesDir, "custom_fonts").apply { mkdirs() }
val cursor = context.contentResolver.query(uri, null, null, null, null)
val name = cursor?.use {
val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
if (it.moveToFirst() && nameIndex != -1) it.getString(nameIndex) else null
} ?: "custom_font.ttf"
val destFile = File(fontDir, name)
context.contentResolver.openInputStream(uri)?.use { input ->
destFile.outputStream().use { output ->
input.copyTo(output)
}
}
refreshCustomFonts(context)
val fontNameWithoutExt = destFile.nameWithoutExtension
prefsRepo.updateReaderSettings {
it.copy(
fontFamily = ReaderFontFamily.CUSTOM,
customFontName = fontNameWithoutExt
)
                    }
                }
            }
        }
    }

    fun navigateToInternalLink(rawHref: String, onSameChapterAnchor: (String) -> Unit = {}) {
        val current = _uiState.value
        val currentHref = current.chapters.getOrNull(current.currentChapterIndex)?.href
        val (matchedIndex, anchorPart) = resolveInternalLinkTarget(
            rawHref = rawHref,
            currentChapterIndex = current.currentChapterIndex,
            currentChapterHref = currentHref,
            chapters = current.chapters
        )
        if (matchedIndex == -2) {
            if (!anchorPart.isNullOrBlank()) {
                onSameChapterAnchor(anchorPart)
            }
            return
        }
        if (matchedIndex >= 0 && matchedIndex != current.currentChapterIndex) {
            selectChapter(
                chapterIndex = matchedIndex,
                initialAnchor = anchorPart?.takeIf { it.isNotBlank() }
            )
        } else if (matchedIndex == current.currentChapterIndex && !anchorPart.isNullOrBlank()) {
            onSameChapterAnchor(anchorPart)
        }
    }

    companion object {
        /**
         * Resolves an internal EPUB link (relative path, resolved ASSET_BASE_URL, or #anchor).
         * Returns Pair(chapterIndex, anchorIdOrNull):
         *  - chapterIndex == -2 when the link is a pure same-page `#anchor`
         *  - chapterIndex == -1 when no matching spine chapter was found
         */
        internal fun resolveInternalLinkTarget(
            rawHref: String,
            currentChapterIndex: Int,
            currentChapterHref: String?,
            chapters: List<EpubChapterSummary>
        ): Pair<Int, String?> {
            val trimmed = rawHref.trim()
            if (trimmed.isBlank()) return -1 to null

            val withoutScheme = when {
                trimmed.startsWith(EpubParser.ASSET_BASE_URL) -> trimmed.removePrefix(EpubParser.ASSET_BASE_URL)
                trimmed.startsWith("about:blank#") -> "#" + trimmed.substringAfter("about:blank#")
                else -> trimmed
            }

            val parts = withoutScheme.split("#", limit = 2)
            val rawPath = parts.getOrNull(0).orEmpty().trim()
            val rawAnchor = parts.getOrNull(1)?.trim().orEmpty()
            val anchorPart = runCatching {
                URLDecoder.decode(rawAnchor, StandardCharsets.UTF_8.name())
            }.getOrDefault(rawAnchor).takeIf { it.isNotBlank() }

            if (rawPath.isBlank()) {
                return -2 to anchorPart
            }

            val decodedPath = runCatching {
                URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name())
            }.getOrDefault(rawPath)
            val curHref = currentChapterHref.orEmpty()
            val currentDir = curHref.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

            val resolvedPath = if (trimmed.startsWith(EpubParser.ASSET_BASE_URL) || decodedPath.startsWith("/")) {
                EpubParser.normalizeZipPath(decodedPath)
            } else {
                EpubParser.normalizeZipPath("$currentDir$decodedPath")
            }

            val matchedIndex = chapters.indexOfFirst { ch ->
                EpubParser.normalizeZipPath(ch.href).equals(resolvedPath, ignoreCase = true)
            }.takeIf { it >= 0 } ?: chapters.indexOfFirst { ch ->
                val normCh = EpubParser.normalizeZipPath(ch.href)
                val normRaw = EpubParser.normalizeZipPath(decodedPath)
                normCh.equals(normRaw, ignoreCase = true) ||
                    normCh.endsWith("/$normRaw", ignoreCase = true) ||
                    normRaw.endsWith("/$normCh", ignoreCase = true) ||
                    File(normCh).name.equals(File(resolvedPath).name, ignoreCase = true)
            }

            return matchedIndex to anchorPart
        }
    }
}