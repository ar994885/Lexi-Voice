package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.db.BookEntity
import com.example.data.db.LibrarySortOption
import com.example.data.db.ShelfEntity
import com.example.ui.LibraryViewModel
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenDrawer: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onNavigateToFolders: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val books by viewModel.libraryBooks.collectAsStateWithLifecycle()
    val recentBooks by viewModel.recentBooks.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val distinctLanguages by viewModel.distinctLanguages.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalBookCount.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFolder by viewModel.selectedFolderFilter.collectAsStateWithLifecycle()
    val selectedShelfId by viewModel.selectedShelfId.collectAsStateWithLifecycle()
    val progressFilter by viewModel.progressFilter.collectAsStateWithLifecycle()
    val languageFilter by viewModel.languageFilter.collectAsStateWithLifecycle()
    val showHidden by viewModel.showHidden.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val isGridView by viewModel.isGridView.collectAsStateWithLifecycle()
    val gridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val showLibraryFolders by viewModel.showLibraryFolders.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }
    var showViewDensityMenu by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showNewShelfDialog by remember { mutableStateOf(false) }
    var newShelfName by remember { mutableStateOf("") }
    var selectedBookForAction by remember { mutableStateOf<BookEntity?>(null) }
    var bookToDeleteConfirm by remember { mutableStateOf<BookEntity?>(null) }
    var showShelfPickerForBook by remember { mutableStateOf<BookEntity?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasAllFilesAccess = remember(resumeTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.addSafFolder(uri)
    }

    val singleEpubLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importSingleEpub(uri) { bookId ->
                onOpenBook(bookId)
            }
        }
    }

    val isUnfiltered = searchQuery.isBlank() &&
        selectedFolder == null &&
        selectedShelfId == null &&
        progressFilter == null &&
        languageFilter == null &&
        !showHidden

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { folderPickerLauncher.launch(null) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("add_folder_fab")
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "Add Book Folder")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Editorial Header with Drawer, Scan, View Mode + Density, and Sort
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.testTag("drawer_menu_button")
                ) {
                    Icon(Icons.Default.Menu, contentDescription = "Open Navigation Menu")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LexiVoice Library",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "$totalCount book(s) • ${folders.size} folder(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = {
                        viewModel.scanDeviceForEpubs()
                        viewModel.syncNow()
                    },
                    modifier = Modifier.testTag("sync_library_button")
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = if (hasAllFilesAccess) "Scan Device for EPUBs" else "Scan & Sync Books"
                    )
                }

                // View Mode & Grid Density Selector (List / Grid 2-5 Columns)
                Box {
                    IconButton(
                        onClick = { showViewDensityMenu = true },
                        modifier = Modifier.testTag("toggle_grid_list_button")
                    ) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = "View Mode & Grid Density"
                        )
                    }
                    DropdownMenu(
                        expanded = showViewDensityMenu,
                        onDismissRequest = { showViewDensityMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "List View",
                                    fontWeight = if (!isGridView) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null)
                            },
                            trailingIcon = if (!isGridView) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                if (isGridView) viewModel.toggleGridView()
                                showViewDensityMenu = false
                            },
                            modifier = Modifier.testTag("view_mode_list")
                        )
                        HorizontalDivider()
                        (2..5).forEach { cols ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Grid ($cols Columns)",
                                        fontWeight = if (isGridView && gridColumns == cols) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.GridView, contentDescription = null)
                                },
                                trailingIcon = if (isGridView && gridColumns == cols) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    if (!isGridView) viewModel.toggleGridView()
                                    viewModel.setGridColumns(cols)
                                    showViewDensityMenu = false
                                },
                                modifier = Modifier.testTag("view_mode_grid_$cols")
                            )
                        }
                    }
                }

                Box {
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier.testTag("sort_library_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort Books")
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        LibrarySortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (option == sortOption) "✓ ${option.label}" else option.label,
                                        fontWeight = if (option == sortOption) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    viewModel.setSortOption(option)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // Live Indexing Progress Banner
            AnimatedVisibility(visible = scanProgress.isScanning) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Scanning ${scanProgress.currentFolderName}: ${scanProgress.scannedFiles} file(s) • ${scanProgress.newlyIndexed} indexed",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (scanProgress.currentFileName.isNotBlank()) {
                            Text(
                                text = scanProgress.currentFileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Instant Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search books by title or author...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("library_search_input")
            )

            // Shelves Bar (All Books, Custom Shelves, + New Shelf)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedShelfId == null && selectedFolder == null && !showHidden,
                        onClick = {
                            viewModel.setShelfFilter(null)
                            viewModel.setFolderFilter(null)
                            viewModel.setShowHidden(false)
                        },
                        label = { Text("All Books") },
                        modifier = Modifier.testTag("shelf_all_books")
                    )
                }
                items(shelves, key = { it.id }) { shelf ->
                    val isSelectedShelf = selectedShelfId == shelf.id
                    FilterChip(
                        selected = isSelectedShelf,
                        onClick = {
                            viewModel.setShelfFilter(if (isSelectedShelf) null else shelf.id)
                            viewModel.setShowHidden(false)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Bookmarks, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        trailingIcon = if (isSelectedShelf) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete shelf ${shelf.name}",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { viewModel.deleteShelf(shelf.id) }
                                )
                            }
                        } else null,
                        label = { Text(shelf.name) },
                        modifier = Modifier.testTag("shelf_chip_${shelf.id}")
                    )
                }
                item {
                    AssistChip(
                        onClick = { showNewShelfDialog = true },
                        leadingIcon = {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("New Shelf") },
                        modifier = Modifier.testTag("new_shelf_button")
                    )
                }
                item {
                    AssistChip(
                        onClick = { viewModel.scanDeviceForEpubs() },
                        leadingIcon = {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Scan Device") },
                        modifier = Modifier.testTag("scan_device_epubs_chip")
                    )
                }
            }

            // Progress, Language, Hidden & Sort Chips Row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val progressItems = listOf(
                    null to "All Progress",
                    "NOT_STARTED" to "Not Started",
                    "IN_PROGRESS" to "In Progress",
                    "FINISHED" to "Finished"
                )
                items(progressItems, key = { it.second }) { (code, label) ->
                    FilterChip(
                        selected = progressFilter == code && !showHidden,
                        onClick = {
                            viewModel.setProgressFilter(code)
                            if (showHidden) viewModel.setShowHidden(false)
                        },
                        label = { Text(label) },
                        modifier = Modifier.testTag("filter_progress_${code ?: "all"}")
                    )
                }

                if (distinctLanguages.isNotEmpty()) {
                    item {
                        Box {
                            FilterChip(
                                selected = languageFilter != null,
                                onClick = { showLanguageMenu = true },
                                leadingIcon = {
                                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = {
                                    Text(languageFilter?.uppercase() ?: "Language")
                                },
                                modifier = Modifier.testTag("filter_language_chip")
                            )
                            DropdownMenu(
                                expanded = showLanguageMenu,
                                onDismissRequest = { showLanguageMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Languages") },
                                    onClick = {
                                        viewModel.setLanguageFilter(null)
                                        showLanguageMenu = false
                                    }
                                )
                                distinctLanguages.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang.uppercase()) },
                                        trailingIcon = if (languageFilter == lang) {
                                            { Icon(Icons.Default.Check, contentDescription = null) }
                                        } else null,
                                        onClick = {
                                            viewModel.setLanguageFilter(lang)
                                            showLanguageMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    FilterChip(
                        selected = showHidden,
                        onClick = { viewModel.setShowHidden(!showHidden) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text("Hidden") },
                        modifier = Modifier.testTag("filter_hidden_chip")
                    )
                }
            }

            // Folder Filter Chips (toggleable via showLibraryFolders in Settings)
            if (showLibraryFolders && folders.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedFolder == null,
                            onClick = { viewModel.setFolderFilter(null) },
                            label = { Text("All Folders") }
                        )
                    }
                    items(folders, key = { it.uriOrPath }) { folder ->
                        FilterChip(
                            selected = selectedFolder == folder.uriOrPath,
                            onClick = {
                                viewModel.setFolderFilter(
                                    if (selectedFolder == folder.uriOrPath) null else folder.uriOrPath
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text("${folder.displayName} (${folder.bookCount})") }
                        )
                    }
                }
            }

            if (books.isEmpty() && !scanProgress.isScanning) {
                EmptyLibraryState(
                    hasFilter = !isUnfiltered,
                    onClearFilters = {
                        viewModel.setSearchQuery("")
                        viewModel.setFolderFilter(null)
                        viewModel.setShelfFilter(null)
                        viewModel.setProgressFilter(null)
                        viewModel.setLanguageFilter(null)
                        viewModel.setShowHidden(false)
                    },
                    onAddSafFolder = { folderPickerLauncher.launch(null) },
                    onImportSingleEpub = { singleEpubLauncher.launch(arrayOf("application/epub+zip", "*/*")) },
                    onOpenFoldersTab = onNavigateToFolders
                )
            } else if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns.coerceIn(2, 5)),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 92.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (recentBooks.isNotEmpty() && isUnfiltered) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            RecentBooksSection(
                                recentBooks = recentBooks,
                                onOpenBook = onOpenBook,
                                onLongPressBook = { selectedBookForAction = it }
                            )
                        }
                    }
                    items(books, key = { it.id }) { book ->
                        BookGridCard(
                            book = book,
                            compact = gridColumns >= 4,
                            onClick = { onOpenBook(book.id) },
                            onLongClick = { selectedBookForAction = book }
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (recentBooks.isNotEmpty() && isUnfiltered) {
                        item {
                            RecentBooksSection(
                                recentBooks = recentBooks,
                                onOpenBook = onOpenBook,
                                onLongPressBook = { selectedBookForAction = it }
                            )
                        }
                    }
                    items(books, key = { it.id }) { book ->
                        BookListCard(
                            book = book,
                            onClick = { onOpenBook(book.id) },
                            onLongClick = { selectedBookForAction = book }
                        )
                    }
                }
            }
        }
    }

    // Create Shelf Dialog
    if (showNewShelfDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewShelfDialog = false
                newShelfName = ""
            },
            title = { Text("Create New Shelf") },
            text = {
                OutlinedTextField(
                    value = newShelfName,
                    onValueChange = { newShelfName = it },
                    label = { Text("Shelf Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_shelf_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createShelf(newShelfName)
                        newShelfName = ""
                        showNewShelfDialog = false
                    },
                    enabled = newShelfName.isNotBlank(),
                    modifier = Modifier.testTag("confirm_create_shelf_button")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNewShelfDialog = false
                        newShelfName = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Book Long-Press Action Sheet
    val actionBook = selectedBookForAction
    if (actionBook != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedBookForAction = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        text = actionBook.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(actionBook.author)
                            if (actionBook.language.isNotBlank()) append(" • ${actionBook.language.uppercase()}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                ListItem(
                    headlineContent = { Text("Add to Shelf") },
                    supportingContent = {
                        val currentShelf = shelves.firstOrNull { it.id == actionBook.shelfId }?.name ?: "None"
                        Text("Current shelf: $currentShelf")
                    },
                    leadingContent = { Icon(Icons.Default.Bookmarks, contentDescription = null) },
                    modifier = Modifier
                        .clickable {
                            showShelfPickerForBook = actionBook
                            selectedBookForAction = null
                        }
                        .testTag("book_action_add_to_shelf")
                )

                val isMarkedDone = actionBook.isFinished || actionBook.progressPercent >= 0.99f
                ListItem(
                    headlineContent = {
                        Text(if (isMarkedDone) "Mark as In Progress" else "Mark as Finished")
                    },
                    leadingContent = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    modifier = Modifier
                        .clickable {
                            viewModel.setBookFinished(actionBook.id, !isMarkedDone)
                            selectedBookForAction = null
                        }
                        .testTag("book_action_toggle_finished")
                )

                ListItem(
                    headlineContent = {
                        Text(if (actionBook.isHidden) "Unhide Book" else "Hide Book")
                    },
                    leadingContent = {
                        Icon(
                            if (actionBook.isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier
                        .clickable {
                            viewModel.setBookHidden(actionBook.id, !actionBook.isHidden)
                            selectedBookForAction = null
                        }
                        .testTag("book_action_toggle_hidden")
                )

                ListItem(
                    headlineContent = {
                        Text("Delete Book", color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier
                        .clickable {
                            bookToDeleteConfirm = actionBook
                            selectedBookForAction = null
                        }
                        .testTag("book_action_delete")
                )
            }
        }
    }

    // Shelf Assignment Dialog
    val shelfTargetBook = showShelfPickerForBook
    if (shelfTargetBook != null) {
        AlertDialog(
            onDismissRequest = { showShelfPickerForBook = null },
            title = { Text("Select Shelf") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ListItem(
                        headlineContent = { Text("No Shelf (All Books Only)") },
                        trailingContent = if (shelfTargetBook.shelfId == 0L) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        modifier = Modifier.clickable {
                            viewModel.assignBookToShelf(shelfTargetBook.id, 0L)
                            showShelfPickerForBook = null
                        }
                    )
                    shelves.forEach { shelf: ShelfEntity ->
                        ListItem(
                            headlineContent = { Text(shelf.name) },
                            trailingContent = if (shelfTargetBook.shelfId == shelf.id) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null,
                            modifier = Modifier.clickable {
                                viewModel.assignBookToShelf(shelfTargetBook.id, shelf.id)
                                showShelfPickerForBook = null
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showShelfPickerForBook = null
                        showNewShelfDialog = true
                    }
                ) {
                    Text("+ New Shelf")
                }
            },
            dismissButton = {
                TextButton(onClick = { showShelfPickerForBook = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Delete Book Confirmation Dialog (Two Choices: Remove from Library vs Delete EPUB File)
    val delBook = bookToDeleteConfirm
    if (delBook != null) {
        AlertDialog(
            onDismissRequest = { bookToDeleteConfirm = null },
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete \"${delBook.title}\"?") },
            text = {
                Text(
                    "Choose whether to only remove this book from the LexiVoice library index, or also permanently delete the EPUB file from storage."
                )
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.deleteBook(delBook, deleteFilePermanently = true)
                            bookToDeleteConfirm = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delete_book_permanently_button")
                    ) {
                        Text("Delete File Permanently")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.deleteBook(delBook, deleteFilePermanently = false)
                            bookToDeleteConfirm = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remove_book_from_library_button")
                    ) {
                        Text("Remove from Library Only")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDeleteConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentBooksSection(
    recentBooks: List<BookEntity>,
    onOpenBook: (Long) -> Unit,
    onLongPressBook: (BookEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Recent Books",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            items(recentBooks, key = { "recent_${it.id}" }) { book ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .width(126.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .combinedClickable(
                            onClick = { onOpenBook(book.id) },
                            onLongClick = { onLongPressBook(book) }
                        )
                ) {
                    Column {
                        BookCoverImage(
                            coverPath = book.coverPath,
                            title = book.title,
                            isFinished = book.isFinished,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.72f)
                        )
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = book.title,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { book.progressPercent.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = "All Books",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridCard(
    book: BookEntity,
    compact: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("book_card_${book.id}")
    ) {
        Column {
            BookCoverImage(
                coverPath = book.coverPath,
                title = book.title,
                isFinished = book.isFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
            )
            Column(modifier = Modifier.padding(if (compact) 6.dp else 10.dp)) {
                Text(
                    text = book.title,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!compact) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { book.progressPercent.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (compact) "${(book.progressPercent * 100f).roundToInt()}%" else "${(book.progressPercent * 100f).roundToInt()}% • ${book.chapterCount} ch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListCard(
    book: BookEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("book_list_card_${book.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCoverImage(
                coverPath = book.coverPath,
                title = book.title,
                isFinished = book.isFinished,
                modifier = Modifier
                    .width(58.dp)
                    .height(82.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(book.author)
                        if (book.language.isNotBlank()) append(" • ${book.language.uppercase()}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { book.progressPercent.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${(book.progressPercent * 100f).roundToInt()}% • ${book.chapterCount} ch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun BookCoverImage(
    coverPath: String?,
    title: String,
    isFinished: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverFile = remember(coverPath) {
        coverPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (coverFile != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverFile)
                    .crossfade(true)
                    .build(),
                contentDescription = "$title cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.img_default_cover),
                contentDescription = "$title default cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = title.take(28),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
        }
        if (isFinished) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Finished",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(3.dp)
                        .size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(
    hasFilter: Boolean,
    onClearFilters: () -> Unit,
    onAddSafFolder: () -> Unit,
    onImportSingleEpub: () -> Unit,
    onOpenFoldersTab: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_library),
            contentDescription = "Empty library illustration",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(20.dp))
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = if (hasFilter) "No Matching Books" else "Your EPUB Library Is Ready",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (hasFilter) {
                "Try a different search query or clear active shelf, progress, language, or folder filters."
            } else {
                "Assign one or more folders to auto-index thousands of EPUBs into your fast local library."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (hasFilter) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onClearFilters) {
                Text("Clear All Filters")
            }
        } else {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAddSafFolder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("empty_add_folder_button")
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Assign EPUB Folder (SAF)")
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenFoldersTab,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Direct Paths", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onImportSingleEpub,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.MenuBook, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Open EPUB", maxLines = 1)
                }
            }
        }
    }
}
