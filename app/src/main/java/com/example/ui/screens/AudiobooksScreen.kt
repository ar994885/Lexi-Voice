package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.LexiVoiceApplication
import com.example.audiobook.M4bPlayerStateHolder
import com.example.data.db.AudiobookEntity
import com.example.export.M4bCompileService
import com.example.export.M4bCompileStateHolder
import com.example.ui.AudiobooksViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobooksScreen(
    viewModel: AudiobooksViewModel,
    onOpenDrawer: () -> Unit,
    onOpenPlayer: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as LexiVoiceApplication
    val audiobooks by viewModel.audiobooks.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val activePlayback by M4bPlayerStateHolder.state.collectAsStateWithLifecycle()
    val compileState by M4bCompileStateHolder.state.collectAsStateWithLifecycle()
    val savedFolders by app.preferencesRepository.savedExportFolders.collectAsStateWithLifecycle(emptyList())

    var compileDialogSeed by remember { mutableStateOf<CompileDialogSeed?>(null) }

    val openM4bLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importM4bUri(uri) { id ->
                onOpenPlayer(id)
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
                        Text("Audiobooks", style = MaterialTheme.typography.titleLarge)
                        val m4bCount = audiobooks.count { !it.isFolderAudiobook }
                        val folderCount = audiobooks.count { it.isFolderAudiobook }
                        Text(
                            text = "${audiobooks.size} total • $m4bCount M4B, $folderCount MP3 folders",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.scanFoldersForM4b() },
                        modifier = Modifier.testTag("scan_m4b_button")
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "Scan Folders for M4B and MP3 Books")
                    }
                    IconButton(
                        onClick = { openM4bLauncher.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) },
                        modifier = Modifier.testTag("import_m4b_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Import M4B Audiobook")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { openM4bLauncher.launch(arrayOf("audio/*", "*/*")) },
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("import_m4b_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Import M4B File")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (compileState.isRunning || compileState.isCompleted || compileState.errorMessage != null) {
                Surface(
                    color = when {
                        compileState.errorMessage != null -> MaterialTheme.colorScheme.errorContainer
                        compileState.isCompleted -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (compileState.isCompleted) Icons.Default.CheckCircle else Icons.Default.Build,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = compileState.errorMessage ?: compileState.statusText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (compileState.isCompleted && compileState.createdAudiobookId > 0L) {
                                TextButton(
                                    onClick = {
                                        val id = compileState.createdAudiobookId
                                        M4bCompileStateHolder.reset()
                                        onOpenPlayer(id)
                                    }
                                ) {
                                    Text("Open M4B")
                                }
                            } else if (!compileState.isRunning) {
                                TextButton(onClick = { M4bCompileStateHolder.reset() }) {
                                    Text("Dismiss")
                                }
                            }
                        }
                        if (compileState.isRunning) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (compileState.progressPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (activePlayback.isActive && activePlayback.audiobookId > 0L) {
                Surface(
                    onClick = { onOpenPlayer(activePlayback.audiobookId) },
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("now_playing_m4b_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buildString {
                                    append(if (activePlayback.isPlaying) "Now Playing" else "Paused")
                                    append(if (activePlayback.isFolderAudiobook) " • MP3 Playlist" else " • M4B")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = activePlayback.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Open Player",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            if (audiobooks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "No Audiobooks Yet",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Export any EPUB as a multi-chapter MP3 folder or chaptered .m4b audiobook with synced read-along highlighting, or import an existing .m4b file.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { openM4bLauncher.launch(arrayOf("audio/*", "*/*")) },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Import .M4B")
                            }
                            OutlinedButton(
                                onClick = { viewModel.scanFoldersForM4b() },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Scan Folders")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(audiobooks, key = { it.id }) { book ->
                        AudiobookCard(
                            audiobook = book,
                            onClick = { onOpenPlayer(book.id) },
                            onCompileToM4b = {
                                compileDialogSeed = seedFromAudiobookEntity(book)
                            },
                            onDelete = { viewModel.deleteAudiobook(book.id) }
                        )
                    }
                }
            }
        }
    }

    compileDialogSeed?.let { seed ->
        CompileMp3sToM4bDialog(
            seed = seed,
            savedExportFolders = savedFolders.map { it.uri to it.displayName },
            onDismiss = { compileDialogSeed = null },
            onConfirmCompile = { request ->
                compileDialogSeed = null
                M4bCompileService.startCompile(context, request)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudiobookCard(
    audiobook: AudiobookEntity,
    onClick: () -> Unit,
    onCompileToM4b: () -> Unit,
    onDelete: () -> Unit
) {
    val hasSync = !audiobook.syncJsonPath.isNullOrBlank() && !audiobook.sourceEpubKey.isNullOrBlank()
    val progress = if (audiobook.durationMs > 0L) {
        (audiobook.lastPositionMs.toFloat() / audiobook.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("audiobook_card_${audiobook.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCoverImage(
                coverPath = audiobook.coverPath,
                title = audiobook.title,
                modifier = Modifier
                    .width(66.dp)
                    .height(92.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = audiobook.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${audiobook.author} • ${audiobook.chapterCount} ch • ${formatDuration(audiobook.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        color = if (audiobook.isFolderAudiobook) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (audiobook.isFolderAudiobook) Icons.Default.Folder else Icons.Default.Headphones,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (audiobook.isFolderAudiobook) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (audiobook.isFolderAudiobook) "MP3 Folder" else "M4B",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (audiobook.isFolderAudiobook) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        }
                    }

                    if (hasSync) {
                        AssistChip(
                            onClick = onClick,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.AutoStories,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            label = { Text("Synced EPUB", style = MaterialTheme.typography.labelSmall) }
                        )
                    }

                    if (audiobook.isFolderAudiobook) {
                        AssistChip(
                            onClick = onCompileToM4b,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Build,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            label = { Text("Compile to M4B", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("compile_mp3_folder_m4b_${audiobook.id}")
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Remove from Audiobooks",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
