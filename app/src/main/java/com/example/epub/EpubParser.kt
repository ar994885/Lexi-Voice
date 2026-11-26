package com.example.epub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Xml
import com.example.data.db.PronunciationRuleEntity
import com.example.tts.PronunciationProcessor
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object EpubParser {

    const val ASSET_HOST = "appassets.androidplatform.net"
    const val ASSET_BASE_URL = "https://$ASSET_HOST/epub/"

    private data class OpfInfo(
        val title: String,
        val author: String,
        val language: String,
        val opfDir: String,
        val coverHref: String?,
        val ncxHref: String?,
        val navHref: String?,
        val spineEntries: List<Pair<String, String>> // (id, fullZipPath)
    )

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    data class WebChapterPayload(
        val chapterIndex: Int,
        val chapterTitle: String,
        val baseUrl: String,
        val htmlWithSentenceSpans: String,
        val sentences: List<ReaderSentence>,
        val paragraphs: List<ReaderParagraph>,
        val plainText: String
    )

    data class ZipResourceData(
        val bytes: ByteArray,
        val mimeType: String
    )

    data class InBookSearchResult(
        val chapterIndex: Int,
        val chapterTitle: String,
        val sentenceIndex: Int,
        val snippet: String
    )

    fun extractMetadataForIndexing(
        context: Context,
        fileKey: String,
        isDirectPath: Boolean,
        fallbackFileName: String
    ): EpubIndexedMetadata {
        return try {
            if (isDirectPath) {
                ZipFile(File(fileKey)).use { zip ->
                    extractMetadataFromZip(context, fileKey, zip, fallbackFileName)
                }
            } else {
                val tempFile = copyUriToTemp(context, Uri.parse(fileKey))
                    ?: return defaultMetadata(fallbackFileName)
                try {
                    ZipFile(tempFile).use { zip ->
                        extractMetadataFromZip(context, fileKey, zip, fallbackFileName)
                    }
                } finally {
                    tempFile.delete()
                }
            }
        } catch (_: Exception) {
            defaultMetadata(fallbackFileName)
        }
    }

    private fun extractMetadataFromZip(
        context: Context,
        fileKey: String,
        zip: ZipFile,
        fallbackFileName: String
    ): EpubIndexedMetadata {
        val opfPath = findOpfPath(zip) ?: return defaultMetadata(fallbackFileName)
        val opfEntry = getZipEntryIgnoreCase(zip, opfPath) ?: return defaultMetadata(fallbackFileName)
        val opf = zip.getInputStream(opfEntry).use { parseOpf(it, opfPath) }

        val coverPath = opf.coverHref?.let { href ->
            val resolved = resolvePath(opf.opfDir, href)
            saveCoverThumbnail(context, zip, resolved, fileKey)
        }

        val cleanTitle = opf.title.ifBlank {
            fallbackFileName.removeSuffix(".epub").removeSuffix(".EPUB").trim()
        }
        val cleanAuthor = opf.author.ifBlank { "Unknown Author" }

        return EpubIndexedMetadata(
            title = cleanTitle,
            author = cleanAuthor,
            language = opf.language,
            coverPath = coverPath,
            chapterCount = opf.spineEntries.size
        )
    }

    fun parseBookStructure(
        context: Context,
        fileKey: String,
        isDirectPath: Boolean,
        fallbackTitle: String
    ): EpubBookStructure {
        val localZipFile = resolveReadableZipFile(context, fileKey, isDirectPath)
            ?: return EpubBookStructure(fallbackTitle, "Unknown Author", "", null, emptyList())
        val shouldDelete = !isDirectPath
        return try {
            ZipFile(localZipFile).use { zip ->
                val opfPath = findOpfPath(zip)
                    ?: return EpubBookStructure(fallbackTitle, "Unknown Author", "", null, emptyList())
                val opfEntry = getZipEntryIgnoreCase(zip, opfPath)
                    ?: return EpubBookStructure(fallbackTitle, "Unknown Author", "", null, emptyList())
                val opf = zip.getInputStream(opfEntry).use { parseOpf(it, opfPath) }

                val tocMap = buildTocTitleMap(zip, opf)
                val chapters = opf.spineEntries.mapIndexed { idx, (_, path) ->
                    val normalized = normalizePath(path)
                    val title = tocMap[normalized]
                        ?: extractTitleFromHtmlQuick(zip, path)
                        ?: "Chapter ${idx + 1}"
                    val entry = getZipEntryIgnoreCase(zip, path)
                    val approxWords = ((entry?.size ?: 0L).coerceAtLeast(0L) / 12L).toInt().coerceAtLeast(50)
                    val estMin = (approxWords / 155).coerceAtLeast(1)
                    EpubChapterSummary(
                        index = idx,
                        title = title,
                        href = path,
                        wordCount = approxWords,
                        estimatedMinutes = estMin
                    )
                }

                val coverPath = opf.coverHref?.let {
                    saveCoverThumbnail(context, zip, resolvePath(opf.opfDir, it), fileKey)
                }

                EpubBookStructure(
                    title = opf.title.ifBlank { fallbackTitle },
                    author = opf.author.ifBlank { "Unknown Author" },
                    language = opf.language,
                    coverPath = coverPath,
                    chapters = chapters
                )
            }
        } catch (_: Exception) {
            EpubBookStructure(fallbackTitle, "Unknown Author", "", null, emptyList())
        } finally {
            if (shouldDelete) localZipFile.delete()
        }
    }

    /**
     * Prepares a chapter for WebView rendering with full original CSS/images preserved AND
     * every sentence wrapped in `<span class="lexi-s" data-idx="N">` for live highlighting & tapping.
     */
    fun loadChapterForWebView(
        context: Context,
        fileKey: String,
        isDirectPath: Boolean,
        chapterIndex: Int,
        chapterHref: String,
        chapterTitle: String,
        visualRules: List<PronunciationRuleEntity> = emptyList(),
        bookId: Long? = null
    ): WebChapterPayload {
        val localZipFile = resolveReadableZipFile(context, fileKey, isDirectPath)
            ?: return emptyWebPayload(chapterIndex, chapterTitle, chapterHref)
        val shouldDelete = !isDirectPath
        return try {
            ZipFile(localZipFile).use { zip ->
                val entry = getZipEntryIgnoreCase(zip, chapterHref)
                    ?: return emptyWebPayload(chapterIndex, chapterTitle, chapterHref)
                val rawHtml = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).readText()
                buildWebChapterPayload(
                    chapterIndex = chapterIndex,
                    chapterTitle = chapterTitle,
                    chapterHref = chapterHref,
                    rawHtml = rawHtml,
                    visualRules = visualRules,
                    bookId = bookId
                )
            }
        } catch (_: Exception) {
            emptyWebPayload(chapterIndex, chapterTitle, chapterHref)
        } finally {
            if (shouldDelete) localZipFile.delete()
        }
    }

    fun buildWebChapterPayload(
        chapterIndex: Int,
        chapterTitle: String,
        chapterHref: String,
        rawHtml: String,
        visualRules: List<PronunciationRuleEntity> = emptyList(),
        bookId: Long? = null
    ): WebChapterPayload {
        val doc = Jsoup.parse(rawHtml)
        doc.outputSettings().prettyPrint(false)

        // EPUB is untrusted input and the format permits scripted XHTML. Strip
        // everything executable from the book's markup before it is serialised
        // into the reader page, so the only script that runs in that page is
        // Lexi-Voice's own bridge below.
        doc.select("script, noscript, iframe, object, embed").remove()
        doc.allElements.forEach { el ->
            val unsafe = el.attributes().asList().filter { attr ->
                attr.key.startsWith("on", ignoreCase = true) ||
                    attr.value.trim().startsWith("javascript:", ignoreCase = true)
            }
            unsafe.forEach { el.removeAttr(it.key) }
        }

        val chapterDir = chapterHref.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val baseUrl = "$ASSET_BASE_URL$chapterDir"

        val head = doc.head()
        if (head.selectFirst("meta[name=viewport]") == null) {
            head.prependElement("meta")
                .attr("name", "viewport")
                .attr("content", "width=device-width, initial-scale=1.0, maximum-scale=3.0")
        }

        val sentences = mutableListOf<ReaderSentence>()
        val paragraphs = mutableListOf<ReaderParagraph>()
        val plainBlocks = mutableListOf<String>()

        var globalSentenceIdx = 0
        var paraIdx = 0

        val body = doc.body()
        val skipTags = setOf("script", "style", "svg", "title", "noscript", "rt", "rp")
        val textNodes = mutableListOf<TextNode>()

        fun collectTextNodes(node: Node) {
            if (node is Element && node.tagName().lowercase(Locale.ROOT) in skipTags) return
            if (node is TextNode) {
                if (node.wholeText.isNotEmpty()) textNodes.add(node)
                return
            }
            for (child in node.childNodes()) {
                collectTextNodes(child)
            }
        }
        collectTextNodes(body)

        val blockTags = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "pre", "div", "section", "dt", "dd")
        fun nearestBlock(node: Node): Element {
            var curr = node.parent()
            while (curr is Element && curr != body) {
                if (curr.tagName().lowercase(Locale.ROOT) in blockTags) return curr
                curr = curr.parent()
            }
            return body
        }

        val groupedByBlock = linkedMapOf<Element, MutableList<TextNode>>()
        for (tn in textNodes) {
            groupedByBlock.getOrPut(nearestBlock(tn)) { mutableListOf() }.add(tn)
        }

        data class NodeSpanRange(
            val node: TextNode,
            val rawText: String,
            val startInConcat: Int,
            val endInConcat: Int
        )

        for ((blockEl, nodes) in groupedByBlock) {
            val blockText = blockEl.text().replace(Regex("\\s+"), " ").trim()
            if (blockText.isNotBlank()) plainBlocks.add(blockText)

            val concatBuilder = StringBuilder()
            val nodeRanges = mutableListOf<NodeSpanRange>()
            for (tn in nodes) {
                val raw = tn.wholeText
                val start = concatBuilder.length
                concatBuilder.append(raw)
                nodeRanges.add(NodeSpanRange(tn, raw, start, concatBuilder.length))
            }

            val fullBlockRaw = concatBuilder.toString()
            if (fullBlockRaw.isBlank()) continue

            val splitSentences = splitPreservingWhitespace(fullBlockRaw)
            val blockSentences = mutableListOf<ReaderSentence>()

            data class SentenceRange(val sentIdx: Int, val start: Int, val end: Int)
            val sentenceRanges = mutableListOf<SentenceRange>()

            var cursor = 0
            var offsetInPara = 0
            for ((isSentence, chunk) in splitSentences) {
                val chunkStart = cursor
                val chunkEnd = cursor + chunk.length
                cursor = chunkEnd

                if (isSentence) {
                    val cleanText = chunk.replace(Regex("\\s+"), " ").trim()
                    val previousRange = sentenceRanges.lastOrNull()
                    val previousSentence = blockSentences.lastOrNull()

                    // A closing bracket that the splitter cut off on its own -- the
                    // "]" after "[ Note 1: ... ]" -- belongs to the sentence before
                    // it. Left alone it becomes its own chunk, which TTS reads as a
                    // stray vowel and which made "previous sentence" look stuck.
                    if (previousRange != null && previousSentence != null &&
                        cleanText.isNotEmpty() && isOrphanClosingFragment(cleanText)
                    ) {
                        val mergedText = (previousSentence.text + " " + cleanText).trim()
                        val merged = previousSentence.copy(
                            endInParagraph = previousSentence.startInParagraph + mergedText.length,
                            text = mergedText
                        )
                        // Replace rather than append: the orphan must not consume a
                        // sentence index, or TTS and the highlight anchors drift apart.
                        blockSentences[blockSentences.size - 1] = merged
                        sentences[sentences.size - 1] = merged
                        sentenceRanges[sentenceRanges.size - 1] =
                            SentenceRange(previousRange.sentIdx, previousRange.start, chunkEnd)
                        offsetInPara = merged.endInParagraph + 1
                    } else {
                        val sIdx = globalSentenceIdx++
                        val startPos = offsetInPara
                        offsetInPara += cleanText.length + 1
                        val sentenceObj = ReaderSentence(
                            globalIndex = sIdx,
                            paragraphIndex = paraIdx,
                            startInParagraph = startPos,
                            endInParagraph = startPos + cleanText.length,
                            text = cleanText
                        )
                        sentences.add(sentenceObj)
                        blockSentences.add(sentenceObj)
                        sentenceRanges.add(SentenceRange(sIdx, chunkStart, chunkEnd))
                    }
                }
            }

            if (sentenceRanges.isEmpty()) continue

            for (nr in nodeRanges) {
                if (nr.rawText.isEmpty()) continue
                val overlapping = sentenceRanges.filter { it.end > nr.startInConcat && it.start < nr.endInConcat }
                if (overlapping.isEmpty()) continue

                val replacementNodes = mutableListOf<Node>()
                var localCursor = 0

                for (sr in overlapping) {
                    val overlapStartLocal = (sr.start - nr.startInConcat).coerceIn(0, nr.rawText.length)
                    val overlapEndLocal = (sr.end - nr.startInConcat).coerceIn(0, nr.rawText.length)

                    if (overlapStartLocal > localCursor) {
                        replacementNodes.add(TextNode(nr.rawText.substring(localCursor, overlapStartLocal)))
                    }
                    if (overlapEndLocal > overlapStartLocal) {
                        val piece = nr.rawText.substring(overlapStartLocal, overlapEndLocal)
                        val span = Element("span")
                            .addClass("lexi-s")
                            .attr("data-idx", sr.sentIdx.toString())
                            .text(piece)
                        replacementNodes.add(span)
                    }
                    localCursor = overlapEndLocal
                }

                if (localCursor < nr.rawText.length) {
                    replacementNodes.add(TextNode(nr.rawText.substring(localCursor)))
                }

                if (replacementNodes.isNotEmpty()) {
                    for (rep in replacementNodes) {
                        nr.node.before(rep)
                    }
                    nr.node.remove()
                }
            }

            if (blockSentences.isNotEmpty()) {
                val tag = blockEl.tagName().lowercase(Locale.ROOT)
                val hLevel = if (tag.startsWith("h") && tag.length == 2) tag[1].digitToIntOrNull() ?: 0 else 0
                paragraphs.add(
                    ReaderParagraph(
                        index = paraIdx++,
                        text = blockText,
                        isHeading = hLevel > 0,
                        headingLevel = hLevel,
                        sentences = blockSentences
                    )
                )
            }
        }

        val activeVisualRules = visualRules.filter { it.isEnabled && it.applyToReaderText && it.findText.isNotEmpty() }
        if (activeVisualRules.isNotEmpty()) {
            PronunciationProcessor.applyReaderRulesToTextNodes(
                root = body,
                rules = activeVisualRules,
                bookId = bookId
            )
        }

        body.appendChild(
            Element("script").appendChild(
                DataNode(
                    """
                    (function(){
                      window.__lexiPagedMode = false;
                      window.__lexiCurrentPage = 0;
                      window.__lexiTotalPages = 1;
                      window.__lexiLastSelectedSentIdx = -1;

                      function getPageWidth() {
                        return Math.max(1, window.innerWidth || document.documentElement.clientWidth || 1);
                      }

                      function notifyPageState() {
                        try {
                          if (window.LexiBridge && window.LexiBridge.onPageStateChanged) {
                            window.LexiBridge.onPageStateChanged(window.__lexiCurrentPage, window.__lexiTotalPages);
                          }
                        } catch (e) {}
                      }

                      window.lexiGoToPage = function(pageIdx, smooth) {
                        var pageW = getPageWidth();
                        var total = Math.max(1, window.__lexiTotalPages || 1);
                        var clamped = Math.max(0, Math.min(total - 1, pageIdx));
                        window.__lexiCurrentPage = clamped;
                        var targetX = clamped * pageW;
                        try {
                          window.scrollTo({ left: targetX, top: 0, behavior: smooth ? 'smooth' : 'auto' });
                        } catch (e) {
                          window.scrollTo(targetX, 0);
                        }
                        notifyPageState();
                      };

                      window.lexiRecalcPagination = function(jumpToPage) {
                        if (!window.__lexiPagedMode) {
                          window.__lexiCurrentPage = 0;
                          window.__lexiTotalPages = 1;
                          notifyPageState();
                          return;
                        }
                        requestAnimationFrame(function() {
                          requestAnimationFrame(function() {
                            var pageW = getPageWidth();
                            var scrollW = document.documentElement.scrollWidth || pageW;
                            var total = Math.max(1, Math.ceil(Math.max(0, scrollW - 4) / pageW));
                            window.__lexiTotalPages = total;
                            var target = 0;
                            if (jumpToPage === -1) {
                              target = total - 1;
                            } else if (typeof jumpToPage === 'number' && jumpToPage >= 0) {
                              target = Math.min(total - 1, jumpToPage);
                            } else {
                              target = Math.max(0, Math.min(total - 1, window.__lexiCurrentPage || 0));
                            }
                            window.lexiGoToPage(target, false);
                          });
                        });
                      };

                      window.lexiFlipPage = function(delta) {
                        if (!window.__lexiPagedMode) return;
                        var next = (window.__lexiCurrentPage || 0) + delta;
                        if (next < 0) {
                          if (window.LexiBridge && window.LexiBridge.onPageBoundaryReached) {
                            window.LexiBridge.onPageBoundaryReached(-1);
                          }
                        } else if (next >= (window.__lexiTotalPages || 1)) {
                          if (window.LexiBridge && window.LexiBridge.onPageBoundaryReached) {
                            window.LexiBridge.onPageBoundaryReached(1);
                          }
                        } else {
                          window.lexiGoToPage(next, false);
                        }
                      };

                      window.lexiHighlightSentence = function(idx, shouldScroll) {
                        var prev = document.querySelectorAll('.lexi-s.lexi-active');
                        for (var i = 0; i < prev.length; i++) {
                          prev[i].classList.remove('lexi-active');
                        }
                        if (idx < 0) return;
                        var targets = document.querySelectorAll('.lexi-s[data-idx="' + idx + '"]');
                        for (var j = 0; j < targets.length; j++) {
                          targets[j].classList.add('lexi-active');
                        }
                        if (shouldScroll && targets.length > 0) {
                          if (window.__lexiPagedMode) {
                            var pageW = getPageWidth();
                            var curScroll = window.scrollX || document.documentElement.scrollLeft || 0;
                            var absX = targets[0].getBoundingClientRect().left + curScroll;
                            var targetPage = Math.max(0, Math.floor(absX / pageW));
                            if (targetPage !== window.__lexiCurrentPage) {
                              window.lexiGoToPage(targetPage, false);
                            }
                          } else {
                            targets[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
                          }
                        }
                      };

                      // ---- user highlights -------------------------------------
                      // A highlight is a character range inside one sentence span, not
                      // the whole span: selecting a word must not paint the line. Ranges
                      // go through the CSS Custom Highlight API so the DOM is never
                      // touched, which keeps pagination and TTS spans intact.
                      window.__lexiHighlightRanges = [];

                      window.lexiTextOffsetWithin = function(span, node, offset) {
                        try {
                          var walker = document.createTreeWalker(span, NodeFilter.SHOW_TEXT, null);
                          var total = 0, n;
                          while ((n = walker.nextNode())) {
                            if (n === node) return total + offset;
                            total += n.textContent.length;
                          }
                        } catch (e) {}
                        return -1;
                      };

                      window.lexiRangeWithin = function(span, start, end, word) {
                        try {
                          var nodes = [];
                          var walker = document.createTreeWalker(span, NodeFilter.SHOW_TEXT, null);
                          var n;
                          while ((n = walker.nextNode())) nodes.push(n);
                          if (nodes.length === 0) return null;

                          function locate(target) {
                            var acc = 0;
                            for (var i = 0; i < nodes.length; i++) {
                              var len = nodes[i].textContent.length;
                              if (target <= acc + len) return { node: nodes[i], offset: target - acc };
                              acc += len;
                            }
                            var last = nodes[nodes.length - 1];
                            return { node: last, offset: last.textContent.length };
                          }

                          var s = start, e = end;
                          if (!(e > s)) {
                            if (!word) return null;
                            var full = span.textContent || '';
                            var at = full.indexOf(word);
                            if (at < 0) return null;
                            s = at;
                            e = at + word.length;
                          }
                          var a = locate(s), b = locate(e);
                          if (!a || !b) return null;
                          var range = document.createRange();
                          range.setStart(a.node, Math.max(0, Math.min(a.offset, a.node.textContent.length)));
                          range.setEnd(b.node, Math.max(0, Math.min(b.offset, b.node.textContent.length)));
                          return range;
                        } catch (err) {
                          return null;
                        }
                      };

                      window.lexiApplyUserHighlights = function(items) {
                        var existing = document.querySelectorAll('.lexi-s.lexi-user-hl');
                        for (var i = 0; i < existing.length; i++) {
                          existing[i].classList.remove('lexi-user-hl');
                          existing[i].style.backgroundColor = '';
                        }
                        window.__lexiHighlightRanges = [];
                        if (window.CSS && CSS.highlights) {
                          Array.from(CSS.highlights.keys()).forEach(function(key) {
                            if (key.indexOf('lexi-hl-') === 0) CSS.highlights.delete(key);
                          });
                        }
                        if (!Array.isArray(items) || items.length === 0) return;

                        var supported = !!(window.CSS && CSS.highlights && window.Highlight);
                        var groups = {};
                        for (var k = 0; k < items.length; k++) {
                          var item = items[k];
                          var spans = document.querySelectorAll('.lexi-s[data-idx="' + item.sentenceIndex + '"]');
                          for (var s2 = 0; s2 < spans.length; s2++) {
                            if (!supported) {
                              // Older WebView: fall back to the sentence, which is the
                              // behaviour this replaces.
                              spans[s2].classList.add('lexi-user-hl');
                              spans[s2].style.backgroundColor = item.colorHex + '66';
                              continue;
                            }
                            var range = window.lexiRangeWithin(spans[s2], item.startOffset, item.endOffset, item.word);
                            if (!range) continue;
                            var key = 'lexi-hl-' + String(item.colorHex || '#FFEB3B').replace('#', '');
                            if (!groups[key]) groups[key] = { color: item.colorHex || '#FFEB3B', hl: new Highlight() };
                            groups[key].hl.add(range);
                            window.__lexiHighlightRanges.push({ range: range, id: item.id, colorHex: item.colorHex });
                          }
                        }
                        // "Highlight all <word> in this book": every other occurrence
                        // in this chapter carries the same note. Occurrences are found
                        // per span so each one becomes its own range.
                        if (supported) {
                          for (var w = 0; w < items.length; w++) {
                            var allItem = items[w];
                            if (!allItem.highlightAll || !allItem.word) continue;
                            var allKey = 'lexi-hl-' + String(allItem.colorHex || '#FFEB3B').replace('#', '');
                            if (!groups[allKey]) groups[allKey] = { color: allItem.colorHex || '#FFEB3B', hl: new Highlight() };
                            var everySpan = document.querySelectorAll('.lexi-s');
                            for (var q = 0; q < everySpan.length; q++) {
                              var text = everySpan[q].textContent || '';
                              var from = 0, at;
                              while ((at = text.indexOf(allItem.word, from)) >= 0) {
                                var hit = window.lexiRangeWithin(everySpan[q], at, at + allItem.word.length, allItem.word);
                                if (hit) {
                                  groups[allKey].hl.add(hit);
                                  window.__lexiHighlightRanges.push({ range: hit, id: allItem.id, colorHex: allItem.colorHex });
                                }
                                from = at + allItem.word.length;
                              }
                            }
                          }
                        }

                        Object.keys(groups).forEach(function(key) {
                          CSS.highlights.set(key, groups[key].hl);
                          var styleId = 'lexi-style-' + key;
                          if (!document.getElementById(styleId)) {
                            var st = document.createElement('style');
                            st.id = styleId;
                            st.textContent = '::highlight(' + key + ') { background-color: ' + groups[key].color + '; }';
                            (document.head || document.documentElement).appendChild(st);
                          }
                        });
                      };

                      // Tapping a highlight opens its note. The ranges are plain objects,
                      // so hit-testing is done against the stored range list.
                      document.addEventListener('click', function(ev) {
                        try {
                          if (!window.__lexiHighlightRanges || window.__lexiHighlightRanges.length === 0) return;
                          var sel = window.getSelection ? window.getSelection() : null;
                          if (sel && sel.toString && sel.toString().trim().length > 0) return;
                          var caret = document.caretRangeFromPoint
                            ? document.caretRangeFromPoint(ev.clientX, ev.clientY)
                            : null;
                          if (!caret) return;
                          for (var i = 0; i < window.__lexiHighlightRanges.length; i++) {
                            var entry = window.__lexiHighlightRanges[i];
                            var r = entry.range;
                            if (r.isPointInRange(caret.startContainer, caret.startOffset)) {
                              if (window.LexiBridge && window.LexiBridge.onHighlightTapped) {
                                window.LexiBridge.onHighlightTapped(String(entry.id));
                              }
                              ev.preventDefault();
                              ev.stopPropagation();
                              return;
                            }
                          }
                        } catch (e) {}
                      }, true);

                      window.lexiGetVisibleSentenceIndex = function() {
                        var spans = document.querySelectorAll('.lexi-s');
                        var vw = getPageWidth();
                        var vh = window.innerHeight || document.documentElement.clientHeight || 1;
                        for (var i = 0; i < spans.length; i++) {
                          var rect = spans[i].getBoundingClientRect();
                          if (rect.width > 0 && rect.height > 0 && rect.left >= -10 && rect.left < vw && rect.top >= 0 && rect.top < vh) {
                            var idx = parseInt(spans[i].getAttribute('data-idx'), 10);
                            if (!isNaN(idx)) return idx;
                          }
                        }
                        return 0;
                      };

                      window.lexiScrollToAnchor = function(anchorId) {
                        if (!anchorId) return;
                        var anchor = document.getElementById(anchorId) || (document.getElementsByName ? document.getElementsByName(anchorId)[0] : null);
                        if (anchor) {
                          if (window.__lexiPagedMode) {
                            var pW = getPageWidth();
                            var cS = window.scrollX || document.documentElement.scrollLeft || 0;
                            var aX = anchor.getBoundingClientRect().left + cS;
                            window.lexiGoToPage(Math.max(0, Math.floor(aX / pW)), false);
                          } else {
                            anchor.scrollIntoView({ behavior: 'smooth', block: 'start' });
                          }
                        }
                      };

                      document.addEventListener('click', function(ev) {
                        try {
                          var selText = window.getSelection ? window.getSelection().toString().trim() : '';
                          if (selText.length > 0) return;
                          var link = ev.target && ev.target.closest ? ev.target.closest('a') : null;
                          if (link && (link.hasAttribute('href') || link.hasAttribute('xlink:href'))) {
                            var href = (link.getAttribute('href') || link.getAttribute('xlink:href') || '').trim();
                            var resolvedHref = (typeof link.href === 'string' && link.href.length > 0) ? link.href : href;
                            if (href.indexOf('http://') === 0 || href.indexOf('https://') === 0) {
                              if (href.indexOf('${ASSET_BASE_URL}') !== 0) {
                                ev.preventDefault();
                                ev.stopPropagation();
                                if (window.LexiBridge && window.LexiBridge.onExternalLinkClicked) {
                                  window.LexiBridge.onExternalLinkClicked(href);
                                }
                                return;
                              }
                            }
                            if (href.indexOf('#') === 0) {
                              ev.preventDefault();
                              ev.stopPropagation();
                              if (href.length > 1) {
                                window.lexiScrollToAnchor && window.lexiScrollToAnchor(href.substring(1));
                              }
                              return;
                            }
                            if (href.length > 0 && href.indexOf('javascript:') !== 0) {
                              ev.preventDefault();
                              ev.stopPropagation();
                              if (window.LexiBridge && window.LexiBridge.onInternalLinkClicked) {
                                window.LexiBridge.onInternalLinkClicked(resolvedHref || href);
                              }
                              return;
                            }
                          }
                          var vw = getPageWidth();
                          var x = ev.clientX || 0;
                          if (window.__lexiPagedMode) {
                            if (x < vw * 0.25) {
                              window.lexiFlipPage(-1);
                              return;
                            }
                            if (x > vw * 0.75) {
                              window.lexiFlipPage(1);
                              return;
                            }
                          }
                          var span = ev.target && ev.target.closest ? ev.target.closest('.lexi-s') : null;
                          var sentIdx = -1;
                          if (span) {
                            var parsed = parseInt(span.getAttribute('data-idx'), 10);
                            if (!isNaN(parsed) && parsed >= 0) {
                              sentIdx = parsed;
                            }
                          }
                          if (sentIdx >= 0 && window.LexiBridge && window.LexiBridge.onSentenceTapped) {
                            window.LexiBridge.onSentenceTapped(sentIdx);
                          } else if (window.LexiBridge && window.LexiBridge.onCenterTapped) {
                            window.LexiBridge.onCenterTapped();
                          }
                        } catch (e) {}
                        });
var lexiSelTimer = null;
var lexiHadSelection = false;
var lexiEmitSelection = function() {
  try {
    var selection = window.getSelection ? window.getSelection() : null;
    var sel = selection ? selection.toString().trim() : '';
    if (sel.length > 0 && selection && selection.rangeCount > 0) {
      var range = selection.getRangeAt(0);
      var rect = range.getBoundingClientRect ? range.getBoundingClientRect() : null;
      if (!rect || (rect.width <= 0 && rect.height <= 0)) {
        var rects = range.getClientRects ? range.getClientRects() : null;
        if (rects && rects.length > 0) rect = rects[0];
      }
      var tryFindSpan = function(n) {
        if (!n) return null;
        var el = n.nodeType === 1 ? n : n.parentElement;
        if (el && el.closest) {
          var direct = el.closest('.lexi-s');
          if (direct) return direct;
          if (el.querySelector) {
            return el.querySelector('.lexi-s');
          }
        }
        return null;
      };
      var span = tryFindSpan(selection.anchorNode) || tryFindSpan(selection.focusNode) || tryFindSpan(range.commonAncestorContainer);
      var sentIdx = -1;
      if (span) {
        var parsed = parseInt(span.getAttribute('data-idx'), 10);
        if (!isNaN(parsed)) sentIdx = parsed;
      }
      window.__lexiLastSelectedSentIdx = sentIdx;
      window.__lexiLastSelectedSpan = span || null;
      lexiHadSelection = true;
      var vw = Math.max(1, window.innerWidth || document.documentElement.clientWidth || 1);
      var vh = Math.max(1, window.innerHeight || document.documentElement.clientHeight || 1);
      if (window.LexiBridge && window.LexiBridge.onTextSelectedWithRect) {
        var offsetStart = -1;
        var offsetEnd = -1;
        try {
          var sentSpan = window.__lexiLastSelectedSpan;
          var r0 = sel.getRangeAt(0);
          if (sentSpan) {
            offsetStart = window.lexiTextOffsetWithin(sentSpan, r0.startContainer, r0.startOffset);
            offsetEnd = window.lexiTextOffsetWithin(sentSpan, r0.endContainer, r0.endOffset);
          }
        } catch (e0) {}
        var payloadJson = JSON.stringify({
          text: sel,
          sentenceIndex: sentIdx,
          startOffset: offsetStart,
          endOffset: offsetEnd,
          top: rect ? rect.top : 0,
          bottom: rect ? rect.bottom : 0,
          left: rect ? rect.left : 0,
          right: rect ? rect.right : vw,
          viewportWidth: vw,
          viewportHeight: vh
        });
        window.LexiBridge.onTextSelectedWithRect(payloadJson);
      } else if (window.LexiBridge && window.LexiBridge.onTextSelectedWithSentence) {
        window.LexiBridge.onTextSelectedWithSentence(sel, sentIdx);
      } else if (window.LexiBridge && window.LexiBridge.onTextSelected) {
        window.LexiBridge.onTextSelected(sel);
      }
    } else if (lexiHadSelection) {
      lexiHadSelection = false;
      window.__lexiLastSelectedSentIdx = -1;
      if (window.LexiBridge && window.LexiBridge.onTextSelectionCleared) {
        window.LexiBridge.onTextSelectionCleared();
      } else if (window.LexiBridge && window.LexiBridge.onTextSelectedWithSentence) {
        window.LexiBridge.onTextSelectedWithSentence('', -1);
      }
    }
  } catch (e) {}
};
window.lexiSelectAll = function() {
  try {
    var ok = document.execCommand && document.execCommand('selectAll');
    var sel = window.getSelection ? window.getSelection() : null;
    if (!ok || !sel || sel.toString().trim().length === 0) {
      if (sel && document.body) {
        var r = document.createRange();
        r.selectNodeContents(document.body);
        sel.removeAllRanges();
        sel.addRange(r);
      }
    }
    setTimeout(lexiEmitSelection, 30);
  } catch (e) {}
};
window.lexiClearSelection = function() {
  try {
    if (window.getSelection) {
      window.getSelection().removeAllRanges();
    }
    lexiHadSelection = false;
  } catch (e) {}
};
document.addEventListener('selectionchange', function() {
  if (lexiSelTimer) clearTimeout(lexiSelTimer);
  lexiSelTimer = setTimeout(lexiEmitSelection, 140);
});
})();
""".trimIndent()
)
)
)

        val resolvedChapterTitle = if (activeVisualRules.isNotEmpty()) {
            PronunciationProcessor.applyReaderRules(chapterTitle, activeVisualRules, bookId)
        } else {
            chapterTitle
        }

        return WebChapterPayload(
            chapterIndex = chapterIndex,
            chapterTitle = resolvedChapterTitle,
            baseUrl = baseUrl,
            htmlWithSentenceSpans = doc.outerHtml(),
            sentences = sentences,
            paragraphs = paragraphs,
            plainText = plainBlocks.joinToString("\n\n")
        )
    }


    /**
     * Reads internal CSS, image, SVG, or font resources directly from an EPUB Zip archive
     * for WebView's `shouldInterceptRequest`.
     */
    fun readZipResource(zip: ZipFile, requestUrl: String): ZipResourceData? {
        if (!requestUrl.startsWith(ASSET_BASE_URL)) return null
        val relPath = decodeHref(requestUrl.removePrefix(ASSET_BASE_URL).substringBefore('?').substringBefore('#'))
        val normalized = normalizePath(relPath)
        val entry = getZipEntryIgnoreCase(zip, normalized) ?: return null
        if (entry.size > 16L * 1024L * 1024L) return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        return ZipResourceData(bytes = bytes, mimeType = guessMimeType(normalized))
    }

    private fun guessMimeType(path: String): String {
        val lower = path.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") -> "application/xhtml+xml"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".ttf") -> "font/ttf"
            lower.endsWith(".otf") -> "font/otf"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".js") -> "application/javascript"
            else -> "application/octet-stream"
        }
    }

    fun loadChapterContent(
        context: Context,
        fileKey: String,
        isDirectPath: Boolean,
        chapterIndex: Int,
        chapterHref: String,
        chapterTitle: String
    ): EpubChapterContent {
        val webPayload = loadChapterForWebView(
            context = context,
            fileKey = fileKey,
            isDirectPath = isDirectPath,
            chapterIndex = chapterIndex,
            chapterHref = chapterHref,
            chapterTitle = chapterTitle
        )
        return EpubChapterContent(
            index = chapterIndex,
            title = chapterTitle,
            paragraphs = webPayload.paragraphs,
            sentences = webPayload.sentences,
            plainText = webPayload.plainText
        )
    }

    fun searchBookChapters(
        context: Context,
        fileKey: String,
        isDirectPath: Boolean,
        chapters: List<EpubChapterSummary>,
        query: String,
        maxResults: Int = 150
    ): List<InBookSearchResult> {
        val needle = query.trim()
        if (needle.length < 2 || chapters.isEmpty()) return emptyList()
        val localZipFile = resolveReadableZipFile(context, fileKey, isDirectPath) ?: return emptyList()
        val shouldDelete = !isDirectPath
        val results = mutableListOf<InBookSearchResult>()
        return try {
            ZipFile(localZipFile).use { zip ->
                for (chapter in chapters) {
                    if (results.size >= maxResults) break
                    val entry = getZipEntryIgnoreCase(zip, chapter.href) ?: continue
                    val rawHtml = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).readText()
                    val payload = buildWebChapterPayload(chapter.index, chapter.title, chapter.href, rawHtml)
                    for (sentence in payload.sentences) {
                        if (sentence.text.contains(needle, ignoreCase = true)) {
                            results.add(
                                InBookSearchResult(
                                    chapterIndex = chapter.index,
                                    chapterTitle = chapter.title,
                                    sentenceIndex = sentence.globalIndex,
                                    snippet = sentence.text
                                )
                            )
                            if (results.size >= maxResults) break
                        }
                    }
                }
                results
            }
        } catch (_: Exception) {
            results
        } finally {
            if (shouldDelete) localZipFile.delete()
        }
    }

    fun loadAllChapterSentencesForExport(
        context: Context,
        fileKey: String,
        isDirectPath: Boolean,
        chapters: List<EpubChapterSummary>
    ): Map<Int, List<ReaderSentence>> {
        val localZipFile = resolveReadableZipFile(context, fileKey, isDirectPath) ?: return emptyMap()
        val shouldDelete = !isDirectPath
        return try {
            ZipFile(localZipFile).use { zip ->
                val result = linkedMapOf<Int, List<ReaderSentence>>()
                for (chapter in chapters) {
                    val entry = getZipEntryIgnoreCase(zip, chapter.href) ?: continue
                    val html = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).readText()
                    val payload = buildWebChapterPayload(chapter.index, chapter.title, chapter.href, html)
                    result[chapter.index] = payload.sentences
                }
                result
            }
        } catch (_: Exception) {
            emptyMap()
        } finally {
            if (shouldDelete) localZipFile.delete()
        }
    }

    fun loadAllChaptersForExport(
        context: Context,
        fileKey: String,
        isDirectPath: Boolean,
        chapters: List<EpubChapterSummary>
    ): Map<Int, String> {
        val localZipFile = resolveReadableZipFile(context, fileKey, isDirectPath) ?: return emptyMap()
        val shouldDelete = !isDirectPath
        return try {
            ZipFile(localZipFile).use { zip ->
                val result = linkedMapOf<Int, String>()
                for (chapter in chapters) {
                    val entry = getZipEntryIgnoreCase(zip, chapter.href) ?: continue
                    val html = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).readText()
                    result[chapter.index] = Jsoup.parse(html).body().text()
                }
                result
            }
        } catch (_: Exception) {
            emptyMap()
        } finally {
            if (shouldDelete) localZipFile.delete()
        }
    }

    fun parsePlainTextToChapter(title: String, rawText: String): EpubChapterContent {
        val rawParagraphs = rawText
            .replace("\r\n", "\n")
            .split(Regex("\n{2,}"))
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }

        val paragraphs = mutableListOf<ReaderParagraph>()
        val allSentences = mutableListOf<ReaderSentence>()
        var globalSentenceIdx = 0

        rawParagraphs.forEachIndexed { pIdx, paraText ->
            val sentences = splitIntoSentences(paraText, pIdx, globalSentenceIdx)
            globalSentenceIdx += sentences.size
            allSentences.addAll(sentences)
            paragraphs.add(
                ReaderParagraph(
                    index = pIdx,
                    text = paraText,
                    isHeading = false,
                    sentences = sentences
                )
            )
        }

        return EpubChapterContent(
            index = 0,
            title = title,
            paragraphs = paragraphs,
            sentences = allSentences,
            plainText = rawParagraphs.joinToString("\n\n")
        )
    }

    private fun splitPreservingWhitespace(text: String): List<Pair<Boolean, String>> {
        if (text.isBlank()) return listOf(false to text)
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(text)
        val out = mutableListOf<Pair<Boolean, String>>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val slice = text.substring(start, end)
            val firstNonWs = slice.indexOfFirst { !it.isWhitespace() }
            val lastNonWs = slice.indexOfLast { !it.isWhitespace() }
            if (firstNonWs < 0) {
                out.add(false to slice)
            } else {
                if (firstNonWs > 0) out.add(false to slice.substring(0, firstNonWs))
                out.add(true to slice.substring(firstNonWs, lastNonWs + 1))
                if (lastNonWs + 1 < slice.length) out.add(false to slice.substring(lastNonWs + 1))
            }
            start = end
            end = iterator.next()
        }
        return out
    }

    /**
     * Characters that only ever close something. A fragment made entirely of these
     * is the tail of the sentence before it, never a sentence of its own.
     *
     * ASCII quote marks are deliberately absent: they open as often as they close,
     * so merging them backwards would attach an opening quote to the wrong sentence.
     */
    private val ORPHAN_CLOSERS: Set<Char> = "]]］】》〉」』）)｝}〕〗〙〛”’".toSet()

    internal fun isOrphanClosingFragment(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.all { it in ORPHAN_CLOSERS }
    }

    /**
     * Folds sentences that are nothing but a closing bracket into the sentence
     * before them. Used by the BreakIterator path; the block splitter does the same
     * inline so that its character ranges stay correct.
     */
    internal fun mergeOrphanClosers(sentences: List<ReaderSentence>): List<ReaderSentence> {
        if (sentences.size < 2) return sentences
        val merged = mutableListOf<ReaderSentence>()
        for (sentence in sentences) {
            val previous = merged.lastOrNull()
            if (previous != null && isOrphanClosingFragment(sentence.text)) {
                val text = (previous.text + " " + sentence.text).trim()
                merged[merged.size - 1] = previous.copy(
                    endInParagraph = previous.startInParagraph + text.length,
                    text = text
                )
            } else {
                merged.add(sentence)
            }
        }
        return merged
    }

    fun splitIntoSentences(
        paragraph: String,
        paragraphIndex: Int,
        startingGlobalIndex: Int
    ): List<ReaderSentence> {
        if (paragraph.isBlank()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(paragraph)
        val result = mutableListOf<ReaderSentence>()
        var start = iterator.first()
        var end = iterator.next()
        var idx = startingGlobalIndex
        while (end != BreakIterator.DONE) {
            val slice = paragraph.substring(start, end)
            val trimmedStartOffset = slice.indexOfFirst { !it.isWhitespace() }
            val trimmedEndOffset = slice.indexOfLast { !it.isWhitespace() }
            if (trimmedStartOffset >= 0 && trimmedEndOffset >= trimmedStartOffset) {
                val actualStart = start + trimmedStartOffset
                val actualEnd = start + trimmedEndOffset + 1
                val cleanSentence = paragraph.substring(actualStart, actualEnd)
                result.add(
                    ReaderSentence(
                        globalIndex = idx++,
                        paragraphIndex = paragraphIndex,
                        startInParagraph = actualStart,
                        endInParagraph = actualEnd,
                        text = cleanSentence
                    )
                )
            }
            start = end
            end = iterator.next()
        }
        if (result.isEmpty()) {
            result.add(
                ReaderSentence(
                    globalIndex = startingGlobalIndex,
                    paragraphIndex = paragraphIndex,
                    startInParagraph = 0,
                    endInParagraph = paragraph.length,
                    text = paragraph
                )
            )
        }
        return mergeOrphanClosers(result)
    }

    fun openReadableZip(context: Context, fileKey: String, isDirectPath: Boolean): Pair<ZipFile, File?>? {
        val file = resolveReadableZipFile(context, fileKey, isDirectPath) ?: return null
        return runCatching {
            ZipFile(file) to (if (isDirectPath) null else file)
        }.getOrNull()
    }

    private fun emptyWebPayload(chapterIndex: Int, chapterTitle: String, chapterHref: String): WebChapterPayload {
        return WebChapterPayload(
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            baseUrl = "$ASSET_BASE_URL${chapterHref.substringBeforeLast('/', "")}",
            htmlWithSentenceSpans = "<html><body><p>Unable to render chapter.</p></body></html>",
            sentences = emptyList(),
            paragraphs = emptyList(),
            plainText = ""
        )
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val containerEntry = getZipEntryIgnoreCase(zip, "META-INF/container.xml")
        if (containerEntry != null) {
            zip.getInputStream(containerEntry).use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name.equals("rootfile", ignoreCase = true)) {
                        val fullPath = parser.getAttributeValue(null, "full-path")
                        if (!fullPath.isNullOrBlank()) return fullPath
                    }
                    event = parser.next()
                }
            }
        }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.endsWith(".opf", ignoreCase = true)) {
                return entry.name
            }
        }
        return null
    }

    private fun parseOpf(stream: InputStream, opfPath: String): OpfInfo {
        val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, "UTF-8")

        var title = ""
        var author = ""
        var language = ""
        var coverIdMeta = ""
        val manifest = linkedMapOf<String, ManifestItem>()
        val spineIds = mutableListOf<String>()
        var tocAttrId = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':').lowercase(Locale.ROOT)
                when (tag) {
                    "title" -> if (title.isBlank()) title = readTextSafe(parser)
                    "creator" -> if (author.isBlank()) author = readTextSafe(parser)
                    "language" -> if (language.isBlank()) language = readTextSafe(parser)
                    "meta" -> {
                        val name = parser.getAttributeValue(null, "name")
                        val content = parser.getAttributeValue(null, "content")
                        if (name.equals("cover", ignoreCase = true) && !content.isNullOrBlank()) {
                            coverIdMeta = content
                        }
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id").orEmpty()
                        val href = decodeHref(parser.getAttributeValue(null, "href").orEmpty())
                        val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
                        val props = parser.getAttributeValue(null, "properties").orEmpty()
                        if (id.isNotBlank() && href.isNotBlank()) {
                            manifest[id] = ManifestItem(id, href, mediaType, props)
                        }
                    }
                    "spine" -> {
                        tocAttrId = parser.getAttributeValue(null, "toc").orEmpty()
                    }
                    "itemref" -> {
                        val linear = parser.getAttributeValue(null, "linear")
                        val idref = parser.getAttributeValue(null, "idref").orEmpty()
                        if (idref.isNotBlank() && !linear.equals("no", ignoreCase = true)) {
                            spineIds.add(idref)
                        }
                    }
                }
            }
            event = parser.next()
        }

        val coverHref = manifest.values.firstOrNull {
            it.properties.split(" ").any { p -> p.equals("cover-image", ignoreCase = true) }
        }?.href ?: manifest[coverIdMeta]?.href
            ?: manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") &&
                    (it.id.contains("cover", true) || it.href.contains("cover", true))
            }?.href

        val navHref = manifest.values.firstOrNull {
            it.properties.split(" ").any { p -> p.equals("nav", ignoreCase = true) }
        }?.href

        val ncxHref = manifest[tocAttrId]?.href ?: manifest.values.firstOrNull {
            it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) ||
                it.href.endsWith(".ncx", ignoreCase = true)
        }?.href

        val spineEntries = spineIds.mapNotNull { id ->
            val item = manifest[id] ?: return@mapNotNull null
            id to resolvePath(opfDir, item.href)
        }

        return OpfInfo(
            title = title.trim(),
            author = author.trim(),
            language = language.trim(),
            opfDir = opfDir,
            coverHref = coverHref,
            ncxHref = ncxHref,
            navHref = navHref,
            spineEntries = spineEntries
        )
    }

    private fun buildTocTitleMap(zip: ZipFile, opf: OpfInfo): Map<String, String> {
        val map = linkedMapOf<String, String>()

        opf.navHref?.let { navHref ->
            val navPath = resolvePath(opf.opfDir, navHref)
            val navDir = navPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            getZipEntryIgnoreCase(zip, navPath)?.let { entry ->
                runCatching {
                    val html = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).readText()
                    val doc = Jsoup.parse(html)
                    val tocNav = doc.select("nav[epub\\:type=toc], nav[role=doc-toc], nav#toc").firstOrNull()
                        ?: doc.select("nav").firstOrNull()
                    tocNav?.select("a[href]")?.forEach { a ->
                        val href = decodeHref(a.attr("href")).substringBefore('#')
                        val label = a.text().trim()
                        if (href.isNotBlank() && label.isNotBlank()) {
                            val resolved = normalizePath(resolvePath(navDir, href))
                            map.putIfAbsent(resolved, label)
                        }
                    }
                }
            }
        }

        if (map.isEmpty() && opf.ncxHref != null) {
            val ncxPath = resolvePath(opf.opfDir, opf.ncxHref)
            val ncxDir = ncxPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            getZipEntryIgnoreCase(zip, ncxPath)?.let { entry ->
                runCatching {
                    zip.getInputStream(entry).use { stream ->
                        val parser = Xml.newPullParser()
                        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                        parser.setInput(stream, "UTF-8")
                        var currentLabel = ""
                        var inNavLabel = false
                        var event = parser.eventType
                        while (event != XmlPullParser.END_DOCUMENT) {
                            when (event) {
                                XmlPullParser.START_TAG -> {
                                    when (parser.name.lowercase(Locale.ROOT)) {
                                        "navlabel" -> inNavLabel = true
                                        "text" -> if (inNavLabel) currentLabel = readTextSafe(parser)
                                        "content" -> {
                                            val src = decodeHref(parser.getAttributeValue(null, "src").orEmpty())
                                                .substringBefore('#')
                                            if (src.isNotBlank() && currentLabel.isNotBlank()) {
                                                val resolved = normalizePath(resolvePath(ncxDir, src))
                                                map.putIfAbsent(resolved, currentLabel.trim())
                                            }
                                        }
                                    }
                                }
                                XmlPullParser.END_TAG -> {
                                    if (parser.name.equals("navLabel", ignoreCase = true)) {
                                        inNavLabel = false
                                    } else if (parser.name.equals("navPoint", ignoreCase = true)) {
                                        currentLabel = ""
                                    }
                                }
                            }
                            event = parser.next()
                        }
                    }
                }
            }
        }

        return map
    }

    private fun extractTitleFromHtmlQuick(zip: ZipFile, path: String): String? {
        return runCatching {
            val entry = getZipEntryIgnoreCase(zip, path) ?: return null
            val bytes = zip.getInputStream(entry).use { stream ->
                val buf = ByteArray(4096)
                val read = stream.read(buf)
                if (read > 0) String(buf, 0, read, StandardCharsets.UTF_8) else ""
            }
            val doc = Jsoup.parse(bytes)
            doc.select("h1, h2, h3").firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() && it.length <= 90 }
                ?: doc.title().trim().takeIf { it.isNotBlank() && it.length <= 90 }
        }.getOrNull()
    }

    private fun saveCoverThumbnail(
        context: Context,
        zip: ZipFile,
        coverEntryPath: String,
        fileKey: String
    ): String? {
        return runCatching {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val hash = sha256(fileKey)
            val target = File(coversDir, "$hash.jpg")
            if (target.exists() && target.length() > 0L) return target.absolutePath

            val entry = getZipEntryIgnoreCase(zip, coverEntryPath) ?: return null
            val rawBytes = zip.getInputStream(entry).use { it.readBytes() }
            if (rawBytes.isEmpty()) return null

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > 360 || boundsOpts.outHeight / sampleSize > 520) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null

            FileOutputStream(target).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            bmp.recycle()
            target.absolutePath
        }.getOrNull()
    }

    private fun resolveReadableZipFile(
        context: Context,
        fileKey: String,
        isDirectPath: Boolean
    ): File? {
        return if (isDirectPath) {
            File(fileKey).takeIf { it.exists() && it.canRead() }
        } else {
            copyUriToTemp(context, Uri.parse(fileKey))
        }
    }

    private fun copyUriToTemp(context: Context, uri: Uri): File? {
        return runCatching {
            val temp = File.createTempFile("lexi_epub_", ".epub", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            } ?: run {
                temp.delete()
                return null
            }
            temp
        }.getOrNull()
    }

    private fun getZipEntryIgnoreCase(zip: ZipFile, targetPath: String): ZipEntry? {
        val clean = normalizePath(targetPath)
        zip.getEntry(clean)?.let { return it }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (normalizePath(e.name).equals(clean, ignoreCase = true)) return e
        }
        return null
    }

    private fun resolvePath(baseDir: String, relative: String): String {
        val cleanRel = relative.substringBefore('#').trimStart('/')
        if (relative.startsWith("/")) return normalizePath(cleanRel)
        return normalizePath("$baseDir$cleanRel")
    }

    private fun normalizePath(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val stack = ArrayDeque<String>()
        for (p in parts) {
            when (p) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(p)
            }
        }
        return stack.joinToString("/")
    }

    fun normalizeZipPath(path: String): String = normalizePath(path)

    private fun decodeHref(raw: String): String {
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
    }

    private fun readTextSafe(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text.orEmpty())
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString().trim()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun defaultMetadata(fileName: String): EpubIndexedMetadata {
        return EpubIndexedMetadata(
            title = fileName.removeSuffix(".epub").removeSuffix(".EPUB").ifBlank { "Untitled Book" },
            author = "Unknown Author",
            language = "",
            coverPath = null,
            chapterCount = 1
        )
    }
}
