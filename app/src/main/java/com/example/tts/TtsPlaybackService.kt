package com.example.tts

import android.os.SystemClock
import android.view.KeyEvent

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.example.LexiVoiceApplication
import com.example.MainActivity
import com.example.audiobook.M4bPlaybackService
import com.example.audiobook.M4bPlayerStateHolder
import com.example.epub.EpubParser
import com.example.overlay.FloatingOverlayController
import com.example.overlay.UnifiedOverlaySource
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
    private var sleepTimerMinutes: Int = 0

    private var currentUtteranceId: String = ""

    private var lastProgressWriteAt = 0L
    private var pendingSpeakAfterInit: Boolean = false
    private var pendingTestSpeakText: String? = null

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

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LexiVoice::TtsWakeLock")

        mediaSession = MediaSessionCompat(this, "LexiVoiceTtsSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = nextSentence()
                override fun onSkipToPrevious() = previousSentence()
                override fun onStop() = stopPlaybackAndService()
            })
            isActive = true
        }
        // Route headset and Bluetooth buttons to this service even while the
        // session is momentarily inactive. Without a media-button receiver the
        // button can be delivered to the manifest receiver and go nowhere.
        mediaSession?.setMediaButtonReceiver(
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this,
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            )
        )

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

        // A headset button that arrives as a raw MEDIA_BUTTON intent is handled
        // here as well: handleIntent above only replays it into the session, which
        // is enough when the session is the system's chosen media button session
        // and does nothing at all when it is not.
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            @Suppress("DEPRECATION")
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (event != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                        if (TtsStateHolder.state.value.isPlaying) pausePlayback() else resumePlayback()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> resumePlayback()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> pausePlayback()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> nextSentence()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> previousSentence()
                    KeyEvent.KEYCODE_MEDIA_STOP -> stopPlaybackAndService()
                }
            }
        }

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
                startForegroundWithNotification()
                if (TtsStateHolder.state.value.isPlaying) pausePlayback() else resumePlayback()
                if (!TtsStateHolder.state.value.isActive) stopForeground(STOP_FOREGROUND_REMOVE)
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> {
                startForegroundWithNotification()
                resumePlayback()
                if (!TtsStateHolder.state.value.isActive) stopForeground(STOP_FOREGROUND_REMOVE)
            }
            ACTION_NEXT_SENTENCE -> {
                startForegroundWithNotification()
                nextSentence()
                if (!TtsStateHolder.state.value.isActive) stopForeground(STOP_FOREGROUND_REMOVE)
            }
            ACTION_PREV_SENTENCE -> {
                startForegroundWithNotification()
                previousSentence()
                if (!TtsStateHolder.state.value.isActive) stopForeground(STOP_FOREGROUND_REMOVE)
            }
            ACTION_NEXT_CHAPTER -> {
                startForegroundWithNotification()
                jumpChapter(delta = 1)
                if (!TtsStateHolder.state.value.isActive) stopForeground(STOP_FOREGROUND_REMOVE)
            }
            ACTION_PREV_CHAPTER -> {
                startForegroundWithNotification()
                jumpChapter(delta = -1)
                if (!TtsStateHolder.state.value.isActive) stopForeground(STOP_FOREGROUND_REMOVE)
            }
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
                FloatingOverlayController.setVisible(this, visible)
            }
            ACTION_TEMP_HIDE_FLOATING -> {
                val hidden = intent.getBooleanExtra(EXTRA_BOOL, true)
                FloatingOverlayController.setTemporarilyHidden(hidden)
            }
            ACTION_TEST_SPEAK -> {
                val sampleText = intent.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                if (sampleText.isNotBlank()) {
                    speakPronunciationTest(sampleText)
                }
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
                            reportTtsFailure(
                                "The speech engine stopped without producing audio.",
                                missingVoiceData = false
                            )
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId != currentUtteranceId) return
                        val message = when (errorCode) {
                            TextToSpeech.ERROR_SYNTHESIS ->
                                "The speech engine could not synthesise this text (error $errorCode)."
                            TextToSpeech.ERROR_INVALID_REQUEST ->
                                "The speech request was rejected by the engine (error $errorCode)."
                            TextToSpeech.ERROR_OUTPUT ->
                                "The speech engine could not write its audio output (error $errorCode)."
                            else ->
                                "The speech engine reported an error (code $errorCode)."
                        }
                        reportTtsFailure(message, missingVoiceData = false)
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
                } else {
                    pendingTestSpeakText?.let { sample ->
                        pendingTestSpeakText = null
                        speakPronunciationTest(sample)
                    }
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

        FloatingOverlayController.markActiveSource(UnifiedOverlaySource.TTS)
        if (M4bPlayerStateHolder.state.value.isPlaying) {
            M4bPlaybackService.sendAction(this, M4bPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
        }
        requestAudioFocusAndWakeLock()
        if (TtsStateHolder.state.value.isEngineReady) {
            speakCurrentSentence()
        } else {
            pendingSpeakAfterInit = true
        }
        updateMediaSessionAndNotification()
    }

    /**
     * Surfaces a speech failure to the UI instead of failing silently.
     *
     * Resets isPlaying so the transport controls do not claim playback that is
     * not happening, and records whether the cause is missing voice data so the
     * UI can offer the install action.
     */
    private fun reportTtsFailure(message: String, missingVoiceData: Boolean) {
        Log.w(TAG, "TTS failure: $message")
        TtsStateHolder.updateState {
            it.copy(
                isPlaying = false,
                ttsErrorMessage = message,
                missingVoiceData = missingVoiceData
            )
        }
        updateMediaSessionAndNotification()
    }

    private fun speakCurrentSentence() {
        val session = TtsStateHolder.sessionContent ?: return
        val st = TtsStateHolder.state.value
        // Re-arm a persistent sleep timer after a timer-triggered pause.
        if (st.sleepTimerMode == SleepTimerMode.MINUTES &&
            sleepTimerMinutes > 0 &&
            sleepTimerJob?.isActive != true
        ) {
            startSleepCountdown(sleepTimerMinutes * 60_000L)
        }
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

        val bookIdForRules = session.bookId.takeIf { st.sourceType == TtsSourceType.BOOK && it > 0L }
        serviceScope.launch {
            val app = application as? LexiVoiceApplication
            val rules = withContext(Dispatchers.IO) {
                app?.pronunciationRepository?.getEnabledRulesForPlayback(bookIdForRules).orEmpty()
            }
            val latestState = TtsStateHolder.state.value
            if (!latestState.isPlaying || latestState.sentenceIndex != st.sentenceIndex) return@launch
            val spokenText = PronunciationProcessor.applyAudioRules(
                rawText = sentence.text,
                rules = rules,
                bookId = bookIdForRules
            )

            // A chunk with no speakable character in it -- "...", a lone "]",
            // a divider line, or whatever a rule just emptied -- is rendered
            // by the engine as a stray vowel or click, not silence.
            if (spokenText.none { it.isLetterOrDigit() }) {
                nextSentence()
                return@launch
            }

            // Pre-flight: on several engines a language with no installed voice
            // data produces silence and never fires onError, so the app would look
            // like it was playing. Check first and report clearly instead.
            val localeTag = latestState.selectedLocaleTag
            if (localeTag.isNotBlank()) {
                val availability = engine.isLanguageAvailable(Locale.forLanguageTag(localeTag))
                if (availability == TextToSpeech.LANG_MISSING_DATA ||
                    availability == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    reportTtsFailure(
                        "No voice data is installed for $localeTag. Install voice data to use text-to-speech.",
                        missingVoiceData = true
                    )
                    return@launch
                }
            }

            currentUtteranceId = "utt_${UUID.randomUUID()}"
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            engine.setSpeechRate(latestState.speechRate)
            engine.setPitch(latestState.pitch)
            engine.speak(spokenText, TextToSpeech.QUEUE_FLUSH, params, currentUtteranceId)
        }

        updateMediaSessionAndNotification()
    }

    private fun speakPronunciationTest(sampleText: String) {
        val engine = tts
        val st = TtsStateHolder.state.value
        if (engine == null || !st.isEngineReady) {
            pendingTestSpeakText = sampleText
            if (engine == null) {
                initTts(st.selectedEnginePackage.ifBlank { null })
            }
            return
        }
        if (st.isPlaying) {
            pausePlayback()
        }
        currentUtteranceId = "test_${UUID.randomUUID()}"
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        engine.setSpeechRate(st.speechRate)
        engine.setPitch(st.pitch)
        engine.speak(sampleText, TextToSpeech.QUEUE_FLUSH, params, currentUtteranceId)
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
                // Stay armed: resuming carries on into the next chapter, and the end
                // of that chapter stops playback again until the user turns it off.
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
            }
        }
    }

    private fun pausePlayback() {
        currentUtteranceId = ""
        tts?.stop()
        flushProgress()
        releaseWakeLock()
        TtsStateHolder.updateState { it.copy(isPlaying = false) }
        updateMediaSessionAndNotification()
    }

    private fun resumePlayback() {
        val session = TtsStateHolder.sessionContent ?: return
        if (session.sentences.isEmpty()) return
        FloatingOverlayController.markActiveSource(UnifiedOverlaySource.TTS)
        if (M4bPlayerStateHolder.state.value.isPlaying) {
            M4bPlaybackService.sendAction(this, M4bPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
        }
        requestAudioFocusAndWakeLock()
        TtsStateHolder.updateState { it.copy(isPlaying = true, isActive = true) }
        speakCurrentSentence()
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
                sleepTimerMinutes = minutes.coerceIn(1, 600)
                val totalMs = sleepTimerMinutes * 60_000L
                TtsStateHolder.updateState {
                    it.copy(sleepTimerMode = SleepTimerMode.MINUTES, sleepRemainingMs = totalMs)
                }
                startSleepCountdown(totalMs)
            }
        }
    }

    /**
     * Counts down a sleep timer. When it expires playback pauses but the timer stays
     * armed, so resuming starts a fresh countdown — the timer is a persistent limit
     * the user switches off explicitly, not a one-shot.
     */
    private fun startSleepCountdown(totalMs: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = serviceScope.launch {
            var remaining = totalMs
            while (remaining > 0L) {
                delay(1000L)
                remaining -= 1000L
                TtsStateHolder.updateState { it.copy(sleepRemainingMs = remaining.coerceAtLeast(0L)) }
            }
            pausePlayback()
            TtsStateHolder.updateState { it.copy(sleepRemainingMs = totalMs) }
        }
    }

    private fun stopPlaybackAndService() {
        sleepTimerJob?.cancel()
        currentUtteranceId = ""
        tts?.stop()
        releaseAudioFocus()
        releaseWakeLock()
        FloatingOverlayController.setVisible(this, false)
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

    /** Writes the exact current position, bypassing the throttle above. */
    private fun flushProgress() {
        val session = TtsStateHolder.sessionContent ?: return
        val st = TtsStateHolder.state.value
        lastProgressWriteAt = 0L
        persistBookProgress(
            bookId = session.bookId,
            chapterIdx = session.currentChapterIndex,
            sentenceIdx = st.sentenceIndex,
            totalChapters = session.chapters.size
        )
    }

    private fun persistBookProgress(bookId: Long, chapterIdx: Int, sentenceIdx: Int, totalChapters: Int) {
        if (bookId <= 0L) return
        val app = application as? LexiVoiceApplication ?: return
        // TTS finishes a sentence every few seconds. Writing progress on each one
        // makes the library's Room flows re-emit continuously, and the "Recent
        // Books" row re-sorts under the user while they look at it -- that is the
        // flicker on the library screen. Write at most every few seconds; pause
        // and stop flush the exact position through flushProgress().
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressWriteAt < PROGRESS_WRITE_INTERVAL_MS) return
        lastProgressWriteAt = now
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

    override fun onDestroy() {
        releaseAudioFocus()
        releaseWakeLock()
        mediaSession?.release()
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TtsPlaybackService"
        private const val NOTIFICATION_ID = 4101
        private const val PROGRESS_WRITE_INTERVAL_MS = 5_000L

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
        const val ACTION_TEST_SPEAK = "com.example.tts.TEST_SPEAK"
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
            val needsForeground = action == ACTION_PLAY_BOOK ||
                action == ACTION_PLAY_SCRATCHPAD ||
                action == ACTION_RESUME ||
                action == ACTION_TOGGLE_PLAY_PAUSE ||
                action == ACTION_NEXT_SENTENCE ||
                action == ACTION_PREV_SENTENCE ||
                action == ACTION_NEXT_CHAPTER ||
                action == ACTION_PREV_CHAPTER
            if (needsForeground) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                runCatching { context.startService(intent) }
            }
        }
    }
}
