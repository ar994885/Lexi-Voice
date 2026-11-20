package com.example.audiobook

/**
 * Aligns a chapter's spoken sync segments to the sentences the parser produces now.
 *
 * The problem this solves: a sync map stores `sentenceIndex`, which is a *position*
 * in the sentence list the parser produced on the day the audio was recorded. Any
 * later change to how sentences are split — for example the rule that stopped
 * treating an italic run as its own sentence — shifts every index after the first
 * changed boundary, so the read-along highlights the wrong sentence and appears to
 * jump around. The stored timestamps are still correct; only the positions went
 * stale.
 *
 * Both lists are in reading order, so a single forward pass is enough: no searching
 * back, no per-frame scanning, and no dependence on playback direction. That also
 * fixes a defect in the previous inline heuristic, which advanced a cursor forward
 * only and so could not recover after the user seeked backwards.
 *
 * Pure Kotlin with no Android dependencies, so it is directly unit-testable.
 */
object SyncReconciler {

    /** How far to look ahead when the two lists temporarily disagree. */
    private const val LOOKAHEAD = 6

    /**
     * @param segmentTexts the text of each spoken segment in a chapter, in spoken order
     * @param sentenceTexts the text of each sentence the parser produces now, in reading order
     * @return for each segment, the position in [sentenceTexts] it corresponds to, or -1
     *         when it could not be aligned at all
     */
    fun align(segmentTexts: List<String>, sentenceTexts: List<String>): IntArray {
        val result = IntArray(segmentTexts.size) { -1 }
        if (segmentTexts.isEmpty() || sentenceTexts.isEmpty()) return result

        val segments = segmentTexts.map(::normalise)
        val sentences = sentenceTexts.map(::normalise)

        // Fast, exact path. When the book's wording is unchanged and only the
        // sentence boundaries moved, the two sides are the same character sequence
        // once whitespace is ignored — so a segment's first character maps straight
        // onto the sentence that owns the same character. This is exact for both
        // merges and splits, with no heuristics at all.
        val sentenceChars = StringBuilder()
        val sentenceOwner = ArrayList<Int>()
        for ((index, text) in sentences.withIndex()) {
            for (ch in text) {
                sentenceChars.append(ch)
                sentenceOwner.add(index)
            }
        }

        val segmentChars = StringBuilder()
        val segmentStart = IntArray(segments.size) { -1 }
        for ((index, text) in segments.withIndex()) {
            if (text.isEmpty()) continue
            segmentStart[index] = segmentChars.length
            segmentChars.append(text)
        }

        if (sentenceChars.contentEquals(segmentChars)) {
            for (index in segments.indices) {
                val start = segmentStart[index]
                if (start >= 0 && start < sentenceOwner.size) {
                    result[index] = sentenceOwner[start]
                }
            }
            return result
        }

        // The wording itself differs (a sentence was edited, added or removed), so
        // fall back to walking both lists together with a bounded lookahead.
        return alignApproximately(segments, sentences, result)
    }

    private fun alignApproximately(
        segments: List<String>,
        sentences: List<String>,
        result: IntArray
    ): IntArray {
        var i = 0
        var j = 0
        while (i < segments.size && j < sentences.size) {
            val spoken = segments[i]
            val current = sentences[j]
            when {
                spoken.isEmpty() -> i++
                current.isEmpty() -> j++
                spoken == current -> {
                    result[i] = j
                    i++
                    j++
                }
                // Folded into a longer sentence: record it, and move on. The sentence
                // is only left behind when the next segment no longer fits inside it,
                // which the mismatch branch below handles.
                current.contains(spoken) -> {
                    result[i] = j
                    i++
                }
                spoken.contains(current) -> {
                    result[i] = j
                    j++
                }
                else -> {
                    val nextSentence = findAhead(segments[i], sentences, j)
                    if (nextSentence >= 0) {
                        // A sentence was inserted; skip forward in the book.
                        j = nextSentence
                    } else {
                        val nextSegment = findAhead(sentences[j], segments, i)
                        if (nextSegment >= 0) {
                            // A segment has no counterpart; drop it and continue.
                            i = nextSegment
                        } else {
                            i++
                        }
                    }
                }
            }
        }
        return result
    }

    /** Index of [needle] within [haystack] at or after [from], searching [LOOKAHEAD] items. */
    private fun findAhead(needle: String, haystack: List<String>, from: Int): Int {
        val last = minOf(haystack.size - 1, from + LOOKAHEAD)
        for (k in from..last) {
            if (haystack[k] == needle) return k
        }
        return -1
    }

    /**
     * Comparison key: case-insensitive and whitespace-insensitive.
     *
     * Whitespace is removed rather than collapsed because the old italic rule could
     * emit `" Shi Ming` where the source HTML has `"Shi Ming` — a space that never
     * existed in the book. Removing whitespace makes those compare equal.
     */
    fun normalise(text: String): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder(text.length)
        for (ch in text) {
            if (!ch.isWhitespace()) out.append(ch.lowercaseChar())
        }
        return out.toString()
    }
}
