package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WatchedFolderEntity::class, BookEntity::class, AudiobookEntity::class],
    version = 2,
    exportSchema = false
)
abstract class LexiDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun folderDao(): FolderDao
    abstract fun audiobookDao(): AudiobookDao

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

        fun getInstance(context: Context): LexiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LexiDatabase::class.java,
                    "lexivoice_library.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
