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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.ui.LibraryViewModel
import com.example.ui.ReaderViewModel
import com.example.ui.components.InAppFloatingTtsPopup
import com.example.ui.screens.FoldersAndPermissionsScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PasteAndSpeakScreen
import com.example.ui.screens.ReaderScreen
import com.example.ui.theme.LexiVoiceTheme

object Routes {
    const val LIBRARY = "library"
    const val PASTE_SPEAK = "paste_speak"
    const val FOLDERS = "folders"
    const val READER = "reader/{bookId}"

    fun readerRoute(bookId: Long): String = "reader/$bookId"
}

private data class TopLevelTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val TOP_LEVEL_TABS = listOf(
    TopLevelTab(
        route = Routes.LIBRARY,
        labelRes = R.string.tab_library,
        selectedIcon = Icons.Filled.MenuBook,
        unselectedIcon = Icons.Outlined.MenuBook,
        testTag = "nav_tab_library"
    ),
    TopLevelTab(
        route = Routes.PASTE_SPEAK,
        labelRes = R.string.tab_paste_speak,
        selectedIcon = Icons.Filled.ContentPaste,
        unselectedIcon = Icons.Outlined.ContentPaste,
        testTag = "nav_tab_paste_speak"
    ),
    TopLevelTab(
        route = Routes.FOLDERS,
        labelRes = R.string.tab_folders,
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder,
        testTag = "nav_tab_folders"
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
    val libraryViewModel: LibraryViewModel = viewModel()
    val readerViewModel: ReaderViewModel = viewModel()
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
    val showNavigationChrome = currentRoute in TOP_LEVEL_TABS.map { it.route }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 700.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showNavigationChrome && !useNavRail) {
                    NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                        TOP_LEVEL_TABS.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showNavigationChrome && useNavRail) {
                    NavigationRail(modifier = Modifier.statusBarsPadding()) {
                        TOP_LEVEL_TABS.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.LIBRARY,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(Routes.LIBRARY) {
                            LibraryScreen(
                                viewModel = libraryViewModel,
                                onOpenBook = { bookId ->
                                    navController.navigate(Routes.readerRoute(bookId))
                                },
                                onNavigateToFolders = {
                                    navController.navigate(Routes.FOLDERS) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }

                        composable(Routes.PASTE_SPEAK) {
                            PasteAndSpeakScreen()
                        }

                        composable(Routes.FOLDERS) {
                            FoldersAndPermissionsScreen(viewModel = libraryViewModel)
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
                    }

                    InAppFloatingTtsPopup(ttsState = ttsState)
                }
            }
        }
    }
}
