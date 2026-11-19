package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import com.example.data.db.LexiDatabase
import com.example.data.prefs.PreferencesRepository
import com.example.data.repo.AudiobookRepository
import com.example.data.repo.BackupSyncRepository
import com.example.data.repo.LibraryRepository
import com.example.data.repo.PronunciationRepository
import com.example.library.FolderSyncWorker
import com.example.overlay.FloatingOverlayController

class LexiVoiceApplication : Application(), Configuration.Provider, ImageLoaderFactory {

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
            preferencesRepository = preferencesRepository,
            libraryRepository = libraryRepository
        )
    }
    val backupSyncRepository: BackupSyncRepository by lazy {
        BackupSyncRepository(
            context = this,
            database = database,
            preferencesRepository = preferencesRepository
        )
    }
    val pronunciationRepository: PronunciationRepository by lazy {
        PronunciationRepository(
            dao = database.pronunciationRuleDao(),
            onDataChanged = { backupSyncRepository.scheduleAutoMirror() }
        )
    }

    /**
     * LexiVoice is offline-first: it has no INTERNET permission and every cover it
     * shows comes from a local File, a SAF content:// URI or a bundled resource.
     *
     * This ImageLoader enforces that at request level, so a cover href that
     * happens to be an http(s) URL inside an imported EPUB fails fast with a clear
     * error instead of silently attempting a socket that cannot exist.
     *
     * Note: Coil 2.7.0 declares OkHttp as an api dependency of coil-base, so
     * OkHttp cannot be excluded from the graph without replacing Coil itself.
     * R8 removes the parts that are never reached; the remaining bytes are the
     * price of the library. Moving to Coil 3 (where the network layer is a
     * separate artifact) would remove it entirely.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(LocalOnlyImageInterceptor())
            }
            .crossfade(true)
            .build()

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

            // The floating overlay is a foreground service so it survives while
            // the user is in other apps; this channel carries its persistent,
            // minimal-importance notification.
            val overlayChannel = NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.notification_channel_overlay),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the floating reader overlay available over other apps"
                setShowBadge(false)
            }

            manager.createNotificationChannel(ttsChannel)
            manager.createNotificationChannel(exportChannel)
            manager.createNotificationChannel(m4bChannel)
            manager.createNotificationChannel(overlayChannel)
        }
    }

    companion object {
        const val CHANNEL_TTS_PLAYBACK = "lexivoice_tts_playback"
        const val CHANNEL_AUDIO_EXPORT = "lexivoice_audio_export"
        const val CHANNEL_M4B_PLAYBACK = "lexivoice_m4b_playback"
        const val CHANNEL_OVERLAY = "lexivoice_overlay"
    }
}

/**
 * Rejects any image request that is not backed by a local source.
 *
 * Decorative and cover images in LexiVoice are always a File, a content:// or
 * resource Uri, a resource id, a Drawable or a Bitmap. A String is accepted only
 * when it is not an http(s) URL.
 */
private class LocalOnlyImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val isLocal = when (val data = request.data) {
            is java.io.File, is android.graphics.Bitmap, is android.graphics.drawable.Drawable -> true
            is android.net.Uri -> !data.toString().startsWith("http", ignoreCase = true)
            is Int -> true
            is String -> !data.startsWith("http://", ignoreCase = true) &&
                !data.startsWith("https://", ignoreCase = true)
            else -> false
        }
        if (!isLocal) {
            // Positional arguments: Coil declares drawable as nullable, and this
            // avoids depending on the parameter names of a library constructor.
            return ErrorResult(
                null,
                request,
                IllegalArgumentException(
                    "LexiVoice loads images from local sources only; refusing " +
                        request.data?.javaClass?.simpleName
                )
            )
        }
        return chain.proceed(request)
    }
}
