package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LexiVoiceApplication
import com.example.data.db.AudiobookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AudiobooksViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LexiVoiceApplication
    private val repo = app.audiobookRepository

    val audiobooks: StateFlow<List<AudiobookEntity>> = repo.audiobooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun scanFoldersForM4b() {
        viewModelScope.launch {
            _isScanning.value = true
            runCatching { repo.scanFoldersForM4b() }
            _isScanning.value = false
        }
    }

    fun importM4bUri(uri: Uri, onImported: (Long) -> Unit) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModelScope.launch {
            val entity = repo.importM4bUri(uri)
            if (entity != null && entity.id > 0L) {
                onImported(entity.id)
            }
        }
    }

    fun deleteAudiobook(id: Long) {
        viewModelScope.launch {
            repo.deleteAudiobook(id)
        }
    }
}
