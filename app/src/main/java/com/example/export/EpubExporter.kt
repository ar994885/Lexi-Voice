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

object EpubExporter {

    suspend fun exportEditedEpubToUri(
        context: Context,
        sourceFileKey: String,
        isDirectPath: Boolean,
        bookId: Long?,
        visualRules: List<PronunciationRuleEntity>,
        destinationUri: Uri,
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
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): EpubExportResult {
        val entriesList = zip.entries().toList().filter { !it.isDirectory }
        val total = entriesList.size.coerceAtLeast(1)
        var processedCount = 0
        var htmlDocsProcessed = 0

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

                if (isHtmlEntry && visualRules.isNotEmpty()) {
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
        }

        return EpubExportResult(
            success = true,
            processedChapters = htmlDocsProcessed,
            totalEntries = entriesList.size
        )
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
        PronunciationProcessor.applyReaderRulesToTextNodes(
            root = bodyNode,
            rules = visualRules,
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
