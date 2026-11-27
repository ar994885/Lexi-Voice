package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.db.PronunciationRuleEntity

/**
 * Edited terms for one book: text edits that change what the reader shows and what
 * the exported EPUB contains. Kept separate from TTS pronunciation rules, which
 * change what the voice says and never touch the page.
 */
@Composable
fun ReaderEditedTermsSheet(
    terms: List<PronunciationRuleEntity>,
    onSave: (
        id: Long,
        original: String,
        replacement: String,
        termScope: String,
        sentenceIndex: Int,
        startOffset: Int,
        endOffset: Int
    ) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<PronunciationRuleEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Edited Terms", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Applies to this book only: the reader shows the replacement and the " +
                    "exported EPUB contains it. Pronunciation rules are untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            TextButton(
                onClick = { creating = true },
                modifier = Modifier.testTag("edited_terms_add")
            ) { Text("Add a term") }

            if (terms.isEmpty()) {
                Text(
                    text = "No edited terms yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(terms, key = { it.id }) { term ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = term.findText + " → " + term.replaceWith,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (term.termScope == "WORD") "This word only" else "Whole book",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { editing = term }) { Text("Edit") }
                            TextButton(onClick = { onDelete(term.id) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    val target = editing
    if (creating || target != null) {
        EditorDialog(
            existing = target,
            onDismiss = {
                creating = false
                editing = null
            },
            onConfirm = { original, replacement, scope, sentence, start, end ->
                onSave(target?.id ?: 0L, original, replacement, scope, sentence, start, end)
                creating = false
                editing = null
            }
        )
    }
}

@Composable
private fun EditorDialog(
    existing: PronunciationRuleEntity?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, Int, Int, Int) -> Unit
) {
    var original by remember { mutableStateOf(existing?.findText ?: "") }
    var replacement by remember { mutableStateOf(existing?.replaceWith ?: "") }
    var scope by remember { mutableStateOf(existing?.termScope ?: "BOOK") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Term") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = original,
                    onValueChange = { original = it },
                    label = { Text("Original Text") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edited_term_original")
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("Replacement Text") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edited_term_replacement")
                )
                Text("Scope", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scope == "WORD",
                        onClick = { scope = "WORD" },
                        label = { Text("This word") },
                        modifier = Modifier.testTag("edited_term_scope_word")
                    )
                    FilterChip(
                        selected = scope == "BOOK",
                        onClick = { scope = "BOOK" },
                        label = { Text("Whole book") },
                        modifier = Modifier.testTag("edited_term_scope_book")
                    )
                }
                Text(
                    text = if (scope == "WORD") {
                        "Only the occurrence you selected is changed."
                    } else {
                        "Every occurrence in this book is changed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (original.isNotBlank()) {
                        onConfirm(
                            original,
                            replacement,
                            scope,
                            existing?.sentenceIndex ?: 0,
                            existing?.startOffset ?: 0,
                            existing?.endOffset ?: 0
                        )
                    }
                },
                modifier = Modifier.testTag("edited_term_save")
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
