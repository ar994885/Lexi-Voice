package com.example.tts

import com.example.data.db.PronunciationRuleEntity
import com.example.data.db.PronunciationScope

/**
 * Applies custom pronunciation and word-replacement rules strictly to strings sent to
 * Android TextToSpeech (speak / synthesizeToFile), leaving EPUB WebView display text and
 * sync_map.json display text untouched.
 *
 * Precedence: Single-Book rules (for matching bookId) are applied first, followed by Global rules.
 */
object PronunciationProcessor {

    fun applyRules(
        rawText: String,
        rules: List<PronunciationRuleEntity>,
        bookId: Long? = null
    ): String {
        if (rawText.isBlank() || rules.isEmpty()) return rawText

        val activeOrdered = rules.asSequence()
            .filter { it.isEnabled && it.findText.isNotEmpty() }
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

        if (activeOrdered.isEmpty()) return rawText

        var processed = rawText
        for (rule in activeOrdered) {
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
            val regex = Regex(pattern, options)
            processed = regex.replace(processed, Regex.escapeReplacement(rule.replaceWith))
        }
        return processed
    }
}
