package com.example.data.repo

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.audiobook.AudiobookChapterMeta
import com.example.audiobook.BookSyncMap
import com.example.audiobook.Mp3ChapterFileMeta
import com.example.audiobook.SyncAlignmentSegment
import com.example.audiobook.SyncAlignmentSerializer
import com.example.audiobook.SyncMapChapterEntry
import com.example.audiobook.SyncMapMerger
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookEntity
import com.example.data.db.FolderDao
import com.example.data.prefs.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

class AudiobookRepository(
    private val context: Context,
    private val audiobookDao: AudiobookDao,
    private val folderDao: FolderDao,
    private val preferencesRepository: PreferencesRepository? = null,
    private val libraryRepository: LibraryRepository? = null
) {
    val audiobooks: Flow<List<AudiobookEntity>> = audiobookDao.observeAllAudiobooks()

    fun observeAudiobook(id: Long): Flow<AudiobookEntity?> = audiobookDao.observeAudiobookById(id)

    suspend fun getAudiobookById(id: Long): AudiobookEntity? = audiobookDao.getAudiobookById(id)

    fun observeAudiobooksForEpub(fileKey: String): Flow<List<AudiobookEntity>> =
        audiobookDao.observeAudiobooksBySourceEpubKey(fileKey)

    suspend fun getLatestAudiobookForEpub(fileKey: String): AudiobookEntity? =
        audiobookDao.getLatestAudiobookForEpub(fileKey)

    suspend fun updatePlaybackProgress(
        id: Long,
        positionMs: Long,
        playedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        audiobookDao.updatePlaybackProgress(id, positionMs, playedAt)
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
        syncSegments: List<SyncAlignmentSegment>,
        folderUri: String? = null,
        isDirectPath: Boolean = false
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
            isDirectPath = isDirectPath,
            title = title,
            author = author,
            narrator = narrator,
            year = year,
            genre = genre,
            coverPath = coverPath ?: existing?.coverPath,
            durationMs = durationMs,
            chapterCount = chapters.size.coerceAtLeast(1),
            chaptersJson = SyncAlignmentSerializer.encodeChapters(chapters),
            sourceEpubKey = sourceEpubKey ?: existing?.sourceEpubKey,
            sourceEpubIsDirect = if (sourceEpubKey != null) sourceEpubIsDirect else (existing?.sourceEpubIsDirect ?: false),
            syncJsonPath = syncFile ?: existing?.syncJsonPath,
            isFolderAudiobook = false,
            folderUri = folderUri ?: existing?.folderUri,
            chapterFilesJson = "[]",
            lastPositionMs = existing?.lastPositionMs ?: 0L,
            lastPlayedAt = existing?.lastPlayedAt ?: 0L,
            addedAt = existing?.addedAt ?: System.currentTimeMillis()
        )
        audiobookDao.upsertAudiobookPreservingProgress(entity)
    }

    suspend fun registerExportedMp3Folder(
        folderUri: String,
        isDirectPath: Boolean,
        syncMap: BookSyncMap
    ): Long = withContext(Dispatchers.IO) {
        val flattened = SyncAlignmentSerializer.flattenToTimeline(syncMap)
        val folderKey = "folder://$folderUri"

        val syncFile = if (flattened.segments.isNotEmpty()) {
            val dir = File(context.filesDir, "sync").apply { mkdirs() }
            val hash = sha256(folderKey)
            File(dir, "$hash.json").also {
                SyncAlignmentSerializer.saveSegments(it, flattened.segments)
            }.absolutePath
        } else null

        val existing = audiobookDao.getAudiobookByUri(folderKey)

        // Resolve local EPUB key on this device (falling back to Book Title match across devices)
        var resolvedEpubKey = syncMap.sourceEpubKey ?: existing?.sourceEpubKey
        var resolvedEpubDirect = if (syncMap.sourceEpubKey != null) syncMap.sourceEpubIsDirect else (existing?.sourceEpubIsDirect ?: false)

        if (libraryRepository != null) {
            val localMatchByKey = resolvedEpubKey?.let { libraryRepository.getBookByFileKey(it) }
            if (localMatchByKey == null && syncMap.bookTitle.isNotBlank()) {
                val localMatchByTitle = runCatching {
                    libraryRepository.observeLibraryBooks(
                        searchQuery = syncMap.bookTitle,
                        folderFilter = null,
                        sortOption = com.example.data.db.LibrarySortOption.RECENT
                    ).first().firstOrNull {
                        it.title.equals(syncMap.bookTitle, ignoreCase = true)
                    }
                }.getOrNull()
                if (localMatchByTitle != null) {
                    resolvedEpubKey = localMatchByTitle.fileKey
                    resolvedEpubDirect = localMatchByTitle.isDirectPath
                }
            }
        }

        val validCoverPath = syncMap.coverPath?.takeIf { File(it).exists() }
            ?: existing?.coverPath?.takeIf { File(it).exists() }

        val entity = AudiobookEntity(
            id = existing?.id ?: 0L,
            fileUri = folderKey,
            isDirectPath = isDirectPath,
            title = syncMap.bookTitle,
            author = syncMap.author,
            narrator = syncMap.narrator,
            year = syncMap.year,
            genre = syncMap.genre,
            coverPath = validCoverPath,
            durationMs = flattened.totalDurationMs,
            chapterCount = flattened.chapterFiles.size.coerceAtLeast(flattened.chapters.size).coerceAtLeast(1),
            chaptersJson = SyncAlignmentSerializer.encodeChapters(flattened.chapters),
            sourceEpubKey = resolvedEpubKey,
            sourceEpubIsDirect = resolvedEpubDirect,
            syncJsonPath = syncFile ?: existing?.syncJsonPath,
            isFolderAudiobook = true,
            folderUri = folderUri,
            chapterFilesJson = SyncAlignmentSerializer.encodeChapterFiles(flattened.chapterFiles),
            lastPositionMs = existing?.lastPositionMs ?: 0L,
            lastPlayedAt = existing?.lastPlayedAt ?: 0L,
            addedAt = existing?.addedAt ?: System.currentTimeMillis()
        )
        audiobookDao.upsertAudiobookPreservingProgress(entity)
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
            isFolderAudiobook = false,
            folderUri = existing?.folderUri,
            chapterFilesJson = "[]",
            lastPositionMs = existing?.lastPositionMs ?: 0L,
            lastPlayedAt = existing?.lastPlayedAt ?: 0L,
            addedAt = existing?.addedAt ?: System.currentTimeMillis()
        )
        val id = audiobookDao.upsertAudiobookPreservingProgress(entity)
        audiobookDao.getAudiobookByUri(key) ?: entity.copy(id = id)
    }

    suspend fun scanFoldersForAudiobooks() = withContext(Dispatchers.IO) {
        val watched = folderDao.getAllFolders()
        val exportFolderUris = runCatching {
            preferencesRepository?.savedExportFolders?.first()?.map { it.uri }.orEmpty()
        }.getOrDefault(emptyList())

        val scannedSafTrees = HashSet<String>()
        val discoveredFolderKeys = HashSet<String>()

        // Automatic whole-device discovery through MediaStore first. Audio is
        // media, so this needs no restricted permission and no folder picking.
        scanMediaStoreForAudiobooks(discoveredFolderKeys)

        for (folder in watched) {
            if (folder.isDirectPath) {
                scanDirectDirectoryForAudiobooks(File(folder.uriOrPath), discoveredFolderKeys)
            } else {
                if (scannedSafTrees.add(folder.uriOrPath)) {
                    val treeUri = Uri.parse(folder.uriOrPath)
                    val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
                    scanSafDirectoryForAudiobooks(treeUri, rootId, discoveredFolderKeys)
                }
            }
        }

        for (uriStr in exportFolderUris) {
            if (scannedSafTrees.add(uriStr)) {
                val treeUri = Uri.parse(uriStr)
                val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
                scanSafDirectoryForAudiobooks(treeUri, rootId, discoveredFolderKeys)
            }
        }

        // Remove stale/renamed folder rows from Room (never deletes files on storage)
        if (watched.isNotEmpty() || exportFolderUris.isNotEmpty()) {
            val allExisting = runCatching { audiobookDao.observeAllAudiobooks().first() }.getOrDefault(emptyList())
            for (item in allExisting) {
                if (!item.isFolderAudiobook) continue
                if (item.fileUri in discoveredFolderKeys) continue
                // "Not rediscovered" is not proof the folder is gone: a revoked
                // permission, an unmounted volume or a lost SAF grant all look the
                // same, and deleting on that basis silently wipes library rows the
                // user never removed. Only drop a row when the folder is provably
                // absent.
                if (isFolderProvablyGone(item.fileUri)) {
                    audiobookDao.deleteAudiobook(item.id)
                }
            }
        }
    }


    suspend fun scanFoldersForM4b() = scanFoldersForAudiobooks()

    /**
     * Automatic audiobook discovery through MediaStore.
     *
     * Audio is media, so on API 33+ READ_MEDIA_AUDIO exposes every .m4b and .mp3
     * on the device, and on API <= 32 READ_EXTERNAL_STORAGE does the same. No
     * folder picking and no All Files Access is involved. Results are stored as
     * content:// URIs with isDirectPath = false so every later read goes through
     * ContentResolver.
     *
     * This complements, rather than replaces, the SAF folders below: SAF remains
     * the route for custom and SD-card directories the user grants explicitly,
     * and anything already indexed by URI is skipped, so the two paths never
     * produce duplicate rows.
     */
    /** One file as MediaStore exposed it, before de-duplication. */
    private data class MediaStoreAudioCandidate(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val fromAudioCollection: Boolean
    )

    private suspend fun scanMediaStoreForAudiobooks(discoveredFolderKeys: MutableSet<String>) {
        val candidates = mutableListOf<MediaStoreAudioCandidate>()

        collectMediaStoreAudio(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, candidates)
        // .m4b is audio/mp4 and normally lands in MediaStore.Audio, but some vendor
        // indexers file it as a generic document, so sweep Files too.
        collectMediaStoreAudio(MediaStore.Files.getContentUri("external"), false, candidates)

        if (candidates.isEmpty()) return

        // The SAME file appears in both collections under different URIs
        // (…/audio/media/<id> and …/file/<id>). Keying on the URI string therefore
        // produced two audiobook rows for every file, so group by name+size and
        // treat each group as a single file.
        val byFile = candidates.groupBy {
            "${it.displayName.lowercase(Locale.ROOT)}|${it.sizeBytes}"
        }

        for (group in byFile.values) {
            // Prefer the audio collection: it yields the proper audio content URI.
            val preferred = group.firstOrNull { it.fromAudioCollection } ?: group.first()
            val displayName = preferred.displayName
            val lower = displayName.lowercase(Locale.ROOT)
            val key = preferred.uri.toString()

            // Remove rows the other collection's URI created for this same file.
            for (other in group) {
                if (other.uri == preferred.uri) continue
                val stray = audiobookDao.getAudiobookByUri(other.uri.toString()) ?: continue
                if (!stray.isFolderAudiobook) audiobookDao.deleteAudiobook(stray.id)
            }

            val existing = audiobookDao.getAudiobookByUri(key)

            // Only .m4b is a genuinely single-file audiobook. An .mp3 is chapter
            // material owned by the folder scan, which registers the containing
            // folder as one audiobook with chapters.
            if (!lower.endsWith(".m4b")) {
                if (existing != null && !existing.isFolderAudiobook) {
                    audiobookDao.deleteAudiobook(existing.id)
                }
                continue
            }

            if (existing != null) continue

            val entity = mediaStoreM4bEntity(preferred.uri, displayName, key) ?: continue
            audiobookDao.upsertAudiobookPreservingProgress(entity)
            entity.folderUri?.let { discoveredFolderKeys.add(it) }
        }
    }

    /** Reads one MediaStore collection and appends every .m4b / .mp3 it exposes. */
    private fun collectMediaStoreAudio(
        collection: Uri,
        fromAudioCollection: Boolean,
        out: MutableList<MediaStoreAudioCandidate>
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%.m4b", "%.mp3")

        runCatching {
            context.contentResolver
                .query(collection, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        val lower = name.lowercase(Locale.ROOT)
                        if (!lower.endsWith(".m4b") && !lower.endsWith(".mp3")) continue
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else -1L
                        out.add(
                            MediaStoreAudioCandidate(
                                uri = ContentUris.withAppendedId(collection, id),
                                displayName = name,
                                sizeBytes = size,
                                fromAudioCollection = fromAudioCollection
                            )
                        )
                    }
                }
        }
    }

    /**
     * True only when the backing folder can be shown to no longer exist. Any doubt —
     * unreadable volume, missing permission, provider error — returns false so the
     * row survives.
     */
    private fun isFolderProvablyGone(folderKey: String): Boolean {
        if (!folderKey.startsWith("folder://")) return false
        val target = folderKey.removePrefix("folder://")

        if (target.startsWith("content://")) {
            // SAF: only when the provider still resolves the document and says it is
            // gone. A failed query means "unknown", so the row survives.
            return runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(target))?.exists() == false
            }.getOrDefault(false)
        }

        // Direct path. File.exists() returns false both for "deleted" and for "this
        // app may not read here" — an SD card without All Files Access looks exactly
        // like a deleted folder. Only trust a negative answer when the parent
        // directory is actually listable, otherwise the row is kept.
        val file = File(target)
        val parent = file.parentFile ?: return false
        if (!parent.canRead()) return false
        return runCatching { !file.exists() }.getOrDefault(false)
    }

    private fun mediaStoreM4bEntity(uri: Uri, displayName: String, key: String): AudiobookEntity? {
        val fallbackTitle = displayName.substringBeforeLast('.')
        val parsed = inspectM4bFromUri(uri, fallbackTitle, key)
        return AudiobookEntity(
            fileUri = key,
            isDirectPath = false,
            title = parsed.title,
            author = parsed.author,
            narrator = parsed.narrator,
            year = parsed.year,
            genre = parsed.genre,
            coverPath = parsed.coverPath,
            durationMs = parsed.durationMs,
            chapterCount = parsed.chapters.size.coerceAtLeast(1),
            chaptersJson = SyncAlignmentSerializer.encodeChapters(parsed.chapters),
            isFolderAudiobook = false,
            folderUri = MEDIASTORE_AUDIO_FOLDER_PREFIX + (uri.lastPathSegment ?: "Device audio"),
            chapterFilesJson = "[]",
            addedAt = System.currentTimeMillis()
        )
    }

    private suspend fun scanDirectDirectoryForAudiobooks(
        root: File,
        discoveredFolderKeys: MutableSet<String>
    ) {
        if (!root.exists() || !root.isDirectory) return

        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            if (dir.name.startsWith(".")) continue
            val children = dir.listFiles() ?: continue

            val mp3Files = mutableListOf<File>()
            val syncMapFiles = mutableListOf<File>()

            for (child in children) {
                if (child.isDirectory) {
                    if (!child.name.startsWith(".")) queue.addLast(child)
                } else if (child.isFile) {
                    when {
                        child.name.endsWith(".m4b", ignoreCase = true) -> {
                            if (audiobookDao.getAudiobookByUri(child.absolutePath) == null) {
                                val parsed = inspectM4bFromFile(child, child.nameWithoutExtension, child.absolutePath)
                                audiobookDao.upsertAudiobook(
                                    AudiobookEntity(
                                        fileUri = child.absolutePath,
                                        isDirectPath = true,
                                        title = parsed.title,
                                        author = parsed.author,
                                        narrator = parsed.narrator,
                                        year = parsed.year,
                                        genre = parsed.genre,
                                        coverPath = parsed.coverPath,
                                        durationMs = parsed.durationMs,
                                        chapterCount = parsed.chapters.size.coerceAtLeast(1),
                                        chaptersJson = SyncAlignmentSerializer.encodeChapters(parsed.chapters),
                                        folderUri = dir.absolutePath
                                    )
                                )
                            }
                        }
                        child.name.endsWith(".mp3", ignoreCase = true) -> mp3Files.add(child)
                        isSyncMapFileName(child.name) -> syncMapFiles.add(child)
                    }
                }
            }

            if (mp3Files.isNotEmpty()) {
                // A folder can hold several sync maps (sync_map.json, sync_map (1).json,
                // sync_map_2.json ...) after recordings move between devices. Merge them
                // so chapters outside the first file are not silently invisible.
                val decodedSyncs = syncMapFiles.mapNotNull { f ->
                    runCatching { SyncAlignmentSerializer.decodeBookSyncMap(f.readText()) }
                        .getOrNull()?.let { f to it }
                }
                val existingSync = SyncMapMerger.merge(decodedSyncs.map { it.second })
                if (existingSync != null && decodedSyncs.size > 1) {
                    val consolidated = decodedSyncs
                        .firstOrNull { it.first.name.equals(SYNC_MAP_FILE_NAME, ignoreCase = true) }
                        ?.second
                    if (!SyncMapMerger.isUpToDate(consolidated, existingSync)) {
                        writeConsolidatedSyncMapToFile(File(dir, SYNC_MAP_FILE_NAME), existingSync)
                    }
                }
                val syncMap = buildBookSyncMapFromDirectMp3s(dir, mp3Files, existingSync)
                discoveredFolderKeys.add("folder://${dir.absolutePath}")
                registerExportedMp3Folder(
                    folderUri = dir.absolutePath,
                    isDirectPath = true,
                    syncMap = syncMap
                )
            }
        }
    }


    private suspend fun scanSafDirectoryForAudiobooks(
        treeUri: Uri,
        rootDocId: String,
        discoveredFolderKeys: MutableSet<String>
    ) {
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootDocId to "Audiobooks")
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        while (queue.isNotEmpty()) {
            val (docId, dirName) = queue.removeFirst()
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

            val mp3Docs = mutableListOf<Triple<String, String, Uri>>()
            val syncMapUris = mutableListOf<Uri>()

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
                            if (!name.startsWith(".")) queue.addLast(cid to name)
                        } else {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cid)
                            when {
                                name.endsWith(".m4b", ignoreCase = true) -> {
                                    if (audiobookDao.getAudiobookByUri(docUri.toString()) == null) {
                                        importM4bUri(docUri)
                                    }
                                }
                                name.endsWith(".mp3", ignoreCase = true) -> {
                                    mp3Docs.add(Triple(cid, name, docUri))
                                }
                                isSyncMapFileName(name) -> syncMapUris.add(docUri)
                            }
                        }
                    }
                }
            }

            if (mp3Docs.isNotEmpty()) {
                // See the direct-path branch above: merge every sync_map*.json in the
                // folder so a transfer that produced duplicates does not hide chapters.
                val decodedSyncs = syncMapUris.mapNotNull { uri ->
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                            SyncAlignmentSerializer.decodeBookSyncMap(r.readText())
                        }
                    }.getOrNull()
                }
                val existingSync = SyncMapMerger.merge(decodedSyncs)
                if (existingSync != null && decodedSyncs.size > 1) {
                    val consolidatedUri = findChildDocument(dirUri, SYNC_MAP_FILE_NAME)
                    val consolidated = consolidatedUri?.let { uri ->
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                                SyncAlignmentSerializer.decodeBookSyncMap(r.readText())
                            }
                        }.getOrNull()
                    }
                    if (!SyncMapMerger.isUpToDate(consolidated, existingSync)) {
                        writeConsolidatedSyncMapToDocument(dirUri, existingSync)
                    }
                }
                val syncMap = buildBookSyncMapFromSafMp3s(
                    folderName = dirName,
                    mp3Docs = mp3Docs,
                    existingSync = existingSync
                )
                discoveredFolderKeys.add("folder://$dirUri")
                registerExportedMp3Folder(
                    folderUri = dirUri.toString(),
                    isDirectPath = false,
                    syncMap = syncMap
                )
            }
        }
    }



    private fun buildBookSyncMapFromDirectMp3s(
        dir: File,
        mp3Files: List<File>,
        existingSync: BookSyncMap?
    ): BookSyncMap {
        val sorted = mp3Files.sortedBy { it.name.lowercase() }
        val existingByName = existingSync?.chapters.orEmpty().associateBy { it.fileName }
        var detectedAlbum = existingSync?.bookTitle.orEmpty()
        var detectedArtist = existingSync?.author.orEmpty()
        var detectedCoverPath = existingSync?.coverPath?.takeIf { File(it).exists() }

        val chapterEntries = sorted.mapIndexed { idx, file ->
            val prev = existingByName[file.name]
            val meta = inspectMp3FromFile(file)
            if (detectedAlbum.isBlank() && meta.album.isNotBlank()) detectedAlbum = meta.album
            if (detectedArtist.isBlank() && meta.artist.isNotBlank()) detectedArtist = meta.artist
            if (detectedCoverPath == null && meta.coverBytes != null) {
                detectedCoverPath = saveCoverBytes(meta.coverBytes, dir.absolutePath)
            }
            SyncMapChapterEntry(
                chapterIndex = prev?.chapterIndex ?: extractChapterIndexFromFileName(file.name, idx),
                title = prev?.title?.takeIf { it.isNotBlank() } ?: meta.title.ifBlank { file.nameWithoutExtension },
                fileName = file.name,
                fileUri = file.absolutePath,
                durationMs = prev?.durationMs?.takeIf { it > 0L } ?: meta.durationMs.coerceAtLeast(1000L),
                isDirectPath = true,
                sentences = prev?.sentences.orEmpty()
            )
        }

        return BookSyncMap(
            bookTitle = detectedAlbum.ifBlank { dir.name },
            author = detectedArtist.ifBlank { "Unknown Author" },
            narrator = existingSync?.narrator ?: "Audiobook",
            year = existingSync?.year ?: "2026",
            genre = existingSync?.genre ?: "Audiobook",
            coverPath = detectedCoverPath,
            sourceEpubKey = existingSync?.sourceEpubKey,
            sourceEpubIsDirect = existingSync?.sourceEpubIsDirect ?: false,
            chapters = chapterEntries
        )
    }

    private fun buildBookSyncMapFromSafMp3s(
        folderName: String,
        mp3Docs: List<Triple<String, String, Uri>>,
        existingSync: BookSyncMap?
    ): BookSyncMap {
        val sorted = mp3Docs.sortedBy { it.second.lowercase() }
        val existingByName = existingSync?.chapters.orEmpty().associateBy { it.fileName }
        var detectedAlbum = existingSync?.bookTitle.orEmpty()
        var detectedArtist = existingSync?.author.orEmpty()
        var detectedCoverPath = existingSync?.coverPath?.takeIf { File(it).exists() }

        val chapterEntries = sorted.mapIndexed { idx, (_, name, uri) ->
            val prev = existingByName[name]
            val needMeta = prev == null || prev.durationMs <= 0L || detectedAlbum.isBlank() || detectedCoverPath == null
            val meta = if (!needMeta) {
                ParsedMp3Info(prev!!.title, detectedArtist, detectedAlbum, prev.durationMs, null)
            } else {
                inspectMp3FromUri(uri, name.removeSuffix(".mp3").removeSuffix(".MP3"))
            }
            if (detectedAlbum.isBlank() && meta.album.isNotBlank()) detectedAlbum = meta.album
            if (detectedArtist.isBlank() && meta.artist.isNotBlank()) detectedArtist = meta.artist
            if (detectedCoverPath == null && meta.coverBytes != null) {
                detectedCoverPath = saveCoverBytes(meta.coverBytes, uri.toString())
            }
            SyncMapChapterEntry(
                chapterIndex = prev?.chapterIndex ?: extractChapterIndexFromFileName(name, idx),
                title = prev?.title?.takeIf { it.isNotBlank() }
                    ?: meta.title.ifBlank { name.removeSuffix(".mp3") },
                fileName = name,
                fileUri = uri.toString(),
                durationMs = prev?.durationMs?.takeIf { it > 0L } ?: meta.durationMs.coerceAtLeast(1000L),
                isDirectPath = false,
                sentences = prev?.sentences.orEmpty()
            )
        }

        return BookSyncMap(
            bookTitle = detectedAlbum.ifBlank { folderName },
            author = detectedArtist.ifBlank { "Unknown Author" },
            narrator = existingSync?.narrator ?: "Audiobook",
            year = existingSync?.year ?: "2026",
            genre = existingSync?.genre ?: "Audiobook",
            coverPath = detectedCoverPath,
            sourceEpubKey = existingSync?.sourceEpubKey,
            sourceEpubIsDirect = existingSync?.sourceEpubIsDirect ?: false,
            chapters = chapterEntries
        )
    }

    private fun extractChapterIndexFromFileName(fileName: String, fallbackIdx: Int): Int {
        val prefix = Regex("^(\\d{1,4})\\s*[-_.]").find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (prefix != null && prefix > 0) prefix - 1 else fallbackIdx
    }

    suspend fun deleteAudiobook(id: Long) = withContext(Dispatchers.IO) {
        audiobookDao.deleteAudiobook(id)
    }

    suspend fun deleteSourceMp3Chapters(audiobook: AudiobookEntity, chapterUris: List<Mp3ChapterFileMeta>) =
        withContext(Dispatchers.IO) {
            for (ch in chapterUris) {
                runCatching {
                    if (ch.isDirectPath) {
                        File(ch.fileUri).delete()
                    } else {
                        DocumentFile.fromSingleUri(context, Uri.parse(ch.fileUri))?.delete()
                    }
                }
            }
            if (audiobook.isFolderAudiobook) {
                audiobookDao.deleteAudiobook(audiobook.id)
            }
        }

    private data class ParsedMp3Info(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val coverBytes: ByteArray?
    )

    private fun inspectMp3FromUri(uri: Uri, fallbackTitle: String): ParsedMp3Info {
        val retriever = MediaMetadataRetriever()
        var title = fallbackTitle
        var artist = ""
        var album = ""
        var durationMs = 0L
        var coverBytes: ByteArray? = null
        runCatching {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            coverBytes = retriever.embeddedPicture
        }
        runCatching { retriever.release() }
        return ParsedMp3Info(title, artist, album, durationMs, coverBytes)
    }

    private fun inspectMp3FromFile(file: File): ParsedMp3Info {
        val retriever = MediaMetadataRetriever()
        var title = file.nameWithoutExtension
        var artist = ""
        var album = ""
        var durationMs = 0L
        var coverBytes: ByteArray? = null
        runCatching {
            retriever.setDataSource(file.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            coverBytes = retriever.embeddedPicture
        }
        runCatching { retriever.release() }
        return ParsedMp3Info(title, artist, album, durationMs, coverBytes)
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
            // A file descriptor rather than openInputStream: the chpl box has to be
            // reached by seeking, not by reading the whole file.
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream ->
                    readChplChapters(stream, durationMs)
                }
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
                readChplChapters(stream, durationMs)
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

    /**
     * Reads the Nero `chpl` chapter atom by walking the MP4 box tree.
     *
     * The previous implementation called `stream.readBytes()`, which pulls the WHOLE
     * file into a single ByteArray just to reach a box of a few hundred bytes. For a
     * 600 MB audiobook that is an immediate OutOfMemoryError; the caller's runCatching
     * swallowed it and the `ifEmpty` fallback turned it into a single chapter, so
     * every large book silently reported "1 ch".
     *
     * Seeking instead means memory use is a few kilobytes regardless of file size,
     * and it works whether `moov` sits at the front (faststart) or the end.
     */
    private fun readChplChapters(
        stream: FileInputStream,
        totalDurationMs: Long
    ): List<AudiobookChapterMeta> {
        val fileSize = runCatching { stream.channel.size() }.getOrDefault(0L)
        if (fileSize <= 16L) return emptyList()

        // moov/udta/chpl is the standard location; a few muxers put it directly
        // under moov.
        val chpl = findBox(stream, 0L, fileSize, listOf("moov", "udta", "chpl"))
            ?: findBox(stream, 0L, fileSize, listOf("moov", "chpl"))
            ?: return emptyList()

        val payloadSize = (chpl.end - chpl.bodyStart).toInt()
        if (payloadSize < 9) return emptyList()
        val payload = ByteArray(payloadSize)
        runCatching { stream.channel.position(chpl.bodyStart) }.getOrElse { return emptyList() }
        if (!readFully(stream, payload)) return emptyList()

        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        bb.int                    // version + flags
        bb.get()                  // first reserved byte
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
        if (rawList.isEmpty()) return emptyList()
        return rawList.mapIndexed { i, (startMs, chTitle) ->
            val endMs = rawList.getOrNull(i + 1)?.first ?: totalDurationMs.coerceAtLeast(startMs)
            AudiobookChapterMeta(i, chTitle, startMs, endMs)
        }
    }

    private data class Mp4Box(val type: String, val bodyStart: Long, val end: Long)

    /** Depth-first search for a box path, seeking over media data instead of reading it. */
    private fun findBox(
        stream: FileInputStream,
        start: Long,
        end: Long,
        path: List<String>
    ): Mp4Box? {
        if (path.isEmpty()) return null
        var pos = start
        while (pos + 8 <= end) {
            runCatching { stream.channel.position(pos) }.getOrElse { return null }
            val header = ByteArray(8)
            if (!readFully(stream, header)) return null
            var size = ((header[0].toLong() and 0xFF) shl 24) or
                ((header[1].toLong() and 0xFF) shl 16) or
                ((header[2].toLong() and 0xFF) shl 8) or
                (header[3].toLong() and 0xFF)
            val type = String(header, 4, 4, StandardCharsets.US_ASCII)
            var headerSize = 8L
            if (size == 1L) {
                val ext = ByteArray(8)
                if (!readFully(stream, ext)) return null
                size = 0L
                for (b in ext) size = (size shl 8) or (b.toLong() and 0xFF)
                headerSize = 16L
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < headerSize || pos + size > end) return null

            if (type == path.first()) {
                val box = Mp4Box(type, pos + headerSize, pos + size)
                if (path.size == 1) return box
                findBox(stream, box.bodyStart, box.end, path.drop(1))?.let { return it }
            }
            pos += size
        }
        return null
    }

    private fun readFully(stream: FileInputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = stream.read(buf, read, buf.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    fun saveCoverBytes(rawBytes: ByteArray, key: String): String? {
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

    /**
     * True for `sync_map.json` and for the duplicate copies a file manager creates
     * when the same name is copied twice: `sync_map (1).json`, `sync_map_1.json`,
     * `sync_map-2.json`.
     */
    private fun isSyncMapFileName(name: String): Boolean {
        val n = name.trim().lowercase(Locale.ROOT)
        return n.startsWith("sync_map") && n.endsWith(".json")
    }

    /**
     * Writes the merged map back so the next scan is a single read. Written to a
     * temporary file and renamed, so an interrupted write cannot leave a truncated
     * map behind.
     */
    private fun writeConsolidatedSyncMapToFile(target: File, map: BookSyncMap) {
        runCatching {
            val json = SyncAlignmentSerializer.encodeBookSyncMap(map)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }

    /** SAF equivalent of [writeConsolidatedSyncMapToFile]. */
    private fun writeConsolidatedSyncMapToDocument(dirUri: Uri, map: BookSyncMap) {
        runCatching {
            val json = SyncAlignmentSerializer.encodeBookSyncMap(map)
            val resolver = context.contentResolver
            findChildDocument(dirUri, SYNC_MAP_FILE_NAME)?.let { old ->
                runCatching { DocumentsContract.deleteDocument(resolver, old) }
            }
            val created = DocumentsContract.createDocument(
                resolver, dirUri, "application/json", SYNC_MAP_FILE_NAME
            ) ?: return
            resolver.openOutputStream(created)?.use { out ->
                out.write(json.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        }
    }

    /** Resolves a child document by display name inside a tree-backed directory URI. */
    private fun findChildDocument(dirUri: Uri, targetName: String): Uri? {
        return runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                dirUri, DocumentsContract.getDocumentId(dirUri)
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    if (name.equals(targetName, ignoreCase = true)) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(
                            dirUri, c.getString(idIdx)
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }

    private companion object {
        /** Folder key prefix used for audiobooks discovered through MediaStore. */
        const val MEDIASTORE_AUDIO_FOLDER_PREFIX = "mediastore-audio://"

        /** The name the app reads; a consolidated merge is written under this. */
        const val SYNC_MAP_FILE_NAME = "sync_map.json"
    }

}
