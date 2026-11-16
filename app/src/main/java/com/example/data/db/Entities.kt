package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "watched_folders")
data class WatchedFolderEntity(
    @PrimaryKey
    val uriOrPath: String,
    val displayName: String,
    val isDirectPath: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSyncedAt: Long = 0L,
    val bookCount: Int = 0
)

@Entity(
    tableName = "books",
    indices = [
        Index(value = ["fileKey"], unique = true),
        Index(value = ["folderKey"]),
        Index(value = ["title"]),
        Index(value = ["author"]),
        Index(value = ["lastOpenedAt"]),
        Index(value = ["addedAt"])
    ]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val fileKey: String,
    val folderKey: String,
    val isDirectPath: Boolean,
    val title: String,
    val author: String,
    val language: String = "",
    val coverPath: String? = null,
    val chapterCount: Int = 0,
    val fileSizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0L,
    val currentChapterIndex: Int = 0,
    val currentSentenceIndex: Int = 0,
    val scrollOffset: Int = 0,
    val progressPercent: Float = 0f
)

@Entity(
    tableName = "audiobooks",
    indices = [
        Index(value = ["fileUri"], unique = true),
        Index(value = ["lastPlayedAt"]),
        Index(value = ["addedAt"])
    ]
)
data class AudiobookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val fileUri: String,
    val isDirectPath: Boolean,
    val title: String,
    val author: String,
    val narrator: String = "LexiVoice TTS",
    val year: String = "",
    val genre: String = "Audiobook",
    val coverPath: String? = null,
    val durationMs: Long = 0L,
    val chapterCount: Int = 0,
    val chaptersJson: String = "[]",
    val sourceEpubKey: String? = null,
    val sourceEpubIsDirect: Boolean = false,
    val syncJsonPath: String? = null,
    val lastPositionMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val addedAt: Long = System.currentTimeMillis()
)
