package com.example.export

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.LexiVoiceApplication
import com.example.MainActivity
import com.example.R
import com.example.audiobook.BookSyncMap
import com.example.audiobook.SyncAlignmentSerializer
import com.example.audiobook.SyncMapChapterEntry
import com.example.audiobook.SyncMapSentenceEntry
import com.example.epub.EpubChapterSummary
import com.example.epub.EpubParser
import com.example.epub.ReaderSentence
import com.example.tts.PronunciationProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AudioExportService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var exportJob: Job? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val pendingSyntheses = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    @Volatile
    private var isCancelledFlag = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LexiVoice::AudioExportWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_EXPORT -> {
                val config = ExportStateHolder.pendingBatchConfig
                if (config == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                isCancelledFlag = false
                startForeground(
                    NOTIFICATION_ID,
                    buildDualProgressNotification(
                        chapterTitle = "Initializing TTS engine...",
                        chapterPercent = 0,
                        currentChapterNum = 0,
                        totalChapters = config.chapters.size,
                        batchPercent = 0
                    )
                )
                startExportPipeline(config)
            }
            ACTION_CANCEL_EXPORT -> {
                isCancelledFlag = true
                pendingSyntheses.values.forEach { it.complete(false) }
                pendingSyntheses.clear()
                exportJob?.cancel()
                ExportStateHolder.update {
                    it.copy(
                        isRunning = false,
                        isCancelled = true,
                        currentChapterTitle = "Export cancelled"
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startExportPipeline(config: ExportBatchConfig) {
        exportJob?.cancel()
        exportJob = serviceScope.launch {
            wakeLock?.let { if (!it.isHeld) it.acquire(4 * 60 * 60 * 1000L) }
            val workDir = File(cacheDir, "export_${System.currentTimeMillis()}").apply { mkdirs() }
            val m4bChapterGroups = mutableListOf<M4bChapterWavGroup>()
            var exportedCount = 0
            var completedCleanly = false

            try {
                val app = applicationContext as LexiVoiceApplication
                val bookTitle = config.m4aMetadata.title.ifBlank { config.sourceTitle }
                val bookAuthor = config.m4aMetadata.author.ifBlank { "Unknown Author" }
                val bookFolderName = sanitizeFileName(bookTitle)

                ExportStateHolder.update {
                    ExportProgressState(
                        isRunning = true,
                        totalChapters = config.chapters.size,
                        outputFolderName = "${config.outputFolderName}/$bookFolderName",
                        currentChapterTitle = "Initializing speech engine..."
                    )
                }

                val engineReady = initExportTts(config)
                if (!engineReady) {
                    throw IllegalStateException("Unable to initialize TextToSpeech engine for export")
                }

                val outputRoot = DocumentFile.fromTreeUri(applicationContext, Uri.parse(config.outputTreeUri))
                    ?: throw IllegalStateException("Cannot access selected export folder")

                val bookSubfolder = outputRoot.findFile(bookFolderName)?.takeIf { it.isDirectory }
                    ?: outputRoot.createDirectory(bookFolderName)
                    ?: outputRoot

                val (coverBytes, savedCoverPath) = resolveCoverArtBytes(config)
                var currentSyncMap: BookSyncMap? = readSyncMapFromSafFolder(bookSubfolder)

                val matchedBookId = if (config.bookFileKey.isNotBlank()) {
                    app.libraryRepository.getBookByFileKey(config.bookFileKey)?.id
                } else null
                val activeRules = app.pronunciationRepository.getEnabledRulesForPlayback(matchedBookId)

                val buildM4b = config.m4aMetadata.enabled
                val wantSentenceSync = config.bookFileKey.isNotBlank() && config.m4aMetadata.syncWithEpub
                val needSentenceWavs = buildM4b || wantSentenceSync

                val chapterSummaries = config.chapters.map {
                    EpubChapterSummary(index = it.index, title = it.originalTitle, href = it.href)
                }

                val chapterSentencesMap: Map<Int, List<ReaderSentence>> =
                    if (needSentenceWavs && config.bookFileKey.isNotBlank()) {
                        EpubParser.loadAllChapterSentencesForExport(
                            context = applicationContext,
                            fileKey = config.bookFileKey,
                            isDirectPath = config.isDirectPath,
                            chapters = chapterSummaries
                        )
                    } else {
                        emptyMap()
                    }

                val chapterTextMap: Map<Int, String> =
                    if (!needSentenceWavs && config.bookFileKey.isNotBlank()) {
                        EpubParser.loadAllChaptersForExport(
                            context = applicationContext,
                            fileKey = config.bookFileKey,
                            isDirectPath = config.isDirectPath,
                            chapters = chapterSummaries
                        )
                    } else {
                        emptyMap()
                    }

                val total = config.chapters.size
                config.chapters.forEachIndexed { batchIdx, chapterReq ->
                    if (isCancelledFlag) throw CancellationException("Cancelled by user")

                    val displayNum = batchIdx + 1
                    val fileOrderNum = (chapterReq.index + 1).coerceAtLeast(1)
                    val safeTitle = sanitizeFileName(chapterReq.exportTitle.ifBlank { chapterReq.originalTitle })
                    val prefix = String.format(Locale.US, "%03d", fileOrderNum)
                    val mp3FileName = "$prefix - $safeTitle.mp3"

                    updateDualNotification(
                        chapterTitle = chapterReq.exportTitle,
                        chapterPercent = 0,
                        currentChapterNum = displayNum,
                        totalChapters = total,
                        batchPercent = ((batchIdx.toFloat() / total.toFloat()) * (if (buildM4b) 90f else 100f)).toInt()
                    )

                    if (needSentenceWavs) {
                        val sentences: List<ReaderSentence> = if (chapterReq.plainTextOverride != null) {
                            EpubParser.parsePlainTextToChapter(chapterReq.exportTitle, chapterReq.plainTextOverride).sentences
                        } else {
                            chapterSentencesMap[chapterReq.index].orEmpty()
                        }.ifEmpty {
                            listOf(
                                ReaderSentence(
                                    globalIndex = 0,
                                    paragraphIndex = 0,
                                    startInParagraph = 0,
                                    endInParagraph = chapterReq.exportTitle.length,
                                    text = chapterReq.exportTitle
                                )
                            )
                        }

                        val sentenceWavs = mutableListOf<M4bSentenceWavInput>()
                        val allChapterWavFiles = mutableListOf<File>()
                        val chapterSentenceEntries = mutableListOf<SyncMapSentenceEntry>()
                        var chapterCursorMs = 0L

                        sentences.forEachIndexed { sPos, sentence ->
                            if (isCancelledFlag) throw CancellationException("Cancelled by user")
                            val subChunks = splitTextIntoSafeTtsChunks(sentence.text, maxChunkChars = 1400)
                            val chunkWavs = mutableListOf<File>()
                            val sentStartMs = chapterCursorMs

                            subChunks.forEachIndexed { subIdx, subText ->
                                val cleanRaw = subText.trim()
                                val cleanSpeech = PronunciationProcessor.applyRules(
                                    rawText = cleanRaw,
                                    rules = activeRules,
                                    bookId = matchedBookId
                                ).trim()
                                if (cleanSpeech.isNotBlank()) {
                                    val wavFile = File(workDir, "c_${batchIdx}_s_${sPos}_$subIdx.wav")
                                    val ok = synthesizeChunkToWav(cleanSpeech, wavFile)
                                    if (ok && wavFile.exists() && wavFile.length() > 44L) {
                                        chunkWavs.add(wavFile)
                                        allChapterWavFiles.add(wavFile)
                                        chapterCursorMs += Mp3AudioEncoder.computeWavDurationMs(wavFile)
                                    } else {
                                        wavFile.delete()
                                    }
                                }
                            }

                            val sentEndMs = chapterCursorMs.coerceAtLeast(sentStartMs + 50L)
                            chapterSentenceEntries.add(
                                SyncMapSentenceEntry(
                                    sentenceIndex = sentence.globalIndex,
                                    text = sentence.text,
                                    offsetMs = sentStartMs,
                                    endOffsetMs = sentEndMs
                                )
                            )

                            sentenceWavs.add(
                                M4bSentenceWavInput(
                                    sentenceIndex = sentence.globalIndex,
                                    text = sentence.text,
                                    wavFiles = chunkWavs
                                )
                            )
                            if (sPos % 3 == 0 || sPos == sentences.lastIndex) {
                                val chPct = (((sPos + 1).toFloat() / sentences.size.toFloat()) * 90f).toInt()
                                val overallPct = (((batchIdx + (chPct / 100f)) / total.toFloat()) * (if (buildM4b) 88f else 95f)).toInt()
                                updateDualNotification(
                                    chapterTitle = chapterReq.exportTitle,
                                    chapterPercent = chPct,
                                    currentChapterNum = displayNum,
                                    totalChapters = total,
                                    batchPercent = overallPct,
                                    exportedCount = exportedCount
                                )
                            }
                        }

                        val shouldWriteMp3 = (!buildM4b) || config.m4aMetadata.keepMp3Files
                        var savedMp3Uri: Uri? = null
                        if (shouldWriteMp3 && allChapterWavFiles.isNotEmpty()) {
                            val tempMp3 = File(workDir, mp3FileName)
                            val mp3Ok = Mp3AudioEncoder.mergeWavChunksToMp3(
                                wavFiles = allChapterWavFiles,
                                outputMp3 = tempMp3,
                                title = chapterReq.exportTitle,
                                artist = bookAuthor,
                                album = config.m4aMetadata.album.ifBlank { bookTitle },
                                trackNumber = fileOrderNum,
                                bitrateKbps = 96
                            )
                            if (mp3Ok) {
                                savedMp3Uri = writeFileToSafFolder(bookSubfolder, tempMp3, "audio/mpeg", mp3FileName)
                                exportedCount++
                            }
                            tempMp3.delete()
                        }

                        if (!buildM4b) {
                            // Free WAV intermediates immediately when not building a direct M4B
                            allChapterWavFiles.forEach { it.delete() }
                        } else {
                            m4bChapterGroups.add(
                                M4bChapterWavGroup(
                                    chapterIndex = chapterReq.index,
                                    chapterTitle = chapterReq.exportTitle,
                                    sentences = sentenceWavs
                                )
                            )
                        }

                        if (savedMp3Uri != null || buildM4b) {
                            val chapterEntry = SyncMapChapterEntry(
                                chapterIndex = chapterReq.index,
                                title = chapterReq.exportTitle,
                                fileName = mp3FileName,
                                fileUri = savedMp3Uri?.toString().orEmpty(),
                                durationMs = chapterCursorMs.coerceAtLeast(100L),
                                isDirectPath = false,
                                sentences = if (wantSentenceSync) chapterSentenceEntries else emptyList()
                            )
                            currentSyncMap = SyncAlignmentSerializer.mergeChapterIntoSyncMap(
                                existing = currentSyncMap,
                                newChapter = chapterEntry,
                                bookTitle = bookTitle,
                                author = bookAuthor,
                                narrator = config.m4aMetadata.narrator.ifBlank { "LexiVoice TTS" },
                                year = config.m4aMetadata.year,
                                genre = config.m4aMetadata.genre,
                                coverPath = savedCoverPath ?: config.coverPath,
                                sourceEpubKey = config.bookFileKey.takeIf { it.isNotBlank() && wantSentenceSync },
                                sourceEpubIsDirect = config.isDirectPath
                            )
                            writeSyncMapToSafFolder(bookSubfolder, currentSyncMap!!)
                            if (savedMp3Uri != null) {
                                app.audiobookRepository.registerExportedMp3Folder(
                                    folderUri = bookSubfolder.uri.toString(),
                                    isDirectPath = false,
                                    syncMap = currentSyncMap!!
                                )
                            }
                        }
                    } else {
                        val rawText = chapterReq.plainTextOverride
                            ?: chapterTextMap[chapterReq.index]
                            ?: ""
                        val chunks = splitTextIntoSafeTtsChunks(
                            text = if (rawText.isBlank()) chapterReq.exportTitle else rawText,
                            maxChunkChars = 1500
                        )
                        val wavChunkFiles = mutableListOf<File>()
                        var chapterDurationMs = 0L
                        chunks.forEachIndexed { chunkIdx, chunkText ->
                            if (isCancelledFlag) throw CancellationException("Cancelled by user")
                            val spokenChunk = PronunciationProcessor.applyRules(
                                rawText = chunkText,
                                rules = activeRules,
                                bookId = matchedBookId
                            ).ifBlank { chunkText }
                            val wavFile = File(workDir, "c_${batchIdx}_k_${chunkIdx}.wav")
                            val ok = synthesizeChunkToWav(spokenChunk, wavFile)
                            if (ok && wavFile.exists() && wavFile.length() > 44L) {
                                wavChunkFiles.add(wavFile)
                                chapterDurationMs += Mp3AudioEncoder.computeWavDurationMs(wavFile)
                            }
                            val chPct = (((chunkIdx + 1).toFloat() / chunks.size.toFloat()) * 85f).toInt()
                            val overallPct = (((batchIdx + (chPct / 100f)) / total.toFloat()) * 100f).toInt()
                            updateDualNotification(
                                chapterTitle = chapterReq.exportTitle,
                                chapterPercent = chPct,
                                currentChapterNum = displayNum,
                                totalChapters = total,
                                batchPercent = overallPct,
                                exportedCount = exportedCount
                            )
                        }
                        val tempMp3 = File(workDir, mp3FileName)
                        val mp3Ok = Mp3AudioEncoder.mergeWavChunksToMp3(
                            wavFiles = wavChunkFiles,
                            outputMp3 = tempMp3,
                            title = chapterReq.exportTitle,
                            artist = bookAuthor,
                            album = config.m4aMetadata.album.ifBlank { bookTitle },
                            trackNumber = fileOrderNum,
                            bitrateKbps = 96
                        )
                        wavChunkFiles.forEach { it.delete() }
                        if (mp3Ok) {
                            val savedMp3Uri = writeFileToSafFolder(bookSubfolder, tempMp3, "audio/mpeg", mp3FileName)
                            exportedCount++
                            if (savedMp3Uri != null) {
                                val chapterEntry = SyncMapChapterEntry(
                                    chapterIndex = chapterReq.index,
                                    title = chapterReq.exportTitle,
                                    fileName = mp3FileName,
                                    fileUri = savedMp3Uri.toString(),
                                    durationMs = chapterDurationMs.coerceAtLeast(1000L),
                                    isDirectPath = false,
                                    sentences = emptyList()
                                )
                                currentSyncMap = SyncAlignmentSerializer.mergeChapterIntoSyncMap(
                                    existing = currentSyncMap,
                                    newChapter = chapterEntry,
                                    bookTitle = bookTitle,
                                    author = bookAuthor,
                                    narrator = config.m4aMetadata.narrator.ifBlank { "LexiVoice TTS" },
                                    year = config.m4aMetadata.year,
                                    genre = config.m4aMetadata.genre,
                                    coverPath = savedCoverPath ?: config.coverPath,
                                    sourceEpubKey = config.bookFileKey.takeIf { it.isNotBlank() },
                                    sourceEpubIsDirect = config.isDirectPath
                                )
                                writeSyncMapToSafFolder(bookSubfolder, currentSyncMap!!)
                                app.audiobookRepository.registerExportedMp3Folder(
                                    folderUri = bookSubfolder.uri.toString(),
                                    isDirectPath = false,
                                    syncMap = currentSyncMap!!
                                )
                            }
                        }
                        tempMp3.delete()
                    }

                    val overallDonePct = (((batchIdx + 1).toFloat() / total.toFloat()) * (if (buildM4b) 88f else 100f)).toInt()
                    updateDualNotification(
                        chapterTitle = chapterReq.exportTitle,
                        chapterPercent = 100,
                        currentChapterNum = displayNum,
                        totalChapters = total,
                        batchPercent = overallDonePct,
                        exportedCount = exportedCount
                    )
                }

                var m4bOutputName: String? = null
                if (buildM4b && m4bChapterGroups.isNotEmpty() && !isCancelledFlag) {
                    val m4bTitle = sanitizeFileName(bookTitle)
                    val m4bFileName = "$m4bTitle.m4b"
                    val tempM4b = File(workDir, m4bFileName)

                    updateDualNotification(
                        chapterTitle = "Encoding M4B directly to AAC...",
                        chapterPercent = 5,
                        currentChapterNum = total,
                        totalChapters = total,
                        batchPercent = 90,
                        exportedCount = exportedCount
                    )

                    val buildResult = M4aAudiobookBuilder.buildChapteredM4bFromWavs(
                        chapters = m4bChapterGroups,
                        outputM4bFile = tempM4b,
                        metadata = config.m4aMetadata,
                        coverBytes = coverBytes,
                        onProgress = { pct ->
                            updateDualNotification(
                                chapterTitle = "Injecting M4B chapters & metadata...",
                                chapterPercent = pct,
                                currentChapterNum = total,
                                totalChapters = total,
                                batchPercent = 90 + (pct * 10 / 100),
                                exportedCount = exportedCount
                            )
                        }
                    )

                    if (buildResult.success) {
                        val destUri = writeFileToSafFolder(bookSubfolder, tempM4b, "audio/mp4", m4bFileName)
                        if (destUri != null) {
                            val segments = if (wantSentenceSync) buildResult.syncSegments else emptyList()
                            app.audiobookRepository.registerExportedM4b(
                                fileUri = destUri.toString(),
                                title = bookTitle,
                                author = bookAuthor,
                                narrator = config.m4aMetadata.narrator.ifBlank { "LexiVoice TTS" },
                                year = config.m4aMetadata.year,
                                genre = config.m4aMetadata.genre,
                                coverPath = savedCoverPath ?: config.coverPath,
                                durationMs = buildResult.totalDurationMs,
                                chapters = buildResult.chapterMetas,
                                sourceEpubKey = config.bookFileKey.takeIf { it.isNotBlank() && wantSentenceSync },
                                sourceEpubIsDirect = config.isDirectPath,
                                syncSegments = segments,
                                folderUri = bookSubfolder.uri.toString(),
                                isDirectPath = false
                            )
                        }
                        m4bOutputName = m4bFileName
                        exportedCount++
                    }
                }

                ExportStateHolder.update {
                    it.copy(
                        isRunning = false,
                        isCompleted = true,
                        isCancelled = false,
                        currentChapterTitle = "Export complete",
                        currentChapterProgressPercent = 100,
                        batchProgressPercent = 100,
                        exportedFilesCount = exportedCount,
                        m4aCreatedName = m4bOutputName
                    )
                }

                showCompletionNotification(
                    title = "Export Complete",
                    text = "Saved $exportedCount file(s) to ${config.outputFolderName}/$bookFolderName"
                )
                completedCleanly = true
            } catch (_: CancellationException) {
                ExportStateHolder.update {
                    it.copy(isRunning = false, isCancelled = true, currentChapterTitle = "Export cancelled")
                }
            } catch (e: Exception) {
                ExportStateHolder.update {
                    it.copy(
                        isRunning = false,
                        isCompleted = false,
                        errorMessage = e.message ?: "Export failed"
                    )
                }
                showCompletionNotification("Export Failed", e.message ?: "Unexpected synthesis error")
            } finally {
                workDir.deleteRecursively()
                tts?.stop()
                tts?.shutdown()
                tts = null
                wakeLock?.let { if (it.isHeld) it.release() }
                stopForeground(if (completedCleanly) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun readSyncMapFromSafFolder(folderDoc: DocumentFile): BookSyncMap? {
        val syncDoc = folderDoc.findFile("sync_map.json") ?: return null
        return runCatching {
            contentResolver.openInputStream(syncDoc.uri)?.bufferedReader()?.use { reader ->
                SyncAlignmentSerializer.decodeBookSyncMap(reader.readText())
            }
        }.getOrNull()
    }

    private fun writeSyncMapToSafFolder(folderDoc: DocumentFile, syncMap: BookSyncMap) {
        val json = SyncAlignmentSerializer.encodeBookSyncMap(syncMap)
        runCatching {
            folderDoc.findFile("sync_map.json")?.delete()
            val destDoc = folderDoc.createFile("application/json", "sync_map.json") ?: return
            contentResolver.openOutputStream(destDoc.uri)?.use { out ->
                out.write(json.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    private fun resolveCoverArtBytes(config: ExportBatchConfig): Pair<ByteArray?, String?> {
        if (!config.m4aMetadata.includeCover) return null to config.coverPath

        val overrideUri = config.m4aMetadata.coverOverrideUri
        if (!overrideUri.isNullOrBlank()) {
            val bytes = runCatching {
                contentResolver.openInputStream(Uri.parse(overrideUri))?.use { input ->
                    normalizeToJpeg(input.readBytes())
                }
            }.getOrNull()
            if (bytes != null) {
                val coversDir = File(filesDir, "covers").apply { mkdirs() }
                val savedFile = File(coversDir, "custom_cover_${System.currentTimeMillis()}.jpg")
                FileOutputStream(savedFile).use { it.write(bytes) }
                return bytes to savedFile.absolutePath
            }
        }

        val epubCover = config.coverPath?.let { path ->
            runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()
        }
        return normalizeToJpeg(epubCover) to config.coverPath
    }

    private fun normalizeToJpeg(raw: ByteArray?): ByteArray? {
        if (raw == null || raw.isEmpty()) return null
        return runCatching {
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
            val maxDim = 800
            val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1))
            val scaled = if (scale < 0.99f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            out.toByteArray()
        }.getOrDefault(raw)
    }

    private suspend fun initExportTts(config: ExportBatchConfig): Boolean = withContext(Dispatchers.Main) {
        val readyDeferred = CompletableDeferred<Boolean>()
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
                    engine.setSpeechRate(config.speechRate)
                    engine.setPitch(config.pitch)
                    if (config.voiceName.isNotBlank()) {
                        runCatching {
                            engine.voices?.firstOrNull { it.name == config.voiceName }?.let {
                                engine.voice = it
                            }
                        }
                    } else if (config.localeTag.isNotBlank()) {
                        engine.language = Locale.forLanguageTag(config.localeTag)
                    }
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { pendingSyntheses.remove(it)?.complete(true) }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { pendingSyntheses.remove(it)?.complete(false) }
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            utteranceId?.let { pendingSyntheses.remove(it)?.complete(false) }
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            utteranceId?.let { pendingSyntheses.remove(it)?.complete(false) }
                        }
                    })
                    readyDeferred.complete(true)
                } else {
                    readyDeferred.complete(false)
                }
            } else {
                readyDeferred.complete(false)
            }
        }
        tts = if (config.enginePackage.isNotBlank()) {
            TextToSpeech(applicationContext, listener, config.enginePackage)
        } else {
            TextToSpeech(applicationContext, listener)
        }
        withTimeoutOrNull(15_000L) {
            readyDeferred.await()
        } ?: false
    }

    private suspend fun synthesizeChunkToWav(text: String, outFile: File): Boolean {
        val engine = tts ?: return false
        val uttId = "exp_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<Boolean>()
        pendingSyntheses[uttId] = deferred
        val params = Bundle()
        val res = engine.synthesizeToFile(text, params, outFile, uttId)
        if (res != TextToSpeech.SUCCESS) {
            pendingSyntheses.remove(uttId)
            return false
        }
        return try {
            withTimeoutOrNull(60_000L) {
                deferred.await()
            } ?: false
        } finally {
            pendingSyntheses.remove(uttId)
        }
    }

    private fun splitTextIntoSafeTtsChunks(text: String, maxChunkChars: Int): List<String> {
        val clean = text.trim()
        if (clean.length <= maxChunkChars) return listOf(clean)

        val chunks = mutableListOf<String>()
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(clean)
        val current = StringBuilder()

        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = clean.substring(start, end)
            if (current.length + sentence.length > maxChunkChars && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.setLength(0)
            }
            if (sentence.length > maxChunkChars) {
                var pos = 0
                while (pos < sentence.length) {
                    val sliceEnd = (pos + maxChunkChars).coerceAtMost(sentence.length)
                    val splitAt = if (sliceEnd < sentence.length) {
                        sentence.lastIndexOf(' ', sliceEnd).takeIf { it > pos } ?: sliceEnd
                    } else sliceEnd
                    chunks.add(sentence.substring(pos, splitAt).trim())
                    pos = splitAt
                }
            } else {
                current.append(sentence)
            }
            start = end
            end = iterator.next()
        }
        if (current.isNotBlank()) {
            chunks.add(current.toString().trim())
        }
        return chunks.ifEmpty { listOf(clean) }
    }

    private fun writeFileToSafFolder(
        folderDoc: DocumentFile,
        sourceFile: File,
        mimeType: String,
        displayName: String
    ): Uri? {
        folderDoc.findFile(displayName)?.delete()
        val destDoc = folderDoc.createFile(mimeType, displayName) ?: return null
        contentResolver.openOutputStream(destDoc.uri)?.use { out ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(out, bufferSize = 32 * 1024)
            }
        }
        return destDoc.uri
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "Chapter" }
    }

    private fun updateDualNotification(
        chapterTitle: String,
        chapterPercent: Int,
        currentChapterNum: Int,
        totalChapters: Int,
        batchPercent: Int,
        exportedCount: Int = 0
    ) {
        ExportStateHolder.update {
            it.copy(
                currentChapterTitle = chapterTitle,
                currentChapterProgressPercent = chapterPercent,
                currentChapterNumber = currentChapterNum,
                totalChapters = totalChapters,
                batchProgressPercent = batchPercent,
                exportedFilesCount = exportedCount
            )
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            buildDualProgressNotification(
                chapterTitle = chapterTitle,
                chapterPercent = chapterPercent,
                currentChapterNum = currentChapterNum,
                totalChapters = totalChapters,
                batchPercent = batchPercent
            )
        )
    }

    private fun buildDualProgressNotification(
        chapterTitle: String,
        chapterPercent: Int,
        currentChapterNum: Int,
        totalChapters: Int,
        batchPercent: Int
    ): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            99,
            Intent(this, AudioExportService::class.java).setAction(ACTION_CANCEL_EXPORT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val remoteViews = RemoteViews(packageName, R.layout.notification_export_progress).apply {
            setTextViewText(R.id.tv_current_chapter, chapterTitle)
            setTextViewText(R.id.tv_chapter_percent, "$chapterPercent%")
            setProgressBar(R.id.pb_chapter_progress, 100, chapterPercent.coerceIn(0, 100), false)
            setTextViewText(R.id.tv_batch_status, "Chapter $currentChapterNum of $totalChapters")
            setProgressBar(R.id.pb_batch_progress, 100, batchPercent.coerceIn(0, 100), false)
            setOnClickPendingIntent(R.id.btn_cancel_export, cancelIntent)
        }

        return NotificationCompat.Builder(this, LexiVoiceApplication.CHANNEL_AUDIO_EXPORT)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Exporting Audio: $chapterTitle")
            .setContentText("Chapter $currentChapterNum of $totalChapters ($chapterPercent%)")
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun showCompletionNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notif = NotificationCompat.Builder(this, LexiVoiceApplication.CHANNEL_AUDIO_EXPORT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_COMPLETE_ID, notif)
    }

    override fun onDestroy() {
        exportJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val NOTIFICATION_COMPLETE_ID = 4202

        const val ACTION_START_EXPORT = "com.example.export.START"
        const val ACTION_CANCEL_EXPORT = "com.example.export.CANCEL"

        fun startExport(context: Context, config: ExportBatchConfig) {
            ExportStateHolder.pendingBatchConfig = config
            val intent = Intent(context, AudioExportService::class.java).setAction(ACTION_START_EXPORT)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelExport(context: Context) {
            val intent = Intent(context, AudioExportService::class.java).setAction(ACTION_CANCEL_EXPORT)
            context.startService(intent)
        }
    }
}
