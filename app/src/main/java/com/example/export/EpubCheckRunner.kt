package com.example.export

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional on-device EPUBCheck.
 *
 * EPUBCheck is a Java SE application, so it cannot be linked into an Android app.
 * It is published as `epubcheck-standalone`: the official build compiled to plain
 * JavaScript by TeaVM, whose authors describe the output as identical to the Java
 * tool. That build is a set of ES modules, so it runs in a WebView.
 *
 * It is **not** shipped in the APK. The engine is 21 MB, so it is downloaded only
 * when the reader asks for it from the export flow, and the app works without it
 * (see [EpubValidator] for the built-in fallback).
 *
 * The modules are served to the WebView over https://lexi.local/ rather than from
 * file://, because module imports and fetch() are both blocked on a file origin.
 */
object EpubCheckRunner {

    data class Message(val severity: String, val code: String, val text: String)

    data class Result(
        val valid: Boolean,
        val engineVersion: String,
        val messages: List<Message>,
        val failure: String? = null
    ) {
        val errors: List<Message> get() = messages.filter {
            it.severity.equals("ERROR", true) || it.severity.equals("FATAL", true)
        }
        val warnings: List<Message> get() = messages.filter { it.severity.equals("WARNING", true) }

        /** Flattens into the shape the validation dialog already renders. */
        fun asReport(): EpubValidationReport = EpubValidationReport(
            errors = errors.map { EpubIssue(EpubIssue.Severity.ERROR, it.describe()) },
            warnings = warnings.map { EpubIssue(EpubIssue.Severity.WARNING, it.describe()) },
            checkedEntries = 0,
            engine = if (engineVersion.isBlank()) "EPUBCheck" else "EPUBCheck " + engineVersion
        )

        private fun Message.describe(): String =
            if (code.isBlank()) text else code + ": " + text
    }

    private const val ORIGIN = "https://lexi.local"
    private const val ASSET_DIR = "epubcheck"
    private const val BOOK_PATH = "/book.epub"
    private const val TIMEOUT_MS = 240_000L
    private const val PACKAGE_VERSION = "5.4.0-build3"
    private const val ENGINE_FILE = "epubcheck-engine.js"
    private const val ENGINE_MIN_BYTES = 1_000_000L

    /** Everything the engine needs, relative to the package's dist/ folder. */
    private val MODULES = listOf(
        ENGINE_FILE,
        "validate-browser.js",
        "validate-core.js",
        "run-core.js",
        "engine-browser.js",
        "engine-run.js",
        "parse.js",
        "plugins.js",
        "formatters/index.js",
        "formatters/console.js"
    )

    private val BASES = listOf(
        "https://unpkg.com/epubcheck-standalone@$PACKAGE_VERSION/dist/",
        "https://cdn.jsdelivr.net/npm/epubcheck-standalone@$PACKAGE_VERSION/dist/"
    )

    private fun installDir(context: Context): File = File(context.filesDir, "epubcheck")

    /** True when the engine has been downloaded and looks complete. */
    fun isAvailable(context: Context): Boolean {
        val engine = File(installDir(context), ENGINE_FILE)
        return engine.isFile && engine.length() > ENGINE_MIN_BYTES
    }

    /** Roughly how much the download costs, for the prompt. */
    fun downloadSizeLabel(): String = "about 21 MB"

    /**
     * Downloads the engine modules. [onProgress] gets a 0..1 fraction.
     * Returns true when the engine is installed and usable.
     */
    suspend fun download(
        context: Context,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = installDir(context)
        if (!dir.exists() && !dir.mkdirs()) return@withContext false
        File(dir, "formatters").mkdirs()

        val total = MODULES.size + 1
        var done = 0
        for (name in MODULES) {
            val target = File(dir, name)
            val ok = if (target.isFile && target.length() > 0L) {
                true
            } else {
                fetchToFile(name, target)
            }
            if (!ok) {
                onProgress(0f)
                return@withContext false
            }
            done++
            onProgress(done.toFloat() / total)
        }

        // The package's own version.js imports package.json with an import
        // attribute, which older WebViews reject. Write a plain stub instead.
        File(dir, "version.js").writeText(
            "export const EPUBCHECK_VERSION = \"" + PACKAGE_VERSION.substringBefore("-build") + "\";\n"
        )
        onProgress(1f)
        isAvailable(context)
    }

