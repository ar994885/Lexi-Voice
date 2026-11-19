package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.LibraryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersAndPermissionsScreen(
    viewModel: LibraryViewModel,
    onOpenDrawer: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()

    var resumeTick by remember { mutableIntStateOf(0) }
    var showDirectPathDialog by remember { mutableStateOf(false) }
    var showNeedPermissionDialog by remember { mutableStateOf(false) }
    var customDirectPath by remember {
        mutableStateOf(Environment.getExternalStorageDirectory().absolutePath + "/Books")
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A raw filesystem path is only usable on API <= 32 with the legacy read
    // permission; on newer releases SAF is the supported route, so the direct-path
    // option is offered only when it can actually work.
    val canUseDirectPath = remember(resumeTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
    }

    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.addSafFolder(uri)
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
                title = { Text("Manage Folders") },
                actions = {
                    IconButton(onClick = { viewModel.syncNow() }) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync All Folders")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Add Library Folder",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { safFolderLauncher.launch(null) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("folders_add_saf_button")
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Pick Folder (SAF)")
                    }
                    OutlinedButton(
                        onClick = {
                            if (canUseDirectPath) {
                                showDirectPathDialog = true
                            } else {
                                showNeedPermissionDialog = true
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("folders_add_direct_button")
                    ) {
                        Icon(Icons.Default.FolderSpecial, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Direct Path")
                    }
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Watched Folders (${folders.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (folders.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No folders assigned yet. Add a folder to automatically scan and index EPUB books and M4B audiobooks.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(folders, key = { it.uriOrPath }) { folder ->
                    val syncTime = remember(folder.lastSyncedAt) {
                        if (folder.lastSyncedAt <= 0L) "Pending sync"
                        else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                            .format(Date(folder.lastSyncedAt))
                    }
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (folder.isDirectPath) Icons.Default.FolderSpecial else Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = folder.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${folder.bookCount} EPUB(s) • ${if (folder.isDirectPath) "Direct Path" else "SAF"} • $syncTime",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.removeFolder(folder.uriOrPath) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove Folder",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            if (scanProgress.isScanning) {
                item {
                    Text(
                        text = "Sync in progress: ${scanProgress.scannedFiles} scanned...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    if (showDirectPathDialog) {
        val quickDirs = remember { viewModel.commonStorageDirectories() }
        AlertDialog(
            onDismissRequest = { showDirectPathDialog = false },
            title = { Text("Add Direct Storage Path") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customDirectPath,
                        onValueChange = { customDirectPath = it },
                        label = { Text("Directory Path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Quick Shortcuts:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    quickDirs.forEach { dir ->
                        OutlinedButton(
                            onClick = { customDirectPath = dir.absolutePath },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(dir.absolutePath, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addDirectFolderPath(customDirectPath)
                    showDirectPathDialog = false
                }) {
                    Text("Scan Folder")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDirectPathDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showNeedPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showNeedPermissionDialog = false },
            title = { Text("All-Files Access Required") },
            text = {
                Text("Enable All-Files Direct Scanning in Settings to add direct filesystem paths, or use Pick Folder (SAF) without extra permissions.")
            },
            confirmButton = {
                Button(onClick = {
                    showNeedPermissionDialog = false
                    onNavigateToSettings()
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNeedPermissionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
