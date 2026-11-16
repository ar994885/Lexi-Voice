package com.example.export

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.LexiVoiceApplication
import com.example.MainActivity
import com.example.audiobook.BookSyncMap
import com.example.audiobook.Mp3ChapterFileMeta
import com.example.audiobook.SyncAlignmentSerializer
import com.example.data.db.AudiobookEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class M4bCompileChapterItem(
    val chapterIndex: Int,
    val title: String,
    val fileName: String,
    val uriString: String,
    val isDirectPath: Boolean,
    val durationMs: Long,
    val isSelected: Boolean = true
)

data class M4bCompileRequest(
    val sourceAudiobook: AudiobookEntity? = null,
    val targetFolderUri: String,
    val isDirectFolder: Boolean,
    val chapters: List<M4bCompileChapterItem>,
    val metadata: M4aMetadataConfig,
    val coverPath: String?,
    val deleteSourceMp3s: Boolean
)

data class M4bCompileProgressState(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null,
    val progressPercent: Int = 0,
    val statusText: String = "",
    val createdAudiobookId: Long = -1L,
    val createdFileName: String = ""
)

object M4bCompileStateHolder {
    private val _state = MutableStateFlow(M4bCompileProgressState())
    val state: StateFlow<M4bCompileProgressState> = _state.asStateFlow()

    @Volatile
    var pendingRequest: M4bCompileRequest? = null

    fun update(transform: (M4bCompileProgressState) -> M4bCompileProgressState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = M4bCompileProgressState()
    }
}

