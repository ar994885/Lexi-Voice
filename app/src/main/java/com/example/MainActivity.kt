package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateBottomPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.db.BookEntity
import com.example.export.ExportChaptersActivity
import com.example.overlay.FloatingOverlayController
import com.example.overlay.UnifiedOverlaySource
import com.example.tts.TtsPlaybackService
import com.example.ui.AudiobooksViewModel
import com.example.ui.LibraryViewModel
import com.example.ui.ReaderViewModel
import com.example.ui.components.InAppFloatingTtsPopup
import com.example.ui.screens.AudiobooksScreen
import com.example.ui.screens.BookScreen
import com.example.ui.screens.FoldersAndPermissionsScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.M4bBuilderScreen
import com.example.ui.screens.M4bPlayerScreen
import com.example.ui.screens.PasteAndSpeakScreen
import com.example.ui.screens.ReaderScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SpeakingTextProcessScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.LexiVoiceTheme
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val LIBRARY = "library"
    const val AUDIOBOOKS = "audiobooks"
    const val M4B_BUILDER = "m4b_builder"
    const val PASTE_SPEAK = "paste_speak"
    const val FOLDERS = "folders"
    const val SETTINGS = "settings"
    const val STUDIO = "studio"
    const val BOOK = "book/{bookId}"
    const val READER = "reader/{bookId}"
    const val M4B_PLAYER = "m4b_player/{audiobookId}"
    const val PRONUNCIATION_RULES = "pronunciation_rules?bookId={bookId}"

    fun bookRoute(bookId: Long): String = "book/$bookId"
    fun readerRoute(bookId: Long): String = "reader/$bookId"
    fun m4bPlayerRoute(audiobookId: Long): String = "m4b_player/$audiobookId"
    fun pronunciationRulesRoute(bookId: Long = -1L): String = "pronunciation_rules?bookId=$bookId"
}

data class OverlayDeepLinkRequest(
    val source: UnifiedOverlaySource,
    val bookId: Long,
    val audiobookId: Long,
    val chapterIndex: Int,
    val sentenceIndex: Int,
    val timestamp: Long = System.currentTimeMillis()
)

private data class BarDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

/** The four destinations of section 5.1, in order. */
private val BAR_ITEMS = listOf(
    BarDestination(
        route = Routes.LIBRARY,
        labelRes = R.string.tab_library,
        selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
        unselectedIcon = Icons.AutoMirrored.Outlined.MenuBook,
        testTag = "tab_library"
    ),
    BarDestination(
        route = Routes.AUDIOBOOKS,
        labelRes = R.string.tab_listen,
        selectedIcon = Icons.Filled.Headphones,
        unselectedIcon = Icons.Outlined.Headphones,
        testTag = "tab_listen"
    ),
    BarDestination(
        route = Routes.STUDIO,
        labelRes = R.string.tab_studio,
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome,
        testTag = "tab_studio"
    ),
    BarDestination(
        route = Routes.SETTINGS,
        labelRes = R.string.tab_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "tab_settings"
    )
)

/**
 * Screens that are not top level, so the bar hides on them: the reader, the player, the
 * text-rules screen and the Book detail screen (5.3).
 */
private val BAR_HIDDEN_ROUTES = setOf(
    Routes.READER,
    Routes.M4B_PLAYER,
    Routes.PRONUNCIATION_RULES,
    Routes.BOOK
)

/**
 * Which bar item is selected for a route. The sub-screens that keep the bar (folders,
 * Quick Listen, Build from audio) light up the destination they will move under in a
 * later phase, so the bar never looks like nothing is selected.
 */
private fun selectedTabFor(route: String?): String? = when (route) {
    Routes.LIBRARY, Routes.FOLDERS -> Routes.LIBRARY
    Routes.AUDIOBOOKS, Routes.PASTE_SPEAK -> Routes.AUDIOBOOKS
    Routes.STUDIO, Routes.M4B_BUILDER -> Routes.STUDIO
    Routes.SETTINGS -> Routes.SETTINGS
    else -> null
}

