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
        PronunciationRuleEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(LexiTypeConverters::class)
abstract class LexiDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun folderDao(): FolderDao
    abstract fun audiobookDao(): AudiobookDao
    abstract fun pronunciationRuleDao(): PronunciationRuleDao

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

        fun getInstance(context: Context): LexiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LexiDatabase::class.java,
                    "lexivoice_library.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
