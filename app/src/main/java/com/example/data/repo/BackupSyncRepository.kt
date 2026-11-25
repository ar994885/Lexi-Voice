package com.example.data.repo

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.example.data.db.BookmarkEntity
import com.example.data.db.LexiDatabase
import com.example.data.db.PronunciationRuleEntity
import com.example.data.db.PronunciationScope
import com.example.data.prefs.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

data class ImportBackupSummary(
    val addedRules: Int = 0,
    val skippedRules: Int = 0,
    val addedBookmarks: Int = 0,
    val skippedBookmarks: Int = 0,
    val restoredReadingPositions: Int = 0,
    val restoredAudiobookPositions: Int = 0
)

class BackupSyncRepository(
    private val context: Context,
    private val database: LexiDatabase,
    private val preferencesRepository: PreferencesRepository
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mirrorMutex = Mutex()

    suspend fun exportToJsonString(): String = withContext(Dispatchers.IO) {
        val books = database.bookDao().getAllBooksOnce()
        val booksById = books.associateBy { it.id }
        val rules = database.pronunciationRuleDao().getAllRulesOnce()
        val bookmarks = database.bookmarkDao().getAllBookmarksOnce()

        val root = JSONObject().apply {
            put("version", BACKUP_SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())

            val rulesArr = JSONArray()
            rules.forEach { rule ->
                val matchedBook = rule.bookId?.let { booksById[it] }
                rulesArr.put(
                    JSONObject().apply {
                        put("findText", rule.findText)
                        put("replaceWith", rule.replaceWith)
                        put("scope", rule.scope.name)
                        put("bookTitle", matchedBook?.title ?: JSONObject.NULL)
                        put("bookFileKey", matchedBook?.fileKey ?: JSONObject.NULL)
                        put("matchCase", rule.matchCase)
                        put("wholeWordOnly", rule.wholeWordOnly)
                        put("isEnabled", rule.isEnabled)
                        put("applyToAudio", rule.applyToAudio)
                        put("applyToReaderText", rule.applyToReaderText)
                    }
                )
            }
            put("pronunciationRules", rulesArr)

            val bookmarksArr = JSONArray()
            bookmarks.forEach { bm ->
                val matchedBook = booksById[bm.bookId]
                bookmarksArr.put(
                    JSONObject().apply {
                        put("bookTitle", matchedBook?.title ?: "")
                        put("bookFileKey", matchedBook?.fileKey ?: "")
                        put("chapterIndex", bm.chapterIndex)
                        put("sentenceIndex", bm.sentenceIndex)
                        put("selectedText", bm.selectedText)
                        put("note", bm.note)
                        put("colorHex", bm.colorHex)
                        put("type", bm.type)
                        put("createdAt", bm.createdAt)
                    }
                )
            }
            put("bookmarks", bookmarksArr)

            // Reading positions. Without these a restored backup brings back the
            // rules and highlights but every book still opens at page one.
            val progressArr = JSONArray()
            books.forEach { book ->
                val started = book.progressPercent > 0f ||
                    book.currentChapterIndex > 0 ||
                    book.currentSentenceIndex > 0 ||
                    book.isFinished
                if (!started) return@forEach
                progressArr.put(
                    JSONObject().apply {
                        put("title", book.title)
                        put("fileKey", book.fileKey)
                        put("chapterIndex", book.currentChapterIndex)
                        put("sentenceIndex", book.currentSentenceIndex)
                        put("scrollOffset", book.scrollOffset)
                        put("progressPercent", book.progressPercent.toDouble())
                        put("lastOpenedAt", book.lastOpenedAt)
                        put("isFinished", book.isFinished)
                    }
                )
            }
            put("readingProgress", progressArr)

            val audioProgressArr = JSONArray()
            database.audiobookDao().getAllAudiobooksOnce().forEach { audio ->
                if (audio.lastPositionMs <= 0L) return@forEach
                audioProgressArr.put(
                    JSONObject().apply {
                        put("title", audio.title)
                        put("fileUri", audio.fileUri)
                        put("sourceEpubKey", audio.sourceEpubKey ?: JSONObject.NULL)
                        put("positionMs", audio.lastPositionMs)
                        put("durationMs", audio.durationMs)
                        put("lastPlayedAt", audio.lastPlayedAt)
                    }
                )
            }
            put("audiobookProgress", audioProgressArr)
        }
        root.toString(2)
    }

    suspend fun importFromJsonString(rawJson: String): ImportBackupSummary = withContext(Dispatchers.IO) {
        val root = JSONObject(rawJson)
        val books = database.bookDao().getAllBooksOnce()
        val booksByFileKey = books.associateBy { it.fileKey }
        val booksByTitleLower = books.groupBy { it.title.trim().lowercase() }

        fun resolveBookId(fileKey: String?, title: String?): Long? {
            if (!fileKey.isNullOrBlank()) {
                booksByFileKey[fileKey]?.let { return it.id }
            }
            if (!title.isNullOrBlank()) {
                booksByTitleLower[title.trim().lowercase()]?.firstOrNull()?.let { return it.id }
            }
            return null
        }

        var addedRules = 0
        var skippedRules = 0
        val existingRules = database.pronunciationRuleDao().getAllRulesOnce().toMutableList()
        val rulesArr = root.optJSONArray("pronunciationRules") ?: JSONArray()
        for (i in 0 until rulesArr.length()) {
            val obj = rulesArr.optJSONObject(i) ?: continue
            val findText = obj.optString("findText").trim()
            val replaceWith = obj.optString("replaceWith").trim()
            if (findText.isEmpty() || replaceWith.isEmpty()) {
                skippedRules++
                continue
            }
            val scope = runCatching {
                PronunciationScope.valueOf(obj.optString("scope", PronunciationScope.GLOBAL.name))
            }.getOrDefault(PronunciationScope.GLOBAL)
            val bookFileKey = obj.optString("bookFileKey").takeIf { it.isNotBlank() && it != "null" }
            val bookTitle = obj.optString("bookTitle").takeIf { it.isNotBlank() && it != "null" }
            val resolvedBookId = if (scope == PronunciationScope.BOOK) {
                resolveBookId(bookFileKey, bookTitle)
            } else {
                null
            }
            if (scope == PronunciationScope.BOOK && resolvedBookId == null) {
                skippedRules++
                continue
            }
            val matchCase = obj.optBoolean("matchCase", false)
            val wholeWordOnly = obj.optBoolean("wholeWordOnly", true)
            val isEnabled = obj.optBoolean("isEnabled", true)
            val applyToAudio = obj.optBoolean("applyToAudio", true)
            val applyToReaderText = obj.optBoolean("applyToReaderText", false)

            val isDup = existingRules.any { existing ->
                existing.findText == findText &&
                    existing.replaceWith == replaceWith &&
                    existing.scope == scope &&
                    existing.bookId == resolvedBookId &&
                    existing.matchCase == matchCase &&
                    existing.wholeWordOnly == wholeWordOnly &&
                    existing.applyToAudio == applyToAudio &&
                    existing.applyToReaderText == applyToReaderText
            }
            if (isDup) {
                skippedRules++
                continue
            }
            val newRule = PronunciationRuleEntity(
                findText = findText,
                replaceWith = replaceWith,
                scope = scope,
                bookId = resolvedBookId,
                matchCase = matchCase,
                wholeWordOnly = wholeWordOnly,
                isEnabled = isEnabled,
                applyToAudio = applyToAudio,
                applyToReaderText = applyToReaderText
            )
            val insertedId = database.pronunciationRuleDao().insertRule(newRule)
            existingRules.add(newRule.copy(id = insertedId))
            addedRules++
        }

        var addedBookmarks = 0
        var skippedBookmarks = 0
        val existingBookmarks = database.bookmarkDao().getAllBookmarksOnce().toMutableList()
        val bookmarksArr = root.optJSONArray("bookmarks") ?: JSONArray()
        for (i in 0 until bookmarksArr.length()) {
            val obj = bookmarksArr.optJSONObject(i) ?: continue
            val bookFileKey = obj.optString("bookFileKey").takeIf { it.isNotBlank() && it != "null" }
            val bookTitle = obj.optString("bookTitle").takeIf { it.isNotBlank() && it != "null" }
            val resolvedBookId = resolveBookId(bookFileKey, bookTitle)
            if (resolvedBookId == null) {
                skippedBookmarks++
                continue
            }
            val chapterIndex = obj.optInt("chapterIndex", 0)
            val sentenceIndex = obj.optInt("sentenceIndex", 0)
            val selectedText = obj.optString("selectedText").trim()
            val note = obj.optString("note", "")
            val colorHex = obj.optString("colorHex", "#FFEB3B")
            val type = obj.optString("type", "HIGHLIGHT")
            val createdAt = obj.optLong("createdAt", System.currentTimeMillis())

            val isDup = existingBookmarks.any { existing ->
                existing.bookId == resolvedBookId &&
                    existing.chapterIndex == chapterIndex &&
                    existing.sentenceIndex == sentenceIndex &&
                    existing.type == type &&
                    existing.selectedText.trim() == selectedText
            }
            if (isDup) {
                skippedBookmarks++
                continue
            }
            val entity = BookmarkEntity(
                bookId = resolvedBookId,
                chapterIndex = chapterIndex,
                sentenceIndex = sentenceIndex,
                selectedText = selectedText,
                note = note,
                colorHex = colorHex,
                type = type,
                createdAt = createdAt
            )
            val insertedId = database.bookmarkDao().insertBookmark(entity)
            existingBookmarks.add(entity.copy(id = insertedId))
            addedBookmarks++
        }

        if (addedRules > 0 || addedBookmarks > 0) {
            scheduleAutoMirror()
        }

        // Reading positions are restored only when the backup is further along than
        // this device, so importing an older file never rewinds a book. Files written
        // by schema v1 have neither array and simply restore nothing here.
        var restoredReadingPositions = 0
        val progressArr = root.optJSONArray("readingProgress") ?: JSONArray()
        for (i in 0 until progressArr.length()) {
            val obj = progressArr.optJSONObject(i) ?: continue
            val bookId = resolveBookId(obj.optString("fileKey"), obj.optString("title")) ?: continue
            val local = books.firstOrNull { it.id == bookId } ?: continue
            val backupPercent = obj.optDouble("progressPercent", 0.0).toFloat()
            val backupChapter = obj.optInt("chapterIndex", 0)
            val backupAhead = backupPercent > local.progressPercent ||
                (backupPercent >= local.progressPercent && backupChapter > local.currentChapterIndex)
            if (!backupAhead) continue
            database.bookDao().updateReadingProgress(
                bookId = bookId,
                chapterIndex = backupChapter,
                sentenceIndex = obj.optInt("sentenceIndex", 0),
                scrollOffset = obj.optInt("scrollOffset", 0),
                progressPercent = backupPercent,
                lastOpenedAt = obj.optLong("lastOpenedAt", local.lastOpenedAt)
            )
            restoredReadingPositions++
        }

        var restoredAudiobookPositions = 0
        val audioProgressArr = root.optJSONArray("audiobookProgress") ?: JSONArray()
        for (i in 0 until audioProgressArr.length()) {
            val obj = audioProgressArr.optJSONObject(i) ?: continue
            val fileUri = obj.optString("fileUri")
            if (fileUri.isBlank()) continue
            val local = database.audiobookDao().getAudiobookByUri(fileUri) ?: continue
            val positionMs = obj.optLong("positionMs", 0L)
            if (positionMs <= local.lastPositionMs) continue
            database.audiobookDao().updatePlaybackProgress(
                id = local.id,
                positionMs = positionMs,
                playedAt = obj.optLong("lastPlayedAt", local.lastPlayedAt)
            )
            restoredAudiobookPositions++
        }

        ImportBackupSummary(
            addedRules = addedRules,
            skippedRules = skippedRules,
            addedBookmarks = addedBookmarks,
            skippedBookmarks = skippedBookmarks,
            restoredReadingPositions = restoredReadingPositions,
            restoredAudiobookPositions = restoredAudiobookPositions
        )
    }

    suspend fun exportToUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = exportToJsonString()
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } ?: context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    suspend fun importFromUri(uri: Uri): Result<ImportBackupSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(StandardCharsets.UTF_8).readText()
            } ?: throw IllegalStateException("Cannot read selected backup file")
            importFromJsonString(json)
        }
    }

    fun scheduleAutoMirror() {
        ioScope.launch {
            mirrorToConfiguredExportFolder()
        }
    }

    suspend fun mirrorToConfiguredExportFolder(): Boolean = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            runCatching {
                val jsonBytes = exportToJsonString().toByteArray(StandardCharsets.UTF_8)
                var wroteAny = false

                // 1. Write to saved SAF export folder (if configured and writable)
                val folders = preferencesRepository.savedExportFolders.first()
                for (folder in folders.take(1)) {
                    val tree = runCatching {
                        DocumentFile.fromTreeUri(context, Uri.parse(folder.uri))
                    }.getOrNull() ?: continue
                    if (!tree.canWrite()) continue
                    val target = tree.findFile(AUTO_MIRROR_FILENAME)?.takeIf { it.isFile }
                        ?: tree.createFile("application/json", AUTO_MIRROR_FILENAME)
                        ?: continue
                    context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                        out.write(jsonBytes)
                        out.flush()
                        wroteAny = true
                    }
                }

                // 2. Mirror into <ExternalStorage>/LexiVoice/Export only when the app
                //    can genuinely write there. All Files Access is no longer
                //    requested, so this direct path is only reachable on API <= 32
                //    where the legacy storage permission still applies.
                val hasDirectWrite =
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        Environment.isExternalStorageManager()) ||
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                            Environment.getExternalStorageDirectory().canWrite())
                if (hasDirectWrite) {
                    val exportDir = File(Environment.getExternalStorageDirectory(), "LexiVoice/Export")
                    if (exportDir.exists() || exportDir.mkdirs()) {
                        File(exportDir, AUTO_MIRROR_FILENAME).writeBytes(jsonBytes)
                        wroteAny = true
                    }
                }

                // 3. Always keep an internal app-private mirror for zero-permission resilience
                File(context.filesDir, AUTO_MIRROR_FILENAME).writeBytes(jsonBytes)
                wroteAny
            }.getOrDefault(false)
        }
    }

    companion object {
        const val BACKUP_SCHEMA_VERSION = 2
        const val AUTO_MIRROR_FILENAME = "lexivoice_sync_backup.json"
    }
}
