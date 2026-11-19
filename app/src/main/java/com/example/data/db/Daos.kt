package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

enum class LibrarySortOption(val label: String) {
    RECENT("Recent"),
    TITLE("Title (A–Z)"),
    AUTHOR("Author (A–Z)"),
    DATE_ADDED("Date Added"),
    PROGRESS_ASC("Progress (0% → 100%)"),
    PROGRESS_DESC("Progress (100% → 0%)")
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM watched_folders ORDER BY addedAt DESC")
    fun observeFolders(): Flow<List<WatchedFolderEntity>>

    @Query("SELECT * FROM watched_folders")
    suspend fun getAllFolders(): List<WatchedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: WatchedFolderEntity)

    @Query("UPDATE watched_folders SET lastSyncedAt = :timestamp, bookCount = :count WHERE uriOrPath = :uriOrPath")
    suspend fun updateSyncStats(uriOrPath: String, timestamp: Long, count: Int)

    @Query("DELETE FROM watched_folders WHERE uriOrPath = :uriOrPath")
    suspend fun deleteFolder(uriOrPath: String)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 AND isHidden = 0 ORDER BY lastOpenedAt DESC LIMIT 12")
    fun observeRecentBooks(): Flow<List<BookEntity>>

    @Query("SELECT COUNT(*) FROM books WHERE isHidden = 0")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBookById(bookId: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    fun observeBookById(bookId: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE fileKey = :fileKey LIMIT 1")
    suspend fun getBookByFileKey(fileKey: String): BookEntity?

    @Query("SELECT * FROM books WHERE folderKey = :folderKey")
    suspend fun getBooksForFolder(folderKey: String): List<BookEntity>

    @Query("SELECT * FROM books")
    suspend fun getAllBooksOnce(): List<BookEntity>

    @Query("SELECT COUNT(*) FROM books WHERE folderKey = :folderKey")
    suspend fun countBooksInFolder(folderKey: String): Int

    @Query("SELECT DISTINCT language FROM books WHERE language != '' ORDER BY language ASC")
    fun observeDistinctLanguages(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBooksIgnore(books: List<BookEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookEntity): Long

    @Query(
        """
        UPDATE books SET
            parentFolderName = :parentFolderName,
            title = :title,
            author = :author,
            language = :language,
            coverPath = :coverPath,
            chapterCount = :chapterCount,
            fileSizeBytes = :fileSizeBytes,
            lastModified = :lastModified
        WHERE fileKey = :fileKey
        """
    )
    suspend fun updateBookMetadata(
        fileKey: String,
        parentFolderName: String,
        title: String,
        author: String,
        language: String,
        coverPath: String?,
        chapterCount: Int,
        fileSizeBytes: Long,
        lastModified: Long
    )

    @Transaction
    suspend fun upsertPreservingProgress(books: List<BookEntity>) {
        for (book in books) {
            val inserted = insertBooksIgnore(listOf(book)).firstOrNull() ?: -1L
            if (inserted == -1L) {
                updateBookMetadata(
                    fileKey = book.fileKey,
                    parentFolderName = book.parentFolderName,
                    title = book.title,
                    author = book.author,
                    language = book.language,
                    coverPath = book.coverPath,
                    chapterCount = book.chapterCount,
                    fileSizeBytes = book.fileSizeBytes,
                    lastModified = book.lastModified
                )
            }
        }
    }

    @Query(
        """
        UPDATE books SET
            currentChapterIndex = :chapterIndex,
            currentSentenceIndex = :sentenceIndex,
            scrollOffset = :scrollOffset,
            progressPercent = :progressPercent,
            lastOpenedAt = :lastOpenedAt,
            isFinished = CASE WHEN :progressPercent >= 0.99 THEN 1 ELSE isFinished END
        WHERE id = :bookId
        """
    )
    suspend fun updateReadingProgress(
        bookId: Long,
        chapterIndex: Int,
        sentenceIndex: Int,
        scrollOffset: Int,
        progressPercent: Float,
        lastOpenedAt: Long
    )

    @Query("UPDATE books SET shelfId = :shelfId WHERE id = :bookId")
    suspend fun updateBookShelf(bookId: Long, shelfId: Long)

    @Query("UPDATE books SET isFinished = :isFinished WHERE id = :bookId")
    suspend fun updateBookFinished(bookId: Long, isFinished: Boolean)

    @Query("UPDATE books SET isHidden = :isHidden WHERE id = :bookId")
    suspend fun updateBookHidden(bookId: Long, isHidden: Boolean)

    @Query("DELETE FROM books WHERE folderKey = :folderKey AND fileKey NOT IN (:activeKeys)")
    suspend fun deleteMissingInFolder(folderKey: String, activeKeys: List<String>)

    @Query("DELETE FROM books WHERE folderKey = :folderKey")
    suspend fun deleteAllInFolder(folderKey: String)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: Long)

    @RawQuery(observedEntities = [BookEntity::class])
    fun observeFilteredBooks(query: SupportSQLiteQuery): Flow<List<BookEntity>>

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

    fun observeLibraryBooks(
        searchQuery: String = "",
        folderFilter: String? = null,
        shelfId: Long? = null,
        progressFilter: String? = null,
        languageFilter: String? = null,
        showHidden: Boolean = false,
        sortOption: LibrarySortOption = LibrarySortOption.RECENT
    ): Flow<List<BookEntity>> {
        val args = mutableListOf<Any>()
        val whereClauses = mutableListOf<String>()

        if (showHidden) {
            whereClauses += "isHidden = 1"
        } else {
            whereClauses += "isHidden = 0"
        }

        if (!folderFilter.isNullOrBlank()) {
            whereClauses += "folderKey = ?"
            args += folderFilter
        }

        if (shelfId != null) {
            whereClauses += "shelfId = ?"
            args += shelfId
        }

        if (!languageFilter.isNullOrBlank()) {
            whereClauses += "language = ?"
            args += languageFilter
        }

        when (progressFilter) {
            "NOT_STARTED" -> whereClauses += "progressPercent = 0.0 AND isFinished = 0"
            "IN_PROGRESS" -> whereClauses += "progressPercent > 0.0 AND progressPercent < 0.99 AND isFinished = 0"
            "FINISHED" -> whereClauses += "(isFinished = 1 OR progressPercent >= 0.99)"
        }

        val trimmed = searchQuery.trim()
        if (trimmed.isNotEmpty()) {
            whereClauses += "(title LIKE ? OR author LIKE ?)"
            val pattern = "%$trimmed%"
            args += pattern
            args += pattern
        }

        val whereSql = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"
        val orderSql = when (sortOption) {
            LibrarySortOption.RECENT -> "ORDER BY CASE WHEN lastOpenedAt > 0 THEN 0 ELSE 1 END, lastOpenedAt DESC, addedAt DESC"
            LibrarySortOption.TITLE -> "ORDER BY title COLLATE NOCASE ASC"
            LibrarySortOption.AUTHOR -> "ORDER BY author COLLATE NOCASE ASC, title COLLATE NOCASE ASC"
            LibrarySortOption.DATE_ADDED -> "ORDER BY addedAt DESC"
            LibrarySortOption.PROGRESS_ASC -> "ORDER BY progressPercent ASC, title COLLATE NOCASE ASC"
            LibrarySortOption.PROGRESS_DESC -> "ORDER BY progressPercent DESC, title COLLATE NOCASE ASC"
        }

        val sql = "SELECT * FROM books $whereSql $orderSql"
        return observeFilteredBooks(SimpleSQLiteQuery(sql, args.toTypedArray()))
    }
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex ASC, sentenceIndex ASC")
    fun observeBookmarksForBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAllBookmarksOnce(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE bookId = :bookId AND chapterIndex = :chapterIndex AND type = 'BOOKMARK')")
    suspend fun hasBookmarkForChapter(bookId: Long, chapterIndex: Int): Boolean

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND chapterIndex = :chapterIndex AND type = 'BOOKMARK'")
    suspend fun deleteBookmarkForChapter(bookId: Long, chapterIndex: Int)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND chapterIndex = :chapterIndex AND sentenceIndex = :sentenceIndex")
    suspend fun deleteBookmarkBySentence(bookId: Long, chapterIndex: Int, sentenceIndex: Int)
}

@Dao
interface ShelfDao {
    @Query("SELECT * FROM shelves ORDER BY createdAt ASC")
    fun observeShelves(): Flow<List<ShelfEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShelf(shelf: ShelfEntity): Long

    @Query("DELETE FROM shelves WHERE id = :id")
    suspend fun deleteShelf(id: Long)
}

@Dao
interface AudiobookDao {
    @Query("SELECT * FROM audiobooks ORDER BY CASE WHEN lastPlayedAt > 0 THEN 0 ELSE 1 END, lastPlayedAt DESC, addedAt DESC")
    fun observeAllAudiobooks(): Flow<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks WHERE id = :id LIMIT 1")
    suspend fun getAudiobookById(id: Long): AudiobookEntity?

    @Query("SELECT * FROM audiobooks WHERE id = :id LIMIT 1")
    fun observeAudiobookById(id: Long): Flow<AudiobookEntity?>

    @Query("SELECT * FROM audiobooks WHERE fileUri = :fileUri LIMIT 1")
    suspend fun getAudiobookByUri(fileUri: String): AudiobookEntity?

    @Query("SELECT * FROM audiobooks WHERE sourceEpubKey = :fileKey ORDER BY lastPlayedAt DESC, addedAt DESC")
    fun observeAudiobooksBySourceEpubKey(fileKey: String): Flow<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks WHERE sourceEpubKey = :fileKey ORDER BY lastPlayedAt DESC, addedAt DESC LIMIT 1")
    suspend fun getLatestAudiobookForEpub(fileKey: String): AudiobookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAudiobook(entity: AudiobookEntity): Long

    @Query(
        """
        UPDATE audiobooks SET
            isDirectPath = :isDirectPath,
            title = :title,
            author = :author,
            narrator = :narrator,
            year = :year,
            genre = :genre,
            coverPath = :coverPath,
            durationMs = :durationMs,
            chapterCount = :chapterCount,
            chaptersJson = :chaptersJson,
            sourceEpubKey = :sourceEpubKey,
            sourceEpubIsDirect = :sourceEpubIsDirect,
            syncJsonPath = :syncJsonPath,
            isFolderAudiobook = :isFolderAudiobook,
            folderUri = :folderUri,
            chapterFilesJson = :chapterFilesJson
        WHERE id = :id
        """
    )
    suspend fun updateAudiobookContent(
        id: Long,
        isDirectPath: Boolean,
        title: String,
        author: String,
        narrator: String,
        year: String,
        genre: String,
        coverPath: String?,
        durationMs: Long,
        chapterCount: Int,
        chaptersJson: String,
        sourceEpubKey: String?,
        sourceEpubIsDirect: Boolean,
        syncJsonPath: String?,
        isFolderAudiobook: Boolean,
        folderUri: String?,
        chapterFilesJson: String
    )

    @Transaction
    suspend fun upsertAudiobookPreservingProgress(entity: AudiobookEntity): Long {
        val existing = getAudiobookByUri(entity.fileUri)
        return if (existing != null) {
            updateAudiobookContent(
                id = existing.id,
                isDirectPath = entity.isDirectPath,
                title = entity.title,
                author = entity.author,
                narrator = entity.narrator,
                year = entity.year,
                genre = entity.genre,
                coverPath = entity.coverPath ?: existing.coverPath,
                durationMs = entity.durationMs,
                chapterCount = entity.chapterCount,
                chaptersJson = entity.chaptersJson,
                sourceEpubKey = entity.sourceEpubKey ?: existing.sourceEpubKey,
                sourceEpubIsDirect = if (entity.sourceEpubKey != null) entity.sourceEpubIsDirect else existing.sourceEpubIsDirect,
                syncJsonPath = entity.syncJsonPath ?: existing.syncJsonPath,
                isFolderAudiobook = entity.isFolderAudiobook,
                folderUri = entity.folderUri ?: existing.folderUri,
                chapterFilesJson = entity.chapterFilesJson
            )
            existing.id
        } else {
            upsertAudiobook(entity)
        }
    }

    @Query("UPDATE audiobooks SET lastPositionMs = :positionMs, lastPlayedAt = :playedAt WHERE id = :id")
    suspend fun updatePlaybackProgress(id: Long, positionMs: Long, playedAt: Long)

    @Query("DELETE FROM audiobooks WHERE id = :id")
    suspend fun deleteAudiobook(id: Long)
}

@Dao
interface PronunciationRuleDao {
    @Query("SELECT * FROM pronunciation_rules ORDER BY CASE WHEN scope = 'BOOK' THEN 0 ELSE 1 END, id DESC")
    fun observeAllRules(): Flow<List<PronunciationRuleEntity>>

    @Query(
        """
        SELECT * FROM pronunciation_rules
        WHERE scope = 'GLOBAL' OR bookId = :bookId
        ORDER BY CASE WHEN scope = 'BOOK' THEN 0 ELSE 1 END, id DESC
        """
    )
    fun observeRulesForBook(bookId: Long): Flow<List<PronunciationRuleEntity>>

    @Query("SELECT * FROM pronunciation_rules ORDER BY CASE WHEN scope = 'BOOK' THEN 0 ELSE 1 END, id ASC")
    suspend fun getAllRulesOnce(): List<PronunciationRuleEntity>

    @Query(
        """
        SELECT * FROM pronunciation_rules
        WHERE isEnabled = 1 AND applyToAudio = 1 AND (scope = 'GLOBAL' OR (:bookId IS NOT NULL AND scope = 'BOOK' AND bookId = :bookId))
        ORDER BY CASE WHEN scope = 'BOOK' THEN 0 ELSE 1 END, id ASC
        """
    )
    suspend fun getEnabledRulesForPlayback(bookId: Long?): List<PronunciationRuleEntity>

    @Query(
        """
        SELECT * FROM pronunciation_rules
        WHERE isEnabled = 1 AND applyToReaderText = 1 AND (scope = 'GLOBAL' OR (:bookId IS NOT NULL AND scope = 'BOOK' AND bookId = :bookId))
        ORDER BY CASE WHEN scope = 'BOOK' THEN 0 ELSE 1 END, id ASC
        """
    )
    suspend fun getEnabledRulesForReaderDisplay(bookId: Long?): List<PronunciationRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: PronunciationRuleEntity): Long

    @androidx.room.Update
    suspend fun updateRule(rule: PronunciationRuleEntity)

    @Query("UPDATE pronunciation_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM pronunciation_rules WHERE id = :id")
    suspend fun deleteRule(id: Long)
}
