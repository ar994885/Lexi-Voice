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
import com.example.data.db.WatchedFolderEntity
import com.example.data.repo.ScanProgressState
import com.example.library.FolderSyncWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LexiVoiceApplication
    private val libraryRepo = app.libraryRepository
    private val prefsRepo = app.preferencesRepository

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedFolderFilter = MutableStateFlow<String?>(null)
    val selectedFolderFilter: StateFlow<String?> = _selectedFolderFilter

    private val _sortOption = MutableStateFlow(LibrarySortOption.RECENT)
    val sortOption: StateFlow<LibrarySortOption> = _sortOption

    val isGridView: StateFlow<Boolean> = prefsRepo.isGridView
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val folders: StateFlow<List<WatchedFolderEntity>> = libraryRepo.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentBooks: StateFlow<List<BookEntity>> = libraryRepo.recentBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalBookCount: StateFlow<Int> = libraryRepo.totalBookCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val scanProgress: StateFlow<ScanProgressState> = libraryRepo.scanProgress

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val libraryBooks: StateFlow<List<BookEntity>> = combine(
        _searchQuery.debounce(180),
        _selectedFolderFilter,
        _sortOption
    ) { query, folder, sort ->
        Triple(query, folder, sort)
    }.flatMapLatest { (query, folder, sort) ->
        libraryRepo.observeLibraryBooks(query, folder, sort)
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

    fun setSortOption(option: LibrarySortOption) {
        _sortOption.value = option
    }

    fun toggleGridView() {
        viewModelScope.launch {
            prefsRepo.setGridView(!isGridView.value)
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
}
