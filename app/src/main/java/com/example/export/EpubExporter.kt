package com.example.export

import android.content.Context
import android.net.Uri
import com.example.data.db.PronunciationRuleEntity
import com.example.epub.EpubParser
import com.example.tts.PronunciationProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class EpubExportResult(
    val success: Boolean,
    val processedChapters: Int = 0,
    val totalEntries: Int = 0,
    val errorMessage: String? = null
)

/**
 * Metadata written into the exported copy's OPF. Null fields are left as they are,
 * so exporting without opening the metadata dialog behaves exactly as before.
 */
data class EpubMetadataOverride(
    val title: String? = null,
    val subtitle: String? = null,
    val author: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val date: String? = null,
    val identifier: String? = null,
    val rights: String? = null,
    val series: String? = null,
    val seriesIndex: String? = null,
    val subjects: List<String> = emptyList(),
    val synopsis: String? = null,
    val coverBytes: ByteArray? = null,
    val coverFileName: String? = null,
    val coverMimeType: String? = null
)

object EpubExporter {

    suspend fun exportEditedEpubToUri(
        context: Context,
        sourceFileKey: String,
        isDirectPath: Boolean,
        bookId: Long?,
        visualRules: List<PronunciationRuleEntity>,
        destinationUri: Uri,
        metadata: EpubMetadataOverride? = null,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): EpubExportResult = withContext(Dispatchers.IO) {
        val opened = EpubParser.openReadableZip(context, sourceFileKey, isDirectPath)
            ?: return@withContext EpubExportResult(
                success = false,
                errorMessage = "Unable to open source EPUB archive."
            )
        val (zip, tempFile) = opened
        try {
            val out = context.contentResolver.openOutputStream(destinationUri)
                ?: return@withContext EpubExportResult(
                    success = false,
                    errorMessage = "Cannot open output destination."
                )
            out.use { stream ->
                val activeRules = visualRules.filter { it.isEnabled && it.applyToReaderText && it.findText.isNotEmpty() }
                exportEditedEpubToStream(
                    zip = zip,
                    bookId = bookId,
                    visualRules = activeRules,
                    outputStream = stream,
                    metadata = metadata,
                    onProgress = onProgress
                )
            }
        } catch (t: Throwable) {
            EpubExportResult(success = false, errorMessage = t.message ?: "EPUB export failed.")
        } finally {
            runCatching { zip.close() }
            runCatching { tempFile?.delete() }
        }
    }

    internal fun exportEditedEpubToStream(
        zip: java.util.zip.ZipFile,
        bookId: Long?,
        visualRules: List<PronunciationRuleEntity>,
        outputStream: OutputStream,
        metadata: EpubMetadataOverride? = null,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): EpubExportResult {
        val entriesList = zip.entries().toList().filter { !it.isDirectory }
        val total = entriesList.size.coerceAtLeast(1)
        var processedCount = 0
        var htmlDocsProcessed = 0

        // Where the current cover lives, and whether we have to add one because the
        // book had none. Resolved before the loop so the OPF rewrite can register it.
        val opfEntry = entriesList.firstOrNull { it.name.lowercase(Locale.ROOT).endsWith(".opf") }
        val opfBytesForCover = opfEntry?.let { entry ->
            runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
        }
        val existingCoverEntry = if (opfEntry != null && opfBytesForCover != null) {
            resolveCoverEntryName(zip, opfEntry.name, opfBytesForCover)
        } else {
            null
        }
        val coverBytes = metadata?.coverBytes
        val opfDir = opfEntry?.name?.substringBeforeLast('/', "") ?: ""
        val addedCoverFileName = if (coverBytes != null && existingCoverEntry == null) {
            metadata?.coverFileName?.takeIf { it.isNotBlank() } ?: "lexi-cover.jpg"
        } else {
            null
        }
        val addedCoverHref = addedCoverFileName?.let { if (opfDir.isEmpty()) it else it }
        val addedCoverId = if (addedCoverHref != null) "lexi-cover-image" else null

        ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
            // 1. Write `mimetype` FIRST, uncompressed (STORED) per OCF/EPUB specification
            val mimeZipEntry = entriesList.firstOrNull { it.name.equals("mimetype", ignoreCase = true) }
            val mimeBytes = if (mimeZipEntry != null) {
                zip.getInputStream(mimeZipEntry).use { it.readBytes() }
            } else {
                "application/epub+zip".toByteArray(StandardCharsets.US_ASCII)
            }
            val crc = CRC32().apply { update(mimeBytes) }.value
            val storedEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                this.crc = crc
            }
            zos.putNextEntry(storedEntry)
            zos.write(mimeBytes)
            zos.closeEntry()
            processedCount++
            onProgress(processedCount, total)

