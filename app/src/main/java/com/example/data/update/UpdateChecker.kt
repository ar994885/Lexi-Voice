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

    suspend fun fetchLatest(context: Context): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        val myPackage = context.packageName
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
                var fallbackUrl: String? = null
                var fallbackUpdatedAt = 0L
                val assets = root.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        if (!name.endsWith(".apk", ignoreCase = true)) continue
                        val url = asset.optString("browser_download_url")
                        val updated = parseGithubDate(asset.optString("updated_at"))
                        // A release carries one APK per flavour. Taking the first one
                        // handed the Pro build to the standard app.
                        if (flavourMatches(name, myPackage)) {
                            apkUrl = url
                            assetUpdatedAt = updated
                            break
                        }
                        if (fallbackUrl == null) {
                            fallbackUrl = url
                            fallbackUpdatedAt = updated
                        }
                    }
                }
                if (apkUrl == null && fallbackUrl != null) {
                    apkUrl = fallbackUrl
                    assetUpdatedAt = fallbackUpdatedAt
                }
                if (apkUrl.isNullOrBlank()) {
                    throw IllegalStateException(
                        "Release ${tag.ifBlank { "latest" }} has no APK attached"
                    )
                }
                if (!isOwnReleaseAsset(apkUrl)) {
                    throw IllegalStateException(
                        "Release asset points outside this repository: $apkUrl"
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

    /**
     * Only this repository's own release assets are installable.
     *
     * The API response is trusted input, but a compromised or mistaken release can
     * name any URL as its APK. Android would still refuse an APK signed with a
     * different key, so this is defence in depth rather than the only barrier: it
     * keeps the download pointed at github.com and at this repository's release
     * path, so a redirected asset cannot be substituted for something else.
     */
    /**
     * Release assets are named per flavour (LexiVoice-… and LexiVoice-Pro-…). The
     * installed package is the authority: a standard install must never be offered
     * the Pro APK, and the other way round.
     */
    private fun flavourMatches(assetName: String, packageName: String): Boolean {
        val wantsPro = packageName.endsWith(".pro", ignoreCase = true)
        val nameSaysPro = assetName.contains("pro", ignoreCase = true)
        return wantsPro == nameSaysPro
    }

    /**
     * Reads the package name out of the downloaded file. Android would install a
     * differently-named package as a second app rather than as an update, so this
     * is the check that stops a wrong-flavour download before the installer runs.
     */
    private fun downloadedPackageName(context: Context, apk: File): String? =
        runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
        }.getOrNull()

    private fun isOwnReleaseAsset(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        val host = parsed.host.lowercase()
        val path = parsed.path.orEmpty()
        return when (host) {
            "github.com" -> path.startsWith("/$OWNER/$REPO/releases/download/", ignoreCase = true)
            // GitHub redirects release downloads here; the connection follows the
            // redirect, so the initial host is what can be checked.
            "objects.githubusercontent.com" -> true
            else -> false
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
        // Checked again here: Info can outlive the check that produced it.
        if (!isOwnReleaseAsset(info.apkUrl)) {
            throw IllegalStateException("Refusing to download from ${info.apkUrl}")
        }
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

        val downloadedPackage = downloadedPackageName(context, target)
        if (downloadedPackage != context.packageName) {
            runCatching { target.delete() }
            throw IllegalStateException(
                "This release's APK is for " + (downloadedPackage ?: "an unknown app") +
                    ", not for " + context.packageName
            )
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
