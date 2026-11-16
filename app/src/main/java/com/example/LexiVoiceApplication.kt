package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.example.data.db.LexiDatabase
import com.example.data.prefs.PreferencesRepository
import com.example.data.repo.AudiobookRepository
import com.example.data.repo.LibraryRepository
import com.example.data.repo.PronunciationRepository
import com.example.library.FolderSyncWorker
import com.example.overlay.FloatingOverlayController

class LexiVoiceApplication : Application(), Configuration.Provider {

    val database: LexiDatabase by lazy { LexiDatabase.getInstance(this) }
    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(this) }
    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(
            context = this,
            bookDao = database.bookDao(),
            folderDao = database.folderDao()
        )
    }
    val audiobookRepository: AudiobookRepository by lazy {
        AudiobookRepository(
            context = this,
            audiobookDao = database.audiobookDao(),
            folderDao = database.folderDao(),
            preferencesRepository = preferencesRepository
        )
    }
    val pronunciationRepository: PronunciationRepository by lazy {
        PronunciationRepository(database.pronunciationRuleDao())
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        FloatingOverlayController.attachPreferences(preferencesRepository)
        runCatching {
            FolderSyncWorker.schedulePeriodic(this)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            val ttsChannel = NotificationChannel(
                CHANNEL_TTS_PLAYBACK,
                getString(R.string.notification_channel_tts),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent media controls for text-to-speech playback"
                setShowBadge(false)
            }

            val exportChannel = NotificationChannel(
                CHANNEL_AUDIO_EXPORT,
                getString(R.string.notification_channel_export),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time dual progress notifications for MP3 and M4B audiobook export"
                setShowBadge(false)
            }

            val m4bChannel = NotificationChannel(
                CHANNEL_M4B_PLAYBACK,
                getString(R.string.notification_channel_m4b),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background playback controls for M4B audiobooks"
                setShowBadge(false)
            }

            manager.createNotificationChannel(ttsChannel)
            manager.createNotificationChannel(exportChannel)
            manager.createNotificationChannel(m4bChannel)
        }
    }

    companion object {
        const val CHANNEL_TTS_PLAYBACK = "lexivoice_tts_playback"
        const val CHANNEL_AUDIO_EXPORT = "lexivoice_audio_export"
        const val CHANNEL_M4B_PLAYBACK = "lexivoice_m4b_playback"
    }
}
