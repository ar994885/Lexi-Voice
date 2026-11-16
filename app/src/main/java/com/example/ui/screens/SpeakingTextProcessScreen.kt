package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.LexiVoiceApplication
import com.example.data.db.BookEntity
import com.example.data.db.LibrarySortOption
import com.example.data.db.PronunciationRuleEntity
import com.example.data.db.PronunciationScope
import com.example.tts.TtsPlaybackService
import kotlinx.coroutines.launch

private enum class RuleFilterTab(val label: String) {
    ALL("All"),
    GLOBAL("Global Rules"),
    BOOK("Current Book Rules")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakingTextProcessScreen(
    initialBookId: Long = -1L,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as LexiVoiceApplication
    val repo = app.pronunciationRepository
    val scope = rememberCoroutineScope()

    val allRules by repo.observeAllRules().collectAsStateWithLifecycle(emptyList())
    val allBooks by app.libraryRepository.observeLibraryBooks("", null, LibrarySortOption.TITLE)
        .collectAsStateWithLifecycle(emptyList())

    var selectedBookId by remember(initialBookId) {
        mutableStateOf(initialBookId.takeIf { it > 0L })
    }
    var activeTabIdx by remember(initialBookId) {
        mutableIntStateOf(if (initialBookId > 0L) RuleFilterTab.ALL.ordinal else RuleFilterTab.ALL.ordinal)
    }
    var bookDropdownExpanded by remember { mutableStateOf(false) }

    var editingRule by remember { mutableStateOf<PronunciationRuleEntity?>(null) }
    var showEditorDialog by remember { mutableStateOf(false) }

    val selectedBook = remember(allBooks, selectedBookId) {
        allBooks.firstOrNull { it.id == selectedBookId }
    }
    val bookTitleMap = remember(allBooks) {
        allBooks.associate { it.id to it.title }
    }

    val activeTab = RuleFilterTab.entries[activeTabIdx]
    val filteredRules = remember(allRules, activeTab, selectedBookId) {
        when (activeTab) {
            RuleFilterTab.ALL -> {
                if (selectedBookId != null) {
                    allRules.filter { it.scope == PronunciationScope.GLOBAL || it.bookId == selectedBookId }
                } else {
                    allRules
                }
            }
            RuleFilterTab.GLOBAL -> allRules.filter { it.scope == PronunciationScope.GLOBAL }
            RuleFilterTab.BOOK -> {
                if (selectedBookId != null) {
                    allRules.filter { it.scope == PronunciationScope.BOOK && it.bookId == selectedBookId }
                } else {
                    allRules.filter { it.scope == PronunciationScope.BOOK }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("pronunciation_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("Speaking Text Process", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = selectedBook?.title?.let { "Book: $it" } ?: "Global & Book Pronunciation Rules",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingRule = null
                    showEditorDialog = true
                },
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("add_pronunciation_rule_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Pronunciation Rule")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = activeTabIdx) {
                RuleFilterTab.entries.forEachIndexed { idx, tab ->
                    Tab(
                        selected = activeTabIdx == idx,
                        onClick = { activeTabIdx = idx },
                        text = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("pronunciation_tab_${tab.name.lowercase()}")
                    )
                }
            }

            if (allBooks.isNotEmpty() && (initialBookId <= 0L || activeTab == RuleFilterTab.BOOK)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    ExposedDropdownMenuBox(
                        expanded = bookDropdownExpanded,
                        onExpandedChange = { bookDropdownExpanded = !bookDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedBook?.title ?: "All Books (No Single-Book Filter)",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Book Filter / Target") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bookDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = bookDropdownExpanded,
                            onDismissRequest = { bookDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All Books") },
                                onClick = {
                                    selectedBookId = null
                                    bookDropdownExpanded = false
                                }
                            )
                            allBooks.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        selectedBookId = b.id
                                        bookDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "Rules alter spoken TTS and exported MP3/M4B audio only. EPUB display text remains unchanged. Single-Book rules run before Global rules.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (filteredRules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No Pronunciation Rules Yet",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Tap + to fix mispronounced names, fantasy terms, or abbreviations for live TTS and audio export.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                editingRule = null
                                showEditorDialog = true
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add First Rule")
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredRules, key = { it.id }) { rule ->
                        PronunciationRuleCard(
                            rule = rule,
                            bookTitle = rule.bookId?.let { bookTitleMap[it] },
                            onEdit = {
                                editingRule = rule
                                showEditorDialog = true
                            },
                            onToggleEnabled = { enabled ->
                                scope.launch { repo.setRuleEnabled(rule.id, enabled) }
                            },
                            onTestSpeak = {
                                val targetText = rule.replaceWith.ifBlank { rule.findText }
                                TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TEST_SPEAK) {
                                    putExtra(TtsPlaybackService.EXTRA_TEXT, targetText)
                                }
                            },
                            onDelete = {
                                scope.launch { repo.deleteRule(rule.id) }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showEditorDialog) {
        PronunciationRuleEditorDialog(
            existingRule = editingRule,
            defaultFindText = "",
            defaultBookId = selectedBookId,
            availableBooks = allBooks,
            onDismiss = { showEditorDialog = false },
            onSave = { findText, replaceWith, ruleScope, ruleBookId, matchCase, wholeWord ->
                scope.launch {
                    repo.saveRule(
                        id = editingRule?.id ?: 0L,
                        findText = findText,
                        replaceWith = replaceWith,
                        scope = ruleScope,
                        bookId = ruleBookId,
                        matchCase = matchCase,
                        wholeWordOnly = wholeWord,
                        isEnabled = editingRule?.isEnabled ?: true
                    )
                    showEditorDialog = false
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PronunciationRuleCard(
    rule: PronunciationRuleEntity,
    bookTitle: String?,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTestSpeak: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onEdit,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pronunciation_rule_card_${rule.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = rule.findText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "speaks as",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = rule.replaceWith,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            color = if (rule.scope == PronunciationScope.BOOK) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = if (rule.scope == PronunciationScope.BOOK) Icons.Default.Book else Icons.Default.Public,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (rule.scope == PronunciationScope.BOOK) {
                                        bookTitle ?: "Book #${rule.bookId ?: 0}"
                                    } else {
                                        "Global"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (rule.matchCase) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Match Case",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                        if (rule.wholeWordOnly) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Whole Word",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = onTestSpeak) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Test Pronunciation", tint = MaterialTheme.colorScheme.primary)
                }
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggleEnabled
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete Rule",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PronunciationRuleEditorDialog(
    existingRule: PronunciationRuleEntity? = null,
    defaultFindText: String = "",
    defaultBookId: Long? = null,
    availableBooks: List<BookEntity> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (
        findText: String,
        replaceWith: String,
        scope: PronunciationScope,
        bookId: Long?,
        matchCase: Boolean,
        wholeWordOnly: Boolean
    ) -> Unit
) {
    val context = LocalContext.current
    var findText by remember(existingRule, defaultFindText) {
        mutableStateOf(existingRule?.findText ?: defaultFindText)
    }
    var replaceWith by remember(existingRule) {
        mutableStateOf(existingRule?.replaceWith ?: "")
    }
    var scopeChoice by remember(existingRule, defaultBookId) {
        mutableStateOf(
            existingRule?.scope
                ?: if (defaultBookId != null && defaultBookId > 0L) PronunciationScope.BOOK else PronunciationScope.GLOBAL
        )
    }
    var targetBookId by remember(existingRule, defaultBookId, availableBooks) {
        mutableStateOf(
            existingRule?.bookId
                ?: defaultBookId?.takeIf { it > 0L }
                ?: availableBooks.firstOrNull()?.id
        )
    }
    var matchCase by remember(existingRule) { mutableStateOf(existingRule?.matchCase ?: false) }
    var wholeWordOnly by remember(existingRule) { mutableStateOf(existingRule?.wholeWordOnly ?: true) }
    var bookMenuExpanded by remember { mutableStateOf(false) }

    val selectedBookTitle = remember(targetBookId, availableBooks) {
        availableBooks.firstOrNull { it.id == targetBookId }?.title
            ?: if (targetBookId != null) "Current Book (#$targetBookId)" else "Select Book"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (existingRule == null) "Add Pronunciation Rule" else "Edit Pronunciation Rule")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = findText,
                    onValueChange = { findText = it },
                    label = { Text("Original Text") },
                    placeholder = { Text("e.g., martial arts") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pronunciation_find_input")
                )

                OutlinedTextField(
                    value = replaceWith,
                    onValueChange = { replaceWith = it },
                    label = { Text("Speak As (Replacement)") },
                    placeholder = { Text("e.g., maartial arts") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pronunciation_replace_input")
                )

                OutlinedButton(
                    onClick = {
                        val textToSpeak = replaceWith.trim().ifBlank { findText.trim() }
                        if (textToSpeak.isNotBlank()) {
                            TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TEST_SPEAK) {
                                putExtra(TtsPlaybackService.EXTRA_TEXT, textToSpeak)
                            }
                        }
                    },
                    enabled = findText.isNotBlank() || replaceWith.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pronunciation_test_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Test Pronunciation")
                }

                HorizontalDivider()

                Text("Rule Scope", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scopeChoice == PronunciationScope.GLOBAL,
                        onClick = { scopeChoice = PronunciationScope.GLOBAL },
                        label = { Text("Global — All Books") },
                        modifier = Modifier.testTag("scope_global_chip")
                    )
                    FilterChip(
                        selected = scopeChoice == PronunciationScope.BOOK,
                        onClick = { scopeChoice = PronunciationScope.BOOK },
                        enabled = (targetBookId != null && targetBookId!! > 0L) || availableBooks.isNotEmpty(),
                        label = { Text("This Book Only") },
                        modifier = Modifier.testTag("scope_book_chip")
                    )
                }

                if (scopeChoice == PronunciationScope.BOOK && availableBooks.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = bookMenuExpanded,
                        onExpandedChange = { bookMenuExpanded = !bookMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedBookTitle,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Target Book") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bookMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = bookMenuExpanded,
                            onDismissRequest = { bookMenuExpanded = false }
                        ) {
                            availableBooks.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        targetBookId = b.id
                                        bookMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Match Case", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = matchCase,
                        onCheckedChange = { matchCase = it },
                        modifier = Modifier.testTag("pronunciation_match_case_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Whole Word Only", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = wholeWordOnly,
                        onCheckedChange = { wholeWordOnly = it },
                        modifier = Modifier.testTag("pronunciation_whole_word_switch")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val bookIdToSave = if (scopeChoice == PronunciationScope.BOOK) targetBookId else null
                    onSave(findText.trim(), replaceWith.trim(), scopeChoice, bookIdToSave, matchCase, wholeWordOnly)
                },
                enabled = findText.isNotBlank() && replaceWith.isNotBlank() &&
                    (scopeChoice == PronunciationScope.GLOBAL || (targetBookId ?: -1L) > 0L),
                modifier = Modifier.testTag("save_pronunciation_rule_button")
            ) {
                Text("Save Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
