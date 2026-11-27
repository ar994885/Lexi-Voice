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
        Index(value = ["addedAt"]),
        Index(value = ["shelfId"]),
        Index(value = ["isHidden"]),
        Index(value = ["isFinished"])
    ]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val fileKey: String,
    val folderKey: String,
    val parentFolderName: String = "",
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
    val progressPercent: Float = 0f,
    val shelfId: Long = 0L,
    val isHidden: Boolean = false,
    val isFinished: Boolean = false
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
    val isFolderAudiobook: Boolean = false,
    val folderUri: String? = null,
    val chapterFilesJson: String = "[]",
    val lastPositionMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["bookId", "chapterIndex", "sentenceIndex"])
    ]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val bookId: Long,
    val chapterIndex: Int,
    val sentenceIndex: Int,
    val selectedText: String,
    val note: String = "",
    val colorHex: String = "#FFEB3B",
    val type: String = "HIGHLIGHT", // HIGHLIGHT or BOOKMARK
    val createdAt: Long = System.currentTimeMillis(),
    /** Character offsets inside the sentence span; 0,0 means "the whole sentence". */
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    /** Base64 JPEG, downscaled, or empty. Kept small enough for the backup JSON. */
    val noteImage: String = "",
    /** Highlight every occurrence of the selected word in this book, not just this one. */
    val highlightAll: Boolean = false
)

@Entity(tableName = "shelves")
data class ShelfEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

enum class PronunciationScope {
    GLOBAL,
    BOOK
}

@Entity(
    tableName = "pronunciation_rules",
    indices = [
        Index(value = ["scope"]),
        Index(value = ["bookId"])
    ]
)
data class PronunciationRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val findText: String,
    val replaceWith: String,
    val scope: PronunciationScope = PronunciationScope.GLOBAL,
    val bookId: Long? = null,
    val matchCase: Boolean = false,
    val wholeWordOnly: Boolean = true,
    val isEnabled: Boolean = true,
    val applyToAudio: Boolean = true,
    val applyToReaderText: Boolean = false,
    /** TTS = pronunciation rule. TERM = edited term shown in the reader and exported. */
    val kind: String = "TTS",
    /** WORD = only the anchored occurrence. BOOK = every occurrence in this book. */
    val termScope: String = "BOOK",
    /** Anchor for a WORD-scoped term: the sentence it was selected in, and the range. */
    val sentenceIndex: Int = 0,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    /** Zip path of the chapter this term is anchored in; empty for whole-book terms. */
    val chapterKey: String = "",
    /** How many occurrences of the original text precede the anchor in that chapter. */
    val occurrenceIndex: Int = 0
)
