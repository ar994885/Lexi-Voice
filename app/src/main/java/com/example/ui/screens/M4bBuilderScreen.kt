package com.example.ui.screens

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.LexiVoiceApplication
import com.example.audiobook.SyncAlignmentSerializer
import com.example.data.db.AudiobookEntity
import com.example.export.M4aMetadataConfig
import com.example.export.M4bCompileChapterItem
import com.example.export.M4bCompileRequest
import com.example.export.M4bCompileService
import com.example.export.M4bCompileStateHolder
import com.example.ui.AudiobooksViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M4bBuilderScreen(
    viewModel: AudiobooksViewModel,
    onOpenDrawer: () -> Unit,
    onOpenPlayer: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as LexiVoiceApplication
    val scope = rememberCoroutineScope()

    val audiobooks by viewModel.audiobooks.collectAsStateWithLifecycle()
    val compileState by M4bCompileStateHolder.state.collectAsStateWithLifecycle()
    val savedFolders by app.preferencesRepository.savedExportFolders.collectAsStateWithLifecycle(emptyList())

    val mp3FolderBooks = remember(audiobooks) {
        audiobooks.filter { it.isFolderAudiobook }
    }

    var activeCompileTarget by remember { mutableStateOf<CompileDialogSeed?>(null) }

    val pickMp3FilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val items = withContext(Dispatchers.IO) {
                    uris.mapIndexed { idx, uri ->
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        val name = doc?.name ?: "Chapter_${idx + 1}.mp3"
                        val retriever = MediaMetadataRetriever()
                        var title = name.removeSuffix(".mp3").removeSuffix(".MP3")
                        var dur = 0L
                        runCatching {
                            retriever.setDataSource(context, uri)
                            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                ?.takeIf { it.isNotBlank() } ?: title
                            dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                        }
                        runCatching { retriever.release() }
                        M4bCompileChapterItem(
                            chapterIndex = idx,
                            title = title,
                            fileName = name,
                            uriString = uri.toString(),
                            isDirectPath = false,
                            durationMs = dur
                        )
                    }.sortedBy { it.fileName.lowercase() }
                }
                val defaultTree = savedFolders.firstOrNull()?.uri.orEmpty()
                activeCompileTarget = CompileDialogSeed(
                    sourceAudiobook = null,
                    initialTitle = items.firstOrNull()?.title?.substringBefore(" - ")?.ifBlank { "Custom Audiobook" }
                        ?: "Custom Audiobook",
                    initialAuthor = "Unknown Author",
                    initialNarrator = "Audiobook",
                    initialYear = "2026",
                    coverPath = null,
                    targetFolderUri = defaultTree,
                    isDirectFolder = false,
                    needsFolderPick = defaultTree.isBlank(),
                    chapters = items
                )
            }
        }
    }

    val pickMp3FolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            scope.launch {
                val seed = withContext(Dispatchers.IO) {
                    buildSeedFromSafTree(context, treeUri)
                }
                if (seed != null && seed.chapters.isNotEmpty()) {
                    activeCompileTarget = seed
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
                        onClick = onOpenDrawer,
                        modifier = Modifier.testTag("drawer_menu_button")
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Open Navigation Menu")
                    }
                },
                title = {
                    Column {
                        Text("M4B Builder", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Merge existing MP3 chapters into a chaptered .m4b",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 10.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding()
        ) {
            if (compileState.isRunning || compileState.isCompleted || compileState.errorMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (compileState.errorMessage != null) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("m4b_compile_status_card")
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = when {
                                    compileState.isRunning -> "Building M4B Audiobook..."
                                    compileState.isCompleted -> "M4B Ready: ${compileState.createdFileName}"
                                    else -> "Build Error"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = compileState.errorMessage ?: compileState.statusText,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (compileState.isRunning) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { compileState.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (compileState.isCompleted && compileState.createdAudiobookId > 0L) {
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val id = compileState.createdAudiobookId
                                            M4bCompileStateHolder.reset()
                                            onOpenPlayer(id)
                                        }
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Listen Now")
                                    }
                                    TextButton(onClick = { M4bCompileStateHolder.reset() }) {
                                        Text("Dismiss")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Create from Custom MP3 Files", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Select an exported book folder or multiple chapter MP3 files to merge into a chaptered .m4b audiobook with cover art and TOC markers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { pickMp3FolderLauncher.launch(null) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("builder_pick_mp3_folder_button")
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pick Folder")
                            }
                            OutlinedButton(
                                onClick = { pickMp3FilesLauncher.launch(arrayOf("audio/mpeg", "audio/*")) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("builder_pick_mp3_files_button")
                            ) {
                                Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pick MP3s")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Exported MP3 Book Folders (${mp3FolderBooks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (mp3FolderBooks.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.LibraryMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "No multi-chapter MP3 folders indexed yet. Export chapters from any EPUB or pick an MP3 folder above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(mp3FolderBooks, key = { it.id }) { book ->
                    Card(
                        onClick = { activeCompileTarget = seedFromAudiobookEntity(book) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("builder_folder_card_${book.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BookCoverImage(
                                coverPath = book.coverPath,
                                title = book.title,
                                modifier = Modifier
                                    .size(width = 52.dp, height = 72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = book.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${book.author} • ${book.chapterCount} MP3 chapters • ${formatDuration(book.durationMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { activeCompileTarget = seedFromAudiobookEntity(book) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Compile")
                            }
                        }
                    }
                }
            }
        }
    }

    activeCompileTarget?.let { seed ->
        CompileMp3sToM4bDialog(
            seed = seed,
            savedExportFolders = savedFolders.map { it.uri to it.displayName },
            onDismiss = { activeCompileTarget = null },
            onConfirmCompile = { req ->
                activeCompileTarget = null
                M4bCompileService.startCompile(context, req)
            }
        )
    }
}

data class CompileDialogSeed(
    val sourceAudiobook: AudiobookEntity?,
    val initialTitle: String,
    val initialAuthor: String,
    val initialNarrator: String,
    val initialYear: String,
    val coverPath: String?,
    val targetFolderUri: String,
    val isDirectFolder: Boolean,
    val needsFolderPick: Boolean,
    val chapters: List<M4bCompileChapterItem>
)

fun seedFromAudiobookEntity(entity: AudiobookEntity): CompileDialogSeed {
    val files = SyncAlignmentSerializer.decodeChapterFiles(entity.chapterFilesJson)
    val items = files.map { f ->
        M4bCompileChapterItem(
            chapterIndex = f.chapterIndex,
            title = f.title,
            fileName = f.fileName,
            uriString = f.fileUri,
            isDirectPath = f.isDirectPath,
            durationMs = f.durationMs,
            isSelected = true
        )
    }
    return CompileDialogSeed(
        sourceAudiobook = entity,
        initialTitle = entity.title,
        initialAuthor = entity.author,
        initialNarrator = entity.narrator,
        initialYear = entity.year.ifBlank { "2026" },
        coverPath = entity.coverPath,
        targetFolderUri = entity.folderUri.orEmpty(),
        isDirectFolder = entity.isDirectPath,
        needsFolderPick = entity.folderUri.isNullOrBlank(),
        chapters = items
    )
}

private fun buildSeedFromSafTree(context: android.content.Context, treeUri: Uri): CompileDialogSeed? {
    val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    val docFiles = rootDoc.listFiles()
    val mp3Docs = docFiles
        .filter { it.isFile && (it.name?.endsWith(".mp3", ignoreCase = true) == true) }
        .sortedBy { it.name.orEmpty().lowercase() }

    if (mp3Docs.isEmpty()) return null

    val syncDoc = docFiles.firstOrNull { it.isFile && it.name.equals("sync_map.json", ignoreCase = true) }
    val syncMap = syncDoc?.let { doc ->
        runCatching {
            context.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.use {
                SyncAlignmentSerializer.decodeBookSyncMap(it.readText())
            }
        }.getOrNull()
    }
    val syncByName = syncMap?.chapters.orEmpty().associateBy { it.fileName }

    val chapters = mp3Docs.mapIndexed { idx, doc ->
        val name = doc.name ?: "Chapter_${idx + 1}.mp3"
        val prev = syncByName[name]
        val retriever = MediaMetadataRetriever()
        var title = prev?.title ?: name.removeSuffix(".mp3").removeSuffix(".MP3")
        var dur = prev?.durationMs ?: 0L
        if (dur <= 0L) {
            runCatching {
                retriever.setDataSource(context, doc.uri)
                if (prev == null) {
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() } ?: title
                }
                dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
            runCatching { retriever.release() }
        }
        M4bCompileChapterItem(
            chapterIndex = prev?.chapterIndex ?: idx,
            title = title,
            fileName = name,
            uriString = doc.uri.toString(),
            isDirectPath = false,
            durationMs = dur,
            isSelected = true
        )
    }

    return CompileDialogSeed(
        sourceAudiobook = null,
        initialTitle = syncMap?.bookTitle ?: (rootDoc.name ?: "Audiobook"),
        initialAuthor = syncMap?.author ?: "Unknown Author",
        initialNarrator = syncMap?.narrator ?: "Audiobook",
        initialYear = syncMap?.year ?: "2026",
        coverPath = syncMap?.coverPath,
        targetFolderUri = rootDoc.uri.toString(),
        isDirectFolder = false,
        needsFolderPick = false,
        chapters = chapters
    )
}

@Composable
fun CompileMp3sToM4bDialog(
    seed: CompileDialogSeed,
    savedExportFolders: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirmCompile: (M4bCompileRequest) -> Unit
) {
    val context = LocalContext.current
    val chapters = remember(seed) { mutableStateListOf(*seed.chapters.toTypedArray()) }
    var bookTitle by remember(seed) { mutableStateOf(seed.initialTitle) }
    var author by remember(seed) { mutableStateOf(seed.initialAuthor) }
    var narrator by remember(seed) { mutableStateOf(seed.initialNarrator) }
    var year by remember(seed) { mutableStateOf(seed.initialYear) }
    var includeCover by remember { mutableStateOf(true) }
    var customCoverUri by remember { mutableStateOf<String?>(null) }
    var deleteSourceMp3s by remember { mutableStateOf(false) }
    var destFolderUri by remember(seed) { mutableStateOf(seed.targetFolderUri) }
    var destIsDirect by remember(seed) { mutableStateOf(seed.isDirectFolder) }
    var destDisplayLabel by remember(seed) {
        mutableStateOf(
            if (seed.targetFolderUri.isNotBlank()) {
                savedExportFolders.firstOrNull { it.first == seed.targetFolderUri }?.second
                    ?: "Same Book Folder"
            } else {
                ""
            }
        )
    }

    val pickCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            customCoverUri = uri.toString()
            includeCover = true
        }
    }

    val pickOutputFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            destFolderUri = uri.toString()
            destIsDirect = false
            destDisplayLabel = DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected Folder"
        }
    }

    val selectedCount = chapters.count { it.isSelected }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compile MP3s to M4B") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val coverModel: Any? = when {
                        !includeCover -> null
                        customCoverUri != null -> Uri.parse(customCoverUri)
                        seed.coverPath != null -> File(seed.coverPath).takeIf { it.exists() }
                        else -> null
                    }
                    if (coverModel != null) {
                        AsyncImage(
                            model = coverModel,
                            contentDescription = "Cover Preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 48.dp, height = 66.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Include Cover", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = includeCover, onCheckedChange = { includeCover = it })
                        }
                        OutlinedButton(
                            onClick = {
                                pickCoverLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Choose Cover Image", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                OutlinedTextField(
                    value = bookTitle,
                    onValueChange = { bookTitle = it },
                    label = { Text("Book Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text("Author") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it },
                        label = { Text("Year") },
                        singleLine = true,
                        modifier = Modifier.weight(0.55f)
                    )
                }
                OutlinedTextField(
                    value = narrator,
                    onValueChange = { narrator = it },
                    label = { Text("Narrator") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (seed.needsFolderPick || destFolderUri.isBlank()) {
                    OutlinedButton(
                        onClick = { pickOutputFolderLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (destDisplayLabel.isBlank()) "Select Output Folder"
                            else "Output: $destDisplayLabel"
                        )
                    }
                }

                HorizontalDivider()
                Text(
                    text = "Chapters ($selectedCount of ${chapters.size} selected)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    chapters.forEachIndexed { idx, ch ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chapters[idx] = ch.copy(isSelected = !ch.isSelected)
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = ch.isSelected,
                                onCheckedChange = { checked -> chapters[idx] = ch.copy(isSelected = checked) }
                            )
                            Text(
                                text = ch.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    if (idx > 0) {
                                        val moved = chapters.removeAt(idx)
                                        chapters.add(idx - 1, moved)
                                    }
                                },
                                enabled = idx > 0,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    if (idx < chapters.lastIndex) {
                                        val moved = chapters.removeAt(idx)
                                        chapters.add(idx + 1, moved)
                                    }
                                },
                                enabled = idx < chapters.lastIndex,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteSourceMp3s = !deleteSourceMp3s }
                        .testTag("delete_source_mp3_checkbox_row")
                ) {
                    Checkbox(
                        checked = deleteSourceMp3s,
                        onCheckedChange = { deleteSourceMp3s = it }
                    )
                    Column {
                        Text(
                            text = "Delete source MP3 files after M4B creation",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Frees storage once .m4b is verified",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = chapters.filter { it.isSelected }
                    if (chosen.isEmpty() || destFolderUri.isBlank()) return@Button
                    onConfirmCompile(
                        M4bCompileRequest(
                            sourceAudiobook = seed.sourceAudiobook,
                            targetFolderUri = destFolderUri,
                            isDirectFolder = destIsDirect,
                            chapters = chosen,
                            metadata = M4aMetadataConfig(
                                enabled = true,
                                keepMp3Files = !deleteSourceMp3s,
                                syncWithEpub = true,
                                title = bookTitle.trim().ifBlank { "Audiobook" },
                                author = author.trim().ifBlank { "Unknown Author" },
                                album = bookTitle.trim(),
                                narrator = narrator.trim().ifBlank { "LexiVoice" },
                                year = year.trim(),
                                genre = "Audiobook",
                                includeCover = includeCover,
                                coverOverrideUri = customCoverUri
                            ),
                            coverPath = seed.coverPath,
                            deleteSourceMp3s = deleteSourceMp3s
                        )
                    )
                },
                enabled = selectedCount > 0 && destFolderUri.isNotBlank(),
                modifier = Modifier.testTag("confirm_compile_m4b_button")
            ) {
                Text("Build M4B")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
