package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.data.prefs.ReaderSettings
import com.example.epub.EpubParser
import com.example.ui.theme.ReaderThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs

enum class ChapterPullDirection {
    NONE,
    PREV,
    NEXT
}

private class EpubZipHolder(
    private val zip: ZipFile,
    private val tempFile: File?
) {
    private val lock = Any()

    @Volatile
    private var closed = false

    fun readResource(url: String): EpubParser.ZipResourceData? {
        synchronized(lock) {
            if (closed) return null
            return runCatching { EpubParser.readZipResource(zip, url) }.getOrNull()
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { zip.close() }
            runCatching { tempFile?.delete() }
        }
    }
}

private class LexiBookWebView(context: Context) : WebView(context) {
    var isDestroyedOrDead: Boolean = false
    var hasPrevChapter: Boolean = false
    var hasNextChapter: Boolean = false
    var onPullStateChanged: ((ChapterPullDirection, Float) -> Unit)? = null
    var onPullTriggered: ((ChapterPullDirection) -> Unit)? = null
    var onScrollPositionChanged: ((Int) -> Unit)? = null

    private var touchStartY = 0f
    private var activePull = ChapterPullDirection.NONE
    private var currentProgress = 0f
    private val triggerThresholdPx by lazy { 150f * resources.displayMetrics.density }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (abs(t - oldt) > 24) {
            onScrollPositionChanged?.invoke(t)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.y
                activePull = ChapterPullDirection.NONE
                currentProgress = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - touchStartY
                val maxScrollY = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
                val atTop = scrollY <= 3
                val atBottom = scrollY >= (maxScrollY - 4).coerceAtLeast(0)

                if (activePull == ChapterPullDirection.NONE) {
                    if (atBottom && dy < -24f && hasNextChapter) {
                        activePull = ChapterPullDirection.NEXT
                    } else if (atTop && dy > 24f && hasPrevChapter) {
                        activePull = ChapterPullDirection.PREV
                    }
                }

                when (activePull) {
                    ChapterPullDirection.NEXT -> {
                        val pullPx = (-dy - 24f).coerceAtLeast(0f)
                        currentProgress = (pullPx / triggerThresholdPx).coerceIn(0f, 1.3f)
                        onPullStateChanged?.invoke(ChapterPullDirection.NEXT, currentProgress)
                        if (pullPx > 8f) return true
                    }
                    ChapterPullDirection.PREV -> {
                        val pullPx = (dy - 24f).coerceAtLeast(0f)
                        currentProgress = (pullPx / triggerThresholdPx).coerceIn(0f, 1.3f)
                        onPullStateChanged?.invoke(ChapterPullDirection.PREV, currentProgress)
                        if (pullPx > 8f) return true
                    }
                    ChapterPullDirection.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val firedDir = if (event.actionMasked == MotionEvent.ACTION_UP && currentProgress >= 1.0f) {
                    activePull
                } else {
                    ChapterPullDirection.NONE
                }
                activePull = ChapterPullDirection.NONE
                currentProgress = 0f
                onPullStateChanged?.invoke(ChapterPullDirection.NONE, 0f)
                if (firedDir != ChapterPullDirection.NONE) {
                    onPullTriggered?.invoke(firedDir)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}

private class LexiWebJsBridge(
    private val onSentenceTappedCallback: (Int) -> Unit,
    private val onTextSelectedCallback: (String) -> Unit
) {
    @JavascriptInterface
    fun onSentenceTapped(idx: Int) {
        onSentenceTappedCallback(idx)
    }

    @JavascriptInterface
    fun onTextSelected(text: String) {
        onTextSelectedCallback(text)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubWebView(
    fileKey: String,
    isDirectPath: Boolean,
    chapterPayload: EpubParser.WebChapterPayload,
    readerSettings: ReaderSettings,
    activeSentenceIndex: Int,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onSentenceTapped: (Int) -> Unit,
    onPullPrevChapter: () -> Unit,
    onPullNextChapter: () -> Unit,
    onScrollChanged: (Int) -> Unit = {},
    onTextSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var zipHolder by remember(fileKey, isDirectPath) { mutableStateOf<EpubZipHolder?>(null) }
    var zipOpenCompleted by remember(fileKey, isDirectPath) { mutableStateOf(fileKey.isBlank()) }
    var webViewRef by remember { mutableStateOf<LexiBookWebView?>(null) }
    var lastLoadedKey by remember { mutableStateOf("") }
    var pageLoadedKey by remember { mutableStateOf("") }
    var renderGeneration by remember { mutableIntStateOf(0) }
    var consecutiveRendererFailures by remember { mutableIntStateOf(0) }

    var pullDir by remember { mutableStateOf(ChapterPullDirection.NONE) }
    var pullProgress by remember { mutableFloatStateOf(0f) }

    val latestOnSentenceTapped by rememberUpdatedState(onSentenceTapped)
    val latestOnPullPrev by rememberUpdatedState(onPullPrevChapter)
    val latestOnPullNext by rememberUpdatedState(onPullNextChapter)
    val latestOnScroll by rememberUpdatedState(onScrollChanged)
    val latestOnTextSelected by rememberUpdatedState(onTextSelected)
    val latestSettings by rememberUpdatedState(readerSettings)
    val latestActiveSentence by rememberUpdatedState(activeSentenceIndex)
    val latestZipHolder by rememberUpdatedState(zipHolder)

    LaunchedEffect(fileKey, isDirectPath) {
        zipOpenCompleted = false
        val previous = zipHolder
        zipHolder = null
        previous?.close()

        if (fileKey.isNotBlank()) {
            val opened = withContext(Dispatchers.IO) {
                EpubParser.openReadableZip(context, fileKey, isDirectPath)
            }
            zipHolder = opened?.let { (zip, temp) -> EpubZipHolder(zip, temp) }
        }
        zipOpenCompleted = true
    }

    DisposableEffect(fileKey, isDirectPath) {
        onDispose {
            zipHolder?.close()
            zipHolder = null
        }
    }

    val contentKey = remember(fileKey, chapterPayload.chapterIndex, chapterPayload.htmlWithSentenceSpans.length, renderGeneration) {
        "$fileKey#${chapterPayload.chapterIndex}#${chapterPayload.htmlWithSentenceSpans.length}#$renderGeneration"
    }

    // Apply live theme/typography CSS updates without full page reload
    LaunchedEffect(readerSettings, pageLoadedKey) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (pageLoadedKey.isNotBlank()) {
            runCatching { wv.evaluateJavascript(buildThemeInjectionScript(readerSettings), null) }
        }
    }

    // Highlight and smooth-scroll to active sentence
    LaunchedEffect(activeSentenceIndex, pageLoadedKey) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (pageLoadedKey == contentKey) {
            runCatching {
                wv.evaluateJavascript("window.lexiHighlightSentence && window.lexiHighlightSentence($activeSentenceIndex, true);", null)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (consecutiveRendererFailures > 3) {
            // Safe native fallback if Android WebView renderer crashes repeatedly
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(readerSettings.themeMode.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = readerSettings.horizontalMarginDp.dp, vertical = 20.dp)
            ) {
                Button(
                    onClick = {
                        consecutiveRendererFailures = 0
                        lastLoadedKey = ""
                        pageLoadedKey = ""
                        renderGeneration++
                    }
                ) {
                    Text("Reload Rich Chapter View")
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = chapterPayload.plainText.ifBlank { "Unable to render chapter." },
                    color = readerSettings.themeMode.text,
                    fontSize = readerSettings.fontSizeSp.sp,
                    lineHeight = (readerSettings.fontSizeSp * readerSettings.lineSpacing).sp
                )
            }
        } else {
            key(renderGeneration) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("epub_webview"),
                    factory = { ctx ->
                        LexiBookWebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(latestSettings.themeMode.background.toArgb())
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                            }
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = false
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = false
                                loadWithOverviewMode = false
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            }
                            addJavascriptInterface(
                                LexiWebJsBridge(
                                    onSentenceTappedCallback = { idx ->
                                        post { latestOnSentenceTapped(idx) }
                                    },
                                    onTextSelectedCallback = { txt ->
                                        post { latestOnTextSelected(txt) }
                                    }
                                ),
                                "LexiBridge"
                            )
                            onPullStateChanged = { dir, prog ->
                                pullDir = dir
                                pullProgress = prog
                            }
                            onPullTriggered = { dir ->
                                when (dir) {
                                    ChapterPullDirection.NEXT -> latestOnPullNext()
                                    ChapterPullDirection.PREV -> latestOnPullPrev()
                                    ChapterPullDirection.NONE -> Unit
                                }
                            }
                            onScrollPositionChanged = { y ->
                                latestOnScroll(y)
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val url = request?.url?.toString() ?: return null
                                    return runCatching {
                                        val corsHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                                        if (url.startsWith("${EpubParser.ASSET_BASE_URL}__lexi_fonts/")) {
                                            val resId = when {
                                                url.endsWith("literata.ttf") -> R.font.literata
                                                url.endsWith("plus_jakarta_sans.ttf") -> R.font.plus_jakarta_sans
                                                else -> null
                                            }
                                            if (resId != null) {
                                                val stream = ctx.resources.openRawResource(resId)
                                                return@runCatching WebResourceResponse(
                                                    "font/ttf",
                                                    null,
                                                    200,
                                                    "OK",
                                                    corsHeaders,
                                                    stream
                                                )
                                            }
                                        }
                                        val holder = latestZipHolder
                                        val data = holder?.readResource(url)
                                        if (data != null) {
                                            return@runCatching WebResourceResponse(
                                                data.mimeType,
                                                "UTF-8",
                                                200,
                                                "OK",
                                                corsHeaders,
                                                ByteArrayInputStream(data.bytes)
                                            )
                                        }
                                        if (url.startsWith(EpubParser.ASSET_BASE_URL) ||
                                            url.startsWith("http://") ||
                                            url.startsWith("https://")
                                        ) {
                                            return@runCatching WebResourceResponse(
                                                "text/plain",
                                                "UTF-8",
                                                404,
                                                "Not Found",
                                                corsHeaders,
                                                ByteArrayInputStream(ByteArray(0))
                                            )
                                        }
                                        null
                                    }.getOrNull()
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    consecutiveRendererFailures = 0
                                    pageLoadedKey = lastLoadedKey
                                    view?.evaluateJavascript(buildFontFaceInjectionScript(), null)
                                    view?.evaluateJavascript(buildThemeInjectionScript(latestSettings), null)
                                    val activeIdx = latestActiveSentence
                                    if (activeIdx >= 0) {
                                        view?.evaluateJavascript(
                                            "window.lexiHighlightSentence && window.lexiHighlightSentence($activeIdx, true);",
                                            null
                                        )
                                    }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    Log.w(
                                        "EpubWebView",
                                        "WebView renderer terminated (didCrash=${detail?.didCrash()}); recovering cleanly."
                                    )
                                    view?.let { dead ->
                                        (dead as? LexiBookWebView)?.isDestroyedOrDead = true
                                        runCatching {
                                            dead.stopLoading()
                                            dead.removeJavascriptInterface("LexiBridge")
                                            (dead.parent as? ViewGroup)?.removeView(dead)
                                            dead.destroy()
                                        }
                                        if (webViewRef === dead) {
                                            webViewRef = null
                                        }
                                    }
                                    lastLoadedKey = ""
                                    pageLoadedKey = ""
                                    consecutiveRendererFailures++
                                    if (consecutiveRendererFailures <= 3) {
                                        renderGeneration++
                                    }
                                    return true
                                }
                            }
                            webViewRef = this
                        }
                    },
                    update = { wv ->
                        wv.hasPrevChapter = hasPrevChapter
                        wv.hasNextChapter = hasNextChapter
                        wv.setBackgroundColor(readerSettings.themeMode.background.toArgb())
                        if (zipOpenCompleted && lastLoadedKey != contentKey && !wv.isDestroyedOrDead) {
                            lastLoadedKey = contentKey
                            pageLoadedKey = ""
                            wv.scrollTo(0, 0)
                            wv.loadDataWithBaseURL(
                                chapterPayload.baseUrl,
                                chapterPayload.htmlWithSentenceSpans,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    },
                    onRelease = { wv ->
                        wv.onPullStateChanged = null
                        wv.onPullTriggered = null
                        wv.onScrollPositionChanged = null
                        if (!wv.isDestroyedOrDead) {
                            wv.isDestroyedOrDead = true
                            runCatching {
                                wv.stopLoading()
                                wv.removeJavascriptInterface("LexiBridge")
                                wv.clearHistory()
                                wv.loadUrl("about:blank")
                                wv.removeAllViews()
                                wv.destroy()
                            }
                        }
                        if (webViewRef === wv) {
                            webViewRef = null
                        }
                    }
                )
            }
        }

        // Top Pull-Down Indicator (Previous Chapter)
        AnimatedVisibility(
            visible = pullDir == ChapterPullDirection.PREV,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        ) {
            val ready = pullProgress >= 1.0f
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (ready) "Release for Previous Chapter" else "Pull down for Previous Chapter",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Bottom Pull-Up Indicator (Next Chapter)
        AnimatedVisibility(
            visible = pullDir == ChapterPullDirection.NEXT,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
        ) {
            val ready = pullProgress >= 1.0f
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (ready) "Release to load Next Chapter" else "Pull up to load Next Chapter",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun Color.toCssHex(): String {
    val argb = this.toArgb()
    return String.format(java.util.Locale.US, "#%06X", argb and 0xFFFFFF)
}

private fun buildFontFaceInjectionScript(): String {
    val fontCss = """
        @font-face {
            font-family: 'LexiLiterata';
            src: url('${EpubParser.ASSET_BASE_URL}__lexi_fonts/literata.ttf') format('truetype');
        }
        @font-face {
            font-family: 'LexiSans';
            src: url('${EpubParser.ASSET_BASE_URL}__lexi_fonts/plus_jakarta_sans.ttf') format('truetype');
        }
    """.trimIndent().replace("'", "\\'").replace("\n", " ")

    return """
        (function(){
          var parent = document.head || document.documentElement;
          if (!parent || document.getElementById('lexi-fonts-style')) return;
          var el = document.createElement('style');
          el.id = 'lexi-fonts-style';
          el.textContent = '$fontCss';
          parent.appendChild(el);
        })();
    """.trimIndent()
}

private fun buildThemeInjectionScript(settings: ReaderSettings): String {
    val theme = settings.themeMode
    val bgHex = theme.background.toCssHex()
    val textHex = theme.text.toCssHex()
    val hiBgHex = theme.highlightBg.toCssHex()
    val hiTextHex = theme.highlightText.toCssHex()
    val linkHex = if (theme == ReaderThemeMode.DARK) "#8CC4FF" else "#1D60A6"
    val fontName = if (settings.useSerifFont) "LexiLiterata, Georgia, serif" else "LexiSans, sans-serif"
    val fontSizePx = settings.fontSizeSp.toInt().coerceIn(12, 36)
    val lineHeight = String.format(java.util.Locale.US, "%.2f", settings.lineSpacing)
    val marginPx = settings.horizontalMarginDp.coerceIn(8, 48)

    val css = """
        html, body {
            background-color: $bgHex !important;
            color: $textHex !important;
            font-family: $fontName !important;
            font-size: ${fontSizePx}px !important;
            line-height: $lineHeight !important;
            margin: 0 !important;
            padding: 18px ${marginPx}px 64px ${marginPx}px !important;
            box-sizing: border-box !important;
            overflow-x: hidden !important;
            word-wrap: break-word !important;
        }
        body p, body div, body span, body li, body blockquote, body h1, body h2, body h3, body h4, body h5, body h6 {
            color: inherit !important;
            font-family: inherit !important;
            line-height: inherit !important;
        }
        body p, body li, body blockquote {
            font-size: ${fontSizePx}px !important;
        }
        a { color: $linkHex !important; }
        img, svg, figure {
            max-width: 100% !important;
            height: auto !important;
            display: block;
            margin-left: auto;
            margin-right: auto;
        }
        .lexi-s {
            cursor: pointer;
            border-radius: 4px;
        }
        .lexi-s.lexi-active {
            background-color: $hiBgHex !important;
            color: $hiTextHex !important;
        }
    """.trimIndent().replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")

    return """
        (function(){
          var parent = document.head || document.documentElement;
          if (!parent) return;
          var el = document.getElementById('lexi-theme-style');
          if (!el) {
            el = document.createElement('style');
            el.id = 'lexi-theme-style';
            parent.appendChild(el);
          }
          el.textContent = '$css';
        })();
    """.trimIndent()
}