            // 2. Stream remaining entries: transform HTML/XHTML TextNodes, copy all others byte-for-byte
            for (entry in entriesList) {
                if (entry.name.equals("mimetype", ignoreCase = true)) continue

                val lower = entry.name.lowercase(Locale.ROOT)
                val isHtmlEntry = lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")

                val isOpfEntry = lower.endsWith(".opf")

                if (coverBytes != null && entry.name == existingCoverEntry) {
                    // Replace the cover image in place, keeping its manifest entry.
                    val outEntry = ZipEntry(entry.name)
                    zos.putNextEntry(outEntry)
                    zos.write(coverBytes)
                    zos.closeEntry()
                } else if (isOpfEntry && metadata != null && !metadata.isEmpty()) {
                    val rawBytes = zip.getInputStream(entry).use { it.readBytes() }
                    val outEntry = ZipEntry(entry.name)
                    zos.putNextEntry(outEntry)
                    zos.write(rewriteOpfMetadata(rawBytes, metadata, addedCoverId, addedCoverHref))
                    zos.closeEntry()
                } else if (isHtmlEntry && visualRules.isNotEmpty()) {
                    val rawBytes = zip.getInputStream(entry).use { it.readBytes() }
                    val transformedBytes = transformHtmlDocumentBytes(
                        rawBytes = rawBytes,
                        entryName = entry.name,
                        bookId = bookId,
                        visualRules = visualRules
                    )
                    val outEntry = ZipEntry(entry.name)
                    zos.putNextEntry(outEntry)
                    zos.write(transformedBytes)
                    zos.closeEntry()
                    htmlDocsProcessed++
                } else {
                    val outEntry = ZipEntry(entry.name)
                    zos.putNextEntry(outEntry)
                    zip.getInputStream(entry).use { input ->
                        input.copyTo(zos, bufferSize = 32 * 1024)
                    }
                    zos.closeEntry()
                }
                processedCount++
                onProgress(processedCount, total)
            }

