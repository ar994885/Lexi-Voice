package com.example.export

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** One finding from a validation run. */
data class EpubIssue(
    val severity: Severity,
    val message: String
) {
    enum class Severity { ERROR, WARNING }
}

data class EpubValidationReport(
    val errors: List<EpubIssue>,
    val warnings: List<EpubIssue>,
    val checkedEntries: Int,
    /** Which checker produced this: EPUBCheck's version, or the built-in one. */
    val engine: String = "built-in structural checker"
) {
    val isClean: Boolean get() = errors.isEmpty()

    fun summary(): String = when {
        errors.isEmpty() && warnings.isEmpty() -> "No problems found in $checkedEntries entries"
        errors.isEmpty() -> "${warnings.size} warning(s), no errors"
        else -> "${errors.size} error(s), ${warnings.size} warning(s)"
    }
}

/**
 * Structural checks on an EPUB, done on-device and offline.
 *
 * This is not EPUBCheck: EPUBCheck is a Java SE tool that needs a full JVM and the
 * W3C schemas, neither of which exists on Android. What is here is the subset of
 * its rules that actually catches a broken repack — the mistakes an editor or an
 * export can introduce: a misplaced mimetype, a manifest pointing at files that
 * are not in the archive, a spine idref that names no item, an XHTML document that
 * is not well-formed, a missing nav or NCX.
 */
object EpubValidator {

