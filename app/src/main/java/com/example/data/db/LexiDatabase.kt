package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WatchedFolderEntity::class, BookEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LexiDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun folderDao(): FolderDao

    companion object {
        @Volatile
        private var INSTANCE: LexiDatabase? = null

        fun getInstance(context: Context): LexiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LexiDatabase::class.java,
                    "lexivoice_library.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