class MainActivity : ComponentActivity() {
    private val deepLinkFlow = MutableStateFlow<OverlayDeepLinkRequest?>(null)
    private var volumeKeyHandler: ((Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TtsPlaybackService.sendAction(this, TtsPlaybackService.ACTION_INIT_ENGINES)
        handleOverlayIntent(intent)

        setContent {
            LexiVoiceTheme {
                LexiVoiceAppRoot(
                    deepLinkFlow = deepLinkFlow,
                    onConsumeDeepLink = { deepLinkFlow.value = null },
                    onRegisterVolumeKeyHandler = { handler -> volumeKeyHandler = handler }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (volumeKeyHandler?.invoke(keyCode) == true) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            volumeKeyHandler != null
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOverlayIntent(intent)
    }

    private fun handleOverlayIntent(intent: Intent?) {
        val srcName = intent?.getStringExtra(FloatingOverlayController.EXTRA_OVERLAY_SOURCE) ?: return
        val source = runCatching { UnifiedOverlaySource.valueOf(srcName) }.getOrNull() ?: return
        if (source == UnifiedOverlaySource.NONE) return
        deepLinkFlow.value = OverlayDeepLinkRequest(
            source = source,
            bookId = intent.getLongExtra(FloatingOverlayController.EXTRA_BOOK_ID, -1L),
            audiobookId = intent.getLongExtra(FloatingOverlayController.EXTRA_AUDIOBOOK_ID, -1L),
            chapterIndex = intent.getIntExtra(FloatingOverlayController.EXTRA_CHAPTER_INDEX, 0),
            sentenceIndex = intent.getIntExtra(FloatingOverlayController.EXTRA_SENTENCE_INDEX, 0)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LexiVoiceAppRoot(
    deepLinkFlow: MutableStateFlow<OverlayDeepLinkRequest?>,
    onConsumeDeepLink: () -> Unit,
    onRegisterVolumeKeyHandler: (((Int) -> Boolean)?) -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()

    val libraryViewModel: LibraryViewModel = viewModel()
    val readerViewModel: ReaderViewModel = viewModel()
    val audiobooksViewModel: AudiobooksViewModel = viewModel()
    val overlayState by FloatingOverlayController.state.collectAsStateWithLifecycle()
    val pendingDeepLink by deepLinkFlow.collectAsStateWithLifecycle()
    var forceReadAlongTrigger by remember { mutableLongStateOf(0L) }

    // Phase 1 keeps the old hamburger on every existing screen alive by pointing it at a
    // small sheet, because the drawer is gone and Quick Listen and Book folders have no
    // other entry point until Phase 6C and Phase 2 give them one. Deleting this sheet is
    // the last step of those phases.
    var showMoreSheet by remember { mutableStateOf(false) }
    val moreSheetState = rememberModalBottomSheetState()

    // The Studio tiles need a book. Until Phase 5 builds S12 step 1, they pick one from
    // this list and open the Book screen.
    val recentBooks by libraryViewModel.recentBooks.collectAsStateWithLifecycle()
    val libraryBooks by libraryViewModel.libraryBooks.collectAsStateWithLifecycle()
    val studioBooks = remember(recentBooks, libraryBooks) {
        (recentBooks + libraryBooks).distinctBy { it.id }
    }

    var pendingExportBook by remember { mutableStateOf<BookEntity?>(null) }
    val exportEditedEpubLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        val target = pendingExportBook
        pendingExportBook = null
        if (uri != null && target != null) {
            libraryViewModel.exportEditedEpubForBook(target, uri) { result ->
                Toast.makeText(
                    context,
                    context.getString(
                        if (result.success) R.string.book_export_done else R.string.book_export_failed
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // An .epub opened from a file manager or a share sheet arrives as a VIEW
    // intent. Import it into the library and go straight into the reader.
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            // Clear it first so a rotation does not import the same file twice.
            intent.action = null
            intent.data = null
            if (uri != null) {
                libraryViewModel.importSingleEpub(uri) { bookId ->
                    if (bookId > 0L) {
                        navController.navigate("reader/$bookId")
                    }
                }
            }
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun executeJump(
        source: UnifiedOverlaySource,
        bookId: Long,
        audiobookId: Long,
        chapterIndex: Int,
        sentenceIndex: Int
    ) {
        when (source) {
            UnifiedOverlaySource.AUDIOBOOK -> {
                if (audiobookId > 0L) {
                    forceReadAlongTrigger = System.currentTimeMillis()
                    navController.navigate(Routes.m4bPlayerRoute(audiobookId)) {
                        launchSingleTop = true
                    }
                }
            }
            UnifiedOverlaySource.TTS -> {
                if (bookId > 0L) {
                    readerViewModel.requestJumpToLocation(bookId, chapterIndex, sentenceIndex)
                    navController.navigate(Routes.readerRoute(bookId)) {
                        launchSingleTop = true
                    }
                } else {
                    navController.navigate(Routes.PASTE_SPEAK) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
            UnifiedOverlaySource.NONE -> Unit
        }
    }

    LaunchedEffect(pendingDeepLink) {
        val req = pendingDeepLink ?: return@LaunchedEffect
        executeJump(
            source = req.source,
            bookId = req.bookId,
            audiobookId = req.audiobookId,
            chapterIndex = req.chapterIndex,
            sentenceIndex = req.sentenceIndex
        )
        onConsumeDeepLink()
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == null || currentRoute !in BAR_HIDDEN_ROUTES

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Every existing screen still takes an onOpenDrawer callback. Until the phase that
    // restyles its top bar, that callback opens the temporary More sheet.
    val openMoreAction: () -> Unit = { showMoreSheet = true }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                LexiBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { route -> navigateTopLevel(route) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.LIBRARY,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        onOpenDrawer = openMoreAction,
                        onOpenBook = { bookId ->
                            navController.navigate(Routes.readerRoute(bookId))
                        },
                        onNavigateToFolders = { navigateTopLevel(Routes.FOLDERS) }
                    )
                }

                composable(Routes.AUDIOBOOKS) {
                    AudiobooksScreen(
                        viewModel = audiobooksViewModel,
                        onOpenDrawer = openMoreAction,
                        onOpenPlayer = { audiobookId ->
                            navController.navigate(Routes.m4bPlayerRoute(audiobookId))
                        }
                    )
                }

                composable(Routes.STUDIO) {
                    StudioScreen(
                        books = studioBooks,
                        onOpenBook = { bookId ->
                            navController.navigate(Routes.bookRoute(bookId)) {
                                launchSingleTop = true
                            }
                        },
                        onBuildFromAudio = { navigateTopLevel(Routes.M4B_BUILDER) },
                        onOpenLibrary = { navigateTopLevel(Routes.LIBRARY) }
                    )
                }

                composable(Routes.M4B_BUILDER) {
                    M4bBuilderScreen(
                        viewModel = audiobooksViewModel,
                        onOpenDrawer = openMoreAction,
                        onOpenPlayer = { audiobookId ->
                            navController.navigate(Routes.m4bPlayerRoute(audiobookId))
                        }
                    )
                }

                composable(Routes.PASTE_SPEAK) {
                    PasteAndSpeakScreen(onOpenDrawer = openMoreAction)
                }

                composable(Routes.FOLDERS) {
                    FoldersAndPermissionsScreen(
                        viewModel = libraryViewModel,
                        onOpenDrawer = openMoreAction,
                        onNavigateToSettings = { navigateTopLevel(Routes.SETTINGS) }
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenDrawer = openMoreAction,
                        onOpenPronunciationRules = {
                            navController.navigate(Routes.pronunciationRulesRoute()) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(
                    route = Routes.BOOK,
                    arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                ) { entry ->
                    val bookId = entry.arguments?.getLong("bookId") ?: -1L
                    BookScreen(
                        bookId = bookId,
                        viewModel = libraryViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenReader = { id ->
                            navController.navigate(Routes.readerRoute(id)) {
                                launchSingleTop = true
                            }
                        },
                        onOpenAudiobook = { id ->
                            forceReadAlongTrigger = System.currentTimeMillis()
                            navController.navigate(Routes.m4bPlayerRoute(id)) {
                                launchSingleTop = true
                            }
                        },
                        onCreateAudiobook = { book ->
                            ExportChaptersActivity.launchForBook(
                                context = context,
                                title = book.title,
                                author = book.author,
                                fileKey = book.fileKey,
                                isDirectPath = book.isDirectPath,
                                coverPath = book.coverPath
                            )
                        },
                        onExportEditedBook = { book ->
                            pendingExportBook = book
                            exportEditedEpubLauncher.launch("${book.title} (Edited).epub")
                        }
                    )
                }

                composable(
                    route = Routes.PRONUNCIATION_RULES,
                    arguments = listOf(
                        navArgument("bookId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        }
                    )
                ) { entry ->
                    val targetBookId = entry.arguments?.getLong("bookId") ?: -1L
                    SpeakingTextProcessScreen(
                        initialBookId = targetBookId,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Routes.READER,
                    arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                ) { entry ->
                    val bookId = entry.arguments?.getLong("bookId") ?: -1L
                    ReaderScreen(
                        bookId = bookId,
                        viewModel = readerViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenPronunciationRules = { bid ->
                            navController.navigate(Routes.pronunciationRulesRoute(bid)) {
                                launchSingleTop = true
                            }
                        },
                        onOpenAudiobook = { aid ->
                            forceReadAlongTrigger = System.currentTimeMillis()
                            navController.navigate(Routes.m4bPlayerRoute(aid)) {
                                launchSingleTop = true
                            }
                        },
                        onRegisterVolumeKeyHandler = onRegisterVolumeKeyHandler
                    )
                }

                composable(
                    route = Routes.M4B_PLAYER,
                    arguments = listOf(navArgument("audiobookId") { type = NavType.LongType })
                ) { entry ->
                    val audiobookId = entry.arguments?.getLong("audiobookId") ?: -1L
                    M4bPlayerScreen(
                        audiobookId = audiobookId,
                        forceReadAlongTrigger = forceReadAlongTrigger,
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            InAppFloatingTtsPopup(
                overlayState = overlayState,
                onTapSentence = { state ->
                    executeJump(
                        source = state.activeSource,
                        bookId = state.bookId,
                        audiobookId = state.audiobookId,
                        chapterIndex = state.chapterIndex,
                        sentenceIndex = state.sentenceIndex
                    )
                }
            )
        }
    }

    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            sheetState = moreSheetState
        ) {
            MoreSheet(
                onQuickListen = {
                    showMoreSheet = false
                    navigateTopLevel(Routes.PASTE_SPEAK)
                },
                onBookFolders = {
                    showMoreSheet = false
                    navigateTopLevel(Routes.FOLDERS)
                }
            )
        }
    }
}

@Composable
private fun LexiBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit
) {
    val selectedTab = selectedTabFor(currentRoute)
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        BAR_ITEMS.forEach { item ->
            val selected = selectedTab == item.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(item.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = stringResource(item.labelRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.testTag(item.testTag)
            )
        }
    }
}

/**
 * Temporary surface, deleted once Quick Listen moves under Listen (Phase 6C) and Book
 * folders moves under Library (Phase 2). It exists so that removing the drawer does not
 * orphan two working screens in the meantime.
 */
@Composable
private fun MoreSheet(
    onQuickListen: () -> Unit,
    onBookFolders: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
            .testTag("legacy_more_sheet")
    ) {
        Text(
            text = stringResource(R.string.more_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        MoreSheetRow(
            icon = Icons.Filled.ContentPaste,
            label = stringResource(R.string.more_quick_listen),
            onClick = onQuickListen
        )
        MoreSheetRow(
            icon = Icons.Filled.Folder,
            label = stringResource(R.string.more_book_folders),
            onClick = onBookFolders
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MoreSheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
