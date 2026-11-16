package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsStateHolder
import com.example.ui.AudiobooksViewModel
import com.example.ui.LibraryViewModel
import com.example.ui.ReaderViewModel
import com.example.ui.components.InAppFloatingTtsPopup
import com.example.ui.screens.AudiobooksScreen
import com.example.ui.screens.FoldersAndPermissionsScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.M4bPlayerScreen
import com.example.ui.screens.PasteAndSpeakScreen
import com.example.ui.screens.ReaderScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.LexiVoiceTheme
import kotlinx.coroutines.launch

object Routes {
    const val LIBRARY = "library"
    const val AUDIOBOOKS = "audiobooks"
    const val PASTE_SPEAK = "paste_speak"
    const val FOLDERS = "folders"
    const val SETTINGS = "settings"
    const val READER = "reader/{bookId}"
    const val M4B_PLAYER = "m4b_player/{audiobookId}"

    fun readerRoute(bookId: Long): String = "reader/$bookId"
    fun m4bPlayerRoute(audiobookId: Long): String = "m4b_player/$audiobookId"
}

private data class DrawerDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val DRAWER_ITEMS = listOf(
    DrawerDestination(
        route = Routes.LIBRARY,
        labelRes = R.string.tab_library,
        selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
        unselectedIcon = Icons.AutoMirrored.Outlined.MenuBook,
        testTag = "drawer_item_library"
    ),
    DrawerDestination(
        route = Routes.AUDIOBOOKS,
        labelRes = R.string.tab_audiobooks,
        selectedIcon = Icons.Filled.Headphones,
        unselectedIcon = Icons.Outlined.Headphones,
        testTag = "drawer_item_audiobooks"
    ),
    DrawerDestination(
        route = Routes.PASTE_SPEAK,
        labelRes = R.string.tab_paste_speak,
        selectedIcon = Icons.Filled.ContentPaste,
        unselectedIcon = Icons.Outlined.ContentPaste,
        testTag = "drawer_item_paste_speak"
    ),
    DrawerDestination(
        route = Routes.FOLDERS,
        labelRes = R.string.tab_folders,
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder,
        testTag = "drawer_item_folders"
    ),
    DrawerDestination(
        route = Routes.SETTINGS,
        labelRes = R.string.tab_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "drawer_item_settings"
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TtsPlaybackService.sendAction(this, TtsPlaybackService.ACTION_INIT_ENGINES)

        setContent {
            LexiVoiceTheme {
                LexiVoiceAppRoot()
            }
        }
    }
}

@Composable
private fun LexiVoiceAppRoot() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val libraryViewModel: LibraryViewModel = viewModel()
    val readerViewModel: ReaderViewModel = viewModel()
    val audiobooksViewModel: AudiobooksViewModel = viewModel()
    val ttsState by TtsStateHolder.state.collectAsStateWithLifecycle()

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

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevelScreen = currentRoute in DRAWER_ITEMS.map { it.route }

    fun navigateTopLevel(route: String) {
        scope.launch { drawerState.close() }
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val openDrawerAction: () -> Unit = {
        scope.launch { drawerState.open() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isTopLevelScreen,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
                    Text(
                        text = "LexiVoice",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "EPUB Reader, TTS & M4B Studio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                DRAWER_ITEMS.forEach { item ->
                    val selected = currentRoute == item.route
                    NavigationDrawerItem(
                        label = { Text(stringResource(item.labelRes)) },
                        selected = selected,
                        onClick = { navigateTopLevel(item.route) },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag(item.testTag)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.LIBRARY,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        onOpenDrawer = openDrawerAction,
                        onOpenBook = { bookId ->
                            navController.navigate(Routes.readerRoute(bookId))
                        },
                        onNavigateToFolders = { navigateTopLevel(Routes.FOLDERS) }
                    )
                }

                composable(Routes.AUDIOBOOKS) {
                    AudiobooksScreen(
                        viewModel = audiobooksViewModel,
                        onOpenDrawer = openDrawerAction,
                        onOpenPlayer = { audiobookId ->
                            navController.navigate(Routes.m4bPlayerRoute(audiobookId))
                        }
                    )
                }

                composable(Routes.PASTE_SPEAK) {
                    PasteAndSpeakScreen(onOpenDrawer = openDrawerAction)
                }

                composable(Routes.FOLDERS) {
                    FoldersAndPermissionsScreen(
                        viewModel = libraryViewModel,
                        onOpenDrawer = openDrawerAction,
                        onNavigateToSettings = { navigateTopLevel(Routes.SETTINGS) }
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(onOpenDrawer = openDrawerAction)
                }

                composable(
                    route = Routes.READER,
                    arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                ) { entry ->
                    val bookId = entry.arguments?.getLong("bookId") ?: -1L
                    ReaderScreen(
                        bookId = bookId,
                        viewModel = readerViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Routes.M4B_PLAYER,
                    arguments = listOf(navArgument("audiobookId") { type = NavType.LongType })
                ) { entry ->
                    val audiobookId = entry.arguments?.getLong("audiobookId") ?: -1L
                    M4bPlayerScreen(
                        audiobookId = audiobookId,
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            InAppFloatingTtsPopup(ttsState = ttsState)
        }
    }
}
