package com.example.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.audiobook.AudiobookChapterMeta
import com.example.audiobook.SyncAlignmentSegment
import com.example.audiobook.SyncAlignmentSerializer
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookEntity
import com.example.data.db.FolderDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class AudiobookRepository(
    private val context: Context,
    private val audiobookDao: AudiobookDao,
    private val folderDao: FolderDao
) {
    val audiobooks: Flow<List<AudiobookEntity>> = audiobookDao.observeAllAudiobooks()

    fun observeAudiobook(id: Long): Flow<AudiobookEntity?> = audiobookDao.observeAudiobookById(id)

    suspend fun getAudiobookById(id: Long): AudiobookEntity? = audiobookDao.getAudiobookById(id)

    suspend fun updatePlaybackProgress(id: Long, positionMs: Long) = withContext(Dispatchers.IO) {
        audiobookDao.updatePlaybackProgress(id, positionMs, System.currentTimeMillis())
    }

    suspend fun registerExportedM4b(
        fileUri: String,
        title: String,
        author: String,
        narrator: String,
        year: String,
        genre: String,
        coverPath: String?,
        durationMs: Long,
        chapters: List<AudiobookChapterMeta>,
        sourceEpubKey: String?,
        sourceEpubIsDirect: Boolean,
        syncSegments: List<SyncAlignmentSegment>
    ): Long = withContext(Dispatchers.IO) {
        val syncFile = if (syncSegments.isNotEmpty()) {
            val dir = File(context.filesDir, "sync").apply { mkdirs() }
            val hash = sha256(fileUri)
            File(dir, "$hash.json").also {
                SyncAlignmentSerializer.saveSegments(it, syncSegments)
            }.absolutePath
        } else null

        val existing = audiobookDao.getAudiobookByUri(fileUri)
        val entity = AudiobookEntity(
            id = existing?.id ?: 0L,
            fileUri = fileUri,
            isDirectPath = false,
            title = title,
            author = author,
            narrator = narrator,
            year = year,
            genre = genre,
            coverPath = coverPath,
            durationMs = durationMs,
            chapterCount = chapters.size,
            chaptersJson = SyncAlignmentSerializer.encodeChapters(chapters),
            sourceEpubKey = sourceEpubKey,
            sourceEpubIsDirect = sourceEpubIsDirect,
            syncJsonPath = syncFile,
            lastPositionMs = existing?.lastPositionMs ?: 0L,
            lastPlayedAt = existing?.lastPlayedAt ?: 0L,
            addedAt = existing?.addedAt ?: System.currentTimeMillis()
        )
        audiobookDao.upsertAudiobook(entity)
    }

    suspend fun importM4bUri(uri: Uri): AudiobookEntity? = withContext(Dispatchers.IO) {
        val key = uri.toString()
        val existing = audiobookDao.getAudiobookByUri(key)
        val doc = DocumentFile.fromSingleUri(context, uri)
        val fallbackName = doc?.name?.removeSuffix(".m4b")?.removeSuffix(".M4B")?.removeSuffix(".m4a")
            ?: "Audiobook"

        val parsed = inspectM4bFromUri(uri, fallbackName, key)
        val entity = AudiobookEntity(
            id = existing?.id ?: 0L,
            fileUri = key,
            isDirectPath = false,
            title = parsed.title,
            author = parsed.author,
            narrator = parsed.narrator,
            year = parsed.year,
            genre = parsed.genre,
            coverPath = parsed.coverPath ?: existing?.coverPath,
            durationMs = parsed.durationMs,
            chapterCount = parsed.chapters.size.coerceAtLeast(1),
            chaptersJson = SyncAlignmentSerializer.encodeChapters(parsed.chapters),
            sourceEpubKey = existing?.sourceEpubKey,
            sourceEpubIsDirect = existing?.sourceEpubIsDirect ?: false,
            syncJsonPath = existing?.syncJsonPath,
            lastPositionMs = existing?.lastPositionMs ?: 0L,
            lastPlayedAt = existing?.lastPlayedAt ?: 0L,
            addedAt = existing?.addedAt ?: System.currentTimeMillis()
        )
        val id = audiobookDao.upsertAudiobook(entity)
        audiobookDao.getAudiobookByUri(key) ?: entity.copy(id = id)
    }

    suspend fun scanFoldersForM4b() = withContext(Dispatchers.IO) {
        val folders = folderDao.getAllFolders()
        for (folder in folders) {
            if (folder.isDirectPath) {
                val root = File(folder.uriOrPath)
                if (root.exists() && root.isDirectory) {
                    root.walkTopDown()
                        .onEnter { !it.name.startsWith(".") }
                        .filter { it.isFile && it.name.endsWith(".m4b", ignoreCase = true) }
                        .forEach { file ->
                            if (audiobookDao.getAudiobookByUri(file.absolutePath) == null) {
                                val parsed = inspectM4bFromFile(file, file.nameWithoutExtension, file.absolutePath)
                                audiobookDao.upsertAudiobook(
                                    AudiobookEntity(
                                        fileUri = file.absolutePath,
                                        isDirectPath = true,
                                        title = parsed.title,
                                        author = parsed.author,
                                        narrator = parsed.narrator,
                                        year = parsed.year,
                                        genre = parsed.genre,
                                        coverPath = parsed.coverPath,
                                        durationMs = parsed.durationMs,
                                        chapterCount = parsed.chapters.size.coerceAtLeast(1),
                                        chaptersJson = SyncAlignmentSerializer.encodeChapters(parsed.chapters)
                                    )
                                )
                            }
                        }
                }
            } else {
                val treeUri = Uri.parse(folder.uriOrPath)
                val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
                scanSafForM4b(treeUri, rootId)
            }
        }
    }

    private suspend fun scanSafForM4b(treeUri: Uri, rootDocId: String) {
        val queue = ArrayDeque<String>()
        queue.add(rootDocId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        while (queue.isNotEmpty()) {
            val docId = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            runCatching {
                context.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
                    val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (c.moveToNext()) {
                        val cid = c.getString(idIdx) ?: continue
                        val name = c.getString(nameIdx).orEmpty()
                        val mime = c.getString(mimeIdx).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.startsWith(".")) queue.addLast(cid)
                        } else if (name.endsWith(".m4b", ignoreCase = true)) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cid)
                            if (audiobookDao.getAudiobookByUri(docUri.toString()) == null) {
                                importM4bUri(docUri)
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun deleteAudiobook(id: Long) = withContext(Dispatchers.IO) {
        audiobookDao.deleteAudiobook(id)
    }

    private data class ParsedM4bInfo(
        val title: String,
        val author: String,
        val narrator: String,
        val year: String,
        val genre: String,
        val durationMs: Long,
        val coverPath: String?,
        val chapters: List<AudiobookChapterMeta>
    )

    private fun inspectM4bFromUri(uri: Uri, fallbackTitle: String, key: String): ParsedM4bInfo {
        val retriever = MediaMetadataRetriever()
        var title = fallbackTitle
        var author = "Unknown Author"
        var year = ""
        var genre = "Audiobook"
        var durationMs = 0L
        var coverBytes: ByteArray? = null

        runCatching {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle
            author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Author"
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty()
            genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.takeIf { it.isNotBlank() } ?: "Audiobook"
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            coverBytes = retriever.embeddedPicture
        }
        runCatching { retriever.release() }

        val chapters = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                readChplChaptersFromStream(stream, durationMs)
            } ?: emptyList()
        }.getOrDefault(emptyList())

        val coverPath = coverBytes?.let { saveCoverBytes(it, key) }
        return ParsedM4bInfo(
            title = title,
            author = author,
            narrator = "Audiobook",
            year = year,
            genre = genre,
            durationMs = durationMs,
            coverPath = coverPath,
            chapters = chapters.ifEmpty {
                listOf(AudiobookChapterMeta(0, title, 0L, durationMs))
            }
        )
    }

    private fun inspectM4bFromFile(file: File, fallbackTitle: String, key: String): ParsedM4bInfo {
        val retriever = MediaMetadataRetriever()
        var title = fallbackTitle
        var author = "Unknown Author"
        var year = ""
        var genre = "Audiobook"
        var durationMs = 0L
        var coverBytes: ByteArray? = null

        runCatching {
            retriever.setDataSource(file.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle
            author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Author"
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty()
            genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.takeIf { it.isNotBlank() } ?: "Audiobook"
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            coverBytes = retriever.embeddedPicture
        }
        runCatching { retriever.release() }

        val chapters = runCatching {
            FileInputStream(file).use { stream ->
                readChplChaptersFromStream(stream, durationMs)
            }
        }.getOrDefault(emptyList())

        val coverPath = coverBytes?.let { saveCoverBytes(it, key) }
        return ParsedM4bInfo(
            title = title,
            author = author,
            narrator = "Audiobook",
            year = year,
            genre = genre,
            durationMs = durationMs,
            coverPath = coverPath,
            chapters = chapters.ifEmpty {
                listOf(AudiobookChapterMeta(0, title, 0L, durationMs))
            }
        )
    }

    private fun readChplChaptersFromStream(stream: InputStream, totalDurationMs: Long): List<AudiobookChapterMeta> {
        val bytes = stream.readBytes()
        val marker = "chpl".toByteArray(StandardCharsets.US_ASCII)
        var idx = -1
        for (i in 4 until bytes.size - 16) {
            if (bytes[i] == marker[0] && bytes[i + 1] == marker[1] &&
                bytes[i + 2] == marker[2] && bytes[i + 3] == marker[3]
            ) {
                idx = i - 4
                break
            }
        }
        if (idx < 0) return emptyList()
        val boxSize = ByteBuffer.wrap(bytes, idx, 4).order(ByteOrder.BIG_ENDIAN).int
        if (boxSize < 17 || idx + boxSize > bytes.size) return emptyList()

        val bb = ByteBuffer.wrap(bytes, idx + 8, boxSize - 8).order(ByteOrder.BIG_ENDIAN)
        bb.int // version + flags
        bb.get() // reserved
        val count = bb.int.coerceIn(0, 2000)
        val rawList = mutableListOf<Pair<Long, String>>()
        for (c in 0 until count) {
            if (bb.remaining() < 9) break
            val start100ns = bb.long
            val len = bb.get().toInt() and 0xFF
            if (bb.remaining() < len) break
            val titleBuf = ByteArray(len)
            bb.get(titleBuf)
            rawList.add((start100ns / 10_000L) to String(titleBuf, StandardCharsets.UTF_8))
        }
        return rawList.mapIndexed { i, (startMs, chTitle) ->
            val endMs = rawList.getOrNull(i + 1)?.first ?: totalDurationMs.coerceAtLeast(startMs)
            AudiobookChapterMeta(i, chTitle, startMs, endMs)
        }
    }

    private fun saveCoverBytes(rawBytes: ByteArray, key: String): String? {
        return runCatching {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val target = File(coversDir, "m4b_${sha256(key)}.jpg")
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
            FileOutputStream(target).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            bmp.recycle()
            target.absolutePath
        }.getOrNull()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }
}