    suspend fun validate(context: Context, uri: Uri): EpubValidationReport = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File.createTempFile("lexi-validate", ".epub", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                } ?: return@runCatching EpubValidationReport(
                    errors = listOf(EpubIssue(EpubIssue.Severity.ERROR, "Could not read the exported file")),
                    warnings = emptyList(),
                    checkedEntries = 0
                )
                validateFile(temp)
            } finally {
                runCatching { temp.delete() }
            }
        }.getOrElse { t ->
            EpubValidationReport(
                errors = listOf(EpubIssue(EpubIssue.Severity.ERROR, "Validation failed: ${t.message}")),
                warnings = emptyList(),
                checkedEntries = 0
            )
        }
    }

    fun validateFile(file: File): EpubValidationReport {
        val errors = mutableListOf<EpubIssue>()
        val warnings = mutableListOf<EpubIssue>()
        val entries = mutableMapOf<String, ByteArray>()
        val names = mutableListOf<String>()
        var mimetypeMethod = -1
        var firstEntryName = ""

        ZipInputStream(file.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            var index = 0
            while (entry != null) {
                if (index == 0) firstEntryName = entry.name
                if (entry.name == "mimetype") mimetypeMethod = entry.method
                if (names.contains(entry.name)) {
                    errors += EpubIssue(EpubIssue.Severity.ERROR, "Duplicate archive entry: ${entry.name}")
                }
                names += entry.name
                if (!entry.isDirectory) {
                    // Keep only the small structural files in memory.
                    val keep = entry.name.equals("mimetype") ||
                        entry.name == "META-INF/container.xml" ||
                        entry.name.endsWith(".opf", true) ||
                        entry.name.endsWith(".xhtml", true) ||
                        entry.name.endsWith(".html", true) ||
                        entry.name.endsWith(".htm", true) ||
                        entry.name.endsWith(".ncx", true)
                    if (keep) {
                        entries[entry.name] = zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
                index++
            }
        }

        if (firstEntryName != "mimetype") {
            errors += EpubIssue(
                EpubIssue.Severity.ERROR,
                "First archive entry is \"$firstEntryName\"; the EPUB spec requires \"mimetype\" first"
            )
        }
        if (mimetypeMethod != 0) {
            errors += EpubIssue(
                EpubIssue.Severity.ERROR,
                "mimetype is compressed; it must be stored uncompressed"
            )
        }
        val mimetypeText = entries["mimetype"]?.toString(Charsets.UTF_8)?.trim()
        if (mimetypeText != "application/epub+zip") {
            errors += EpubIssue(
                EpubIssue.Severity.ERROR,
                "mimetype reads \"${mimetypeText ?: "missing"}\", expected application/epub+zip"
            )
        }

        val container = entries["META-INF/container.xml"]
        if (container == null) {
            errors += EpubIssue(EpubIssue.Severity.ERROR, "META-INF/container.xml is missing")
            return EpubValidationReport(errors, warnings, names.size)
        }

        val opfPath = runCatching {
            val doc = parseXml(container)
            val rootfiles = doc.getElementsByTagName("rootfile")
            if (rootfiles.length == 0) null
            else (rootfiles.item(0) as Element).getAttribute("full-path").takeIf { it.isNotBlank() }
        }.getOrNull()

        if (opfPath == null) {
            errors += EpubIssue(EpubIssue.Severity.ERROR, "container.xml names no rootfile")
            return EpubValidationReport(errors, warnings, names.size)
        }

        val opfBytes = entries[opfPath]
        if (opfBytes == null) {
            errors += EpubIssue(EpubIssue.Severity.ERROR, "The OPF named in container.xml ($opfPath) is not in the archive")
            return EpubValidationReport(errors, warnings, names.size)
        }

        val opfDir = opfPath.substringBeforeLast('/', "")
        val opfDoc = runCatching { parseXml(opfBytes) }.getOrElse { t ->
            errors += EpubIssue(EpubIssue.Severity.ERROR, "OPF is not well-formed XML: ${t.message}")
            return EpubValidationReport(errors, warnings, names.size)
        }

        val metadata = opfDoc.getElementsByTagName("metadata").item(0) as? Element
        val title = metadata?.let { md ->
            val titles = md.getElementsByTagName("dc:title")
            if (titles.length > 0) titles.item(0).textContent?.trim() else null
        }
        if (title.isNullOrBlank()) {
            warnings += EpubIssue(EpubIssue.Severity.WARNING, "No dc:title in the OPF")
        }

        val uniqueIdAttr = opfDoc.documentElement?.getAttribute("unique-identifier")
        if (uniqueIdAttr.isNullOrBlank()) {
            warnings += EpubIssue(EpubIssue.Severity.WARNING, "package element has no unique-identifier attribute")
        }

        val manifestItems = opfDoc.getElementsByTagName("item")
        val ids = mutableSetOf<String>()
        val manifestHrefs = mutableSetOf<String>()
        var navFound = false
        for (i in 0 until manifestItems.length) {
            val item = manifestItems.item(i) as? Element ?: continue
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            val properties = item.getAttribute("properties")
            if (id.isNotBlank() && !ids.add(id)) {
                errors += EpubIssue(EpubIssue.Severity.ERROR, "Duplicate manifest id \"$id\"")
            }
            if (href.isNotBlank()) {
                val resolved = resolve(opfDir, href)
                manifestHrefs += resolved
                if (!names.contains(resolved)) {
                    errors += EpubIssue(EpubIssue.Severity.ERROR, "Manifest item \"$id\" points at a missing file: $resolved")
                }
            }
            if (properties.split(' ').any { it.equals("nav", true) }) navFound = true
        }

        val spineItems = opfDoc.getElementsByTagName("itemref")
        if (spineItems.length == 0) {
            errors += EpubIssue(EpubIssue.Severity.ERROR, "Spine is empty")
        }
        for (i in 0 until spineItems.length) {
            val ref = spineItems.item(i) as? Element ?: continue
            val idref = ref.getAttribute("idref")
            if (idref.isNotBlank() && idref !in ids) {
                errors += EpubIssue(EpubIssue.Severity.ERROR, "Spine references \"$idref\", which is not in the manifest")
            }
        }

        val isEpub3 = opfDoc.documentElement?.getAttribute("version")?.startsWith("3") == true
        val hasNcx = names.any { it.endsWith(".ncx", true) }
        if (isEpub3 && !navFound) {
            errors += EpubIssue(EpubIssue.Severity.ERROR, "EPUB 3 book has no nav document in the manifest")
        }
        if (!isEpub3 && !hasNcx) {
            warnings += EpubIssue(EpubIssue.Severity.WARNING, "EPUB 2 book has no NCX")
        }

        // Well-formedness of the content documents, which is where a bad edit shows up.
        for ((name, bytes) in entries) {
            if (!(name.endsWith(".xhtml", true) || name.endsWith(".html", true) || name.endsWith(".htm", true))) continue
            runCatching { parseXml(bytes) }.onFailure {
                errors += EpubIssue(EpubIssue.Severity.ERROR, "Not well-formed XML: $name")
            }
        }

        return EpubValidationReport(errors, warnings, names.size)
    }

    /**
     * Parses a document for well-formedness.
     *
     * XHTML in the wild uses HTML named entities (&nbsp;, &mdash;, ...) that plain
     * XML has no definition for, and a strict parser rejects them. EPUBCheck
     * resolves them through the XHTML DTD, so reporting them as errors here was a
     * false positive; the entities are substituted before parsing instead.
     */
    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ ->
            org.xml.sax.InputSource(java.io.StringReader(""))
        }
        return builder.parse(ByteArrayInputStream(substituteHtmlEntities(bytes)))
    }

    private val HTML_ENTITIES = mapOf(
        "nbsp" to "\u00A0", "mdash" to "\u2014", "ndash" to "\u2013",
        "hellip" to "\u2026", "lsquo" to "\u2018", "rsquo" to "\u2019",
        "ldquo" to "\u201C", "rdquo" to "\u201D", "sbquo" to "\u201A",
        "bdquo" to "\u201E", "dagger" to "\u2020", "Dagger" to "\u2021",
        "bull" to "\u2022", "prime" to "\u2032", "Prime" to "\u2033",
        "trade" to "\u2122", "copy" to "\u00A9", "reg" to "\u00AE",
        "deg" to "\u00B0", "plusmn" to "\u00B1", "frac12" to "\u00BD",
        "frac14" to "\u00BC", "frac34" to "\u00BE", "times" to "\u00D7",
        "divide" to "\u00F7", "laquo" to "\u00AB", "raquo" to "\u00BB",
        "euro" to "\u20AC", "pound" to "\u00A3", "yen" to "\u00A5",
        "cent" to "\u00A2", "sect" to "\u00A7", "para" to "\u00B6",
        "middot" to "\u00B7", "ensp" to "\u2002", "emsp" to "\u2003",
        "thinsp" to "\u2009", "zwnj" to "\u200C", "zwj" to "\u200D",
        "shy" to "\u00AD", "acute" to "\u00B4", "micro" to "\u00B5",
        "aacute" to "\u00E1", "eacute" to "\u00E9", "iacute" to "\u00ED",
        "oacute" to "\u00F3", "uacute" to "\u00FA", "agrave" to "\u00E0",
        "egrave" to "\u00E8", "igrave" to "\u00EC", "ograve" to "\u00F2",
        "ugrave" to "\u00F9", "ccedil" to "\u00E7", "ntilde" to "\u00F1",
        "uuml" to "\u00FC", "ouml" to "\u00F6", "auml" to "\u00E4",
        "szlig" to "\u00DF", "aring" to "\u00E5", "oslash" to "\u00F8",
        "aelig" to "\u00E6"
    )

    private fun substituteHtmlEntities(bytes: ByteArray): ByteArray {
        var text = String(bytes, Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        if (!text.contains('&')) return bytes
        val pattern = Regex("&([A-Za-z][A-Za-z0-9]{1,31});")
        val replaced = pattern.replace(text) { match ->
            val name = match.groupValues[1]
            when (name) {
                "amp", "lt", "gt", "quot", "apos" -> match.value
                else -> HTML_ENTITIES[name] ?: match.value
            }
        }
        return replaced.toByteArray(Charsets.UTF_8)
    }

    /** Resolves an href against the OPF folder and normalises ./ and ../ segments. */
    private fun resolve(dir: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { java.net.URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val joined = if (dir.isEmpty()) decoded else "$dir/$decoded"
        val out = ArrayDeque<String>()
        for (part in joined.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(part)
            }
        }
        return out.joinToString("/")
    }
}