    fun remove(context: Context) {
        runCatching { installDir(context).deleteRecursively() }
    }

    private fun fetchToFile(name: String, target: File): Boolean {
        for (base in BASES) {
            val ok = runCatching {
                val conn = URL(base + name).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 120_000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode !in 200..299) return@runCatching false
                    conn.inputStream.use { input ->
                        target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                    }
                    target.length() > 0L
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
            if (ok) return true
            runCatching { target.delete() }
        }
        return false
    }

    /** Copies [uri] to a cache file and validates that. */
    suspend fun validate(context: Context, uri: android.net.Uri): Result? {
        if (!isAvailable(context)) return null
        val temp = withContext(Dispatchers.IO) {
            runCatching {
                val file = File.createTempFile("lexi-epubcheck", ".epub", context.cacheDir)
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    true
                } ?: false
                if (copied) file else null
            }.getOrNull()
        } ?: return null
        return try {
            validate(context, temp)
        } finally {
            runCatching { temp.delete() }
        }
    }

    suspend fun validate(context: Context, epubFile: File): Result? {
        if (!isAvailable(context)) return null
        return withContext(Dispatchers.Main) {
            val result = CompletableDeferred<String>()
            var webView: WebView? = null
            try {
                webView = buildWebView(context, epubFile, result)
                webView.loadUrl("$ORIGIN/validator.html")
                val json = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
                if (json == null) {
                    Result(false, "", emptyList(), "EPUBCheck timed out")
                } else {
                    parse(json)
                }
            } catch (t: Throwable) {
                Result(false, "", emptyList(), "EPUBCheck failed to start: ${t.message}")
            } finally {
                runCatching {
                    webView?.stopLoading()
                    webView?.destroy()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun buildWebView(
        context: Context,
        epubFile: File,
        result: CompletableDeferred<String>
    ): WebView {
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onResult(json: String) {
                    result.complete(json)
                }

                @JavascriptInterface
                fun onReady(version: String) = Unit
            },
            "LexiCheck"
        )
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? = serve(context, request?.url?.path, epubFile)

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(
                    "window.lexiValidateEpub && window.lexiValidateEpub('$BOOK_PATH');",
                    null
                )
            }
        }
        return webView
    }

    private fun serve(context: Context, path: String?, epubFile: File): WebResourceResponse? {
        if (path.isNullOrBlank()) return null
        if (path == BOOK_PATH) {
            return if (epubFile.exists()) {
                WebResourceResponse(
                    "application/epub+zip",
                    null,
                    200,
                    "OK",
                    corsHeaders(),
                    ByteArrayInputStream(epubFile.readBytes())
                )
            } else {
                notFound()
            }
        }
        val name = path.removePrefix("/")
        if (name.contains("..")) return notFound()
        val mime = when {
            name.endsWith(".html") -> "text/html"
            name.endsWith(".js") -> "text/javascript"
            name.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
        // Downloaded modules live in filesDir; the two files this repo owns
        // (validator.html, boot.js) live in assets.
        val downloaded = File(installDir(context), name)
        val stream = when {
            downloaded.isFile -> runCatching { downloaded.inputStream() }.getOrNull()
            else -> runCatching { context.assets.open("$ASSET_DIR/$name") }.getOrNull()
        } ?: return notFound()
        return WebResourceResponse(mime, "UTF-8", 200, "OK", corsHeaders(), stream)
    }

    private fun corsHeaders() = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-store"
    )

    private fun notFound() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        corsHeaders(),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun parse(json: String): Result {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return Result(false, "", emptyList(), "Unreadable EPUBCheck output")
        val engine = root.optString("engine")
        if (!root.optBoolean("ok", false)) {
            return Result(false, engine, emptyList(), root.optString("error").ifBlank { "EPUBCheck failed" })
        }
        val payload = root.optJSONObject("result")
            ?: return Result(false, engine, emptyList(), "EPUBCheck returned no result")
        val messages = mutableListOf<Message>()
        val array = payload.optJSONArray("messages")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                messages += Message(
                    severity = item.optString("severity").ifBlank { "INFO" },
                    code = item.optString("id").ifBlank { item.optString("code") },
                    text = item.optString("message")
                )
            }
        }
        return Result(
            valid = payload.optBoolean("valid", false),
            engineVersion = engine,
            messages = messages
        )
    }
}
