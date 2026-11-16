package com.example.tts

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.example.LexiVoiceApplication
import com.example.MainActivity
import com.example.R
import com.example.epub.EpubParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class TtsPlaybackService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var activeEnginePackage: String = ""
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sleepTimerJob: Job? = null

    private var currentUtteranceId: String = ""
    private var pendingSpeakAfterInit: Boolean = false

    // Compact T2S-style System Floating Overlay Views
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlaySentenceTv: TextView? = null
    private var overlayPlayBtn: ImageButton? = null
    private var overlayExpandedContainer: LinearLayout? = null
    private var overlayEdgeTab: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var savedExpandedX: Int = 0
    private var savedExpandedY: Int = 0

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LexiVoice::TtsWakeLock")

        mediaSession = MediaSessionCompat(this, "LexiVoiceTtsSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = nextSentence()
                override fun onSkipToPrevious() = previousSentence()
                override fun onStop() = stopPlaybackAndService()
            })
            isActive = true
        }

        serviceScope.launch {
            val prefs = (application as LexiVoiceApplication).preferencesRepository.ttsSettings.first()
            TtsStateHolder.updateState {
                it.copy(
                    speechRate = prefs.speechRate,
                    pitch = prefs.pitch,
                    selectedEnginePackage = prefs.enginePackage,
                    selectedVoiceName = prefs.voiceName,
                    selectedLocaleTag = prefs.localeTag
                )
            }
            initTts(prefs.enginePackage.ifBlank { null })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            ACTION_INIT_ENGINES -> {
                if (tts == null) initTts(TtsStateHolder.state.value.selectedEnginePackage.ifBlank { null })
            }
            ACTION_PLAY_BOOK -> {
                val sentenceIdx = intent.getIntExtra(EXTRA_SENTENCE_INDEX, 0)
                startForegroundWithNotification()
                playFromSentence(sentenceIdx, TtsSourceType.BOOK)
            }
            ACTION_PLAY_SCRATCHPAD -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Paste & Speak"
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val sentenceIdx = intent.getIntExtra(EXTRA_SENTENCE_INDEX, 0)
                val parsed = EpubParser.parsePlainTextToChapter(title, text)
                TtsStateHolder.setSessionContent(
                    TtsSessionContent(
                        bookId = -1L,
                        bookTitle = title,
                        bookAuthor = "Quick Text",
                        fileKey = "",
                        isDirectPath = false,
                        chapters = emptyList(),
                        currentChapterIndex = 0,
                        paragraphs = parsed.paragraphs,
                        sentences = parsed.sentences
                    )
                )
                startForegroundWithNotification()
                playFromSentence(sentenceIdx, TtsSourceType.SCRATCHPAD)
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                if (TtsStateHolder.state.value.isPlaying) pausePlayback() else resumePlayback()
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> {
                startForegroundWithNotification()
                resumePlayback()
            }
            ACTION_NEXT_SENTENCE -> nextSentence()
            ACTION_PREV_SENTENCE -> previousSentence()
            ACTION_NEXT_CHAPTER -> jumpChapter(delta = 1)
            ACTION_PREV_CHAPTER -> jumpChapter(delta = -1)
            ACTION_SET_RATE -> {
                val rate = intent.getFloatExtra(EXTRA_FLOAT, 1.0f).coerceIn(0.5f, 3.0f)
                TtsStateHolder.updateState { it.copy(speechRate = rate) }
                tts?.setSpeechRate(rate)
                if (TtsStateHolder.state.value.isPlaying) speakCurrentSentence()
            }
            ACTION_SET_PITCH -> {
                val pitch = intent.getFloatExtra(EXTRA_FLOAT, 1.0f).coerceIn(0.5f, 2.0f)
                TtsStateHolder.updateState { it.copy(pitch = pitch) }
                tts?.setPitch(pitch)
                if (TtsStateHolder.state.value.isPlaying) speakCurrentSentence()
            }
            ACTION_SET_ENGINE -> {
                val pkg = intent.getStringExtra(EXTRA_STRING).orEmpty()
                TtsStateHolder.updateState {
                    it.copy(
                        selectedEnginePackage = pkg,
                        selectedVoiceName = "",
                        selectedLocaleTag = "",
                        isEngineReady = false
                    )
                }
                initTts(pkg.ifBlank { null })
            }
            ACTION_SET_LANGUAGE -> {
                val localeTag = intent.getStringExtra(EXTRA_LOCALE).orEmpty()
                applyLanguageSelection(localeTag)
                if (TtsStateHolder.state.value.isPlaying) speakCurrentSentence()
            }
            ACTION_SET_VOICE -> {
                val voiceName = intent.getStringExtra(EXTRA_STRING).orEmpty()
                val localeTag = intent.getStringExtra(EXTRA_LOCALE).orEmpty()
                applyVoiceSelection(voiceName, localeTag)
                if (TtsStateHolder.state.value.isPlaying) speakCurrentSentence()
            }
            ACTION_SET_SLEEP_TIMER -> {
                val modeName = intent.getStringExtra(EXTRA_STRING) ?: SleepTimerMode.OFF.name
                val minutes = intent.getIntExtra(EXTRA_INT, 0)
                val mode = runCatching { SleepTimerMode.valueOf(modeName) }.getOrDefault(SleepTimerMode.OFF)
                configureSleepTimer(mode, minutes)
            }
            ACTION_SET_FLOATING_POPUP -> {
                val visible = intent.getBooleanExtra(EXTRA_BOOL, true)
                TtsStateHolder.updateState {
                    it.copy(
                        floatingPopupVisible = visible,
                        floatingPopupTemporarilyHidden = false
                    )
                }
                syncSystemOverlay()
            }
            ACTION_TEMP_HIDE_FLOATING -> {
                val hidden = intent.getBooleanExtra(EXTRA_BOOL, true)
                TtsStateHolder.updateState { it.copy(floatingPopupTemporarilyHidden = hidden) }
                syncSystemOverlay()
            }
            ACTION_STOP -> stopPlaybackAndService()
        }

        return START_NOT_STICKY
    }

    private fun initTts(enginePackage: String?) {
        tts?.stop()
        tts?.shutdown()
        activeEnginePackage = enginePackage.orEmpty()

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@OnInitListener
                val engines = runCatching {
                    engine.engines.map { TtsEngineOption(it.name, it.label) }
                }.getOrDefault(emptyList())

                val resolvedPkg = activeEnginePackage.ifBlank {
                    engine.defaultEngine.orEmpty()
                }

                val voices = runCatching {
                    engine.voices.orEmpty()
                        .sortedWith(compareBy({ it.locale.displayName }, { it.name }))
                        .map { v ->
                            TtsVoiceOption(
                                name = v.name,
                                localeTag = v.locale.toLanguageTag(),
                                displayLocale = v.locale.displayName,
                                isNetworkRequired = v.isNetworkConnectionRequired
                            )
                        }
                }.getOrDefault(emptyList())

                val languages = buildLanguageOptions(engine, voices)

                val st = TtsStateHolder.state.value
                val defaultTag = Locale.getDefault().toLanguageTag()
                val resolvedLocaleTag = st.selectedLocaleTag.takeIf { tag ->
                    tag.isNotBlank() && languages.any { it.localeTag.equals(tag, ignoreCase = true) }
                } ?: languages.firstOrNull { it.localeTag.equals(defaultTag, ignoreCase = true) }?.localeTag
                    ?: languages.firstOrNull { it.localeTag.startsWith("en", ignoreCase = true) }?.localeTag
                    ?: languages.firstOrNull()?.localeTag
                    ?: defaultTag

                engine.setSpeechRate(st.speechRate)
                engine.setPitch(st.pitch)

                if (st.selectedVoiceName.isNotBlank() && voices.any { it.name == st.selectedVoiceName }) {
                    applyVoiceOnEngine(engine, st.selectedVoiceName, resolvedLocaleTag)
                } else if (resolvedLocaleTag.isNotBlank()) {
                    engine.language = Locale.forLanguageTag(resolvedLocaleTag)
                }

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == currentUtteranceId) {
                            serviceScope.launch { handleSentenceFinished() }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == currentUtteranceId) {
                            serviceScope.launch { handleSentenceFinished() }
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == currentUtteranceId) {
                            serviceScope.launch { handleSentenceFinished() }
                        }
                    }
                })

                TtsStateHolder.updateState {
                    it.copy(
                        isEngineReady = true,
                        selectedEnginePackage = resolvedPkg,
                        selectedLocaleTag = resolvedLocaleTag,
                        availableEngines = engines,
                        availableLanguages = languages,
                        availableVoices = voices,
                        statusMessage = ""
                    )
                }

                if (pendingSpeakAfterInit || TtsStateHolder.state.value.isPlaying) {
                    pendingSpeakAfterInit = false
                    speakCurrentSentence()
                }
            } else {
                TtsStateHolder.updateState {
                    it.copy(
                        isEngineReady = false,
                        isPlaying = false,
                        statusMessage = "TTS engine failed to initialize"
                    )
                }
            }
        }

        tts = if (!enginePackage.isNullOrBlank()) {
            TextToSpeech(applicationContext, listener, enginePackage)
        } else {
            TextToSpeech(applicationContext, listener)
        }
    }

    private fun buildLanguageOptions(
        engine: TextToSpeech,
        voices: List<TtsVoiceOption>
    ): List<TtsLanguageOption> {
        val voiceCountByTag = voices.groupingBy { it.localeTag }.eachCount()
        val localeMap = linkedMapOf<String, Locale>()

        voices.forEach { v ->
            if (v.localeTag.isNotBlank()) {
                localeMap.putIfAbsent(v.localeTag, Locale.forLanguageTag(v.localeTag))
            }
        }
        runCatching { engine.availableLanguages }.getOrNull()?.forEach { loc ->
            val tag = loc.toLanguageTag()
            if (tag.isNotBlank() && tag != "und") {
                localeMap.putIfAbsent(tag, loc)
            }
        }

        val defaultLocale = Locale.getDefault()
        val defaultTag = defaultLocale.toLanguageTag()

        return localeMap.entries.map { (tag, loc) ->
            TtsLanguageOption(
                localeTag = tag,
                displayName = loc.displayName.ifBlank { tag },
                voiceCount = voiceCountByTag[tag] ?: 0
            )
        }.sortedWith(
            compareBy<TtsLanguageOption>(
                { !(it.localeTag.equals(defaultTag, ignoreCase = true)) },
                { !(it.localeTag.startsWith("en", ignoreCase = true)) },
                { it.displayName.lowercase(Locale.ROOT) }
            )
        )
    }

    private fun applyLanguageSelection(localeTag: String) {
        val st = TtsStateHolder.state.value
        val matchingVoice = st.availableVoices.firstOrNull {
            it.localeTag.equals(localeTag, ignoreCase = true)
        }
        TtsStateHolder.updateState {
            it.copy(
                selectedLocaleTag = localeTag,
                selectedVoiceName = matchingVoice?.name.orEmpty()
            )
        }
        tts?.let { engine ->
            if (matchingVoice != null) {
                applyVoiceOnEngine(engine, matchingVoice.name, localeTag)
            } else if (localeTag.isNotBlank()) {
                engine.language = Locale.forLanguageTag(localeTag)
            }
        }
    }

    private fun applyVoiceSelection(voiceName: String, localeTag: String) {
        TtsStateHolder.updateState {
            it.copy(
                selectedVoiceName = voiceName,
                selectedLocaleTag = localeTag.ifBlank { it.selectedLocaleTag }
            )
        }
        tts?.let { applyVoiceOnEngine(it, voiceName, localeTag) }
    }

    private fun applyVoiceOnEngine(engine: TextToSpeech, voiceName: String, localeTag: String) {
        val match = runCatching {
            engine.voices?.firstOrNull { it.name == voiceName }
        }.getOrNull()
        if (match != null) {
            engine.voice = match
        } else if (localeTag.isNotBlank()) {
            engine.language = Locale.forLanguageTag(localeTag)
        }
    }

    private fun playFromSentence(sentenceIndex: Int, sourceType: TtsSourceType) {
        val session = TtsStateHolder.sessionContent ?: return
        if (session.sentences.isEmpty()) return

        val clampedIdx = sentenceIndex.coerceIn(0, session.sentences.lastIndex)
        val sentence = session.sentences[clampedIdx]
        val paragraphText = session.paragraphs.getOrNull(sentence.paragraphIndex)?.text ?: sentence.text
        val chapterTitle = session.chapters.getOrNull(session.currentChapterIndex)?.title
            ?: session.bookTitle

        TtsStateHolder.updateState {
            it.copy(
                isActive = true,
                isPlaying = true,
                sourceType = sourceType,
                bookId = session.bookId,
                bookTitle = session.bookTitle,
                bookAuthor = session.bookAuthor,
                fileKey = session.fileKey,
                isDirectPath = session.isDirectPath,
                chapterIndex = session.currentChapterIndex,
                chapterCount = session.chapters.size.coerceAtLeast(1),
                chapterTitle = chapterTitle,
                sentenceIndex = clampedIdx,
                totalSentencesInChapter = session.sentences.size,
                currentParagraphIndex = sentence.paragraphIndex,
                currentParagraphText = paragraphText,
                currentSentenceText = sentence.text
            )
        }

        requestAudioFocusAndWakeLock()
        if (TtsStateHolder.state.value.isEngineReady) {
            speakCurrentSentence()
        } else {
            pendingSpeakAfterInit = true
        }
        updateMediaSessionAndNotification()
        syncSystemOverlay()
    }

    private fun speakCurrentSentence() {
        val session = TtsStateHolder.sessionContent ?: return
        val st = TtsStateHolder.state.value
        val sentence = session.sentences.getOrNull(st.sentenceIndex) ?: return
        val paragraphText = session.paragraphs.getOrNull(sentence.paragraphIndex)?.text ?: sentence.text

        TtsStateHolder.updateState {
            it.copy(
                isPlaying = true,
                isActive = true,
                currentParagraphIndex = sentence.paragraphIndex,
                currentParagraphText = paragraphText,
                currentSentenceText = sentence.text
            )
        }

        persistBookProgress(st.bookId, session.currentChapterIndex, st.sentenceIndex, session.chapters.size)

        val engine = tts
        if (engine == null || !st.isEngineReady) {
            pendingSpeakAfterInit = true
            return
        }

        currentUtteranceId = "utt_${UUID.randomUUID()}"
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        engine.setSpeechRate(st.speechRate)
        engine.setPitch(st.pitch)
        engine.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, params, currentUtteranceId)

        updateMediaSessionAndNotification()
        updateOverlayContent()
    }

    private suspend fun handleSentenceFinished() {
        val st = TtsStateHolder.state.value
        if (!st.isPlaying) return
        val session = TtsStateHolder.sessionContent ?: return

        val nextIdx = st.sentenceIndex + 1
        if (nextIdx < session.sentences.size) {
            TtsStateHolder.updateState { it.copy(sentenceIndex = nextIdx) }
            speakCurrentSentence()
        } else {
            if (st.sleepTimerMode == SleepTimerMode.END_OF_CHAPTER) {
                TtsStateHolder.updateState {
                    it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L)
                }
                pausePlayback()
                return
            }

            if (st.sourceType == TtsSourceType.BOOK &&
                session.currentChapterIndex + 1 < session.chapters.size
            ) {
                loadAndPlayChapter(session.currentChapterIndex + 1, startAtEnd = false)
            } else {
                pausePlayback()
            }
        }
    }

    private fun loadAndPlayChapter(targetChapterIdx: Int, startAtEnd: Boolean) {
        val session = TtsStateHolder.sessionContent ?: return
        if (targetChapterIdx !in session.chapters.indices) return
        val chapterSummary = session.chapters[targetChapterIdx]

        serviceScope.launch {
            val content = withContext(Dispatchers.IO) {
                EpubParser.loadChapterContent(
                    context = applicationContext,
                    fileKey = session.fileKey,
                    isDirectPath = session.isDirectPath,
                    chapterIndex = targetChapterIdx,
                    chapterHref = chapterSummary.href,
                    chapterTitle = chapterSummary.title
                )
            }
            if (content.sentences.isEmpty()) {
                if (targetChapterIdx + 1 < session.chapters.size) {
                    loadAndPlayChapter(targetChapterIdx + 1, startAtEnd = false)
                } else {
                    pausePlayback()
                }
                return@launch
            }
            val startSentence = if (startAtEnd) content.sentences.lastIndex else 0
            TtsStateHolder.setSessionContent(
                session.copy(
                    currentChapterIndex = targetChapterIdx,
                    paragraphs = content.paragraphs,
                    sentences = content.sentences
                )
            )
            val sentence = content.sentences[startSentence]
            val paraText = content.paragraphs.getOrNull(sentence.paragraphIndex)?.text ?: sentence.text
            TtsStateHolder.updateState {
                it.copy(
                    chapterIndex = targetChapterIdx,
                    chapterTitle = chapterSummary.title,
                    sentenceIndex = startSentence,
                    totalSentencesInChapter = content.sentences.size,
                    currentParagraphIndex = sentence.paragraphIndex,
                    currentParagraphText = paraText,
                    currentSentenceText = sentence.text
                )
            }
            if (TtsStateHolder.state.value.isPlaying) {
                speakCurrentSentence()
            } else {
                updateMediaSessionAndNotification()
                updateOverlayContent()
            }
        }
    }

    private fun pausePlayback() {
        currentUtteranceId = ""
        tts?.stop()
        releaseWakeLock()
        TtsStateHolder.updateState { it.copy(isPlaying = false) }
        updateMediaSessionAndNotification()
        updateOverlayContent()
    }

    private fun resumePlayback() {
        val session = TtsStateHolder.sessionContent ?: return
        if (session.sentences.isEmpty()) return
        requestAudioFocusAndWakeLock()
        TtsStateHolder.updateState { it.copy(isPlaying = true, isActive = true) }
        speakCurrentSentence()
        syncSystemOverlay()
    }

    private fun nextSentence() {
        val session = TtsStateHolder.sessionContent ?: return
        val st = TtsStateHolder.state.value
        val next = st.sentenceIndex + 1
        if (next < session.sentences.size) {
            TtsStateHolder.updateState { it.copy(sentenceIndex = next) }
            if (st.isPlaying) {
                speakCurrentSentence()
            } else {
                val sentence = session.sentences[next]
                val paraText = session.paragraphs.getOrNull(sentence.paragraphIndex)?.text ?: sentence.text
                TtsStateHolder.updateState {
                    it.copy(
                        currentParagraphIndex = sentence.paragraphIndex,
                        currentParagraphText = paraText,
                        currentSentenceText = sentence.text
                    )
                }
                updateOverlayContent()
                updateMediaSessionAndNotification()
            }
        } else if (st.sourceType == TtsSourceType.BOOK &&
            session.currentChapterIndex + 1 < session.chapters.size
        ) {
            loadAndPlayChapter(session.currentChapterIndex + 1, startAtEnd = false)
        }
    }

    private fun previousSentence() {
        val session = TtsStateHolder.sessionContent ?: return
        val st = TtsStateHolder.state.value
        val prev = st.sentenceIndex - 1
        if (prev >= 0) {
            TtsStateHolder.updateState { it.copy(sentenceIndex = prev) }
            if (st.isPlaying) {
                speakCurrentSentence()
            } else {
                val sentence = session.sentences[prev]
                val paraText = session.paragraphs.getOrNull(sentence.paragraphIndex)?.text ?: sentence.text
                TtsStateHolder.updateState {
                    it.copy(
                        currentParagraphIndex = sentence.paragraphIndex,
                        currentParagraphText = paraText,
                        currentSentenceText = sentence.text
                    )
                }
                updateOverlayContent()
                updateMediaSessionAndNotification()
            }
        } else if (st.sourceType == TtsSourceType.BOOK && session.currentChapterIndex > 0) {
            loadAndPlayChapter(session.currentChapterIndex - 1, startAtEnd = true)
        }
    }

    private fun jumpChapter(delta: Int) {
        val session = TtsStateHolder.sessionContent ?: return
        if (TtsStateHolder.state.value.sourceType != TtsSourceType.BOOK) return
        val target = (session.currentChapterIndex + delta).coerceIn(0, session.chapters.lastIndex)
        if (target != session.currentChapterIndex) {
            loadAndPlayChapter(target, startAtEnd = false)
        }
    }

    private fun configureSleepTimer(mode: SleepTimerMode, minutes: Int) {
        sleepTimerJob?.cancel()
        when (mode) {
            SleepTimerMode.OFF -> {
                TtsStateHolder.updateState {
                    it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L)
                }
            }
            SleepTimerMode.END_OF_CHAPTER -> {
                TtsStateHolder.updateState {
                    it.copy(sleepTimerMode = SleepTimerMode.END_OF_CHAPTER, sleepRemainingMs = 0L)
                }
            }
            SleepTimerMode.MINUTES -> {
                val safeMin = minutes.coerceIn(1, 600)
                val totalMs = safeMin * 60_000L
                TtsStateHolder.updateState {
                    it.copy(sleepTimerMode = SleepTimerMode.MINUTES, sleepRemainingMs = totalMs)
                }
                sleepTimerJob = serviceScope.launch {
                    var remaining = totalMs
                    while (remaining > 0L) {
                        delay(1000L)
                        remaining -= 1000L
                        TtsStateHolder.updateState { it.copy(sleepRemainingMs = remaining.coerceAtLeast(0L)) }
                    }
                    TtsStateHolder.updateState {
                        it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepRemainingMs = 0L)
                    }
                    pausePlayback()
                }
            }
        }
    }

    private fun stopPlaybackAndService() {
        sleepTimerJob?.cancel()
        currentUtteranceId = ""
        tts?.stop()
        releaseAudioFocus()
        releaseWakeLock()
        removeSystemOverlay()
        TtsStateHolder.updateState {
            it.copy(
                isActive = false,
                isPlaying = false,
                floatingPopupVisible = false,
                floatingPopupTemporarilyHidden = false
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun persistBookProgress(bookId: Long, chapterIdx: Int, sentenceIdx: Int, totalChapters: Int) {
        if (bookId <= 0L) return
        val app = application as? LexiVoiceApplication ?: return
        serviceScope.launch {
            app.libraryRepository.updateReadingProgress(
                bookId = bookId,
                chapterIndex = chapterIdx,
                sentenceIndex = sentenceIdx,
                scrollOffset = 0,
                totalChapters = totalChapters
            )
        }
    }

    private fun requestAudioFocusAndWakeLock() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        audioFocusRequest = req
        am.requestAudioFocus(req)

        wakeLock?.let { wl ->
            if (!wl.isHeld) wl.acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseAudioFocus() {
        val am = audioManager ?: return
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { wl -> if (wl.isHeld) wl.release() }
    }

    private fun startForegroundWithNotification() {
        startForeground(NOTIFICATION_ID, buildMediaNotification())
    }

    private fun updateMediaSessionAndNotification() {
        val st = TtsStateHolder.state.value
        val session = mediaSession ?: return

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (st.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                st.sentenceIndex.toLong(),
                st.speechRate
            )
            .build()
        session.setPlaybackState(playbackState)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, st.chapterTitle.ifBlank { st.bookTitle })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, st.bookAuthor.ifBlank { "LexiVoice TTS" })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, st.bookTitle)
            .build()
        session.setMetadata(metadata)

        if (st.isActive) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildMediaNotification())
        }
    }

    private fun buildMediaNotification(): android.app.Notification {
        val st = TtsStateHolder.state.value
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevPending = servicePendingIntent(1, ACTION_PREV_SENTENCE)
        val playPausePending = servicePendingIntent(2, ACTION_TOGGLE_PLAY_PAUSE)
        val nextPending = servicePendingIntent(3, ACTION_NEXT_SENTENCE)
        val stopPending = servicePendingIntent(4, ACTION_STOP)

        return NotificationCompat.Builder(this, LexiVoiceApplication.CHANNEL_TTS_PLAYBACK)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(st.bookTitle.ifBlank { "LexiVoice TTS" })
            .setContentText(st.currentSentenceText.ifBlank { st.chapterTitle })
            .setSubText(st.chapterTitle)
            .setContentIntent(openAppIntent)
            .setOngoing(st.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPending)
            .addAction(
                if (st.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (st.isPlaying) "Pause" else "Play",
                playPausePending
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(reqCode: Int, action: String): PendingIntent {
        val intent = Intent(this, TtsPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            reqCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // --- Compact T2S-Style Floating Overlay Window (~260dp wide, 8dp corners, #E61E1E24, 6dp padding) ---

    private fun syncSystemOverlay() {
        val st = TtsStateHolder.state.value
        if (!st.floatingPopupVisible || !Settings.canDrawOverlays(this)) {
            removeSystemOverlay()
            return
        }
        if (overlayView == null) {
            createCompactSystemOverlayView()
        }
        updateOverlayContent()
    }

    private fun createCompactSystemOverlayView() {
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61E1E24"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            elevation = dp(8).toFloat()
        }

        val expanded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // Top row: 2-line sentence preview + Edge-Dock Hide + Close (X)
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val sentenceTv = TextView(this).apply {
            setTextColor(Color.parseColor("#F5F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(2), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val hideBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_dock_hide)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Dock to screen edge"
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setOnClickListener {
                TtsStateHolder.updateState { it.copy(floatingPopupTemporarilyHidden = true) }
                updateOverlayContent()
            }
        }

        val closeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close_small)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Close overlay"
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setOnClickListener {
                TtsStateHolder.updateState {
                    it.copy(floatingPopupVisible = false, floatingPopupTemporarilyHidden = false)
                }
                removeSystemOverlay()
            }
        }

        topRow.addView(sentenceTv)
        topRow.addView(hideBtn)
        topRow.addView(closeBtn)

        // Bottom row: Compact 36dp x 36dp icon buttons (SkipPrevious, Play/Pause, SkipNext)
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }

        val prevBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_skip_previous)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Previous sentence"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(18)
            }
            setOnClickListener { previousSentence() }
        }

        val playBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play_arrow)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D97736"))
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Play or pause"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener {
                if (TtsStateHolder.state.value.isPlaying) pausePlayback() else resumePlayback()
            }
        }

        val nextBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_skip_next)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Next sentence"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginStart = dp(18)
            }
            setOnClickListener { nextSentence() }
        }

        controlsRow.addView(prevBtn)
        controlsRow.addView(playBtn)
        controlsRow.addView(nextBtn)

        expanded.addView(topRow)
        expanded.addView(controlsRow)

        // Tiny 32dp x 48dp semi-transparent edge-dock tab when minimized
        val edgeTab = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(48))
            val icon = ImageView(this@TtsPlaybackService).apply {
                setImageResource(R.drawable.ic_play_arrow)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            }
            addView(icon)
            setOnClickListener {
                TtsStateHolder.updateState { it.copy(floatingPopupTemporarilyHidden = false) }
                updateOverlayContent()
            }
        }

        root.addView(expanded)
        root.addView(edgeTab)

        savedExpandedX = dp(24)
        savedExpandedY = dp(120)

        val params = WindowManager.LayoutParams(
            dp(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedExpandedX
            y = savedExpandedY
        }

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        topRow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - touchX).toInt()
                    params.y = initY + (event.rawY - touchY).toInt()
                    savedExpandedX = params.x
                    savedExpandedY = params.y
                    runCatching { wm.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(root, params)
            overlayView = root
            overlaySentenceTv = sentenceTv
            overlayPlayBtn = playBtn
            overlayExpandedContainer = expanded
            overlayEdgeTab = edgeTab
            overlayParams = params
        }
    }

    private fun updateOverlayContent() {
        val root = overlayView ?: return
        val wm = windowManager ?: return
        val params = overlayParams ?: return
        val st = TtsStateHolder.state.value
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        if (st.floatingPopupTemporarilyHidden) {
            overlayExpandedContainer?.visibility = View.GONE
            overlayEdgeTab?.visibility = View.VISIBLE
            params.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            params.x = 0
            params.y = 0
            params.width = dp(32)
            params.height = dp(48)
        } else {
            overlayExpandedContainer?.visibility = View.VISIBLE
            overlayEdgeTab?.visibility = View.GONE
            params.gravity = Gravity.TOP or Gravity.START
            params.x = savedExpandedX
            params.y = savedExpandedY
            params.width = dp(260)
            params.height = WindowManager.LayoutParams.WRAP_CONTENT

            overlaySentenceTv?.text = st.currentSentenceText.ifBlank {
                st.currentParagraphText.ifBlank { "Tap Play to speak" }
            }
            overlayPlayBtn?.setImageResource(
                if (st.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
        }
        runCatching { wm.updateViewLayout(root, params) }
    }

    private fun removeSystemOverlay() {
        overlayView?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        overlayView = null
        overlaySentenceTv = null
        overlayPlayBtn = null
        overlayExpandedContainer = null
        overlayEdgeTab = null
        overlayParams = null
    }

    override fun onDestroy() {
        removeSystemOverlay()
        releaseAudioFocus()
        releaseWakeLock()
        mediaSession?.release()
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4101

        const val ACTION_INIT_ENGINES = "com.example.tts.INIT_ENGINES"
        const val ACTION_PLAY_BOOK = "com.example.tts.PLAY_BOOK"
        const val ACTION_PLAY_SCRATCHPAD = "com.example.tts.PLAY_SCRATCHPAD"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.example.tts.TOGGLE_PLAY_PAUSE"
        const val ACTION_PAUSE = "com.example.tts.PAUSE"
        const val ACTION_RESUME = "com.example.tts.RESUME"
        const val ACTION_NEXT_SENTENCE = "com.example.tts.NEXT_SENTENCE"
        const val ACTION_PREV_SENTENCE = "com.example.tts.PREV_SENTENCE"
        const val ACTION_NEXT_CHAPTER = "com.example.tts.NEXT_CHAPTER"
        const val ACTION_PREV_CHAPTER = "com.example.tts.PREV_CHAPTER"
        const val ACTION_SET_RATE = "com.example.tts.SET_RATE"
        const val ACTION_SET_PITCH = "com.example.tts.SET_PITCH"
        const val ACTION_SET_ENGINE = "com.example.tts.SET_ENGINE"
        const val ACTION_SET_LANGUAGE = "com.example.tts.SET_LANGUAGE"
        const val ACTION_SET_VOICE = "com.example.tts.SET_VOICE"
        const val ACTION_SET_SLEEP_TIMER = "com.example.tts.SET_SLEEP_TIMER"
        const val ACTION_SET_FLOATING_POPUP = "com.example.tts.SET_FLOATING_POPUP"
        const val ACTION_TEMP_HIDE_FLOATING = "com.example.tts.TEMP_HIDE_FLOATING"
        const val ACTION_STOP = "com.example.tts.STOP"

        const val EXTRA_SENTENCE_INDEX = "sentence_index"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FLOAT = "float_val"
        const val EXTRA_INT = "int_val"
        const val EXTRA_STRING = "string_val"
        const val EXTRA_LOCALE = "locale_val"
        const val EXTRA_BOOL = "bool_val"

        fun sendAction(context: Context, action: String, extras: (Intent.() -> Unit)? = null) {
            val intent = Intent(context, TtsPlaybackService::class.java).setAction(action)
            extras?.invoke(intent)
            if (action == ACTION_PLAY_BOOK || action == ACTION_PLAY_SCRATCHPAD || action == ACTION_RESUME) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
