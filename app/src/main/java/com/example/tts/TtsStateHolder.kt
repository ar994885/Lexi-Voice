package com.example.tts

import com.example.epub.EpubChapterSummary
import com.example.epub.ReaderParagraph
import com.example.epub.ReaderSentence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SleepTimerMode {
    OFF,
    END_OF_CHAPTER,
    MINUTES
}

enum class TtsSourceType {
    NONE,
    BOOK,
    SCRATCHPAD
}

data class TtsEngineOption(
    val packageName: String,
    val label: String
)

data class TtsLanguageOption(
    val localeTag: String,
    val displayName: String,
    val voiceCount: Int
)

data class TtsVoiceOption(
    val name: String,
    val localeTag: String,
    val displayLocale: String,
    val isNetworkRequired: Boolean
)

data class TtsPlaybackState(
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val isEngineReady: Boolean = false,
    val sourceType: TtsSourceType = TtsSourceType.NONE,
    val bookId: Long = -1L,
    val bookTitle: String = "",
    val bookAuthor: String = "",
    val fileKey: String = "",
    val isDirectPath: Boolean = false,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String = "",
    val sentenceIndex: Int = 0,
    val totalSentencesInChapter: Int = 0,
    val currentParagraphIndex: Int = 0,
    val currentParagraphText: String = "",
    val currentSentenceText: String = "",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val selectedEnginePackage: String = "",
    val selectedVoiceName: String = "",
    val selectedLocaleTag: String = "",
    val availableEngines: List<TtsEngineOption> = emptyList(),
    val availableLanguages: List<TtsLanguageOption> = emptyList(),
    val availableVoices: List<TtsVoiceOption> = emptyList(),
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val sleepRemainingMs: Long = 0L,
    val floatingPopupVisible: Boolean = false,
    val floatingPopupTemporarilyHidden: Boolean = false,
    val statusMessage: String = ""
)

data class TtsSessionContent(
    val bookId: Long,
    val bookTitle: String,
    val bookAuthor: String,
    val fileKey: String,
    val isDirectPath: Boolean,
    val chapters: List<EpubChapterSummary>,
    val currentChapterIndex: Int,
    val paragraphs: List<ReaderParagraph>,
    val sentences: List<ReaderSentence>
)

object TtsStateHolder {
    private val _state = MutableStateFlow(TtsPlaybackState())
    val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

    @Volatile
    var sessionContent: TtsSessionContent? = null
        private set

    fun updateState(transform: (TtsPlaybackState) -> TtsPlaybackState) {
        _state.update(transform)
    }

    fun setSessionContent(content: TtsSessionContent) {
        sessionContent = content
    }
}
