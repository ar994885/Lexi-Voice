package com.example.data.repo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

    fun observeLibraryBooks(
        searchQuery: String,
        folderFilter: String?,
        sortOption: LibrarySortOption
    ): Flow<List<BookEntity>> = bookDao.observeLibraryBooks(searchQuery, folderFilter, sortOption)

    fun observeBook(bookId: Long): Flow<BookEntity?> = bookDao.observeBookById(bookId)

    suspend fun getBookById(bookId: Long): BookEntity? = bookDao.getBookById(bookId)

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
        totalChapters: Int
    ) = withContext(Dispatchers.IO) {
        val safeTotal = totalChapters.coerceAtLeast(1)
        val pct = ((chapterIndex + 1).toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
        bookDao.updateReadingProgress(
            bookId = bookId,
            chapterIndex = chapterIndex,
            sentenceIndex = sentenceIndex,
            scrollOffset = scrollOffset,
            progressPercent = pct,
            lastOpenedAt = System.currentTimeMillis()
        )
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
