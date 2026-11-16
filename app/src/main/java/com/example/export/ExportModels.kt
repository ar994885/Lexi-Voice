package com.example.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ExportChapterRequest(
    val index: Int,
    val originalTitle: String,
    val exportTitle: String,
    val href: String,
    val wordCount: Int,
    val estimatedMinutes: Int,
    val plainTextOverride: String? = null
)

data class M4aMetadataConfig(
    val enabled: Boolean = false,
    val keepMp3Files: Boolean = true,
    val title: String = "",
    val author: String = "",
    val album: String = "",
    val narrator: String = "LexiVoice TTS",
    val year: String = "2026",
    val genre: String = "Audiobook",
    val includeCover: Boolean = true
)

data class ExportBatchConfig(
    val sourceTitle: String,
    val bookFileKey: String,
    val isDirectPath: Boolean,
    val coverPath: String?,
    val outputTreeUri: String,
    val outputFolderName: String,
    val chapters: List<ExportChapterRequest>,
    val m4aMetadata: M4aMetadataConfig,
    val enginePackage: String = "",
    val voiceName: String = "",
    val localeTag: String = "",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f
)

data class ExportProgressState(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val isCancelled: Boolean = false,
    val errorMessage: String? = null,
    val currentChapterTitle: String = "",
    val currentChapterProgressPercent: Int = 0,
    val currentChapterNumber: Int = 0,
    val totalChapters: Int = 0,
    val batchProgressPercent: Int = 0,
    val exportedFilesCount: Int = 0,
    val outputFolderName: String = "",
    val m4aCreatedName: String? = null
)

object ExportStateHolder {
    private val _state = MutableStateFlow(ExportProgressState())
    val state: StateFlow<ExportProgressState> = _state.asStateFlow()

    @Volatile
    var pendingBatchConfig: ExportBatchConfig? = null

    fun update(transform: (ExportProgressState) -> ExportProgressState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = ExportProgressState()
    }
}
