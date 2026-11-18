package com.example.data.repo

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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

    suspend fun scanDeviceForEpubs() = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        val hasDirectAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            root.exists() && root.canRead()
        }
        _scanProgress.value = ScanProgressState(isScanning = true, currentFolderName = "Device Storage")
        try {
            val discoveredDirs = linkedMapOf<String, MutableList<File>>()
            var scanned = 0
            if (hasDirectAccess && root.exists() && root.isDirectory) {
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
                                currentFolderName = parent.name.ifBlank { "Device Storage" },
                                currentFileName = file.name
                            )
                        }
                    }
            }

            var newlyIndexed = 0
            if (discoveredDirs.isNotEmpty()) {
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
            } else {
                // MediaStore fallback when MANAGE_EXTERNAL_STORAGE is unavailable
                scanMediaStoreEpubs()
            }
        } finally {
            _scanProgress.value = ScanProgressState(isScanning = false)
        }
    }

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
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("application/epub+zip", "%.epub")

        val grouped = linkedMapOf<String, MutableList<Triple<Uri, String, Pair<Long, Long>>>>()
        var scanned = 0
        runCatching {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val modIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val pathIdx = cursor.getColumnIndex(relPathCol)

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    if (name.isBlank()) continue
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val mod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) * 1000L else 0L
                    val rawPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                    val folderName = rawPath.trim('/').substringAfterLast('/', "Device Storage")
                        .ifBlank { "Device Storage" }
                    val uri = ContentUris.withAppendedId(collection, id)
                    grouped.getOrPut(folderName) { mutableListOf() }
                        .add(Triple(uri, name, size to mod))
                    scanned++
                }
            }
        }

        var newlyIndexed = 0
        for ((folderName, items) in grouped) {
            val folderKey = "mediastore://$folderName"
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
            for ((uri, name, sizeAndMod) in items) {
                val (size, mod) = sizeAndMod
                val fileKey = uri.toString()
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
                        currentFolderName = folderName,
                        currentFileName = name
                    )
                    val meta = EpubParser.extractMetadataForIndexing(
                        context = context,
                        fileKey = fileKey,
                        isDirectPath = false,
                        fallbackFileName = name
                    )
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
            val count = bookDao.countBooksInFolder(folderKey)
            folderDao.updateSyncStats(folderKey, System.currentTimeMillis(), count)
        }
    }

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
        const val SINGLE_IMPORTS_FOLDER = "single_imports"
    }
}