            // A book with no cover gets one appended next to the OPF, and the rewrite
            // above already registered it in the manifest and <meta name="cover">.
            if (coverBytes != null && addedCoverFileName != null && addedCoverHref != null) {
                val entryName = if (opfDir.isEmpty()) addedCoverFileName else "$opfDir/$addedCoverFileName"
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(coverBytes)
                zos.closeEntry()
            }
        }

        return EpubExportResult(
            success = true,
            processedChapters = htmlDocsProcessed,
            totalEntries = entriesList.size
        )
    }

    /**
     * Applies a "this word" term to the one occurrence it was anchored to: the
     * [PronunciationRuleEntity.occurrenceIndex]-th match of the original text inside
     * the chapter named by [PronunciationRuleEntity.chapterKey].
     */
    private fun applyAnchoredTerms(
        root: Node,
        rules: List<PronunciationRuleEntity>,
        entryName: String
    ) {
        val anchored = rules.filter {
            it.kind == "TERM" && it.termScope == "WORD" && it.isEnabled && it.findText.isNotBlank()
        }
        if (anchored.isEmpty()) return
        val entry = entryName.replace('\\', '/')
        for (rule in anchored) {
            val key = rule.chapterKey.replace('\\', '/').trimStart('/')
            if (key.isNotBlank() && !entry.endsWith(key)) continue
            var skip = rule.occurrenceIndex.coerceAtLeast(0)
            var done = false
            fun walk(node: Node) {
                if (done) return
                if (node is Element &&
                    node.tagName().lowercase(Locale.ROOT) in setOf("script", "style", "svg", "noscript", "rt", "rp")
                ) return
                if (node is TextNode) {
                    val text = node.wholeText
                    var at = text.indexOf(rule.findText)
                    while (at >= 0) {
                        if (skip == 0) {
                            node.text(
                                text.substring(0, at) + rule.replaceWith +
                                    text.substring(at + rule.findText.length)
                            )
                            done = true
                            return
                        }
                        skip--
                        at = text.indexOf(rule.findText, at + rule.findText.length)
                    }
                }
                node.childNodes().forEach { walk(it) }
            }
            walk(root)
        }
    }

    internal fun transformHtmlDocumentBytes(
        rawBytes: ByteArray,
        entryName: String,
        bookId: Long?,
        visualRules: List<PronunciationRuleEntity>
    ): ByteArray {
        val rawHtml = String(rawBytes, StandardCharsets.UTF_8)
        val isXmlXhtml = entryName.lowercase(Locale.ROOT).endsWith(".xhtml") ||
            rawHtml.trimStart().startsWith("<?xml", ignoreCase = true) ||
            rawHtml.contains("xmlns=\"http://www.w3.org/1999/xhtml\"", ignoreCase = true)

        val doc = if (isXmlXhtml) {
            Jsoup.parse(rawHtml, "", Parser.xmlParser())
        } else {
            Jsoup.parse(rawHtml)
        }
        doc.outputSettings()
            .prettyPrint(false)
            .charset(StandardCharsets.UTF_8)
        if (isXmlXhtml) {
            doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
        }

        val bodyNode = doc.selectFirst("body") ?: doc
        // Precedence, fixed by order of application rather than by creation time:
        // anchored ("this word") terms claim their spot against the raw text first,
        // then whole-book terms fill in everywhere the anchor did not already
        // replace something. That is what makes a "this word" edit win even when the
        // whole-book rule was created after it.
        applyAnchoredTerms(bodyNode, visualRules, entryName)
        val textWideRules = visualRules.filterNot { it.kind == "TERM" && it.termScope == "WORD" }
        PronunciationProcessor.applyReaderRulesToTextNodes(
            root = bodyNode,
            rules = textWideRules,
            bookId = bookId,
            skipTags = setOf("script", "style", "svg", "noscript", "rt", "rp")
        )
        val titleEl = doc.selectFirst("head > title, title")
        if (titleEl != null) {
            PronunciationProcessor.applyReaderRulesToTextNodes(
                root = titleEl,
                rules = visualRules,
                bookId = bookId,
                skipTags = emptySet()
            )
        }

        val baos = ByteArrayOutputStream(rawBytes.size.coerceAtLeast(1024))
        baos.write(doc.outerHtml().toByteArray(StandardCharsets.UTF_8))
        return baos.toByteArray()
    }
}

private fun EpubMetadataOverride.isEmpty(): Boolean =
    title.isNullOrBlank() && subtitle.isNullOrBlank() && author.isNullOrBlank() &&
        publisher.isNullOrBlank() && language.isNullOrBlank() && date.isNullOrBlank() &&
        identifier.isNullOrBlank() && rights.isNullOrBlank() && series.isNullOrBlank() &&
        seriesIndex.isNullOrBlank() && subjects.isEmpty() && synopsis.isNullOrBlank() &&
        coverBytes == null

