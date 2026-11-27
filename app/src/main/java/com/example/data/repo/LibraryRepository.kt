package com.example.data.repo

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.data.db.BookDao
import com.example.data.db.BookEntity
import com.example.data.db.FolderDao
import com.example.data.db.LibrarySortOption
import com.example.data.db.WatchedFolderEntity
import com.example.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class ScanProgressState(
    val isScanning: Boolean = false,
    val scannedFiles: Int = 0,
    val newlyIndexed: Int = 0,
    val currentFolderName: String = "",
    val currentFileName: String = ""
)

class LibraryRepository(
    private val context: Context,
    private val bookDao: BookDao,
    private val folderDao: FolderDao
) {
    private val _scanProgress = MutableStateFlow(ScanProgressState())
    val scanProgress: StateFlow<ScanProgressState> = _scanProgress.asStateFlow()

    val folders: Flow<List<WatchedFolderEntity>> = folderDao.observeFolders()
    val recentBooks: Flow<List<BookEntity>> = bookDao.observeRecentBooks()
    val totalBookCount: Flow<Int> = bookDao.observeTotalCount()
    val distinctLanguages: Flow<List<String>> = bookDao.observeDistinctLanguages()

    fun observeLibraryBooks(
        searchQuery: String = "",
        folderFilter: String? = null,
        shelfId: Long? = null,
        progressFilter: String? = null,
        languageFilter: String? = null,
        showHidden: Boolean = false,
        sortOption: LibrarySortOption = LibrarySortOption.RECENT
    ): Flow<List<BookEntity>> = bookDao.observeLibraryBooks(
        searchQuery = searchQuery,
        folderFilter = folderFilter,
        shelfId = shelfId,
        progressFilter = progressFilter,
        languageFilter = languageFilter,
        showHidden = showHidden,
        sortOption = sortOption
    )

    fun observeLibraryBooks(
        searchQuery: String,
        folderFilter: String?,
        sortOption: LibrarySortOption
    ): Flow<List<BookEntity>> = observeLibraryBooks(
        searchQuery = searchQuery,
        folderFilter = folderFilter,
        shelfId = null,
        progressFilter = null,
        languageFilter = null,
        showHidden = false,
        sortOption = sortOption
    )

    fun observeBook(bookId: Long): Flow<BookEntity?> = bookDao.observeBookById(bookId)

    suspend fun getBookById(bookId: Long): BookEntity? = bookDao.getBookById(bookId)

    suspend fun getBookByFileKey(fileKey: String): BookEntity? = bookDao.getBookByFileKey(fileKey)

    /**
     * Resolves a sync map's `sourceEpubKey` to a book on THIS device.
     *
     * A sync map is produced on whichever device recorded the audio, so the key it
     * carries is that device's path — `Download/Quick Share/Epub/x.epub` against
     * `Download/Epub/x.epub` here, for example. An exact match therefore almost
     * never succeeds after transferring the map, and read-along silently never
     * attaches. Fall back to the file name, which survives the move.
     */
    suspend fun getBookByFileKeyOrName(fileKey: String): BookEntity? {
        bookDao.getBookByFileKey(fileKey)?.let { return it }
        val raw = fileKey.substringAfterLast('/')
        if (raw.isBlank()) return null
        val name = runCatching { Uri.decode(raw) }.getOrDefault(raw)
        if (name.isBlank()) return null
        return runCatching { bookDao.getBookByFileKeyEndingWith(name) }.getOrNull()
    }

    suspend fun addSafFolderAndScan(treeUri: Uri): WatchedFolderEntity = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        val displayName = doc?.name ?: treeUri.lastPathSegment ?: "Book Folder"
        val entity = WatchedFolderEntity(
            uriOrPath = treeUri.toString(),
            displayName = displayName,
            isDirectPath = false,
            addedAt = System.currentTimeMillis()
        )
        folderDao.upsertFolder(entity)
        syncSingleFolder(entity)
        entity
    }

    suspend fun addDirectFolderAndScan(directory: File): WatchedFolderEntity = withContext(Dispatchers.IO) {
        val entity = WatchedFolderEntity(
            uriOrPath = directory.absolutePath,
            displayName = directory.name.ifBlank { directory.absolutePath },
            isDirectPath = true,
            addedAt = System.currentTimeMillis()
        )
        folderDao.upsertFolder(entity)
        syncSingleFolder(entity)
        entity
    }

    /**
     * The document id of a SAF URI, which is what identifies a file rather than the
     * route it was reached by. The picker hands out
     * content://authority/document/<id>; a folder scan stores
     * content://authority/tree/<tree>/document/<id> for the same file.
     */
    private fun documentIdOf(fileKey: String): String? =
        fileKey.substringAfterLast("/document/", "").takeIf { it.isNotBlank() }

    /**
     * True when this exact file is already indexed from a folder the user added.
     *
     * Deliberately exact: the document id has to match, so two different books that
     * happen to share a title and a size are never confused for one another.
     */
    suspend fun findFolderDuplicateForImport(uri: Uri): BookEntity? = withContext(Dispatchers.IO) {
        val pickedId = documentIdOf(uri.toString()) ?: return@withContext null
        bookDao.getAllBooksOnce().firstOrNull { existing ->
            existing.folderKey != SINGLE_IMPORTS_FOLDER &&
                !existing.folderKey.startsWith(MEDIASTORE_FOLDER_PREFIX) &&
                documentIdOf(existing.fileKey) == pickedId
        }
    }

    suspend fun importSingleEpubUri(uri: Uri): BookEntity? = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromSingleUri(context, uri)
        val name = doc?.name ?: uri.lastPathSegment ?: "Imported.epub"
        val size = doc?.length() ?: 0L
        val mod = doc?.lastModified() ?: System.currentTimeMillis()
        val key = uri.toString()
        val meta = EpubParser.extractMetadataForIndexing(
            context = context,
            fileKey = key,
            isDirectPath = false,
            fallbackFileName = name
        )
        val entity = BookEntity(
            fileKey = key,
            folderKey = SINGLE_IMPORTS_FOLDER,
            parentFolderName = "Imported Files",
            isDirectPath = false,
            title = meta.title,
            author = meta.author,
            language = meta.language,
            coverPath = meta.coverPath,
            chapterCount = meta.chapterCount,
            fileSizeBytes = size,
            lastModified = mod,
            addedAt = System.currentTimeMillis()
        )
        val insertedId = bookDao.upsertBook(entity)
        bookDao.getBookByFileKey(key) ?: entity.copy(id = insertedId)
    }

    suspend fun removeFolder(uriOrPath: String) = withContext(Dispatchers.IO) {
        bookDao.deleteAllInFolder(uriOrPath)
        folderDao.deleteFolder(uriOrPath)
    }

    suspend fun updateReadingProgress(
        bookId: Long,
        chapterIndex: Int,
        sentenceIndex: Int,
        scrollOffset: Int,
        totalChapters: Int,
        syncedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val safeTotal = totalChapters.coerceAtLeast(1)
        val pct = ((chapterIndex + 1).toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
        bookDao.updateReadingProgress(
            bookId = bookId,
            chapterIndex = chapterIndex,
            sentenceIndex = sentenceIndex,
            scrollOffset = scrollOffset,
            progressPercent = pct,
            lastOpenedAt = syncedAt
        )
    }

    suspend fun updateBookShelf(bookId: Long, shelfId: Long) = withContext(Dispatchers.IO) {
        bookDao.updateBookShelf(bookId, shelfId)
    }

    suspend fun updateBookFinished(bookId: Long, isFinished: Boolean) = withContext(Dispatchers.IO) {
        bookDao.updateBookFinished(bookId, isFinished)
    }

    suspend fun updateBookHidden(bookId: Long, isHidden: Boolean) = withContext(Dispatchers.IO) {
        bookDao.updateBookHidden(bookId, isHidden)
    }

    suspend fun deleteBook(book: BookEntity, deleteFilePermanently: Boolean) = withContext(Dispatchers.IO) {
        if (deleteFilePermanently) {
            runCatching {
                if (book.isDirectPath) {
                    File(book.fileKey).delete()
                } else {
                    DocumentFile.fromSingleUri(context, Uri.parse(book.fileKey))?.delete()
                }
            }
        }
        bookDao.deleteBookById(book.id)
    }

    /**
     * Whole-device library refresh, in order of precedence.
     *
     * 1. MediaStore — the Play-compliant path. Audiobooks are audio media and are
     *    fully visible with READ_MEDIA_AUDIO (API 33+) or READ_EXTERNAL_STORAGE
     *    (API <= 32). EPUBs are non-media documents: the platform returns them
     *    from MediaStore on API <= 32, and on API 33+ only when it chooses to
     *    expose them, so user-granted SAF folders stay the reliable route for
     *    EPUBs on modern Android.
     * 2. A legacy direct-path sweep, but only where the storage root is genuinely
     *    readable without All Files Access.
     *
     * MANAGE_EXTERNAL_STORAGE is never requested, so nothing here consults
     * Environment.isExternalStorageManager().
     */
    suspend fun scanDeviceForEpubs() = withContext(Dispatchers.IO) {
        _scanProgress.value = ScanProgressState(isScanning = true, currentFolderName = "Device storage")
        try {
            scanMediaStoreEpubs()
            if (canReadLegacyStorage()) {
                scanLegacyDirectories()
            }
        } finally {
            _scanProgress.value = ScanProgressState(isScanning = false)
        }
    }

    /**
     * True only when a plain filesystem walk can actually see shared storage.
     * On API 33+ that is never the case without All Files Access or a SAF grant,
     * which is exactly why the walk is no longer the primary path.
     */
    private fun canReadLegacyStorage(): Boolean {
        // Pro edition only: All Files Access is declared in that flavour's manifest
        // and granted by the user, so the whole storage root is readable on every
        // API level. In the standard edition the permission is never declared, so
        // this is always false and the direct-path sweep simply does not run.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            return true
        }
        // Legacy path: API 32 and below with the old read permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        val root = Environment.getExternalStorageDirectory()
        if (!root.exists() || !root.canRead()) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Cheap identity for de-duplicating the same file discovered through two
     * routes (for example a SAF folder and MediaStore). Size plus title is enough
     * here because it only ever suppresses an exact duplicate.
     */
    private fun fingerprint(sizeBytes: Long, title: String): String =
        "$sizeBytes|${title.trim().lowercase(Locale.ROOT)}"

    /**
     * Legacy filesystem sweep for API <= 32 devices where READ_EXTERNAL_STORAGE
     * still grants a readable storage root.
     */
    private suspend fun scanLegacyDirectories() {
        val root = Environment.getExternalStorageDirectory()
        val discoveredDirs = linkedMapOf<String, MutableList<File>>()
        var scanned = 0

        runCatching {
            root.walkTopDown()
                .onEnter { dir ->
                    val name = dir.name
                    !name.startsWith(".") &&
                        dir.absolutePath != File(root, "Android").absolutePath
                }
                .filter { it.isFile && it.name.endsWith(".epub", ignoreCase = true) }
                .forEach { file ->
                    scanned++
                    val parent = file.parentFile ?: root
                    discoveredDirs.getOrPut(parent.absolutePath) { mutableListOf() }.add(file)
                    if (scanned % 25 == 0) {
                        _scanProgress.value = _scanProgress.value.copy(
                            scannedFiles = scanned,
                            currentFolderName = parent.name.ifBlank { "Device storage" },
                            currentFileName = file.name
                        )
                    }
                }
        }

        if (discoveredDirs.isEmpty()) return

        val fingerprints = bookDao.getAllBooksOnce()
            .filterNot { it.folderKey.startsWith(MEDIASTORE_FOLDER_PREFIX) }
            .map { fingerprint(it.fileSizeBytes, it.title) }
            .toMutableSet()

        var newlyIndexed = 0
        for ((dirPath, files) in discoveredDirs) {
            val dirFile = File(dirPath)
            val folderDisplayName = dirFile.name.ifBlank { dirPath }
            val folderEntity = WatchedFolderEntity(
                uriOrPath = dirPath,
                displayName = folderDisplayName,
                isDirectPath = true,
                addedAt = System.currentTimeMillis()
            )
            folderDao.upsertFolder(folderEntity)
            val existingMap = bookDao.getBooksForFolder(dirPath).associateBy { it.fileKey }
            val batch = mutableListOf<BookEntity>()
            for (file in files) {
                val fileKey = file.absolutePath
                val size = file.length()
                val mod = file.lastModified()
                val existing = existingMap[fileKey]
                val unchanged = existing != null &&
                    existing.lastModified == mod &&
                    existing.fileSizeBytes == size &&
                    existing.parentFolderName.isNotBlank() &&
                    (existing.coverPath == null || File(existing.coverPath).exists())
                if (!unchanged) {
                    _scanProgress.value = _scanProgress.value.copy(
                        isScanning = true,
                        scannedFiles = scanned,
                        newlyIndexed = newlyIndexed,
                        currentFolderName = folderDisplayName,
                        currentFileName = file.name
                    )
                    val meta = EpubParser.extractMetadataForIndexing(
                        context = context,
                        fileKey = fileKey,
                        isDirectPath = true,
                        fallbackFileName = file.name
                    )
                    if (!fingerprints.add(fingerprint(size, meta.title))) continue
                    batch.add(
                        BookEntity(
                            fileKey = fileKey,
                            folderKey = dirPath,
                            parentFolderName = folderDisplayName,
                            isDirectPath = true,
                            title = meta.title,
                            author = meta.author,
                            language = meta.language,
                            coverPath = meta.coverPath,
                            chapterCount = meta.chapterCount,
                            fileSizeBytes = size,
                            lastModified = mod,
                            addedAt = existing?.addedAt ?: System.currentTimeMillis()
                        )
                    )
                    newlyIndexed++
                }
            }
            if (batch.isNotEmpty()) {
                bookDao.upsertPreservingProgress(batch)
            }
            val count = bookDao.countBooksInFolder(dirPath)
            folderDao.updateSyncStats(dirPath, System.currentTimeMillis(), count)
        }
    }

    /**
     * Automatic whole-device EPUB discovery through MediaStore.
     *
     * Books are grouped by their full RELATIVE_PATH so that two folders with the
     * same leaf name (Books/Fiction and Downloads/Fiction) stay distinct, and are
     * stored as content:// URIs with isDirectPath = false, so every read goes
     * through ContentResolver rather than a raw file path.
     */
    private suspend fun scanMediaStoreEpubs() {
        val collection = MediaStore.Files.getContentUri("external")
        val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.FileColumns.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.FileColumns.DATA
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            relPathCol
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("application/epub+zip", "%.epub")

        val grouped = linkedMapOf<String, MutableList<MediaStoreBookCandidate>>()
        var scanned = 0

        runCatching {
            context.contentResolver
                .query(collection, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                    val modIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    val pathIdx = cursor.getColumnIndex(relPathCol)

                    while (cursor.moveToNext()) {
                        val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        if (name.isBlank() || !name.endsWith(".epub", ignoreCase = true)) continue
                        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                        val mod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) * 1000L else 0L
                        val rawPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                        // Full relative path keeps same-named folders distinct.
                        val relativePath = rawPath.trim('/')
                        val folderKey = if (relativePath.isBlank()) {
                            "${MEDIASTORE_FOLDER_PREFIX}Device storage"
                        } else {
                            "$MEDIASTORE_FOLDER_PREFIX$relativePath"
                        }
                        val displayName = relativePath.substringAfterLast('/', "Device storage")
                            .ifBlank { "Device storage" }
                        grouped.getOrPut(folderKey) { mutableListOf() }
                            .add(
                                MediaStoreBookCandidate(
                                    uri = ContentUris.withAppendedId(collection, id),
                                    displayName = name,
                                    folderDisplayName = displayName,
                                    sizeBytes = size,
                                    lastModified = mod
                                )
                            )
                        scanned++
                    }
                }
        }

        if (grouped.isEmpty()) return

        // Suppress files already indexed through a SAF folder or a direct path.
        val fingerprints = bookDao.getAllBooksOnce()
            .filterNot { it.folderKey.startsWith(MEDIASTORE_FOLDER_PREFIX) }
            .map { fingerprint(it.fileSizeBytes, it.title) }
            .toMutableSet()

        var newlyIndexed = 0
        for ((folderKey, items) in grouped) {
            val folderName = items.first().folderDisplayName
            folderDao.upsertFolder(
                WatchedFolderEntity(
                    uriOrPath = folderKey,
                    displayName = folderName,
                    isDirectPath = false,
                    addedAt = System.currentTimeMillis()
                )
            )
            val existingMap = bookDao.getBooksForFolder(folderKey).associateBy { it.fileKey }
            val batch = mutableListOf<BookEntity>()
            for (item in items) {
                val fileKey = item.uri.toString()
                val existing = existingMap[fileKey]
                val unchanged = existing != null &&
                    existing.lastModified == item.lastModified &&
                    existing.fileSizeBytes == item.sizeBytes &&
                    existing.parentFolderName.isNotBlank() &&
                    (existing.coverPath == null || File(existing.coverPath).exists())
                if (unchanged) continue

                _scanProgress.value = _scanProgress.value.copy(
                    isScanning = true,
                    scannedFiles = scanned,
                    newlyIndexed = newlyIndexed,
                    currentFolderName = folderName,
                    currentFileName = item.displayName
                )
                val meta = EpubParser.extractMetadataForIndexing(
                    context = context,
                    fileKey = fileKey,
                    isDirectPath = false,
                    fallbackFileName = item.displayName
                )
                if (!fingerprints.add(fingerprint(item.sizeBytes, meta.title))) continue
                batch.add(
                    BookEntity(
                        fileKey = fileKey,
                        folderKey = folderKey,
                        parentFolderName = folderName,
                        isDirectPath = false,
                        title = meta.title,
                        author = meta.author,
                        language = meta.language,
                        coverPath = meta.coverPath,
                        chapterCount = meta.chapterCount,
                        fileSizeBytes = item.sizeBytes,
                        lastModified = item.lastModified,
                        addedAt = existing?.addedAt ?: System.currentTimeMillis()
                    )
                )
                newlyIndexed++
            }
            if (batch.isNotEmpty()) {
                bookDao.upsertPreservingProgress(batch)
            }
            val count = bookDao.countBooksInFolder(folderKey)
            folderDao.updateSyncStats(folderKey, System.currentTimeMillis(), count)
        }
    }

    /** A single EPUB row discovered through MediaStore. */
    private data class MediaStoreBookCandidate(
        val uri: Uri,
        val displayName: String,
        val folderDisplayName: String,
        val sizeBytes: Long,
        val lastModified: Long
    )

    suspend fun syncAllFolders() = withContext(Dispatchers.IO) {
        val all = folderDao.getAllFolders()
        if (all.isEmpty()) return@withContext
        _scanProgress.value = ScanProgressState(isScanning = true)
        try {
            for (folder in all) {
                syncFolderInternal(folder)
            }
        } finally {
            _scanProgress.value = ScanProgressState(isScanning = false)
        }
    }

    suspend fun syncSingleFolder(folder: WatchedFolderEntity) = withContext(Dispatchers.IO) {
        _scanProgress.value = ScanProgressState(isScanning = true, currentFolderName = folder.displayName)
        try {
            syncFolderInternal(folder)
        } finally {
            _scanProgress.value = ScanProgressState(isScanning = false)
        }
    }

    private suspend fun syncFolderInternal(folder: WatchedFolderEntity) {
        val existingMap = bookDao.getBooksForFolder(folder.uriOrPath).associateBy { it.fileKey }
        val activeKeys = mutableListOf<String>()
        val batch = mutableListOf<BookEntity>()
        var scanned = 0
        var newlyIndexed = 0

        suspend fun processDiscoveredFile(
            fileKey: String,
            fileName: String,
            size: Long,
            lastModified: Long,
            isDirectPath: Boolean
        ) {
            activeKeys.add(fileKey)
            scanned++
            val existing = existingMap[fileKey]
            val unchanged = existing != null &&
                existing.lastModified == lastModified &&
                existing.fileSizeBytes == size &&
                (existing.coverPath == null || File(existing.coverPath).exists())

            if (!unchanged) {
                _scanProgress.value = _scanProgress.value.copy(
                    isScanning = true,
                    scannedFiles = scanned,
                    newlyIndexed = newlyIndexed,
                    currentFolderName = folder.displayName,
                    currentFileName = fileName
                )
                val meta = EpubParser.extractMetadataForIndexing(
                    context = context,
                    fileKey = fileKey,
                    isDirectPath = isDirectPath,
                    fallbackFileName = fileName
                )
                batch.add(
                    BookEntity(
                        fileKey = fileKey,
                        folderKey = folder.uriOrPath,
                        parentFolderName = folder.displayName,
                        isDirectPath = isDirectPath,
                        title = meta.title,
                        author = meta.author,
                        language = meta.language,
                        coverPath = meta.coverPath,
                        chapterCount = meta.chapterCount,
                        fileSizeBytes = size,
                        lastModified = lastModified,
                        addedAt = existing?.addedAt ?: System.currentTimeMillis()
                    )
                )
                newlyIndexed++
                if (batch.size >= 150) {
                    bookDao.upsertPreservingProgress(batch.toList())
                    batch.clear()
                }
            } else if (scanned % 100 == 0) {
                _scanProgress.value = _scanProgress.value.copy(
                    isScanning = true,
                    scannedFiles = scanned,
                    newlyIndexed = newlyIndexed,
                    currentFolderName = folder.displayName,
                    currentFileName = fileName
                )
            }
        }

        if (folder.isDirectPath) {
            val root = File(folder.uriOrPath)
            if (root.exists() && root.isDirectory) {
                root.walkTopDown()
                    .onEnter { dir -> !dir.name.startsWith(".") }
                    .filter { it.isFile && it.name.endsWith(".epub", ignoreCase = true) }
                    .forEach { file ->
                        processDiscoveredFile(
                            fileKey = file.absolutePath,
                            fileName = file.name,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            isDirectPath = true
                        )
                    }
            }
        } else {
            val treeUri = Uri.parse(folder.uriOrPath)
            val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            if (rootDocId != null) {
                traverseSafTreeFast(treeUri, rootDocId) { docUri, name, size, lastMod ->
                    processDiscoveredFile(
                        fileKey = docUri.toString(),
                        fileName = name,
                        size = size,
                        lastModified = lastMod,
                        isDirectPath = false
                    )
                }
            }
        }

        if (batch.isNotEmpty()) {
            bookDao.upsertPreservingProgress(batch.toList())
            batch.clear()
        }

        if (activeKeys.isEmpty()) {
            bookDao.deleteAllInFolder(folder.uriOrPath)
        } else if (activeKeys.size <= 900) {
            bookDao.deleteMissingInFolder(folder.uriOrPath, activeKeys)
        } else {
            val activeSet = activeKeys.toHashSet()
            existingMap.values.filter { it.fileKey !in activeSet }.forEach {
                bookDao.deleteBookById(it.id)
            }
        }

        val count = bookDao.countBooksInFolder(folder.uriOrPath)
        folderDao.updateSyncStats(folder.uriOrPath, System.currentTimeMillis(), count)
    }

    private suspend fun traverseSafTreeFast(
        treeUri: Uri,
        parentDocId: String,
        onEpubFound: suspend (Uri, String, Long, Long) -> Unit
    ) {
        val queue = ArrayDeque<String>()
        queue.add(parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        while (queue.isNotEmpty()) {
            val docId = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            runCatching {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idIdx) ?: continue
                        val name = cursor.getString(nameIdx).orEmpty()
                        val mime = cursor.getString(mimeIdx).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.startsWith(".")) queue.addLast(childId)
                        } else if (name.endsWith(".epub", ignoreCase = true) ||
                            mime.equals("application/epub+zip", ignoreCase = true)
                        ) {
                            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                            val lastMod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            onEpubFound(docUri, name, size, lastMod)
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Folder-key prefix for books discovered through MediaStore. */
        const val MEDIASTORE_FOLDER_PREFIX = "mediastore://"
        const val SINGLE_IMPORTS_FOLDER = "single_imports"
    }
}
