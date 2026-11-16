package com.example.epub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Xml
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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
import java.util.zip.ZipInputStream

object EpubParser {

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
                // Copy to a small temporary file only if needed or stream via ZipInputStream;
                // copying to cache once is fast and allows random-access ZipFile without reading all HTML!
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

    fun loadChapterContent(
        context: Context,
        fileKey: String,
        isDirectPath: Boolean,
        chapterIndex: Int,
        chapterHref: String,
        chapterTitle: String
    ): EpubChapterContent {
        val localZipFile = resolveReadableZipFile(context, fileKey, isDirectPath)
            ?: return EpubChapterContent(chapterIndex, chapterTitle, emptyList(), emptyList(), "")
        val shouldDelete = !isDirectPath
        return try {
            ZipFile(localZipFile).use { zip ->
                val entry = getZipEntryIgnoreCase(zip, chapterHref)
                    ?: return EpubChapterContent(chapterIndex, chapterTitle, emptyList(), emptyList(), "")
                val html = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).readText()
                parseHtmlToChapterContent(chapterIndex, chapterTitle, html)
            }
        } catch (_: Exception) {
            EpubChapterContent(chapterIndex, chapterTitle, emptyList(), emptyList(), "")
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
                    val parsed = parseHtmlToChapterContent(chapter.index, chapter.title, html)
                    if (parsed.plainText.isNotBlank()) {
                        result[chapter.index] = parsed.plainText
                    }
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

    private fun parseHtmlToChapterContent(
        chapterIndex: Int,
        chapterTitle: String,
        html: String
    ): EpubChapterContent {
        val doc = Jsoup.parse(html)
        doc.select("script, style, svg, nav[epub\\:type=page-list], head").remove()

        val body = doc.body()
        val blockElements = body.select("h1, h2, h3, h4, h5, h6, p, blockquote, li, pre")
        val rawBlocks = mutableListOf<Pair<String, Int>>() // text, headingLevel (0 = normal)

        if (blockElements.isNotEmpty()) {
            val seenElements = HashSet<Element>()
            for (el in blockElements) {
                // Skip nested blocks whose parent was already captured as a paragraph/heading
                if (el.parents().any { it in seenElements }) continue
                val text = el.text().replace(Regex("\\s+"), " ").trim()
                if (text.isNotBlank()) {
                    seenElements.add(el)
                    val tag = el.tagName().lowercase(Locale.ROOT)
                    val headingLevel = if (tag.startsWith("h") && tag.length == 2) {
                        tag[1].digitToIntOrNull() ?: 0
                    } else 0
                    rawBlocks.add(text to headingLevel)
                }
            }
        }

        if (rawBlocks.isEmpty()) {
            val fallback = body.text().replace(Regex("\\s+"), " ").trim()
            if (fallback.isNotBlank()) {
                rawBlocks.add(fallback to 0)
            }
        }

        val paragraphs = mutableListOf<ReaderParagraph>()
        val allSentences = mutableListOf<ReaderSentence>()
        var globalSentenceIdx = 0

        rawBlocks.forEachIndexed { pIdx, (text, headingLevel) ->
            val sentences = splitIntoSentences(text, pIdx, globalSentenceIdx)
            globalSentenceIdx += sentences.size
            allSentences.addAll(sentences)
            paragraphs.add(
                ReaderParagraph(
                    index = pIdx,
                    text = text,
                    isHeading = headingLevel > 0,
                    headingLevel = headingLevel,
                    sentences = sentences
                )
            )
        }

        return EpubChapterContent(
            index = chapterIndex,
            title = chapterTitle,
            paragraphs = paragraphs,
            sentences = allSentences,
            plainText = rawBlocks.joinToString("\n\n") { it.first }
        )
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
        return result
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

        // EPUB 3 Nav document first
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

        // EPUB 2 NCX fallback / supplement
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
