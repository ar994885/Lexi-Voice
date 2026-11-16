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
    TITLE("Title"),
    AUTHOR("Author"),
    DATE_ADDED("Date Added")
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
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 ORDER BY lastOpenedAt DESC LIMIT 12")
    fun observeRecentBooks(): Flow<List<BookEntity>>

    @Query("SELECT COUNT(*) FROM books")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBookById(bookId: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    fun observeBookById(bookId: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE fileKey = :fileKey LIMIT 1")
    suspend fun getBookByFileKey(fileKey: String): BookEntity?

    @Query("SELECT * FROM books WHERE folderKey = :folderKey")
    suspend fun getBooksForFolder(folderKey: String): List<BookEntity>

    @Query("SELECT COUNT(*) FROM books WHERE folderKey = :folderKey")
    suspend fun countBooksInFolder(folderKey: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBooksIgnore(books: List<BookEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookEntity): Long

    @Query(
        """
        UPDATE books SET
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
            lastOpenedAt = :lastOpenedAt
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
    ): Flow<List<BookEntity>> {
        val args = mutableListOf<Any>()
        val whereClauses = mutableListOf<String>()

        if (!folderFilter.isNullOrBlank()) {
            whereClauses += "folderKey = ?"
            args += folderFilter
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
        }

        val sql = "SELECT * FROM books $whereSql $orderSql"
        return observeFilteredBooks(SimpleSQLiteQuery(sql, args.toTypedArray()))
    }
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAudiobook(entity: AudiobookEntity): Long

    @Query("UPDATE audiobooks SET lastPositionMs = :positionMs, lastPlayedAt = :playedAt WHERE id = :id")
    suspend fun updatePlaybackProgress(id: Long, positionMs: Long, playedAt: Long)

    @Query("DELETE FROM audiobooks WHERE id = :id")
    suspend fun deleteAudiobook(id: Long)
}
