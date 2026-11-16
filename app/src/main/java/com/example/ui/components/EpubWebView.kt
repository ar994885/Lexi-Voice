package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

private class LexiBookWebView(context: Context) : WebView(context) {
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
    private val onSentenceTappedCallback: (Int) -> Unit
) {
    @JavascriptInterface
    fun onSentenceTapped(idx: Int) {
        onSentenceTappedCallback(idx)
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var zipHandle by remember(fileKey, isDirectPath) { mutableStateOf<Pair<ZipFile, File?>?>(null) }
    var webViewRef by remember { mutableStateOf<LexiBookWebView?>(null) }
    var lastLoadedKey by remember { mutableStateOf("") }
    var pageLoadedKey by remember { mutableStateOf("") }

    var pullDir by remember { mutableStateOf(ChapterPullDirection.NONE) }
    var pullProgress by remember { mutableFloatStateOf(0f) }

    val latestOnSentenceTapped by rememberUpdatedState(onSentenceTapped)
    val latestOnPullPrev by rememberUpdatedState(onPullPrevChapter)
    val latestOnPullNext by rememberUpdatedState(onPullNextChapter)
    val latestOnScroll by rememberUpdatedState(onScrollChanged)
    val latestSettings by rememberUpdatedState(readerSettings)
    val latestActiveSentence by rememberUpdatedState(activeSentenceIndex)

    LaunchedEffect(fileKey, isDirectPath) {
        if (fileKey.isNotBlank()) {
            val opened = withContext(Dispatchers.IO) {
                EpubParser.openReadableZip(context, fileKey, isDirectPath)
            }
            zipHandle = opened
        }
    }

    DisposableEffect(fileKey, isDirectPath) {
        onDispose {
            zipHandle?.let { (zip, tempFile) ->
                runCatching { zip.close() }
                tempFile?.delete()
            }
            zipHandle = null
        }
    }

    val contentKey = remember(fileKey, chapterPayload.chapterIndex, chapterPayload.htmlWithSentenceSpans.length) {
        "$fileKey#${chapterPayload.chapterIndex}#${chapterPayload.htmlWithSentenceSpans.length}"
    }

    // Apply live theme/typography CSS updates without full page reload
    LaunchedEffect(readerSettings, pageLoadedKey) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (pageLoadedKey.isNotBlank()) {
            wv.evaluateJavascript(buildThemeInjectionScript(readerSettings), null)
        }
    }

    // Highlight and smooth-scroll to active sentence
    LaunchedEffect(activeSentenceIndex, pageLoadedKey) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (pageLoadedKey == contentKey) {
            wv.evaluateJavascript("window.lexiHighlightSentence && window.lexiHighlightSentence($activeSentenceIndex, true);", null)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
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
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = false
                        loadWithOverviewMode = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    addJavascriptInterface(
                        LexiWebJsBridge { idx ->
                            post { latestOnSentenceTapped(idx) }
                        },
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
                            if (url.startsWith("${EpubParser.ASSET_BASE_URL}__lexi_fonts/")) {
                                val resId = when {
                                    url.endsWith("literata.ttf") -> R.font.literata
                                    url.endsWith("plus_jakarta_sans.ttf") -> R.font.plus_jakarta_sans
                                    else -> null
                                }
                                if (resId != null) {
                                    val stream = ctx.resources.openRawResource(resId)
                                    return WebResourceResponse("font/ttf", null, stream)
                                }
                            }
                            val currentZip = zipHandle?.first ?: return null
                            val data = synchronized(currentZip) {
                                EpubParser.readZipResource(currentZip, url)
                            } ?: return null
                            return WebResourceResponse(
                                data.mimeType,
                                "UTF-8",
                                ByteArrayInputStream(data.bytes)
                            )
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            pageLoadedKey = lastLoadedKey
                            view?.evaluateJavascript(buildThemeInjectionScript(latestSettings), null)
                            val activeIdx = latestActiveSentence
                            if (activeIdx >= 0) {
                                view?.evaluateJavascript(
                                    "window.lexiHighlightSentence && window.lexiHighlightSentence($activeIdx, true);",
                                    null
                                )
                            }
                        }
                    }
                    webViewRef = this
                }
            },
            update = { wv ->
                wv.hasPrevChapter = hasPrevChapter
                wv.hasNextChapter = hasNextChapter
                wv.setBackgroundColor(readerSettings.themeMode.background.toArgb())
                if (lastLoadedKey != contentKey) {
                    lastLoadedKey = contentKey
                    wv.scrollTo(0, 0)
                    wv.loadDataWithBaseURL(
                        chapterPayload.baseUrl,
                        chapterPayload.htmlWithSentenceSpans,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        )

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
    return String.format("#%06X", argb and 0xFFFFFF)
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
        @font-face {
            font-family: 'LexiLiterata';
            src: url('${EpubParser.ASSET_BASE_URL}__lexi_fonts/literata.ttf') format('truetype');
        }
        @font-face {
            font-family: 'LexiSans';
            src: url('${EpubParser.ASSET_BASE_URL}__lexi_fonts/plus_jakarta_sans.ttf') format('truetype');
        }
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
            transition: background-color 140ms ease;
        }
        .lexi-s.lexi-active {
            background-color: $hiBgHex !important;
            color: $hiTextHex !important;
        }
    """.trimIndent().replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")

    return """
        (function(){
          var el = document.getElementById('lexi-theme-style');
          if (!el) {
            el = document.createElement('style');
            el.id = 'lexi-theme-style';
            document.head.appendChild(el);
          }
          el.textContent = '$css';
        })();
    """.trimIndent()
}
