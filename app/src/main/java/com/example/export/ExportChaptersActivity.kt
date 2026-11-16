package com.example.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.LexiVoiceApplication
import com.example.data.prefs.SavedExportFolder
import com.example.epub.EpubParser
import com.example.tts.TtsStateHolder
import com.example.ui.theme.LexiVoiceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExportChaptersActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val bookTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Audiobook"
        val bookAuthor = intent.getStringExtra(EXTRA_AUTHOR) ?: "Unknown Author"
        val fileKey = intent.getStringExtra(EXTRA_FILE_KEY).orEmpty()
        val isDirectPath = intent.getBooleanExtra(EXTRA_IS_DIRECT_PATH, false)
        val coverPath = intent.getStringExtra(EXTRA_COVER_PATH)
        val scratchpadText = intent.getStringExtra(EXTRA_SCRATCHPAD_TEXT)

        setContent {
            LexiVoiceTheme {
                ExportChaptersScreen(
                    sourceTitle = bookTitle,
                    sourceAuthor = bookAuthor,
                    fileKey = fileKey,
                    isDirectPath = isDirectPath,
                    coverPath = coverPath,
                    scratchpadText = scratchpadText,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_AUTHOR = "extra_author"
        private const val EXTRA_FILE_KEY = "extra_file_key"
        private const val EXTRA_IS_DIRECT_PATH = "extra_is_direct_path"
        private const val EXTRA_COVER_PATH = "extra_cover_path"
        private const val EXTRA_SCRATCHPAD_TEXT = "extra_scratchpad_text"

        fun launchForBook(
            context: Context,
            title: String,
            author: String,
            fileKey: String,
            isDirectPath: Boolean,
            coverPath: String?
        ) {
            val intent = Intent(context, ExportChaptersActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_AUTHOR, author)
                putExtra(EXTRA_FILE_KEY, fileKey)
                putExtra(EXTRA_IS_DIRECT_PATH, isDirectPath)
                putExtra(EXTRA_COVER_PATH, coverPath)
            }
            context.startActivity(intent)
        }

        fun launchForScratchpad(context: Context, title: String, text: String) {
            val intent = Intent(context, ExportChaptersActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title.ifBlank { "Quick Read" })
                putExtra(EXTRA_AUTHOR, "Paste & Speak")
                putExtra(EXTRA_SCRATCHPAD_TEXT, text)
            }
            context.startActivity(intent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportChaptersScreen(
    sourceTitle: String,
    sourceAuthor: String,
    fileKey: String,
    isDirectPath: Boolean,
    coverPath: String?,
    scratchpadText: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as LexiVoiceApplication
    val prefsRepo = app.preferencesRepository
    val scope = rememberCoroutineScope()

    val savedFolders by prefsRepo.savedExportFolders.collectAsStateWithLifecycle(initialValue = emptyList())
    val exportProgress by ExportStateHolder.state.collectAsStateWithLifecycle()
    val ttsState by TtsStateHolder.state.collectAsStateWithLifecycle()

    val chapters = remember { mutableStateListOf<SelectableExportChapter>() }
    var isLoading by remember { mutableStateOf(true) }
    var rangeSelectMode by remember { mutableStateOf(false) }
    var rangeAnchorPos by remember { mutableIntStateOf(-1) }
    var selectionVersion by remember { mutableIntStateOf(0) }
    var editingChapterPos by remember { mutableIntStateOf(-1) }
    var editChapterDraft by remember { mutableStateOf("") }
    var showOutputSheet by remember { mutableStateOf(false) }

    // Advanced M4B Audiobook & Folder state
    var selectedFolderUri by remember { mutableStateOf("") }
    var selectedFolderName by remember { mutableStateOf("") }
    var combineM4b by remember { mutableStateOf(false) }
    var keepMp3s by remember { mutableStateOf(true) }
    var syncWithEpub by remember { mutableStateOf(fileKey.isNotBlank()) }
    var m4bTitle by remember { mutableStateOf(sourceTitle) }
    var m4bAuthor by remember { mutableStateOf(sourceAuthor) }
    var m4bAlbum by remember { mutableStateOf(sourceTitle) }
    var m4bNarrator by remember { mutableStateOf("LexiVoice TTS") }
    var m4bYear by remember { mutableStateOf("2026") }
    var m4bGenre by remember { mutableStateOf("Audiobook") }
    var m4bIncludeCover by remember { mutableStateOf(true) }
    var customCoverUri by remember { mutableStateOf<String?>(null) }

    val pickCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            customCoverUri = uri.toString()
            m4bIncludeCover = true
        }
    }

    LaunchedEffect(savedFolders) {
        if (selectedFolderUri.isBlank() && savedFolders.isNotEmpty()) {
            selectedFolderUri = savedFolders.first().uri
            selectedFolderName = savedFolders.first().displayName
        }
    }

    LaunchedEffect(fileKey, scratchpadText) {
        isLoading = true
        val loaded = withContext(Dispatchers.IO) {
            if (scratchpadText != null) {
                val words = scratchpadText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
                listOf(
                    SelectableExportChapter(
                        index = 0,
                        originalTitle = sourceTitle,
                        exportTitle = sourceTitle,
                        href = "",
                        wordCount = words,
                        estimatedMinutes = (words / 155).coerceAtLeast(1),
                        plainTextOverride = scratchpadText,
                        isSelected = true
                    )
                )
            } else {
                val struct = EpubParser.parseBookStructure(context, fileKey, isDirectPath, sourceTitle)
                struct.chapters.map { ch ->
                    SelectableExportChapter(
                        index = ch.index,
                        originalTitle = ch.title,
                        exportTitle = ch.title,
                        href = ch.href,
                        wordCount = ch.wordCount,
                        estimatedMinutes = ch.estimatedMinutes,
                        isSelected = true
                    )
                }
            }
        }
        chapters.clear()
        chapters.addAll(loaded)
        selectionVersion++
        isLoading = false
    }

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name
                ?: uri.lastPathSegment
                ?: "Export Folder"
            selectedFolderUri = uri.toString()
            selectedFolderName = displayName
            scope.launch {
                prefsRepo.addSavedExportFolder(uri.toString(), displayName)
            }
        }
    }

    val selectedCount = remember(selectionVersion, chapters.size) {
        chapters.count { it.isSelected }
    }
    val selectedMinutes = remember(selectionVersion, chapters.size) {
        chapters.filter { it.isSelected }.sumOf { it.estimatedMinutes }
    }

    val adapter = remember {
        ExportChapterAdapter(
            onTapChapter = { pos ->
                if (pos !in chapters.indices) return@ExportChapterAdapter
                if (rangeSelectMode) {
                    if (rangeAnchorPos !in chapters.indices) {
                        rangeAnchorPos = pos
                        chapters[pos] = chapters[pos].copy(isSelected = true)
                    } else {
                        val minIdx = minOf(rangeAnchorPos, pos)
                        val maxIdx = maxOf(rangeAnchorPos, pos)
                        for (i in minIdx..maxIdx) {
                            chapters[i] = chapters[i].copy(isSelected = true)
                        }
                        rangeAnchorPos = -1
                    }
                } else {
                    chapters[pos] = chapters[pos].copy(isSelected = !chapters[pos].isSelected)
                }
                selectionVersion++
            },
            onEditChapterName = { pos ->
                if (pos in chapters.indices) {
                    editingChapterPos = pos
                    editChapterDraft = chapters[pos].exportTitle
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("export_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Export Audio Chapters",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = sourceTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (exportProgress.isRunning) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = exportProgress.currentChapterTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Chapter ${exportProgress.currentChapterNumber} of ${exportProgress.totalChapters} (${exportProgress.currentChapterProgressPercent}%)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { AudioExportService.cancelExport(context) },
                                modifier = Modifier.testTag("export_cancel_button")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel Export")
                                Spacer(Modifier.width(4.dp))
                                Text("Cancel")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { exportProgress.currentChapterProgressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { exportProgress.batchProgressPercent / 100f },
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        if (exportProgress.isCompleted) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = buildString {
                                            append("Saved ${exportProgress.exportedFilesCount} file(s) to ${exportProgress.outputFolderName}")
                                            exportProgress.m4aCreatedName?.let { append(" • $it") }
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        } else if (exportProgress.errorMessage != null) {
                            Text(
                                text = exportProgress.errorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "$selectedCount of ${chapters.size} selected",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Est. ~$selectedMinutes min audio",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { showOutputSheet = true },
                                enabled = selectedCount > 0 && !isLoading,
                                modifier = Modifier.testTag("export_choose_output_button")
                            ) {
                                Icon(Icons.Default.AudioFile, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Output & Export")
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Selection Toolbar Controls: Select All, Deselect All, Shift / Range Select
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                for (i in chapters.indices) chapters[i] = chapters[i].copy(isSelected = true)
                                selectionVersion++
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("select_all_chapters_button")
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Select All", style = MaterialTheme.typography.labelLarge)
                        }

                        OutlinedButton(
                            onClick = {
                                for (i in chapters.indices) chapters[i] = chapters[i].copy(isSelected = false)
                                rangeAnchorPos = -1
                                selectionVersion++
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("deselect_all_chapters_button")
                        ) {
                            Icon(Icons.Default.Deselect, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Deselect All", style = MaterialTheme.typography.labelLarge)
                        }

                        FilterChip(
                            selected = rangeSelectMode,
                            onClick = {
                                rangeSelectMode = !rangeSelectMode
                                rangeAnchorPos = -1
                            },
                            label = { Text("Range") },
                            leadingIcon = {
                                Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.testTag("range_select_toggle_button")
                        )
                    }

                    Text(
                        text = if (rangeSelectMode) {
                            if (rangeAnchorPos >= 0) {
                                "Anchor set at Chapter ${rangeAnchorPos + 1} — tap ending chapter to select range"
                            } else {
                                "Shift/Range Select ON: tap start chapter, scroll, then tap end chapter"
                            }
                        } else {
                            "Tip: Long-press any chapter and drag up/down to multi-select continuously"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rangeSelectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
            }

            HorizontalDivider()

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Parsing EPUB chapters...", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                // Genuine RecyclerView with DragSelectTouchListener (OnItemTouchListener)
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("export_chapters_recyclerview"),
                    factory = { ctx ->
                        RecyclerView(ctx).apply {
                            layoutManager = LinearLayoutManager(ctx)
                            this.adapter = adapter
                            itemAnimator = null
                            addOnItemTouchListener(
                                DragSelectTouchListener(
                                    context = ctx,
                                    onRangeSelectionChanged = { startPos, endPos, targetState ->
                                        val minIdx = minOf(startPos, endPos).coerceIn(0, chapters.lastIndex)
                                        val maxIdx = maxOf(startPos, endPos).coerceIn(0, chapters.lastIndex)
                                        for (i in minIdx..maxIdx) {
                                            if (chapters[i].isSelected != targetState) {
                                                chapters[i] = chapters[i].copy(isSelected = targetState)
                                            }
                                        }
                                        selectionVersion++
                                    },
                                    getCurrentSelectedState = { pos ->
                                        chapters.getOrNull(pos)?.isSelected ?: false
                                    }
                                )
                            )
                        }
                    },
                    update = { _ ->
                        adapter.submitChapters(chapters.toList())
                    }
                )
            }
        }
    }

    if (editingChapterPos in chapters.indices) {
        AlertDialog(
            onDismissRequest = { editingChapterPos = -1 },
            title = { Text("Edit Chapter Export Title") },
            text = {
                OutlinedTextField(
                    value = editChapterDraft,
                    onValueChange = { editChapterDraft = it },
                    label = { Text("Chapter Title (MP3 & M4B TOC)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val trimmed = editChapterDraft.trim()
                    if (trimmed.isNotEmpty() && editingChapterPos in chapters.indices) {
                        chapters[editingChapterPos] = chapters[editingChapterPos].copy(exportTitle = trimmed)
                        selectionVersion++
                    }
                    editingChapterPos = -1
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingChapterPos = -1 }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showOutputSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOutputSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = "Output Folder & Audiobook Settings",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(12.dp))

                // 1-Tap Previously Saved Folders
                if (savedFolders.isNotEmpty()) {
                    Text(
                        text = "Previously Saved Folders",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    savedFolders.forEach { folder: SavedExportFolder ->
                        val isChosen = selectedFolderUri == folder.uri
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isChosen) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                }
                            ),
                            border = if (isChosen) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable {
                                    selectedFolderUri = folder.uri
                                    selectedFolderName = folder.displayName
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = folder.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isChosen) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedButton(
                    onClick = { openFolderLauncher.launch(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pick_new_export_folder_button")
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (selectedFolderName.isBlank()) "Choose Output Folder (SAF)"
                        else "Change Folder ($selectedFolderName)"
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))

                // Advanced M4B Audiobook Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Export as Chaptered M4B Audiobook (.m4b)",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Builds AAC .m4b with exact chapter timestamps ($selectedCount ch) & synced text highlight",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = combineM4b,
                        onCheckedChange = { combineM4b = it },
                        modifier = Modifier.testTag("toggle_combine_m4b")
                    )
                }

                if (combineM4b) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Keep individual MP3 chapter files",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(checked = keepMp3s, onCheckedChange = { keepMp3s = it })
                    }

                    if (fileKey.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sync M4B with EPUB read-along highlight",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Saves exact sentence timing JSON for the M4B Player",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = syncWithEpub, onCheckedChange = { syncWithEpub = it })
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "M4B Audiobook Metadata & Cover Art",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(8.dp))

                    // Cover art preview + PickVisualMedia selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val coverModel: Any? = when {
                            !m4bIncludeCover -> null
                            customCoverUri != null -> Uri.parse(customCoverUri)
                            coverPath != null -> File(coverPath).takeIf { it.exists() }
                            else -> null
                        }
                        if (coverModel != null) {
                            AsyncImage(
                                model = coverModel,
                                contentDescription = "Audiobook Cover Preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 56.dp, height = 78.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Embed Cover Art", style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = m4bIncludeCover, onCheckedChange = { m4bIncludeCover = it })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        pickCoverLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier.testTag("pick_m4b_cover_button")
                                ) {
                                    Text(if (customCoverUri != null) "Change Cover" else "Choose Image")
                                }
                                if (customCoverUri != null) {
                                    TextButton(onClick = { customCoverUri = null }) {
                                        Text("Reset")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = m4bTitle,
                        onValueChange = { m4bTitle = it },
                        label = { Text("Audiobook Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = m4bAuthor,
                            onValueChange = { m4bAuthor = it },
                            label = { Text("Author") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = m4bNarrator,
                            onValueChange = { m4bNarrator = it },
                            label = { Text("Narrator") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = m4bAlbum,
                        onValueChange = { m4bAlbum = it },
                        label = { Text("Album / Series") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = m4bYear,
                            onValueChange = { m4bYear = it },
                            label = { Text("Year") },
                            singleLine = true,
                            modifier = Modifier.weight(0.4f)
                        )
                        OutlinedTextField(
                            value = m4bGenre,
                            onValueChange = { m4bGenre = it },
                            label = { Text("Genre") },
                            singleLine = true,
                            modifier = Modifier.weight(0.6f)
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        val chosen = chapters.filter { it.isSelected }
                        if (chosen.isEmpty() || selectedFolderUri.isBlank()) return@Button
                        val config = ExportBatchConfig(
                            sourceTitle = sourceTitle,
                            bookFileKey = fileKey,
                            isDirectPath = isDirectPath,
                            coverPath = coverPath,
                            outputTreeUri = selectedFolderUri,
                            outputFolderName = selectedFolderName.ifBlank { "Export Folder" },
                            chapters = chosen.map {
                                ExportChapterRequest(
                                    index = it.index,
                                    originalTitle = it.originalTitle,
                                    exportTitle = it.exportTitle,
                                    href = it.href,
                                    wordCount = it.wordCount,
                                    estimatedMinutes = it.estimatedMinutes,
                                    plainTextOverride = it.plainTextOverride
                                )
                            },
                            m4aMetadata = M4aMetadataConfig(
                                enabled = combineM4b,
                                keepMp3Files = keepMp3s,
                                syncWithEpub = syncWithEpub,
                                title = m4bTitle,
                                author = m4bAuthor,
                                album = m4bAlbum,
                                narrator = m4bNarrator,
                                year = m4bYear,
                                genre = m4bGenre,
                                includeCover = m4bIncludeCover,
                                coverOverrideUri = customCoverUri
                            ),
                            enginePackage = ttsState.selectedEnginePackage,
                            voiceName = ttsState.selectedVoiceName,
                            localeTag = ttsState.selectedLocaleTag,
                            speechRate = ttsState.speechRate,
                            pitch = ttsState.pitch
                        )
                        AudioExportService.startExport(context, config)
                        showOutputSheet = false
                    },
                    enabled = selectedFolderUri.isNotBlank() && selectedCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("start_batch_export_button")
                ) {
                    Text(
                        text = if (combineM4b) {
                            if (keepMp3s) "Start Export ($selectedCount Ch → MP3 + M4B)"
                            else "Build M4B Audiobook ($selectedCount Ch)"
                        } else {
                            "Start Batch MP3 Export ($selectedCount Ch)"
                        }
                    )
                }
            }
        }
    }
}
