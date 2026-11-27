package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.ActionMode
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.db.BookmarkEntity
import com.example.data.prefs.ReaderFontFamily
import com.example.data.prefs.ReaderMode
import com.example.data.prefs.ReaderSettings
import com.example.data.prefs.ReaderTextAlign
import com.example.epub.EpubParser
import com.example.ui.theme.ReaderThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.pow

enum class ChapterPullDirection {
    NONE,
    PREV,
    NEXT
}

data class TextSelectionRectInfo(
    val text: String = "",
    val sentenceIndex: Int = -1,
    /** Character offsets of the selection inside its sentence span; -1 when unknown. */
    val startOffset: Int = -1,
    val endOffset: Int = -1,
    /** Occurrences of the selected text before it in this chapter, for anchoring. */
    val occurrenceIndex: Int = 0,
    val topCssPx: Float = 0f,
    val bottomCssPx: Float = 0f,
    val leftCssPx: Float = 0f,
    val rightCssPx: Float = 0f,
    val viewportWidthCssPx: Float = 1f,
    val viewportHeightCssPx: Float = 1f
)

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
    var isPagedMode: Boolean = false
    var hasPrevChapter: Boolean = false
    var hasNextChapter: Boolean = false
    var onPullStateChanged: ((ChapterPullDirection, Float) -> Unit)? = null
    var onPullTriggered: ((ChapterPullDirection) -> Unit)? = null
    var onScrollPositionChanged: ((Int) -> Unit)? = null
    var onFlipPageRequested: ((Int) -> Unit)? = null
    var onCenterTapped: (() -> Unit)? = null

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isHorizontalSwipe = false
    private var activePull = ChapterPullDirection.NONE
    private var currentProgress = 0f
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    private val swipeThresholdPx by lazy { 42f * resources.displayMetrics.density }
    private val triggerThresholdPx by lazy { 150f * resources.displayMetrics.density }

    var activeActionMode: ActionMode? = null
        private set
    var hasActiveJsSelection: Boolean = false

    fun finishActiveActionMode() {
        activeActionMode?.finish()
        activeActionMode = null
        hasActiveJsSelection = false
    }

    private fun wrapActionModeCallback(delegate: ActionMode.Callback?): ActionMode.Callback2 {
        return object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
                val keep = delegate?.onCreateActionMode(mode, menu) ?: true
                menu.clear()
                if (keep) {
                    activeActionMode = mode
                }
                return keep
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
                delegate?.onPrepareActionMode(mode, menu)
                menu.clear()
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: android.view.MenuItem): Boolean {
                return delegate?.onActionItemClicked(mode, item) ?: false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (activeActionMode === mode) {
                    activeActionMode = null
                }
                delegate?.onDestroyActionMode(mode)
            }

            override fun onGetContentRect(mode: ActionMode, view: android.view.View, outRect: android.graphics.Rect) {
                // Keep system FloatingToolbar positioned off-screen and empty so only Compose Selection Pill is shown
                outRect.set(-10000, -10000, -9999, -9999)
            }
        }
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        return super.startActionMode(wrapActionModeCallback(callback))
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        return super.startActionMode(wrapActionModeCallback(callback), type)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!isPagedMode && abs(t - oldt) > 24) {
            onScrollPositionChanged?.invoke(t)
        }
    }

    private fun pullProgressFor(pullPx: Float): Float {
        val ratio = (pullPx / triggerThresholdPx).coerceIn(0f, 1.3f)
        return ratio.pow(1.35f).coerceIn(0f, 1.3f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (isPagedMode && !isDestroyedOrDead && isAttachedToWindow && (w != ow || h != oh) && w > 0 && h > 0) {
            post {
                if (!isDestroyedOrDead && isAttachedToWindow) {
                    evaluateJavascript("window.lexiRecalcPagination && window.lexiRecalcPagination();", null)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        handler?.removeCallbacksAndMessages(null)
        activeActionMode = null
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // When text selection or handles are active, never intercept drags/swipes so handles track smoothly
        if (activeActionMode != null || hasActiveJsSelection) {
            isHorizontalSwipe = false
            activePull = ChapterPullDirection.NONE
            currentProgress = 0f
            return super.onTouchEvent(event)
        }

        if (isPagedMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    isHorizontalSwipe = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    if (!isHorizontalSwipe && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.15f) {
                        isHorizontalSwipe = true
                        val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancelEvent)
                        cancelEvent.recycle()
                    }
                    if (isHorizontalSwipe || abs(dy) > touchSlop) {
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isHorizontalSwipe) {
                        val dx = event.x - touchStartX
                        isHorizontalSwipe = false
                        if (dx < -swipeThresholdPx) {
                            onFlipPageRequested?.invoke(1)
                        } else if (dx > swipeThresholdPx) {
                            onFlipPageRequested?.invoke(-1)
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    isHorizontalSwipe = false
                }
            }
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
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
                    if (atBottom && dy < -24f) {
                        activePull = ChapterPullDirection.NEXT
                    } else if (atTop && dy > 24f && hasPrevChapter) {
                        activePull = ChapterPullDirection.PREV
                    }
                    if (activePull != ChapterPullDirection.NONE) {
                        // A vertical drag over text otherwise starts a selection and
                        // leaves a stray blue span behind.
                        runCatching {
                            evaluateJavascript(
                                "window.lexiSuppressSelection && window.lexiSuppressSelection();",
                                null
                            )
                        }
                    }
                }

                when (activePull) {
                    ChapterPullDirection.NEXT -> {
                        val pullPx = (-dy - 24f).coerceAtLeast(0f)
                        // Resistance: progress trails the finger, so the further you
                        // pull the more it costs. A linear ratio felt weightless.
                        currentProgress = pullProgressFor(pullPx)
                        onPullStateChanged?.invoke(ChapterPullDirection.NEXT, currentProgress)
                        if (pullPx > 8f) return true
                    }
                    ChapterPullDirection.PREV -> {
                        val pullPx = (dy - 24f).coerceAtLeast(0f)
                        currentProgress = pullProgressFor(pullPx)
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
                runCatching {
                    evaluateJavascript(
                        "window.lexiRestoreSelection && window.lexiRestoreSelection();",
                        null
                    )
                }
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
private fun destroyWebViewSafely(wv: LexiBookWebView) {
    wv.onPullStateChanged = null
    wv.onPullTriggered = null
    wv.onScrollPositionChanged = null
    wv.onFlipPageRequested = null
    wv.onCenterTapped = null
    if (wv.isDestroyedOrDead) {
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
        return
    }
    wv.isDestroyedOrDead = true
    runCatching {
        wv.handler?.removeCallbacksAndMessages(null)
        wv.stopLoading()
        wv.onPause()
        wv.removeJavascriptInterface("LexiBridge")
        wv.webViewClient = WebViewClient()
        wv.webChromeClient = null
        wv.clearHistory()
        wv.removeAllViews()
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
    }
}

/**
 * Gatekeeper for every URL that leaves the reader.
 *
 * EPUB content is untrusted input: the format permits scripted XHTML, so a
 * crafted or careless book can run JavaScript in the same page as [LexiWebJsBridge]
 * and call [LexiWebJsBridge.onExternalLinkClicked]. Only http and https are
 * allowed through, which rules out intent://, file://, content://, javascript:
 * and arbitrary custom schemes reaching an Intent.ACTION_VIEW.
 */
internal fun isSafeExternalUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val scheme = runCatching { Uri.parse(trimmed).scheme }.getOrNull()
        ?.lowercase(Locale.ROOT) ?: return false
    return scheme == "http" || scheme == "https"
}

private class LexiWebJsBridge(
    private val isAliveProvider: () -> Boolean,
    private val enableSentenceTapProvider: () -> Boolean,
    private val onSentenceTappedCallback: (Int) -> Unit,
    private val onTextSelectedCallback: (TextSelectionRectInfo) -> Unit,
    private val onPageStateChangedCallback: (Int, Int) -> Unit,
    private val onPageBoundaryReachedCallback: (Int) -> Unit,
    private val onCenterTappedCallback: () -> Unit,
    private val onExternalLinkClickedCallback: (String) -> Unit,
    private val onInternalLinkClickedCallback: (String) -> Unit,
    private val onHighlightTappedCallback: (Long) -> Unit
) {
    @JavascriptInterface
    fun onSentenceTapped(idx: Int) {
        if (!isAliveProvider()) return
        if (enableSentenceTapProvider()) {
            onSentenceTappedCallback(idx)
        } else {
            onCenterTappedCallback()
        }
    }
    @JavascriptInterface
    fun onTextSelected(text: String) {
        if (!isAliveProvider()) return
        val clean = text.trim()
        if (clean.isEmpty()) {
            onTextSelectedCallback(TextSelectionRectInfo())
        } else {
            onTextSelectedCallback(TextSelectionRectInfo(text = clean, sentenceIndex = -1))
        }
    }
    @JavascriptInterface
    fun onTextSelectedWithSentence(text: String, sentenceIndex: Int) {
        if (!isAliveProvider()) return
        val clean = text.trim()
        if (clean.isEmpty()) {
            onTextSelectedCallback(TextSelectionRectInfo())
        } else {
            onTextSelectedCallback(TextSelectionRectInfo(text = clean, sentenceIndex = sentenceIndex))
        }
    }
    @JavascriptInterface
    fun onTextSelectedWithRect(payloadJson: String) {
        if (!isAliveProvider()) return
        val info = runCatching {
            val obj = JSONObject(payloadJson)
            val txt = obj.optString("text", "").trim()
            if (txt.isEmpty()) {
                TextSelectionRectInfo()
            } else {
                TextSelectionRectInfo(
                    text = txt,
                    sentenceIndex = obj.optInt("sentenceIndex", -1),
                    startOffset = obj.optInt("startOffset", -1),
                    endOffset = obj.optInt("endOffset", -1),
                    occurrenceIndex = obj.optInt("occurrenceIndex", 0),
                    topCssPx = obj.optDouble("top", 0.0).toFloat(),
                    bottomCssPx = obj.optDouble("bottom", 0.0).toFloat(),
                    leftCssPx = obj.optDouble("left", 0.0).toFloat(),
                    rightCssPx = obj.optDouble("right", 0.0).toFloat(),
                    viewportWidthCssPx = obj.optDouble("viewportWidth", 1.0).toFloat().coerceAtLeast(1f),
                    viewportHeightCssPx = obj.optDouble("viewportHeight", 1.0).toFloat().coerceAtLeast(1f)
                )
            }
        }.getOrElse { TextSelectionRectInfo() }
        onTextSelectedCallback(info)
    }
    @JavascriptInterface
    fun onTextSelectionCleared() {
        if (!isAliveProvider()) return
        onTextSelectedCallback(TextSelectionRectInfo())
    }
    @JavascriptInterface
    fun onHighlightTapped(id: String) {
        if (!isAliveProvider()) return
        val parsed = id.trim().toLongOrNull() ?: return
        onHighlightTappedCallback(parsed)
    }
    @JavascriptInterface
    fun onPageStateChanged(currentPage: Int, totalPages: Int) {
        if (!isAliveProvider()) return
        onPageStateChangedCallback(currentPage, totalPages)
    }
    @JavascriptInterface
    fun onPageBoundaryReached(direction: Int) {
        if (!isAliveProvider()) return
        onPageBoundaryReachedCallback(direction)
    }
    @JavascriptInterface
    fun onCenterTapped() {
        if (!isAliveProvider()) return
        onCenterTappedCallback()
    }
    @JavascriptInterface
    fun onExternalLinkClicked(url: String) {
        if (!isAliveProvider()) return
        // Reject anything that is not plain web content before it reaches the host.
        if (!isSafeExternalUrl(url)) return
        onExternalLinkClickedCallback(url)
    }
    @JavascriptInterface
    fun onInternalLinkClicked(url: String) {
        if (!isAliveProvider()) return
        onInternalLinkClickedCallback(url)
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
    chapterHighlights: List<BookmarkEntity> = emptyList(),
    enableSentenceTap: Boolean = false,
    onSentenceTapped: (Int) -> Unit = {},
    onPullPrevChapter: () -> Unit,
    onPullNextChapter: () -> Unit,
    onEndOfBookReached: () -> Unit = {},
    onPullProgress: (ChapterPullDirection, Float) -> Unit = { _, _ -> },
    anchoredTerms: List<com.example.data.db.PronunciationRuleEntity> = emptyList(),
    onScrollChanged: (Int) -> Unit = {},
    onTextSelectedWithSentence: (String, Int) -> Unit = { _, _ -> },
    onTextSelectedWithRect: (TextSelectionRectInfo) -> Unit = {},
    initialPageOnLoad: Int = 0,
    initialAnchorOnLoad: String? = null,
    targetPageTrigger: Pair<Int, Long>? = null,
    pageFlipTrigger: Pair<Int, Long>? = null,
    webActionTrigger: Pair<String, Long>? = null,
    targetAnchorTrigger: Pair<String, Long>? = null,
    forceScrollMode: Boolean = false,
    onPageStateChanged: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> },
    onCenterTapped: () -> Unit = {},
    onExternalLinkClicked: (String) -> Unit = {},
    onInternalLinkClicked: (String) -> Unit = {},
    onHighlightTapped: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isLifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var zipHolder by remember(fileKey, isDirectPath) { mutableStateOf<EpubZipHolder?>(null) }
    var zipOpenCompleted by remember(fileKey, isDirectPath) { mutableStateOf(fileKey.isBlank()) }
    var webViewRef by remember { mutableStateOf<LexiBookWebView?>(null) }
    var lastLoadedKey by remember { mutableStateOf("") }
    var pageLoadedKey by remember { mutableStateOf("") }
    var renderGeneration by remember { mutableIntStateOf(0) }
    var consecutiveRendererFailures by remember { mutableIntStateOf(0) }
    var pullDir by remember { mutableStateOf(ChapterPullDirection.NONE) }
    var pullProgress by remember { mutableFloatStateOf(0f) }
    val effectivePagedMode = !forceScrollMode && readerSettings.readingMode == ReaderMode.PAGED
    val latestEnableSentenceTap by rememberUpdatedState(enableSentenceTap)
    val latestOnSentenceTapped by rememberUpdatedState(onSentenceTapped)
    val latestOnPullPrev by rememberUpdatedState(onPullPrevChapter)
    val latestOnPullProgress by rememberUpdatedState(onPullProgress)
    val latestAnchoredTerms by rememberUpdatedState(anchoredTerms)
    val latestOnPullNext by rememberUpdatedState(onPullNextChapter)
    val latestOnEndOfBook by rememberUpdatedState(onEndOfBookReached)
    val latestOnScroll by rememberUpdatedState(onScrollChanged)
    val latestOnTextSelected by rememberUpdatedState(onTextSelectedWithSentence)
    val latestOnTextSelectedRect by rememberUpdatedState(onTextSelectedWithRect)
    val latestOnPageStateChanged by rememberUpdatedState(onPageStateChanged)
    val latestOnCenterTapped by rememberUpdatedState(onCenterTapped)
    val latestOnExternalLink by rememberUpdatedState(onExternalLinkClicked)
    val latestOnInternalLink by rememberUpdatedState(onInternalLinkClicked)
    val latestOnHighlightTapped by rememberUpdatedState(onHighlightTapped)
    val latestSettings by rememberUpdatedState(readerSettings)
    val latestForceScroll by rememberUpdatedState(forceScrollMode)
    val latestActiveSentence by rememberUpdatedState(activeSentenceIndex)
    val latestInitialPage by rememberUpdatedState(initialPageOnLoad)
    val latestInitialAnchor by rememberUpdatedState(initialAnchorOnLoad)
    val latestHasPrevChapter by rememberUpdatedState(hasPrevChapter)
    val latestHasNextChapter by rememberUpdatedState(hasNextChapter)
    val latestZipHolder by rememberUpdatedState(zipHolder)
    val latestHighlights by rememberUpdatedState(chapterHighlights)
    val effectiveBgColor = remember(readerSettings.themeMode, readerSettings.customThemeBgHex) {
        if (readerSettings.themeMode == ReaderThemeMode.CUSTOM) {
            runCatching { Color(android.graphics.Color.parseColor(readerSettings.customThemeBgHex)) }
                .getOrDefault(readerSettings.themeMode.background)
        } else {
            readerSettings.themeMode.background
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isLifecycleResumed = true
                    webViewRef?.let { wv ->
                        if (!wv.isDestroyedOrDead) {
                            runCatching {
                                wv.onResume()
                                wv.resumeTimers()
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isLifecycleResumed = false
                    webViewRef?.let { wv ->
                        if (!wv.isDestroyedOrDead) {
                            runCatching {
                                wv.onPause()
                                wv.pauseTimers()
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    isLifecycleResumed = false
                    webViewRef?.let { wv ->
                        destroyWebViewSafely(wv)
                        webViewRef = null
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
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
val contentKey = remember(fileKey, chapterPayload.chapterIndex, chapterPayload.htmlWithSentenceSpans, renderGeneration) {
    "$fileKey#${chapterPayload.chapterIndex}#${chapterPayload.htmlWithSentenceSpans.length}#${chapterPayload.htmlWithSentenceSpans.hashCode()}#$renderGeneration"
}
LaunchedEffect(readerSettings, forceScrollMode, pageLoadedKey, isLifecycleResumed) {
val wv = webViewRef ?: return@LaunchedEffect
if (isLifecycleResumed && pageLoadedKey.isNotBlank() && !wv.isDestroyedOrDead) {
runCatching {
wv.evaluateJavascript(buildFontFaceInjectionScript(readerSettings.customFontName), null)
wv.evaluateJavascript(buildThemeInjectionScript(readerSettings, forceScrollMode), null)
wv.evaluateJavascript(buildUserCustomCssScript(readerSettings.customCss), null)
val pagedFlag = if (!forceScrollMode && readerSettings.readingMode == ReaderMode.PAGED) "true" else "false"
wv.evaluateJavascript("window.__lexiPagedMode = $pagedFlag; window.lexiRecalcPagination && window.lexiRecalcPagination();", null)
}
}
}
LaunchedEffect(chapterHighlights, pageLoadedKey, isLifecycleResumed) {
val wv = webViewRef ?: return@LaunchedEffect
if (isLifecycleResumed && pageLoadedKey == contentKey && !wv.isDestroyedOrDead) {
runCatching {
wv.evaluateJavascript(buildHighlightsInjectionScript(chapterHighlights), null)
}
}
}
LaunchedEffect(activeSentenceIndex, pageLoadedKey, isLifecycleResumed) {
val wv = webViewRef ?: return@LaunchedEffect
if (isLifecycleResumed && pageLoadedKey == contentKey && !wv.isDestroyedOrDead) {
val isActive = if (activeSentenceIndex >= 0) "true" else "false"
runCatching {
wv.evaluateJavascript(
"window.__lexiTtsActive = $isActive; window.lexiHighlightSentence && window.lexiHighlightSentence($activeSentenceIndex, true);",
null
)
}
}
}
LaunchedEffect(targetPageTrigger, pageLoadedKey, isLifecycleResumed) {
val trigger = targetPageTrigger ?: return@LaunchedEffect
val wv = webViewRef ?: return@LaunchedEffect
if (isLifecycleResumed && pageLoadedKey == contentKey && effectivePagedMode && !wv.isDestroyedOrDead) {
runCatching {
wv.evaluateJavascript("window.lexiGoToPage && window.lexiGoToPage(${trigger.first}, false);", null)
}
}
}
    LaunchedEffect(pageFlipTrigger, pageLoadedKey, isLifecycleResumed) {
        val trigger = pageFlipTrigger ?: return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect
        if (isLifecycleResumed && pageLoadedKey == contentKey && !wv.isDestroyedOrDead) {
            runCatching {
                if (effectivePagedMode) {
                    wv.evaluateJavascript("window.lexiFlipPage && window.lexiFlipPage(${trigger.first});", null)
                } else {
                    val scrollDelta = (wv.height * 0.85f).toInt() * trigger.first
                    wv.evaluateJavascript("window.scrollBy({top: $scrollDelta, behavior: 'smooth'});", null)
                }
            }
        }
    }
    LaunchedEffect(webActionTrigger, pageLoadedKey, isLifecycleResumed) {
        val trigger = webActionTrigger ?: return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect
        if (isLifecycleResumed && !wv.isDestroyedOrDead) {
            runCatching {
                when (trigger.first) {
                    "SELECT_ALL" -> wv.evaluateJavascript("window.lexiSelectAll && window.lexiSelectAll();", null)
                    "CLEAR_SELECTION" -> {
                        wv.finishActiveActionMode()
                        wv.evaluateJavascript("window.lexiClearSelection && window.lexiClearSelection();", null)
                    }
                }
            }
        }
    }
    var lastHandledAnchorTs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(targetAnchorTrigger, pageLoadedKey, isLifecycleResumed) {
        val trigger = targetAnchorTrigger ?: return@LaunchedEffect
        if (trigger.second == lastHandledAnchorTs) return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect
        if (isLifecycleResumed && pageLoadedKey == contentKey && !wv.isDestroyedOrDead) {
            lastHandledAnchorTs = trigger.second
            runCatching {
                wv.evaluateJavascript(
                    "window.lexiScrollToAnchor && window.lexiScrollToAnchor(${JSONObject.quote(trigger.first)});",
                    null
                )
            }
        }
    }
Box(modifier = modifier.fillMaxSize()) {
if (consecutiveRendererFailures > 3) {
Column(
modifier = Modifier
.fillMaxSize()
.background(effectiveBgColor)
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
setBackgroundColor(effectiveBgColor.toArgb())
isHorizontalScrollBarEnabled = false
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
}
onResume()
resumeTimers()
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
// Both default to false, but they are set explicitly so a later refactor cannot
// silently re-open file:// access to the reader's own sandbox.
@Suppress("DEPRECATION")
allowFileAccessFromFileURLs = false
@Suppress("DEPRECATION")
allowUniversalAccessFromFileURLs = false
mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
    WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    @Suppress("DEPRECATION")
    forceDark = WebSettings.FORCE_DARK_OFF
}
}
addJavascriptInterface(
LexiWebJsBridge(
isAliveProvider = { !this@apply.isDestroyedOrDead },
enableSentenceTapProvider = { latestEnableSentenceTap },
onSentenceTappedCallback = { idx ->
post { if (!this@apply.isDestroyedOrDead) latestOnSentenceTapped(idx) }
},
onTextSelectedCallback = { rectInfo ->
post {
    if (!this@apply.isDestroyedOrDead) {
        this@apply.hasActiveJsSelection = rectInfo.text.isNotBlank()
        latestOnTextSelected(rectInfo.text, rectInfo.sentenceIndex)
        latestOnTextSelectedRect(rectInfo)
    }
}
},
onPageStateChangedCallback = { curPage, totalPages ->
post { if (!this@apply.isDestroyedOrDead) latestOnPageStateChanged(curPage, totalPages) }
},
onPageBoundaryReachedCallback = { dir ->
post {
if (this@apply.isDestroyedOrDead) return@post
if (dir < 0 && latestHasPrevChapter) {
latestOnPullPrev()
} else if (dir > 0) {
if (latestHasNextChapter) {
latestOnPullNext()
} else {
latestOnEndOfBook()
}
}
}
},
onCenterTappedCallback = {
post { if (!this@apply.isDestroyedOrDead) latestOnCenterTapped() }
},
onExternalLinkClickedCallback = { url ->
post { if (!this@apply.isDestroyedOrDead) latestOnExternalLink(url) }
},
onHighlightTappedCallback = { id ->
post { if (!this@apply.isDestroyedOrDead) latestOnHighlightTapped(id) }
},
onInternalLinkClickedCallback = { url ->
post { if (!this@apply.isDestroyedOrDead) latestOnInternalLink(url) }
}
),
"LexiBridge"
)
onPullStateChanged = { dir, prog ->
pullDir = dir
pullProgress = prog
post { if (!this@apply.isDestroyedOrDead) latestOnPullProgress(dir, prog) }
}
onPullTriggered = { dir ->
when (dir) {
ChapterPullDirection.NEXT -> if (latestHasNextChapter) latestOnPullNext() else latestOnEndOfBook()
ChapterPullDirection.PREV -> latestOnPullPrev()
ChapterPullDirection.NONE -> Unit
}
}
onScrollPositionChanged = { y ->
latestOnScroll(y)
}
onFlipPageRequested = { delta ->
if (!isDestroyedOrDead) {
evaluateJavascript("window.lexiFlipPage && window.lexiFlipPage($delta);", null)
}
}
this.onCenterTapped = {
latestOnCenterTapped()
}
webViewClient = object : WebViewClient() {
override fun shouldOverrideUrlLoading(
view: WebView?,
request: WebResourceRequest?
): Boolean {
if ((view as? LexiBookWebView)?.isDestroyedOrDead == true) return true
val url = request?.url?.toString().orEmpty()
// Only http(s) may be handed to the host. Everything else — intent://,
// file://, content://, javascript:, custom schemes — is swallowed here.
if (!url.startsWith(EpubParser.ASSET_BASE_URL) && isSafeExternalUrl(url)) {
post { if (!this@apply.isDestroyedOrDead) latestOnExternalLink(url) }
return true
}
if (url.startsWith(EpubParser.ASSET_BASE_URL) || url.startsWith("content://") || url.startsWith("file://") || url.startsWith("about:blank#") || url.startsWith("#")) {
post { if (!this@apply.isDestroyedOrDead) latestOnInternalLink(url) }
return true
}
return true
}
override fun shouldInterceptRequest(
view: WebView?,
request: WebResourceRequest?
): WebResourceResponse? {
if ((view as? LexiBookWebView)?.isDestroyedOrDead == true) return null
val url = request?.url?.toString() ?: return null
return runCatching {
val corsHeaders = mapOf("Access-Control-Allow-Origin" to "*")
if (url.startsWith("${EpubParser.ASSET_BASE_URL}__lexi_fonts/")) {
val fileName = url.removePrefix("${EpubParser.ASSET_BASE_URL}__lexi_fonts/")
val resId = when (fileName) {
"literata.ttf" -> R.font.literata
"plus_jakarta_sans.ttf" -> R.font.plus_jakarta_sans
"jetbrains_mono.ttf" -> R.font.jetbrains_mono
"atkinson_hyperlegible.ttf" -> R.font.atkinson_hyperlegible
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
if (fileName.startsWith("custom/")) {
val decoded = URLDecoder.decode(
fileName.removePrefix("custom/"),
StandardCharsets.UTF_8.name()
)
val fontDir = File(ctx.filesDir, "custom_fonts")
val target = fontDir.listFiles()?.firstOrNull {
it.name == decoded || it.nameWithoutExtension == decoded
}
if (target != null && target.exists()) {
val mime = if (target.extension.equals("otf", true)) "font/otf" else "font/ttf"
return@runCatching WebResourceResponse(
mime,
null,
200,
"OK",
corsHeaders,
FileInputStream(target)
)
}
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
if ((view as? LexiBookWebView)?.isDestroyedOrDead == true) return
consecutiveRendererFailures = 0
pageLoadedKey = lastLoadedKey
val anchoredJson = JSONArray().apply {
    latestAnchoredTerms.filter { it.termScope == "WORD" && it.isEnabled }.forEach { term ->
        put(
            JSONObject().apply {
                put("sentenceIndex", term.sentenceIndex)
                put("startOffset", term.startOffset)
                put("endOffset", term.endOffset)
                put("findText", term.findText)
                put("replaceWith", term.replaceWith)
            }
        )
    }
}
val settingsNow = latestSettings
val isPagedNow = !latestForceScroll && settingsNow.readingMode == ReaderMode.PAGED
view?.evaluateJavascript(buildFontFaceInjectionScript(settingsNow.customFontName), null)
view?.evaluateJavascript(buildThemeInjectionScript(settingsNow, latestForceScroll), null)
view?.evaluateJavascript(buildUserCustomCssScript(settingsNow.customCss), null)
view?.evaluateJavascript(buildHighlightsInjectionScript(latestHighlights), null)
val initPage = latestInitialPage
val initAnchor = latestInitialAnchor
val activeIdx = latestActiveSentence
val anchorJs = if (!initAnchor.isNullOrBlank()) {
    "requestAnimationFrame(function(){ requestAnimationFrame(function(){ setTimeout(function(){ window.lexiScrollToAnchor && window.lexiScrollToAnchor(${JSONObject.quote(initAnchor)}); }, 40); }); });"
} else ""
view?.evaluateJavascript(
"""
(function(){
window.__lexiPagedMode = ${if (isPagedNow) "true" else "false"};
if (window.lexiRecalcPagination) {
window.lexiRecalcPagination($initPage);
}
$anchorJs
${if (initPage < 0) "setTimeout(function(){ if (window.lexiScrollToEnd) window.lexiScrollToEnd(); }, 60);" else ""}
setTimeout(function(){ if (window.lexiApplyAnchoredTerms) window.lexiApplyAnchoredTerms($anchoredJson); }, 50);
${if (activeIdx >= 0) "setTimeout(function(){ window.lexiHighlightSentence && window.lexiHighlightSentence($activeIdx, true); }, 60);" else ""}
})();
""".trimIndent(),
null
)
}
override fun onRenderProcessGone(
view: WebView?,
detail: RenderProcessGoneDetail?
): Boolean {
Log.w(
"EpubWebView",
"WebView renderer terminated (didCrash=${detail?.didCrash()}); recovering cleanly."
)
val dead = view as? LexiBookWebView
if (dead != null) {
destroyWebViewSafely(dead)
if (webViewRef === dead) {
webViewRef = null
}
} else {
view?.let { v ->
runCatching {
v.stopLoading()
(v.parent as? ViewGroup)?.removeView(v)
v.destroy()
}
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
wv.isPagedMode = effectivePagedMode
wv.setBackgroundColor(effectiveBgColor.toArgb())
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
destroyWebViewSafely(wv)
if (webViewRef === wv) {
webViewRef = null
}
}
)
}
}
}
}
private fun Color.toCssHex(): String {
val argb = this.toArgb()
return String.format(Locale.US, "#%06X", argb and 0xFFFFFF)
}
internal fun escapeForJsSingleQuotedString(raw: String): String {
    return raw
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "")
        .replace("\n", " ")
}

internal fun buildHighlightsInjectionScript(highlights: List<BookmarkEntity>): String {
    val arr = JSONArray()
    highlights.filter { it.type == "HIGHLIGHT" }.forEach { hl ->
        arr.put(
            JSONObject().apply {
                put("id", hl.id)
                put("sentenceIndex", hl.sentenceIndex)
                put("startOffset", hl.startOffset)
                put("endOffset", hl.endOffset)
                put("word", hl.selectedText)
                put("highlightAll", hl.highlightAll)
                put("colorHex", hl.colorHex)
            }
        )
    }
    return "window.lexiApplyUserHighlights && window.lexiApplyUserHighlights($arr);"
}

internal fun buildFontFaceInjectionScript(customFontName: String = ""): String {
    val customRule = if (customFontName.isNotBlank()) {
        val safeName = java.net.URLEncoder.encode(customFontName, StandardCharsets.UTF_8.name())
        """
        @font-face {
            font-family: 'LexiUserCustom';
            src: url('${EpubParser.ASSET_BASE_URL}__lexi_fonts/custom/$safeName');
        }
        """.trimIndent()
    } else ""
    val fontCss = """
        @font-face {
            font-family: 'LexiLiterata';
            src: url('${EpubParser.ASSET_BASE_URL}__lexi_fonts/literata.ttf') format('truetype');
        }
        @font-face {
            font-family: 'LexiSans';
            src: url('${EpubParser.ASSET_BASE_URL}__lexi_fonts/plus_jakarta_sans.ttf') format('truetype');
        }
        @font-face {
            font-family: 'LexiMono';
            src: url('${EpubParser.ASSET_BASE_URL}__lexi_fonts/jetbrains_mono.ttf') format('truetype');
        }
        @font-face {
            font-family: 'LexiDyslexia';
            src: url('${EpubParser.ASSET_BASE_URL}__lexi_fonts/atkinson_hyperlegible.ttf') format('truetype');
        }
        $customRule
    """.trimIndent()
    val escaped = escapeForJsSingleQuotedString(fontCss)

    return """
        (function(){
          var parent = document.head || document.documentElement;
          if (!parent) return;
          var el = document.getElementById('lexi-fonts-style');
          if (!el) {
            el = document.createElement('style');
            el.id = 'lexi-fonts-style';
            parent.appendChild(el);
          }
          el.textContent = '$escaped';
        })();
    """.trimIndent()
}

internal fun buildThemeInjectionScript(
    settings: ReaderSettings,
    forceScrollMode: Boolean = false
): String {
    val theme = settings.themeMode
    val isCustom = theme == ReaderThemeMode.CUSTOM
    val bgColorObj = if (isCustom) {
        runCatching { Color(android.graphics.Color.parseColor(settings.customThemeBgHex)) }.getOrDefault(theme.background)
    } else theme.background
    val textColorObj = if (isCustom) {
        runCatching { Color(android.graphics.Color.parseColor(settings.customThemeTextHex)) }.getOrDefault(theme.text)
    } else theme.text
    val isDarkEffective = if (isCustom) {
        androidx.core.graphics.ColorUtils.calculateLuminance(bgColorObj.toArgb()) < 0.45
    } else theme.isDark

    val bgHex = bgColorObj.toCssHex()
    val textHex = textColorObj.toCssHex()
    val hiBgHex = theme.highlightBg.toCssHex()
    val hiTextHex = theme.highlightText.toCssHex()
    val linkHex = if (isCustom && settings.customThemeLinkHex.isNotBlank()) {
        runCatching { Color(android.graphics.Color.parseColor(settings.customThemeLinkHex)).toCssHex() }
            .getOrDefault(if (isDarkEffective) "#8CC4FF" else "#1D60A6")
    } else if (isDarkEffective) "#8CC4FF" else "#1D60A6"
    val borderHex = if (isDarkEffective) "#444C5C" else "#C8C2B8"

    val fontSizePx = settings.fontSizeSp.toInt().coerceIn(12, 36)
    val lineHeight = String.format(Locale.US, "%.2f", settings.lineSpacing.coerceIn(1.0f, 2.2f))
    val paraSpacing = String.format(Locale.US, "%.2fem", settings.paragraphSpacingEm.coerceIn(0f, 2.0f))
    val marginPx = settings.horizontalMarginDp.coerceIn(8, 48)
    val isPaged = !forceScrollMode && settings.readingMode == ReaderMode.PAGED

    val explicitFontStack: String? = when (settings.fontFamily) {
        ReaderFontFamily.ORIGINAL -> if (settings.usePublisherStyling) null else "LexiLiterata, Georgia, serif"
        ReaderFontFamily.SERIF -> "LexiLiterata, Georgia, serif"
        ReaderFontFamily.SANS_SERIF -> "LexiSans, sans-serif"
        ReaderFontFamily.MONOSPACE -> "LexiMono, monospace"
        ReaderFontFamily.DYSLEXIA -> "LexiDyslexia, LexiSans, sans-serif"
        ReaderFontFamily.CUSTOM -> if (settings.customFontName.isNotBlank()) "'LexiUserCustom', sans-serif" else "sans-serif"
    }

    val explicitAlign: String? = when (settings.textAlign) {
        ReaderTextAlign.PUBLISHER -> if (settings.usePublisherStyling) null else "left"
        ReaderTextAlign.LEFT -> "left"
        ReaderTextAlign.JUSTIFY -> "justify"
    }

    val rootLayoutCss = if (isPaged) {
        """
        html {
            background-color: $bgHex !important;
            color: $textHex !important;
            height: 100% !important;
            width: 100vw !important;
            margin: 0 !important;
            padding: 0 !important;
            overflow-x: auto !important;
            overflow-y: hidden !important;
            scrollbar-width: none !important;
            box-sizing: border-box !important;
        }
        html::-webkit-scrollbar { display: none !important; }
        body {
            background-color: $bgHex !important;
            color: $textHex !important;
            font-size: ${fontSizePx}px !important;
            line-height: $lineHeight !important;
            height: calc(100% - 24px) !important;
            width: auto !important;
            margin: 16px 0 !important;
            padding: 0 ${marginPx}px !important;
            box-sizing: border-box !important;
            column-width: calc(100vw - ${marginPx * 2}px) !important;
            -webkit-column-width: calc(100vw - ${marginPx * 2}px) !important;
            column-gap: ${marginPx * 2}px !important;
            -webkit-column-gap: ${marginPx * 2}px !important;
            column-fill: auto !important;
            -webkit-column-fill: auto !important;
            word-wrap: break-word !important;
            overflow-wrap: break-word !important;
        }
        figure, img, svg, table, pre, h1, h2, h3, h4, h5, h6 {
            break-inside: avoid-column;
            -webkit-column-break-inside: avoid;
        }
        p, li, blockquote {
            orphans: 2;
            widows: 2;
        }
        img, svg, video, figure {
            max-width: 100% !important;
            max-height: calc(100vh - 56px) !important;
            height: auto !important;
            object-fit: contain !important;
        }
        """.trimIndent()
    } else {
        """
        html, body {
            background-color: $bgHex !important;
            color: $textHex !important;
            font-size: ${fontSizePx}px !important;
            line-height: $lineHeight !important;
            margin: 0 !important;
            padding: 18px ${marginPx}px 64px ${marginPx}px !important;
            box-sizing: border-box !important;
            overflow-x: hidden !important;
            word-wrap: break-word !important;
            overflow-wrap: break-word !important;
        }
        img, svg, figure {
            max-width: 100% !important;
            height: auto !important;
            display: block;
            margin-left: auto;
            margin-right: auto;
        }
        """.trimIndent()
    }

    val typographyOverrideCss = buildString {
        if (settings.usePublisherStyling) {
            // Preserve publisher @font-face, drop caps, tables, ruby, ornamental/accent colors; adapt base surface & text colors cleanly
            if (isDarkEffective || isCustom) {
                append(
                    """
                    body, main, article, section {
                        background-color: transparent !important;
                    }
                    body p, body li, body blockquote, body dd, body dt {
                        color: $textHex;
                        background-color: transparent !important;
                    }
                    """.trimIndent()
                )
            } else {
                append(
                    """
                    body, main, article, section {
                        background-color: transparent !important;
                    }
                    """.trimIndent()
                )
            }
            append(" body p, body li, body blockquote { line-height: $lineHeight !important; } ")
            if (explicitFontStack != null) {
                append(" html, body, body p, body li, body div, body blockquote, body span, body h1, body h2, body h3, body h4, body h5, body h6 { font-family: $explicitFontStack !important; } ")
            }
            if (explicitAlign != null) {
                append(" body p, body li, body blockquote { text-align: $explicitAlign !important; } ")
            }
        } else {
            val fontCss = explicitFontStack ?: "LexiLiterata, Georgia, serif"
            val alignCss = explicitAlign ?: "left"
            append(
                """
                html, body {
                    font-family: $fontCss !important;
                }
                body p, body div, body span, body li, body blockquote, body dd, body dt, body td, body th,
                body h1, body h2, body h3, body h4, body h5, body h6 {
                    color: inherit !important;
                    background-color: transparent !important;
                    font-family: inherit !important;
                    line-height: inherit !important;
                }
                body p, body li, body blockquote {
                    font-size: ${fontSizePx}px !important;
                    margin-top: 0 !important;
                    margin-bottom: $paraSpacing !important;
                    text-align: $alignCss !important;
                }
                """.trimIndent()
            )
        }
        if (settings.fontFamily == ReaderFontFamily.DYSLEXIA) {
            append(" body p, body li, body blockquote { letter-spacing: 0.03em !important; word-spacing: 0.08em !important; } ")
        }
    }

    val commonCss = """
        *, *::before, *::after {
            -webkit-tap-highlight-color: rgba(0, 0, 0, 0) !important;
            outline: none !important;
        }
        a, img {
            -webkit-touch-callout: none !important;
        }
        body, p, div, span, li, blockquote, h1, h2, h3, h4, h5, h6, td, th, dt, dd {
            -webkit-user-select: text !important;
            user-select: text !important;
        }
        a { color: $linkHex !important; }
        table { border-collapse: collapse; max-width: 100% !important; }
        th, td { border-color: $borderHex; }
        body.lexi-no-select, body.lexi-no-select * {
            -webkit-user-select: none !important;
            user-select: none !important;
            -webkit-touch-callout: none !important;
        }
        ruby { ruby-align: center; }
        rt { font-size: 0.55em; line-height: 1.1; }
        .lexi-s {
            cursor: pointer;
            border-radius: 4px;
        }
        .lexi-s.lexi-active {
            background-color: $hiBgHex !important;
            color: $hiTextHex !important;
        }
    """.trimIndent()

    val fullCss = escapeForJsSingleQuotedString("$rootLayoutCss\n$typographyOverrideCss\n$commonCss")

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
          el.textContent = '$fullCss';
          var userEl = document.getElementById('lexi-user-css');
          if (userEl && userEl.parentNode === parent) {
            parent.appendChild(userEl);
          }

          // ---- motion -------------------------------------------------------
          // Page turn and chapter transition, ported from the Web Animations
          // approach used by Arcane Codex: animate the compositor, never the
          // layout. No class juggling and no forced reflow, so a 30k-word
          // chapter costs nothing to animate.
          window.__lexiTurnSound = ${if (settings.pageTurnSound) "true" else "false"};

          window.__lexiAnimateRaw = function (el, frames, opts) {
            try {
              if (!el || !el.animate) return;
              el.animate(frames, opts);
            } catch (e) { /* animation is decoration */ }
          };

          // Honours the system "remove animations" setting for movement. A short
          // opacity change is not movement, so the page turn uses the raw call.
          window.__lexiAnimate = function (el, frames, opts) {
            try {
              if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
              window.__lexiAnimateRaw(el, frames, opts);
            } catch (e) { /* animation is decoration */ }
          };

          // A negative initial page means "the end of this chapter". Paged mode
          // understood that; scroll mode ignored it, so going back a chapter always
          // opened at its first line.
          window.lexiScrollToEnd = function () {
            try {
              window.scrollTo(0, document.body.scrollHeight);
            } catch (e) {}
          };

          window.lexiSuppressSelection = function () {
            try {
              document.body.classList.add('lexi-no-select');
              var sel = window.getSelection ? window.getSelection() : null;
              if (sel && sel.removeAllRanges) sel.removeAllRanges();
            } catch (e) {}
          };
          window.lexiRestoreSelection = function () {
            try { document.body.classList.remove('lexi-no-select'); } catch (e) {}
          };

          window.lexiPageFade = function () {
            var frames = [{ opacity: 0.35 }, { opacity: 1 }];
            var opts = { duration: 180, easing: 'ease-out' };
            // In paged mode the columns live on body and the scroll container is
            // html, so both are faded; whichever one is painting gets the effect.
            window.__lexiAnimateRaw(document.documentElement, frames, opts);
            if (document.body && document.body !== document.documentElement) {
              window.__lexiAnimateRaw(document.body, frames, opts);
            }
          };

          window.lexiChapterIn = function () {
            window.__lexiAnimate(document.body,
              [{ transform: 'translateY(42px)', opacity: 0.25 },
               { transform: 'translateY(0)', opacity: 1 }],
              { duration: 420, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' });
          };

          // Short filtered-noise paper turn. Silent unless the setting is on.
          window.lexiTurnSound = function () {
            if (!window.__lexiTurnSound) return;
            try {
              var Ctx = window.AudioContext || window.webkitAudioContext;
              if (!Ctx) return;
              var ctx = new Ctx();
              var n = Math.floor(ctx.sampleRate * 0.085);
              var buf = ctx.createBuffer(1, n, ctx.sampleRate);
              var data = buf.getChannelData(0);
              for (var i = 0; i < n; i++) {
                data[i] = (Math.random() * 2 - 1) * Math.sin((i / n) * Math.PI) * 0.14;
              }
              var src = ctx.createBufferSource();
              src.buffer = buf;
              var filter = ctx.createBiquadFilter();
              filter.type = 'bandpass';
              filter.frequency.value = 1400;
              filter.Q.value = 1.2;
              src.connect(filter);
              filter.connect(ctx.destination);
              src.start();
              src.onended = function () { try { ctx.close(); } catch (e) {} };
            } catch (e) { /* autoplay policy, ignore */ }
          };

          // Wrap the page flip once so every flip gets the fade and the sound,
          // however it was triggered (tap zone, volume key, TTS follow-along).
          if (!window.__lexiFlipWrapped) {
            var originalFlip = window.lexiFlipPage;
            if (typeof originalFlip === 'function') {
              window.__lexiFlipWrapped = true;
              window.lexiFlipPage = function (delta) {
                window.lexiTurnSound();
                window.lexiPageFade();
                return originalFlip(delta);
              };
            }
          }

          // A chapter that arrives in scroll mode slides up instead of appearing.
          if (!${if (isPaged) "true" else "false"}) {
            window.lexiChapterIn();
          }
        })();
    """.trimIndent()
}

internal fun buildUserCustomCssScript(customCss: String): String {
    val escaped = escapeForJsSingleQuotedString(customCss.trim())
    return """
        (function(){
          var parent = document.head || document.documentElement;
          if (!parent) return;
          var el = document.getElementById('lexi-user-css');
          if (!el) {
            el = document.createElement('style');
            el.id = 'lexi-user-css';
          }
          el.textContent = '$escaped';
          parent.appendChild(el);
        })();
    """.trimIndent()
}
