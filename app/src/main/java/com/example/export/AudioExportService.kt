package com.example.export

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.example.epub.EpubParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
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
            val generatedMp3Inputs = mutableListOf<ChapterInputFile>()
            var exportedCount = 0

            try {
                ExportStateHolder.update {
                    ExportProgressState(
                        isRunning = true,
                        totalChapters = config.chapters.size,
                        outputFolderName = config.outputFolderName,
                        currentChapterTitle = "Initializing speech engine..."
                    )
                }

                val engineReady = initExportTts(config)
                if (!engineReady) {
                    throw IllegalStateException("Unable to initialize TextToSpeech engine for export")
                }

                val outputTree = DocumentFile.fromTreeUri(applicationContext, Uri.parse(config.outputTreeUri))
                    ?: throw IllegalStateException("Cannot access selected export folder")

                val chapterTextMap = if (config.bookFileKey.isNotBlank()) {
                    EpubParser.loadAllChaptersForExport(
                        context = applicationContext,
                        fileKey = config.bookFileKey,
                        isDirectPath = config.isDirectPath,
                        chapters = config.chapters.map {
                            com.example.epub.EpubChapterSummary(
                                index = it.index,
                                title = it.originalTitle,
                                href = it.href
                            )
                        }
                    )
                } else {
                    emptyMap()
                }

                val total = config.chapters.size
                config.chapters.forEachIndexed { batchIdx, chapterReq ->
                    if (isCancelledFlag) throw CancellationException("Cancelled by user")

                    val chapterNum = batchIdx + 1
                    val safeTitle = sanitizeFileName(chapterReq.exportTitle.ifBlank { chapterReq.originalTitle })
                    val prefix = String.format(Locale.US, "%03d", chapterNum)
                    val mp3FileName = "$prefix - $safeTitle.mp3"

                    updateDualNotification(
                        chapterTitle = chapterReq.exportTitle,
                        chapterPercent = 0,
                        currentChapterNum = chapterNum,
                        totalChapters = total,
                        batchPercent = ((batchIdx.toFloat() / total.toFloat()) * 100f).toInt()
                    )

                    val rawText = chapterReq.plainTextOverride
                        ?: chapterTextMap[chapterReq.index]
                        ?: ""

                    val chunks = splitTextIntoSafeTtsChunks(
                        text = if (rawText.isBlank()) chapterReq.exportTitle else rawText,
                        maxChunkChars = 1500
                    )

                    val wavChunkFiles = mutableListOf<File>()
                    chunks.forEachIndexed { chunkIdx, chunkText ->
                        if (isCancelledFlag) throw CancellationException("Cancelled by user")
                        val wavFile = File(workDir, "c_${batchIdx}_k_${chunkIdx}.wav")
                        val ok = synthesizeChunkToWav(chunkText, wavFile)
                        if (ok && wavFile.exists() && wavFile.length() > 44L) {
                            wavChunkFiles.add(wavFile)
                        }
                        val chPct = (((chunkIdx + 1).toFloat() / chunks.size.toFloat()) * 85f).toInt()
                        val overallPct = (((batchIdx + (chPct / 100f)) / total.toFloat()) * 100f).toInt()
                        updateDualNotification(
                            chapterTitle = chapterReq.exportTitle,
                            chapterPercent = chPct,
                            currentChapterNum = chapterNum,
                            totalChapters = total,
                            batchPercent = overallPct
                        )
                    }

                    val tempMp3 = File(workDir, mp3FileName)
                    val mp3Ok = Mp3AudioEncoder.mergeWavChunksToMp3(
                        wavFiles = wavChunkFiles,
                        outputMp3 = tempMp3,
                        title = chapterReq.exportTitle,
                        artist = config.m4aMetadata.author.ifBlank { "LexiVoice" },
                        album = config.m4aMetadata.album.ifBlank { config.sourceTitle },
                        trackNumber = chapterNum,
                        bitrateKbps = 96
                    )
                    wavChunkFiles.forEach { it.delete() }

                    if (mp3Ok) {
                        if (!config.m4aMetadata.enabled || config.m4aMetadata.keepMp3Files) {
                            writeFileToSafFolder(outputTree, tempMp3, "audio/mpeg", mp3FileName)
                            exportedCount++
                        }
                        if (config.m4aMetadata.enabled) {
                            generatedMp3Inputs.add(
                                ChapterInputFile(
                                    chapterTitle = chapterReq.exportTitle,
                                    mp3File = tempMp3
                                )
                            )
                        } else {
                            tempMp3.delete()
                        }
                    }

                    val overallDonePct = (((batchIdx + 1).toFloat() / total.toFloat()) * 100f).toInt()
                    updateDualNotification(
                        chapterTitle = chapterReq.exportTitle,
                        chapterPercent = 100,
                        currentChapterNum = chapterNum,
                        totalChapters = total,
                        batchPercent = overallDonePct,
                        exportedCount = exportedCount
                    )
                }

                var m4aOutputName: String? = null
                if (config.m4aMetadata.enabled && generatedMp3Inputs.isNotEmpty() && !isCancelledFlag) {
                    val m4aTitle = sanitizeFileName(
                        config.m4aMetadata.title.ifBlank { config.sourceTitle }
                    )
                    val m4aFileName = "$m4aTitle.m4a"
                    val tempM4a = File(workDir, m4aFileName)
                    val coverBytes = config.coverPath?.let { path ->
                        runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()
                    }

                    updateDualNotification(
                        chapterTitle = "Combining M4A chapters & metadata...",
                        chapterPercent = 5,
                        currentChapterNum = total,
                        totalChapters = total,
                        batchPercent = 96,
                        exportedCount = exportedCount
                    )

                    val m4aOk = M4aAudiobookBuilder.buildChapteredM4a(
                        chapters = generatedMp3Inputs,
                        outputM4aFile = tempM4a,
                        metadata = config.m4aMetadata,
                        coverBytes = coverBytes,
                        onProgress = { pct ->
                            updateDualNotification(
                                chapterTitle = "Combining M4A chapters & metadata...",
                                chapterPercent = pct,
                                currentChapterNum = total,
                                totalChapters = total,
                                batchPercent = 96 + (pct * 4 / 100),
                                exportedCount = exportedCount
                            )
                        }
                    )
                    if (m4aOk) {
                        writeFileToSafFolder(outputTree, tempM4a, "audio/mp4", m4aFileName)
                        m4aOutputName = m4aFileName
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
                        m4aCreatedName = m4aOutputName
                    )
                }

                showCompletionNotification(
                    title = "Export Complete",
                    text = "Saved $exportedCount audio file(s) to ${config.outputFolderName}"
                )
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
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
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
        readyDeferred.await()
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
        return deferred.await()
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
                // Hard split overly long run-on sentences cleanly on spaces
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
    ) {
        folderDoc.findFile(displayName)?.delete()
        val destDoc = folderDoc.createFile(mimeType, displayName) ?: return
        contentResolver.openOutputStream(destDoc.uri)?.use { out ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(out, bufferSize = 32 * 1024)
            }
        }
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
