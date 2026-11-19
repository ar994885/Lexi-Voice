package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LexiVoiceApplication
import com.example.data.db.BookEntity
import com.example.data.db.LibrarySortOption
import com.example.data.db.ShelfEntity
import com.example.data.db.WatchedFolderEntity
import com.example.data.repo.ScanProgressState
import com.example.export.EpubExportResult
import com.example.export.EpubExporter
import com.example.library.FolderSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class LibraryFilterCriteria(
    val query: String,
    val folder: String?,
    val shelfId: Long?,
    val progress: String?,
    val language: String?,
    val showHidden: Boolean,
    val sort: LibrarySortOption
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LexiVoiceApplication
    private val libraryRepo = app.libraryRepository
    private val prefsRepo = app.preferencesRepository
    private val shelfDao = app.database.shelfDao()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedFolderFilter = MutableStateFlow<String?>(null)
    val selectedFolderFilter: StateFlow<String?> = _selectedFolderFilter

    private val _selectedShelfId = MutableStateFlow<Long?>(null)
    val selectedShelfId: StateFlow<Long?> = _selectedShelfId

    private val _progressFilter = MutableStateFlow<String?>(null)
    val progressFilter: StateFlow<String?> = _progressFilter

    private val _languageFilter = MutableStateFlow<String?>(null)
    val languageFilter: StateFlow<String?> = _languageFilter

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden

    private val _sortOption = MutableStateFlow(LibrarySortOption.RECENT)
    val sortOption: StateFlow<LibrarySortOption> = _sortOption

    val isGridView: StateFlow<Boolean> = prefsRepo.isGridView
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val gridColumns: StateFlow<Int> = prefsRepo.libraryGridColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    val showLibraryFolders: StateFlow<Boolean> = prefsRepo.readerSettings
        .map { it.showLibraryFolders }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val shelves: StateFlow<List<ShelfEntity>> = shelfDao.observeShelves()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val distinctLanguages: StateFlow<List<String>> = libraryRepo.distinctLanguages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders: StateFlow<List<WatchedFolderEntity>> = libraryRepo.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentBooks: StateFlow<List<BookEntity>> = libraryRepo.recentBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalBookCount: StateFlow<Int> = libraryRepo.totalBookCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val scanProgress: StateFlow<ScanProgressState> = libraryRepo.scanProgress

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val libraryBooks: StateFlow<List<BookEntity>> = combine(
        combine(_searchQuery.debounce(180), _selectedFolderFilter, _selectedShelfId) { q, f, s ->
            Triple(q, f, s)
        },
        combine(_progressFilter, _languageFilter, _showHidden, _sortOption) { p, l, h, so ->
            listOf(p, l, h, so)
        }
    ) { (query, folder, shelf), extras ->
        LibraryFilterCriteria(
            query = query,
            folder = folder,
            shelfId = shelf,
            progress = extras[0] as String?,
            language = extras[1] as String?,
            showHidden = extras[2] as Boolean,
            sort = extras[3] as LibrarySortOption
        )
    }.flatMapLatest { criteria ->
        libraryRepo.observeLibraryBooks(
            searchQuery = criteria.query,
            folderFilter = criteria.folder,
            shelfId = criteria.shelfId,
            progressFilter = criteria.progress,
            languageFilter = criteria.language,
            showHidden = criteria.showHidden,
            sortOption = criteria.sort
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            libraryRepo.syncAllFolders()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFolderFilter(folderKey: String?) {
        _selectedFolderFilter.value = folderKey
    }

    fun setShelfFilter(shelfId: Long?) {
        _selectedShelfId.value = shelfId
    }

    fun setProgressFilter(filter: String?) {
        _progressFilter.value = filter
    }

    fun setLanguageFilter(lang: String?) {
        _languageFilter.value = lang
    }

    fun setShowHidden(showHidden: Boolean) {
        _showHidden.value = showHidden
    }

    fun setSortOption(option: LibrarySortOption) {
        _sortOption.value = option
    }

    fun toggleGridView() {
        viewModelScope.launch {
            prefsRepo.setGridView(!isGridView.value)
        }
    }

    fun setGridColumns(cols: Int) {
        viewModelScope.launch {
            prefsRepo.setGridColumns(cols.coerceIn(2, 5))
        }
    }

    fun createShelf(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            shelfDao.insertShelf(ShelfEntity(name = trimmed))
        }
    }

    fun deleteShelf(shelfId: Long) {
        viewModelScope.launch {
            if (_selectedShelfId.value == shelfId) {
                _selectedShelfId.value = null
            }
            shelfDao.deleteShelf(shelfId)
        }
    }

    fun assignBookToShelf(bookId: Long, shelfId: Long) {
        viewModelScope.launch {
            libraryRepo.updateBookShelf(bookId, shelfId)
        }
    }

    fun setBookFinished(bookId: Long, finished: Boolean) {
        viewModelScope.launch {
            libraryRepo.updateBookFinished(bookId, finished)
        }
    }

    fun setBookHidden(bookId: Long, hidden: Boolean) {
        viewModelScope.launch {
            libraryRepo.updateBookHidden(bookId, hidden)
        }
    }

    fun deleteBook(book: BookEntity, deleteFilePermanently: Boolean) {
        viewModelScope.launch {
            libraryRepo.deleteBook(book, deleteFilePermanently)
        }
    }

    fun scanDeviceForEpubs() {
        viewModelScope.launch {
            libraryRepo.scanDeviceForEpubs()
        }
    }

    fun addSafFolder(treeUri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModelScope.launch {
            libraryRepo.addSafFolderAndScan(treeUri)
        }
    }

    fun addDirectFolderPath(path: String) {
        val dir = File(path.trim())
        if (!dir.exists() || !dir.isDirectory) return
        viewModelScope.launch {
            libraryRepo.addDirectFolderAndScan(dir)
        }
    }

    fun importSingleEpub(uri: Uri, onImported: (Long) -> Unit) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModelScope.launch {
            val book = libraryRepo.importSingleEpubUri(uri)
            if (book != null && book.id > 0L) {
                onImported(book.id)
            }
        }
    }

    fun removeFolder(uriOrPath: String) {
        viewModelScope.launch {
            if (_selectedFolderFilter.value == uriOrPath) {
                _selectedFolderFilter.value = null
            }
            libraryRepo.removeFolder(uriOrPath)
        }
    }

    fun syncNow() {
        FolderSyncWorker.triggerImmediate(app)
        viewModelScope.launch {
            libraryRepo.syncAllFolders()
        }
    }

    fun commonStorageDirectories(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        return listOf(
            File(ext, "Books"),
            File(ext, "Download"),
            File(ext, "Documents"),
            ext
        ).distinctBy { it.absolutePath }
    }

    fun exportEditedEpubForBook(
        book: BookEntity,
        destinationUri: Uri,
        onResult: (EpubExportResult) -> Unit
    ) {
        viewModelScope.launch {
            val visualRules = withContext(Dispatchers.IO) {
                app.pronunciationRepository.getEnabledRulesForReaderDisplay(book.id)
            }
            val result = EpubExporter.exportEditedEpubToUri(
                context = app,
                sourceFileKey = book.fileKey,
                isDirectPath = book.isDirectPath,
                bookId = book.id,
                visualRules = visualRules,
                destinationUri = destinationUri
            )
            onResult(result)
        }
    }

    fun importEditedCopyToLibrary(uri: Uri, onResult: (BookEntity?) -> Unit) {
        viewModelScope.launch {
            val imported = libraryRepo.importSingleEpubUri(uri)
            onResult(imported)
        }
    }
}
