package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LexiVoiceApplication
import com.example.audiobook.M4bPlaybackService
import com.example.audiobook.SyncAlignmentSerializer
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookEntity
import com.example.data.prefs.ReaderSettings
import com.example.epub.EpubChapterSummary
import com.example.epub.EpubParser
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsSessionContent
import com.example.tts.TtsSourceType
import com.example.tts.TtsStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ReaderUiState(
    val isLoading: Boolean = true,
    val book: BookEntity? = null,
    val chapters: List<EpubChapterSummary> = emptyList(),
    val currentChapterIndex: Int = 0,
    val webChapterPayload: EpubParser.WebChapterPayload? = null,
    val jumpHighlightSentenceIndex: Int = -1,
    val errorMessage: String? = null
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LexiVoiceApplication
    private val libraryRepo = app.libraryRepository
    private val prefsRepo = app.preferencesRepository

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val readerSettings: StateFlow<ReaderSettings> = prefsRepo.readerSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())

    private var pendingJumpBookId: Long = -1L
    private var pendingJumpChapter: Int = -1
    private var pendingJumpSentence: Int = -1

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
                        EpubParser.loadChapterForWebView(
                            context = app,
                            fileKey = current.book.fileKey,
                            isDirectPath = current.book.isDirectPath,
                            chapterIndex = targetCh,
                            chapterHref = summary.href,
                            chapterTitle = summary.title
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
                EpubParser.loadChapterForWebView(
                    context = app,
                    fileKey = book.fileKey,
                    isDirectPath = book.isDirectPath,
                    chapterIndex = targetChapterIdx,
                    chapterHref = chapters[targetChapterIdx].href,
                    chapterTitle = chapters[targetChapterIdx].title
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

    fun selectChapter(chapterIndex: Int, startPlayingFromSentence: Int? = null) {
        val current = _uiState.value
        val book = current.book ?: return
        if (chapterIndex !in current.chapters.indices) return

        val ttsState = TtsStateHolder.state.value
        val wasPlayingThisBook = ttsState.isPlaying && ttsState.sourceType == TtsSourceType.BOOK && ttsState.bookId == book.id
        val autoSpeakSentence = startPlayingFromSentence ?: if (wasPlayingThisBook) 0 else null

        viewModelScope.launch {
            val summary = current.chapters[chapterIndex]
            val payload = withContext(Dispatchers.IO) {
                EpubParser.loadChapterForWebView(
                    context = app,
                    fileKey = book.fileKey,
                    isDirectPath = book.isDirectPath,
                    chapterIndex = chapterIndex,
                    chapterHref = summary.href,
                    chapterTitle = summary.title
                )
            }
            _uiState.value = current.copy(
                currentChapterIndex = chapterIndex,
                webChapterPayload = payload,
                jumpHighlightSentenceIndex = -1
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
                EpubParser.loadChapterForWebView(
                    context = app,
                    fileKey = current.book.fileKey,
                    isDirectPath = current.book.isDirectPath,
                    chapterIndex = ttsChapterIndex,
                    chapterHref = summary.href,
                    chapterTitle = summary.title
                )
            }
            _uiState.value = _uiState.value.copy(
                currentChapterIndex = ttsChapterIndex,
                webChapterPayload = payload
            )
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

    fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch {
            prefsRepo.updateReaderSettings(transform)
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
                    val chapters = SyncAlignmentSerializer.decodeChapters(audiobook.chaptersJson)
                    chapters.getOrNull(curChapter)?.startMs ?: audiobook.lastPositionMs
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
}
