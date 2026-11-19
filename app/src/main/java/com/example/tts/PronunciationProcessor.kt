package com.example.tts

import com.example.data.db.PronunciationRuleEntity
import com.example.data.db.PronunciationScope
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.Locale

/**
 * Applies custom pronunciation and word/name-replacement rules.
 *
 * Precedence: Single-Book rules (for matching bookId) are applied first, followed by Global rules.
 * Audio playback/export rules check [PronunciationRuleEntity.applyToAudio]; visual reader
 * display and edited EPUB exports check [PronunciationRuleEntity.applyToReaderText].
 */
object PronunciationProcessor {

    private data class CompiledRule(
        val regex: Regex,
        val replacement: String
    )

    fun applyRules(
        rawText: String,
        rules: List<PronunciationRuleEntity>,
        bookId: Long? = null
    ): String {
        val activeOrdered = filterAndOrder(rules, bookId) { true }
        return applyCompiled(rawText, compileRules(activeOrdered))
    }

    fun applyAudioRules(
        rawText: String,
        rules: List<PronunciationRuleEntity>,
        bookId: Long? = null
    ): String {
        val activeOrdered = filterAndOrder(rules, bookId) { it.applyToAudio }
        return applyCompiled(rawText, compileRules(activeOrdered))
    }

    fun applyReaderRules(
        rawText: String,
        rules: List<PronunciationRuleEntity>,
        bookId: Long? = null
    ): String {
        val activeOrdered = filterAndOrder(rules, bookId) { it.applyToReaderText }
        return applyCompiled(rawText, compileRules(activeOrdered))
    }

    /**
     * Applies active reader-display rules strictly to Jsoup [TextNode] text content,
     * leaving element tags, attributes, inline styles, CSS links, and scripts untouched.
     */
    fun applyReaderRulesToTextNodes(
        root: Node,
        rules: List<PronunciationRuleEntity>,
        bookId: Long? = null,
        skipTags: Set<String> = setOf("script", "style", "svg", "title", "noscript", "rt", "rp")
    ) {
        val activeOrdered = filterAndOrder(rules, bookId) { it.applyToReaderText }
        if (activeOrdered.isEmpty()) return
        val compiled = compileRules(activeOrdered)

        fun walk(node: Node) {
            if (node is Element && node.tagName().lowercase(Locale.ROOT) in skipTags) return
            if (node is TextNode) {
                val whole = node.wholeText
                if (whole.isNotBlank()) {
                    val replaced = applyCompiled(whole, compiled)
                    if (replaced != whole) {
                        node.text(replaced)
                    }
                }
                return
            }
            val children = node.childNodes().toList()
            for (child in children) {
                walk(child)
            }
        }
        walk(root)
    }

    private inline fun filterAndOrder(
        rules: List<PronunciationRuleEntity>,
        bookId: Long?,
        crossinline targetPredicate: (PronunciationRuleEntity) -> Boolean
    ): List<PronunciationRuleEntity> {
        if (rules.isEmpty()) return emptyList()
        return rules.asSequence()
            .filter { it.isEnabled && it.findText.isNotEmpty() && targetPredicate(it) }
            .filter { rule ->
                when (rule.scope) {
                    PronunciationScope.GLOBAL -> true
                    PronunciationScope.BOOK -> bookId != null && bookId > 0L && rule.bookId == bookId
                }
            }
            .sortedWith(
                compareBy<PronunciationRuleEntity>(
                    { if (it.scope == PronunciationScope.BOOK) 0 else 1 },
                    { it.id }
                )
            )
            .toList()
    }

    private fun compileRules(activeOrdered: List<PronunciationRuleEntity>): List<CompiledRule> {
        return activeOrdered.map { rule ->
            val escapedFind = Regex.escape(rule.findText)
            val pattern = if (rule.wholeWordOnly) {
                val first = rule.findText.first()
                val last = rule.findText.last()
                val leftBoundary = if (first.isLetterOrDigit() || first == '_') "(?<![\\p{L}\\p{N}_])" else ""
                val rightBoundary = if (last.isLetterOrDigit() || last == '_') "(?![\\p{L}\\p{N}_])" else ""
                "$leftBoundary$escapedFind$rightBoundary"
            } else {
                escapedFind
            }
            val options = if (rule.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            CompiledRule(
                regex = Regex(pattern, options),
                replacement = Regex.escapeReplacement(rule.replaceWith)
            )
        }
    }

    private fun applyCompiled(rawText: String, compiled: List<CompiledRule>): String {
        if (rawText.isBlank() || compiled.isEmpty()) return rawText
        var processed = rawText
        for (rule in compiled) {
            processed = rule.regex.replace(processed, rule.replacement)
        }
        return processed
    }
}
