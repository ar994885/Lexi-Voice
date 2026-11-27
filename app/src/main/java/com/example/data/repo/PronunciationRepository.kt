package com.example.data.repo

import com.example.data.db.PronunciationRuleDao
import com.example.data.db.PronunciationRuleEntity
import com.example.data.db.PronunciationScope
import kotlinx.coroutines.flow.Flow

class PronunciationRepository(
    private val dao: PronunciationRuleDao,
    private val onDataChanged: () -> Unit = {}
) {
    fun observeAllRules(): Flow<List<PronunciationRuleEntity>> = dao.observeAllRules()

    fun observeRulesForBook(bookId: Long): Flow<List<PronunciationRuleEntity>> = dao.observeRulesForBook(bookId)

    suspend fun getEnabledRulesForPlayback(bookId: Long?): List<PronunciationRuleEntity> =
        dao.getEnabledRulesForPlayback(bookId)

    suspend fun getEnabledRulesForReaderDisplay(bookId: Long?): List<PronunciationRuleEntity> =
        // A WORD-scoped term is anchored to one occurrence, so it must not go through
        // the text-wide replacer: the reader applies those itself, by range.
        dao.getEnabledRulesForReaderDisplay(bookId)
            .filterNot { it.kind == "TERM" && it.termScope == "WORD" }

    /**
     * Reader-display rules including anchored ("this word") terms. The reader filters
     * those out because it applies them by range, but the exporter needs them: it
     * applies them itself, one occurrence at a time.
     */
    suspend fun getRulesForExport(bookId: Long?): List<PronunciationRuleEntity> =
        dao.getEnabledRulesForReaderDisplay(bookId)

    fun observeTermRulesForBook(bookId: Long): Flow<List<PronunciationRuleEntity>> =
        dao.observeTermRulesForBook(bookId)

    suspend fun saveRule(
        id: Long = 0L,
        findText: String,
        replaceWith: String,
        scope: PronunciationScope,
        bookId: Long?,
        matchCase: Boolean,
        wholeWordOnly: Boolean,
        isEnabled: Boolean = true,
        applyToAudio: Boolean = true,
        applyToReaderText: Boolean = false,
        kind: String = "TTS",
        termScope: String = "BOOK",
        sentenceIndex: Int = 0,
        startOffset: Int = 0,
        endOffset: Int = 0
    ): Long {
        val normalizedBookId = if (scope == PronunciationScope.BOOK) bookId?.takeIf { it > 0L } else null
        val entity = PronunciationRuleEntity(
            id = id,
            findText = findText.trim(),
            replaceWith = replaceWith.trim(),
            scope = scope,
            bookId = normalizedBookId,
            matchCase = matchCase,
            wholeWordOnly = wholeWordOnly,
            isEnabled = isEnabled,
            applyToAudio = applyToAudio,
            applyToReaderText = applyToReaderText,
            kind = kind,
            termScope = termScope,
            sentenceIndex = sentenceIndex,
            startOffset = startOffset,
            endOffset = endOffset
        )
        val resultId = if (id > 0L) {
            dao.updateRule(entity)
            id
        } else {
            dao.insertRule(entity)
        }
        onDataChanged()
        return resultId
    }

    suspend fun setRuleEnabled(id: Long, enabled: Boolean) {
        dao.setRuleEnabled(id, enabled)
        onDataChanged()
    }

    suspend fun deleteRule(id: Long) {
        dao.deleteRule(id)
        onDataChanged()
    }
}