class M4bCompileService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var compileJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LexiVoice::M4bCompileWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_COMPILE -> {
                val request = M4bCompileStateHolder.pendingRequest
                if (request == null || request.chapters.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Preparing MP3 chapters for M4B...", 0)
                )
                runCompilation(request)
            }
            ACTION_CANCEL_COMPILE -> {
                compileJob?.cancel()
                M4bCompileStateHolder.update {
                    it.copy(isRunning = false, errorMessage = "Compilation cancelled")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runCompilation(request: M4bCompileRequest) {
        compileJob?.cancel()
        compileJob = serviceScope.launch {
            wakeLock?.let { if (!it.isHeld) it.acquire(4 * 60 * 60 * 1000L) }
            val workDir = File(cacheDir, "compile_m4b_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val app = applicationContext as LexiVoiceApplication
                val title = request.metadata.title.ifBlank {
                    request.sourceAudiobook?.title ?: "Audiobook"
                }
                val author = request.metadata.author.ifBlank {
                    request.sourceAudiobook?.author ?: "Unknown Author"
                }
                val safeName = sanitizeFileName(title)
                val outFileName = "$safeName.m4b"
                val tempM4b = File(workDir, outFileName)

                M4bCompileStateHolder.update {
                    M4bCompileProgressState(
                        isRunning = true,
                        progressPercent = 2,
                        statusText = "Loading cover & metadata..."
                    )
                }

                val (coverBytes, savedCoverPath) = resolveCoverBytes(request)
                val inputs = request.chapters.map {
                    Mp3UriChapterInput(
                        chapterIndex = it.chapterIndex,
                        chapterTitle = it.title,
                        uriString = it.uriString,
                        isDirectPath = it.isDirectPath
                    )
                }

                val result = M4aAudiobookBuilder.buildChapteredM4bFromMp3Inputs(
                    context = applicationContext,
                    chapters = inputs,
                    outputM4bFile = tempM4b,
                    metadata = request.metadata.copy(title = title, author = author),
                    coverBytes = coverBytes,
                    onProgress = { pct ->
                        val msg = if (pct < 88) {
                            "Encoding AAC & merging ${inputs.size} chapters ($pct%)..."
                        } else {
                            "Injecting chapter markers & cover art ($pct%)..."
                        }
                        M4bCompileStateHolder.update {
                            it.copy(progressPercent = pct, statusText = msg)
                        }
                        updateNotification(msg, pct)
                    }
                )

                if (!result.success) {
                    throw IllegalStateException("Failed to mux MP3 chapters into M4B")
                }

                // Resolve existing sync_map.json if present so the newly compiled M4B keeps EPUB read-along sync!
                val existingSyncMap: BookSyncMap? = loadExistingBookSyncMap(request)
                val syncSegments = if (existingSyncMap != null && !existingSyncMap.sourceEpubKey.isNullOrBlank()) {
                    SyncAlignmentSerializer.remapSyncSegmentsForM4b(
                        compiledChapters = result.compiledOffsets,
                        syncMap = existingSyncMap
                    )
                } else {
                    emptyList()
                }

                val destUriOrPath = if (request.isDirectFolder) {
                    val destDir = File(request.targetFolderUri).apply { mkdirs() }
                    val destFile = File(destDir, outFileName)
                    tempM4b.copyTo(destFile, overwrite = true)
                    destFile.absolutePath
                } else {
                    val destUri = writeToSafDirectoryUri(
                        dirUri = Uri.parse(request.targetFolderUri),
                        sourceFile = tempM4b,
                        mimeType = "audio/mp4",
                        displayName = outFileName
                    ) ?: throw IllegalStateException("Unable to write $outFileName to destination folder")
                    destUri.toString()
                }

                val newAudiobookId = app.audiobookRepository.registerExportedM4b(
                    fileUri = destUriOrPath,
                    title = title,
                    author = author,
                    narrator = request.metadata.narrator.ifBlank { "LexiVoice" },
                    year = request.metadata.year,
                    genre = request.metadata.genre,
                    coverPath = savedCoverPath ?: request.coverPath,
                    durationMs = result.totalDurationMs,
                    chapters = result.chapterMetas,
                    sourceEpubKey = existingSyncMap?.sourceEpubKey ?: request.sourceAudiobook?.sourceEpubKey,
                    sourceEpubIsDirect = existingSyncMap?.sourceEpubIsDirect
                        ?: (request.sourceAudiobook?.sourceEpubIsDirect ?: false),
                    syncSegments = syncSegments,
                    folderUri = request.targetFolderUri,
                    isDirectPath = request.isDirectFolder
                )

                if (request.deleteSourceMp3s && request.sourceAudiobook != null) {
                    val toDelete = request.chapters.map {
                        Mp3ChapterFileMeta(
                            chapterIndex = it.chapterIndex,
                            title = it.title,
                            fileName = it.fileName,
                            fileUri = it.uriString,
                            startMs = 0L,
                            endMs = it.durationMs,
                            durationMs = it.durationMs,
                            isDirectPath = it.isDirectPath
                        )
                    }
                    app.audiobookRepository.deleteSourceMp3Chapters(request.sourceAudiobook, toDelete)
                } else if (request.deleteSourceMp3s) {
                    for (ch in request.chapters) {
                        runCatching {
                            if (ch.isDirectPath) File(ch.uriString).delete()
                            else DocumentFile.fromSingleUri(applicationContext, Uri.parse(ch.uriString))?.delete()
                        }
                    }
                }

                M4bCompileStateHolder.update {
                    M4bCompileProgressState(
                        isRunning = false,
                        isCompleted = true,
                        progressPercent = 100,
                        statusText = "Created $outFileName",
                        createdAudiobookId = newAudiobookId,
                        createdFileName = outFileName
                    )
                }
                showDoneNotification("M4B Created", "Compiled ${inputs.size} chapters into $outFileName")
            } catch (e: Exception) {
                M4bCompileStateHolder.update {
                    it.copy(
                        isRunning = false,
                        isCompleted = false,
                        errorMessage = e.message ?: "M4B compilation failed"
                    )
                }
            } finally {
                workDir.deleteRecursively()
                wakeLock?.let { if (it.isHeld) it.release() }
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun loadExistingBookSyncMap(request: M4bCompileRequest): BookSyncMap? {
        if (request.isDirectFolder) {
            val f = File(request.targetFolderUri, "sync_map.json")
            if (f.exists()) {
                return runCatching { SyncAlignmentSerializer.decodeBookSyncMap(f.readText()) }.getOrNull()
            }
        } else {
            val dirUri = Uri.parse(request.targetFolderUri)
            val syncUri = findChildSafFileUri(dirUri, "sync_map.json")
            if (syncUri != null) {
                return runCatching {
                    contentResolver.openInputStream(syncUri)?.bufferedReader()?.use {
                        SyncAlignmentSerializer.decodeBookSyncMap(it.readText())
                    }
                }.getOrNull()
            }
        }
        return null
    }

    private fun findChildSafFileUri(dirUri: Uri, targetName: String): Uri? {
        return runCatching {
            val docId = if (DocumentsContract.isDocumentUri(applicationContext, dirUri)) {
                DocumentsContract.getDocumentId(dirUri)
            } else {
                DocumentsContract.getTreeDocumentId(dirUri)
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, docId)
            val proj = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (c.getString(nameIdx).equals(targetName, ignoreCase = true)) {
                        return@runCatching DocumentsContract.buildDocumentUriUsingTree(dirUri, c.getString(idIdx))
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun writeToSafDirectoryUri(
        dirUri: Uri,
        sourceFile: File,
        mimeType: String,
        displayName: String
    ): Uri? {
        findChildSafFileUri(dirUri, displayName)?.let { oldUri ->
            runCatching { DocumentsContract.deleteDocument(contentResolver, oldUri) }
        }
        val createdUri = if (DocumentsContract.isDocumentUri(applicationContext, dirUri)) {
            runCatching {
                DocumentsContract.createDocument(contentResolver, dirUri, mimeType, displayName)
            }.getOrNull()
        } else {
            DocumentFile.fromTreeUri(applicationContext, dirUri)?.let { treeDoc ->
                treeDoc.findFile(displayName)?.delete()
                treeDoc.createFile(mimeType, displayName)?.uri
            }
        } ?: return null

        contentResolver.openOutputStream(createdUri)?.use { out ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(out, bufferSize = 32 * 1024)
            }
        }
        return createdUri
    }

    private fun resolveCoverBytes(request: M4bCompileRequest): Pair<ByteArray?, String?> {
        if (!request.metadata.includeCover) return null to request.coverPath

        val overrideUri = request.metadata.coverOverrideUri
        if (!overrideUri.isNullOrBlank()) {
            val bytes = runCatching {
                contentResolver.openInputStream(Uri.parse(overrideUri))?.use {
                    normalizeToJpeg(it.readBytes())
                }
            }.getOrNull()
            if (bytes != null) {
                val coversDir = File(filesDir, "covers").apply { mkdirs() }
                val target = File(coversDir, "m4b_custom_${System.currentTimeMillis()}.jpg")
                FileOutputStream(target).use { it.write(bytes) }
                return bytes to target.absolutePath
            }
        }

        val existingCover = request.coverPath?.let { path ->
            runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()
        }
        if (existingCover != null) {
            return normalizeToJpeg(existingCover) to request.coverPath
        }

        val firstCh = request.chapters.firstOrNull() ?: return null to null
        val embedded = runCatching {
            val retriever = MediaMetadataRetriever()
            if (firstCh.isDirectPath) retriever.setDataSource(firstCh.uriString)
            else retriever.setDataSource(applicationContext, Uri.parse(firstCh.uriString))
            val pic = retriever.embeddedPicture
            retriever.release()
            pic
        }.getOrNull()
        if (embedded != null) {
            val normalized = normalizeToJpeg(embedded)
            val savedPath = normalized?.let {
                (applicationContext as LexiVoiceApplication).audiobookRepository.saveCoverBytes(it, firstCh.uriString)
            }
            return normalized to savedPath
        }
        return null to null
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

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "Audiobook" }
    }

    private fun updateNotification(text: String, progress: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            501,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, LexiVoiceApplication.CHANNEL_AUDIO_EXPORT)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Compiling M4B Audiobook")
            .setContentText(text)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun showDoneNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notif = NotificationCompat.Builder(this, LexiVoiceApplication.CHANNEL_AUDIO_EXPORT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_DONE_ID, notif)
    }

    override fun onDestroy() {
        compileJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4401
        private const val NOTIFICATION_DONE_ID = 4402

        const val ACTION_START_COMPILE = "com.example.export.COMPILE_M4B_START"
        const val ACTION_CANCEL_COMPILE = "com.example.export.COMPILE_M4B_CANCEL"

        fun startCompile(context: Context, request: M4bCompileRequest) {
            M4bCompileStateHolder.pendingRequest = request
            val intent = Intent(context, M4bCompileService::class.java).setAction(ACTION_START_COMPILE)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
