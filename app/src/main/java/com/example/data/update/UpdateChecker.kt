package com.example.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A release on the project's GitHub releases page. */
data class UpdateInfo(
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String,
    /** When the release (or its APK asset) was last published, in epoch millis. */
    val publishedAtMs: Long = 0L
)

/**
 * Finds and installs a newer build from the repository's releases page.
 *
 * No dependency on a networking library: the call is one GET and one download, so
 * HttpURLConnection keeps the APK free of another dependency.
 */
object UpdateChecker {

    private const val OWNER = "ar994885"
    private const val REPO = "Lexi-voice"
    private const val USER_AGENT = "LexiVoice-Android"

    suspend fun fetchLatest(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("GitHub answered ${conn.responseCode}")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val tag = root.optString("tag_name").ifBlank { root.optString("name") }

                var apkUrl: String? = null
                var assetUpdatedAt = 0L
                val assets = root.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            assetUpdatedAt = parseGithubDate(asset.optString("updated_at"))
                            break
                        }
                    }
                }
                if (apkUrl.isNullOrBlank()) {
                    throw IllegalStateException(
                        "Release ${tag.ifBlank { "latest" }} has no APK attached"
                    )
                }
                val publishedAt = maxOf(
                    parseGithubDate(root.optString("published_at")),
                    assetUpdatedAt
                )
                UpdateInfo(
                    versionName = tag.removePrefix("v").removePrefix("V").trim(),
                    apkUrl = apkUrl,
                    releaseNotes = root.optString("body").trim(),
                    publishedAtMs = publishedAt
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    /** GitHub dates look like 2026-11-25T12:34:56Z. */
    private fun parseGithubDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(raw)?.time ?: 0L
        }.getOrDefault(0L)
    }

    /** When the installed APK was last replaced, in epoch millis. */
    fun installedAtMs(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    /**
     * Whether [info] is worth installing.
     *
     * Version strings are not enough here: a release tag and the versionName baked
     * into the APK are produced by different steps, so a workflow that re-tags every
     * build can hand back a "newer" version name for an APK that is already
     * installed. The APK's own install time is the ground truth, so a release counts
     * as an update only when it was published after the installed APK was written.
     */
    fun isUpdateAvailable(context: Context, info: UpdateInfo): Boolean {
        val installedAt = installedAtMs(context)
        if (info.publishedAtMs > 0L && installedAt > 0L) {
            // A minute of slack for clock skew between the runner and the phone.
            return info.publishedAtMs > installedAt + 60_000L
        }
        val installedName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        return isNewer(info.versionName, installedName)
    }

    /** True when [remote] is a higher dotted version than [current]. */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(value: String): List<Int> = value.trim()
            .removePrefix("v")
            .split('.', '-', '+')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

        val newer = parts(remote)
        val installed = parts(current)
        for (i in 0 until maxOf(newer.size, installed.size)) {
            val a = newer.getOrElse(i) { 0 }
            val b = installed.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "lexivoice-${info.versionName}.apk")
        if (target.exists()) target.delete()

        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Download failed (${conn.responseCode})")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    out.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
        target
    }

    /** Hands the downloaded APK to the system installer. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
