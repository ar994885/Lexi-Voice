package com.example.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.provider.Settings
import com.example.MainActivity
import com.example.audiobook.M4bPlaybackService
import com.example.audiobook.M4bPlayerStateHolder
import com.example.data.prefs.FloatingAppearanceMode
import com.example.data.prefs.FloatingOverlaySettings
import com.example.data.prefs.PreferencesRepository
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsSourceType
import com.example.tts.TtsStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UnifiedOverlaySource {
    NONE,
    TTS,
    AUDIOBOOK
}

data class ResolvedOverlayColors(
    val backgroundArgb: Int,
    val borderArgb: Int,
    val borderWidthDp: Int,
    val primaryTextArgb: Int,
    val secondaryIconArgb: Int
)

object OverlayStyleHelper {
    fun resolveColors(settings: FloatingOverlaySettings): ResolvedOverlayColors {
        val bgArgb: Int
        val borderArgb: Int
        val borderWidthDp: Int
        if (settings.appearanceMode == FloatingAppearanceMode.CUSTOM) {
            bgArgb = settings.backgroundColorArgb
            borderArgb = settings.borderColorArgb
            borderWidthDp = settings.borderWidthDp.coerceIn(0, 2)
        } else {
            bgArgb = 0xE61E1E24.toInt()
            borderArgb = 0x33FFFFFF
            borderWidthDp = 1
        }

        val a = AndroidColor.alpha(bgArgb)
        val r = AndroidColor.red(bgArgb) / 255.0
        val g = AndroidColor.green(bgArgb) / 255.0
        val b = AndroidColor.blue(bgArgb) / 255.0
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        val isLightOpaque = (a >= 110) && (luminance >= 0.55)

        return ResolvedOverlayColors(
            backgroundArgb = bgArgb,
            borderArgb = borderArgb,
            borderWidthDp = borderWidthDp,
            primaryTextArgb = if (isLightOpaque) 0xFF1A1A22.toInt() else 0xFFF5F5F7.toInt(),
            secondaryIconArgb = if (isLightOpaque) 0xFF4F5260.toInt() else 0xFFB8BCC8.toInt()
        )
    }
}

data class FloatingOverlayConfig(
    val isVisible: Boolean = false,
    val isTemporarilyHidden: Boolean = false,
    val lastActiveSource: UnifiedOverlaySource = UnifiedOverlaySource.NONE
)

data class FloatingOverlayState(
    val isVisible: Boolean = false,
    val masterEnabled: Boolean = true,
    val isTemporarilyHidden: Boolean = false,
    val activeSource: UnifiedOverlaySource = UnifiedOverlaySource.NONE,
    val isPlaying: Boolean = false,
    val displayText: String = "Tap Play to speak",
    val subtitleText: String = "",
    val bookId: Long = -1L,
    val audiobookId: Long = -1L,
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val appearanceSettings: FloatingOverlaySettings = FloatingOverlaySettings()
)