/** Escapes text for use inside an XML element. */
private fun String.xmlEscaped(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * Rewrites the OPF of the exported copy. Only the fields that were filled in are
 * touched, so an export with an empty dialog produces byte-identical metadata.
 *
 * [newCoverId] and [newCoverHref] are supplied when the book had no cover and one
 * was added to the archive; they register the new image in the manifest and point
 * <meta name="cover"> at it.
 */
private fun rewriteOpfMetadata(
    bytes: ByteArray,
    metadata: EpubMetadataOverride,
    newCoverId: String? = null,
    newCoverHref: String? = null
): ByteArray {
    var xml = String(bytes, StandardCharsets.UTF_8)

    fun setTag(tag: String, value: String?) {
        if (value.isNullOrBlank()) return
        val escaped = value.xmlEscaped()
        val pattern = Regex("<" + tag + "([^>]*)>.*?</" + tag + ">", RegexOption.DOT_MATCHES_ALL)
        xml = if (pattern.containsMatchIn(xml)) {
            pattern.replace(xml) { match ->
                "<" + tag + match.groupValues[1] + ">" + escaped + "</" + tag + ">"
            }
        } else {
            val open = Regex("<metadata([^>]*)>")
            if (open.containsMatchIn(xml)) {
                open.replaceFirst(xml, "<metadata${'$'}1><" + tag + ">" + escaped + "</" + tag + ">")
            } else {
                xml
            }
        }
    }

    /** calibre-style <meta name="..."> entries, replaced or inserted. */
    fun setMetaName(name: String, value: String?) {
        if (value.isNullOrBlank()) return
        val escaped = value.xmlEscaped()
        val pattern = Regex("<meta\\s+name=\"" + Regex.escape(name) + "\"[^>]*/>", RegexOption.IGNORE_CASE)
        val replacement = "<meta name=\"" + name + "\" content=\"" + escaped + "\"/>"
        xml = if (pattern.containsMatchIn(xml)) {
            pattern.replace(xml, replacement)
        } else {
            val open = Regex("<metadata([^>]*)>")
            if (open.containsMatchIn(xml)) open.replaceFirst(xml, "<metadata${'$'}1>" + replacement) else xml
        }
    }

    setTag("dc:title", metadata.title)
    setTag("dc:creator", metadata.author)
    setTag("dc:publisher", metadata.publisher)
    setTag("dc:language", metadata.language)
    setTag("dc:date", metadata.date)
    setTag("dc:identifier", metadata.identifier)
    setTag("dc:rights", metadata.rights)
    setTag("dc:description", metadata.synopsis)

    // Subtitle is an EPUB3 title refinement. EPUB2 readers ignore it harmlessly.
    if (!metadata.subtitle.isNullOrBlank()) {
        val escaped = metadata.subtitle.xmlEscaped()
        val block = "<dc:title id=\"lexi-subtitle\">" + escaped + "</dc:title>" +
            "<meta refines=\"#lexi-subtitle\" property=\"title-type\">subtitle</meta>"
        val open = Regex("<metadata([^>]*)>")
        if (open.containsMatchIn(xml) && !xml.contains("id=\"lexi-subtitle\"")) {
            xml = open.replaceFirst(xml, "<metadata${'$'}1>" + block)
        }
    }

    // Subjects: the existing dc:subject list is replaced wholesale when any are given.
    if (metadata.subjects.isNotEmpty()) {
        val joined = metadata.subjects.joinToString("") { "<dc:subject>" + it.xmlEscaped() + "</dc:subject>" }
        val existing = Regex("<dc:subject([^>]*)>.*?</dc:subject>", RegexOption.DOT_MATCHES_ALL)
        xml = if (existing.containsMatchIn(xml)) {
            existing.replace(xml, "")
        } else {
            xml
        }
        val open = Regex("<metadata([^>]*)>")
        if (open.containsMatchIn(xml)) {
            xml = open.replaceFirst(xml, "<metadata${'$'}1>" + joined)
        }
    }

    setMetaName("calibre:series", metadata.series)
    setMetaName("calibre:series_index", metadata.seriesIndex)

    if (newCoverId != null && newCoverHref != null) {
        val item = "<item id=\"" + newCoverId + "\" href=\"" + newCoverHref.xmlEscaped() +
            "\" media-type=\"" + (metadata.coverMimeType ?: "image/jpeg") + "\"/>"
        val manifestOpen = Regex("<manifest([^>]*)>")
        if (manifestOpen.containsMatchIn(xml)) {
            xml = manifestOpen.replaceFirst(xml, "<manifest${'$'}1>" + item)
        }
        setMetaName("cover", newCoverId)
    }

    return xml.toByteArray(StandardCharsets.UTF_8)
}

/** Manifest href of the current cover image, resolved against the OPF's folder. */
private fun resolveCoverEntryName(zip: java.util.zip.ZipFile, opfEntryName: String, opfBytes: ByteArray): String? {
    val xml = String(opfBytes, StandardCharsets.UTF_8)
    val coverId = Regex("<meta\\s+name=\"cover\"\\s+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        .find(xml)?.groupValues?.get(1)
        ?: Regex("name=\"cover\"\\s+content=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
        ?: return null
    val itemPattern = Regex("<item[^>]*id=\"" + Regex.escape(coverId) + "\"[^>]*>", RegexOption.IGNORE_CASE)
    val item = itemPattern.find(xml)?.value ?: return null
    val href = Regex("href=\"([^\"]+)\"").find(item)?.groupValues?.get(1) ?: return null
    val dir = opfEntryName.substringBeforeLast('/', "")
    val decoded = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
    val joined = if (dir.isEmpty()) decoded else "$dir/$decoded"
    // Normalise "a/./b" and "a/../b" so it matches the archive entry name.
    val parts = joined.split('/')
    val out = ArrayDeque<String>()
    for (part in parts) {
        when (part) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeLast()
            else -> out.addLast(part)
        }
    }
    val resolved = out.joinToString("/")
    return if (zip.getEntry(resolved) != null) resolved else null
}
