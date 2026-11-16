package com.example.library

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.LexiVoiceApplication
import java.util.concurrent.TimeUnit

class FolderSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LexiVoiceApplication ?: return Result.failure()
        return try {
            app.libraryRepository.syncAllFolders()
            app.audiobookRepository.scanFoldersForM4b()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "lexivoice_periodic_folder_sync"
        private const val IMMEDIATE_WORK_NAME = "lexivoice_immediate_folder_sync"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<FolderSyncWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun triggerImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<FolderSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