object FloatingOverlayController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = MutableStateFlow(FloatingOverlayConfig())
    private val appearanceFlow = MutableStateFlow(FloatingOverlaySettings())
    private var prefsCollectorJob: Job? = null
    private var prefsRepoRef: PreferencesRepository? = null

    val state: StateFlow<FloatingOverlayState> = combine(
        TtsStateHolder.state,
        M4bPlayerStateHolder.state,
        config,
        appearanceFlow
    ) { tts, m4b, cfg, appearance ->
        val resolvedSource = when {
            tts.isPlaying -> UnifiedOverlaySource.TTS
            m4b.isPlaying -> UnifiedOverlaySource.AUDIOBOOK
            cfg.lastActiveSource == UnifiedOverlaySource.AUDIOBOOK && m4b.isActive -> UnifiedOverlaySource.AUDIOBOOK
            cfg.lastActiveSource == UnifiedOverlaySource.TTS && tts.isActive -> UnifiedOverlaySource.TTS
            m4b.isActive -> UnifiedOverlaySource.AUDIOBOOK
            tts.isActive || tts.sourceType != TtsSourceType.NONE -> UnifiedOverlaySource.TTS
            else -> UnifiedOverlaySource.NONE
        }

        val effectiveVisible = appearance.masterEnabled && cfg.isVisible

        when (resolvedSource) {
            UnifiedOverlaySource.AUDIOBOOK -> {
                val chapterName = m4b.chapters.getOrNull(m4b.currentChapterIndex)?.title.orEmpty()
                val text = m4b.activeSyncText.ifBlank {
                    chapterName.ifBlank { m4b.title.ifBlank { "Audiobook Ready" } }
                }
                FloatingOverlayState(
                    isVisible = effectiveVisible,
                    masterEnabled = appearance.masterEnabled,
                    isTemporarilyHidden = cfg.isTemporarilyHidden,
                    activeSource = UnifiedOverlaySource.AUDIOBOOK,
                    isPlaying = m4b.isPlaying,
                    displayText = text,
                    subtitleText = m4b.title,
                    audiobookId = m4b.audiobookId,
                    chapterIndex = m4b.activeSyncChapterIndex.takeIf { m4b.hasSyncedEpub } ?: m4b.currentChapterIndex,
                    sentenceIndex = m4b.activeSyncSentenceIndex.coerceAtLeast(0),
                    appearanceSettings = appearance
                )
            }
            UnifiedOverlaySource.TTS -> {
                val text = tts.currentSentenceText.ifBlank {
                    tts.currentParagraphText.ifBlank {
                        tts.chapterTitle.ifBlank { tts.bookTitle.ifBlank { "Tap Play to speak" } }
                    }
                }
                FloatingOverlayState(
                    isVisible = effectiveVisible,
                    masterEnabled = appearance.masterEnabled,
                    isTemporarilyHidden = cfg.isTemporarilyHidden,
                    activeSource = UnifiedOverlaySource.TTS,
                    isPlaying = tts.isPlaying,
                    displayText = text,
                    subtitleText = tts.bookTitle,
                    bookId = tts.bookId,
                    chapterIndex = tts.chapterIndex,
                    sentenceIndex = tts.sentenceIndex,
                    appearanceSettings = appearance
                )
            }
            UnifiedOverlaySource.NONE -> {
                FloatingOverlayState(
                    isVisible = effectiveVisible,
                    masterEnabled = appearance.masterEnabled,
                    isTemporarilyHidden = cfg.isTemporarilyHidden,
                    activeSource = UnifiedOverlaySource.NONE,
                    isPlaying = false,
                    displayText = "Open an EPUB or Audiobook to play",
                    appearanceSettings = appearance
                )
            }
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = FloatingOverlayState()
    )

    fun attachPreferences(prefsRepo: PreferencesRepository) {
        prefsRepoRef = prefsRepo
        prefsCollectorJob?.cancel()
        prefsCollectorJob = scope.launch {
            prefsRepo.floatingOverlaySettings.collect { settings ->
                appearanceFlow.value = settings
            }
        }
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        appearanceFlow.update { it.copy(masterEnabled = enabled) }
        prefsRepoRef?.let { repo ->
            scope.launch {
                repo.updateFloatingOverlaySettings { it.copy(masterEnabled = enabled) }
            }
        }
        setVisibleInternal(context, enabled)
    }

    fun markActiveSource(source: UnifiedOverlaySource) {
        if (source == UnifiedOverlaySource.NONE) return
        config.update { it.copy(lastActiveSource = source) }
    }

    fun setVisible(context: Context, visible: Boolean) {
        if (visible && !appearanceFlow.value.masterEnabled) {
            appearanceFlow.update { it.copy(masterEnabled = true) }
            prefsRepoRef?.let { repo ->
                scope.launch {
                    repo.updateFloatingOverlaySettings { it.copy(masterEnabled = true) }
                }
            }
        }
        setVisibleInternal(context, visible)
    }

    private fun setVisibleInternal(context: Context, visible: Boolean) {
        config.update {
            it.copy(
                isVisible = visible,
                isTemporarilyHidden = false
            )
        }
        val effective = appearanceFlow.value.masterEnabled && visible
        TtsStateHolder.updateState {
            it.copy(floatingPopupVisible = effective, floatingPopupTemporarilyHidden = false)
        }
        if (Settings.canDrawOverlays(context)) {
            val intent = Intent(context, FloatingOverlayService::class.java).setAction(
                if (effective) FloatingOverlayService.ACTION_SHOW else FloatingOverlayService.ACTION_HIDE
            )
            runCatching { context.startService(intent) }
        }
    }

    fun setTemporarilyHidden(hidden: Boolean) {
        config.update { it.copy(isTemporarilyHidden = hidden) }
        TtsStateHolder.updateState { it.copy(floatingPopupTemporarilyHidden = hidden) }
    }

    fun togglePlayPause(context: Context) {
        when (state.value.activeSource) {
            UnifiedOverlaySource.AUDIOBOOK -> {
                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
            }
            UnifiedOverlaySource.TTS -> {
                TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
            }
            UnifiedOverlaySource.NONE -> {
                val m4b = M4bPlayerStateHolder.state.value
                val tts = TtsStateHolder.state.value
                when {
                    m4b.audiobookId > 0L -> M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
                    tts.bookId > 0L -> TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
                }
            }
        }
    }

    fun skipPrevious(context: Context) {
        when (state.value.activeSource) {
            UnifiedOverlaySource.AUDIOBOOK -> {
                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_PREV_SENTENCE)
            }
            UnifiedOverlaySource.TTS -> {
                TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_PREV_SENTENCE)
            }
            UnifiedOverlaySource.NONE -> Unit
        }
    }

    fun skipNext(context: Context) {
        when (state.value.activeSource) {
            UnifiedOverlaySource.AUDIOBOOK -> {
                M4bPlaybackService.sendAction(context, M4bPlaybackService.ACTION_NEXT_SENTENCE)
            }
            UnifiedOverlaySource.TTS -> {
                TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_NEXT_SENTENCE)
            }
            UnifiedOverlaySource.NONE -> Unit
        }
    }

    fun buildDeepLinkIntent(context: Context, snapshot: FloatingOverlayState = state.value): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OVERLAY_SOURCE, snapshot.activeSource.name)
            putExtra(EXTRA_BOOK_ID, snapshot.bookId)
            putExtra(EXTRA_AUDIOBOOK_ID, snapshot.audiobookId)
            putExtra(EXTRA_CHAPTER_INDEX, snapshot.chapterIndex)
            putExtra(EXTRA_SENTENCE_INDEX, snapshot.sentenceIndex)
        }
    }

    const val EXTRA_OVERLAY_SOURCE = "extra_overlay_source"
    const val EXTRA_BOOK_ID = "extra_overlay_book_id"
    const val EXTRA_AUDIOBOOK_ID = "extra_overlay_audiobook_id"
    const val EXTRA_CHAPTER_INDEX = "extra_overlay_chapter_index"
    const val EXTRA_SENTENCE_INDEX = "extra_overlay_sentence_index"
}
