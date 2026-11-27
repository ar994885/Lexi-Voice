package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class LexiTypeConverters {
    @TypeConverter
    fun fromPronunciationScope(scope: PronunciationScope): String = scope.name

    @TypeConverter
    fun toPronunciationScope(value: String): PronunciationScope =
        runCatching { PronunciationScope.valueOf(value) }.getOrDefault(PronunciationScope.GLOBAL)
}

@Database(
    entities = [
        WatchedFolderEntity::class,
        BookEntity::class,
        AudiobookEntity::class,
        PronunciationRuleEntity::class,
        BookmarkEntity::class,
        ShelfEntity::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(LexiTypeConverters::class)
abstract class LexiDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun folderDao(): FolderDao
    abstract fun audiobookDao(): AudiobookDao
    abstract fun pronunciationRuleDao(): PronunciationRuleDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun shelfDao(): ShelfDao

    companion object {
        @Volatile
        private var INSTANCE: LexiDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audiobooks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fileUri` TEXT NOT NULL,
                        `isDirectPath` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `narrator` TEXT NOT NULL,
                        `year` TEXT NOT NULL,
                        `genre` TEXT NOT NULL,
                        `coverPath` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `chapterCount` INTEGER NOT NULL,
                        `chaptersJson` TEXT NOT NULL,
                        `sourceEpubKey` TEXT,
                        `sourceEpubIsDirect` INTEGER NOT NULL,
                        `syncJsonPath` TEXT,
                        `lastPositionMs` INTEGER NOT NULL,
                        `lastPlayedAt` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_audiobooks_fileUri` ON `audiobooks` (`fileUri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audiobooks_lastPlayedAt` ON `audiobooks` (`lastPlayedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audiobooks_addedAt` ON `audiobooks` (`addedAt`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `audiobooks` ADD COLUMN `isFolderAudiobook` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `audiobooks` ADD COLUMN `folderUri` TEXT")
                db.execSQL("ALTER TABLE `audiobooks` ADD COLUMN `chapterFilesJson` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pronunciation_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `findText` TEXT NOT NULL,
                        `replaceWith` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `bookId` INTEGER,
                        `matchCase` INTEGER NOT NULL,
                        `wholeWordOnly` INTEGER NOT NULL,
                        `isEnabled` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pronunciation_rules_scope` ON `pronunciation_rules` (`scope`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pronunciation_rules_bookId` ON `pronunciation_rules` (`bookId`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `shelfId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `isHidden` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `isFinished` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bookmarks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` INTEGER NOT NULL,
                        `chapterIndex` INTEGER NOT NULL,
                        `sentenceIndex` INTEGER NOT NULL,
                        `selectedText` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `colorHex` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_book_chap_sent` ON `bookmarks` (`bookId`, `chapterIndex`, `sentenceIndex`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shelves` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `parentFolderName` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pronunciation_rules` ADD COLUMN `applyToAudio` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `pronunciation_rules` ADD COLUMN `applyToReaderText` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `startOffset` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `endOffset` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `noteImage` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `highlightAll` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pronunciation_rules` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'TTS'")
                db.execSQL("ALTER TABLE `pronunciation_rules` ADD COLUMN `termScope` TEXT NOT NULL DEFAULT 'BOOK'")
                db.execSQL("ALTER TABLE `pronunciation_rules` ADD COLUMN `sentenceIndex` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pronunciation_rules` ADD COLUMN `startOffset` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pronunciation_rules` ADD COLUMN `endOffset` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): LexiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LexiDatabase::class.java,
                    "lexivoice_library.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
