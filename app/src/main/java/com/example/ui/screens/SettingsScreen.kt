package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import com.example.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.LexiVoiceApplication
import com.example.data.prefs.FloatingAppearanceMode
import com.example.data.prefs.FloatingOverlaySettings
import com.example.data.prefs.ReaderSettings
import com.example.data.repo.BackupSyncRepository
import com.example.overlay.FloatingOverlayController
import com.example.overlay.OverlayStyleHelper
import com.example.tts.TtsStateHolder
import com.example.ui.components.TtsControlsModalSheet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val OVERLAY_BG_SWATCHES = listOf(
    0xE61E1E24.toInt(),
    0xE61A237E.toInt(),
    0xE6004D40.toInt(),
    0xE64A148C.toInt(),
    0xEBFAF5E8.toInt(),
    0xF2FFFFFF.toInt()
)

private val OVERLAY_BORDER_SWATCHES = listOf(
    0x33FFFFFF,
    0xFF7C4DFF.toInt(),
    0xFF00BFA5.toInt(),
    0xFFFFB300.toInt(),
    0xFFFF5252.toInt(),
    0xFF1F2937.toInt()
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenDrawer: () -> Unit,
    onOpenPronunciationRules: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as LexiVoiceApplication
    val prefsRepo = app.preferencesRepository
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val ttsState by TtsStateHolder.state.collectAsStateWithLifecycle()
    val savedExportFolders by prefsRepo.savedExportFolders.collectAsStateWithLifecycle(initialValue = emptyList())
    val overlaySettings by prefsRepo.floatingOverlaySettings.collectAsStateWithLifecycle(
        initialValue = FloatingOverlaySettings()
    )
    val readerSettings by prefsRepo.readerSettings.collectAsStateWithLifecycle(
        initialValue = ReaderSettings()
    )

    var resumeTick by remember { mutableIntStateOf(0) }
    var showTtsSheet by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Audiobook auto-discovery needs the audio media permission only. All Files
    // Access is no longer requested: it is a restricted permission that Play
    // grants to a narrow set of app categories, and MediaStore + SAF cover this
    // app's storage needs without it.
    val hasAudioMediaAccess = remember(resumeTick) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    // True only in the Pro edition, where the flavour manifest declares
    // MANAGE_EXTERNAL_STORAGE. Read from a boolean resource so the shared UI needs
    // no BuildConfig.FLAVOR check.
    val hasAllFilesScanCapability = context.resources.getBoolean(R.bool.feature_all_files_scan)

    val hasAllFilesAccess = remember(resumeTick) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    val hasNotificationPerm = remember(resumeTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    val hasOverlayPerm = remember(resumeTick) {
        Settings.canDrawOverlays(context)
    }

    val isIgnoringBatteryOptimizations = remember(resumeTick) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { resumeTick++ }

    val audioMediaPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { resumeTick++ }

    val snackbarHostState = remember { SnackbarHostState() }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = app.backupSyncRepository.exportToUri(uri)
                snackbarHostState.showSnackbar(
                    if (ok) "Exported rules, bookmarks & highlights (.json)"
                    else "Failed to export backup file"
                )
            }
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                app.backupSyncRepository.importFromUri(uri).fold(
                    onSuccess = { summary ->
                        snackbarHostState.showSnackbar(
                            "Merged ${summary.addedRules} rule(s) & ${summary.addedBookmarks} bookmark/highlight(s)" +
                                if (summary.restoredReadingPositions + summary.restoredAudiobookPositions > 0) {
                                    ", restored ${summary.restoredReadingPositions} reading position(s) & " +
                                        "${summary.restoredAudiobookPositions} audiobook position(s)"
                                } else ""
                        )
                    },
                    onFailure = { err ->
                        snackbarHostState.showSnackbar("Import failed: ${err.message ?: "Invalid JSON"}")
                    }
                )
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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
                title = { Text("Settings") }
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
                    text = "Text-to-Speech & Pronunciation",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
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
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Default Engine, Language & Voice", style = MaterialTheme.typography.titleSmall)
                            val summary = buildString {
                                append(ttsState.availableEngines.firstOrNull { it.packageName == ttsState.selectedEnginePackage }?.label ?: "System Default")
                                if (ttsState.selectedLocaleTag.isNotBlank()) append(" • ${ttsState.selectedLocaleTag}")
                            }
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { showTtsSheet = true },
                            modifier = Modifier.testTag("settings_configure_tts_button")
                        ) {
                            Text("Configure")
                        }
                    }
                }
            }

            item {
                Card(
                    onClick = onOpenPronunciationRules,
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_speaking_text_process_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Spellcheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Speaking Text Process", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Custom pronunciation & word replacement rules for TTS and audio export",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onOpenPronunciationRules) {
                            Text("Rules")
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Floating Overlay Controls",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                FloatingOverlayControlsCard(
                    settings = overlaySettings,
                    onMasterEnabledChange = { enabled ->
                        FloatingOverlayController.setMasterEnabled(context, enabled)
                    },
                    onUpdateSettings = { transform ->
                        scope.launch { prefsRepo.updateFloatingOverlaySettings(transform) }
                    }
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Reader & Library Behavior",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Show Folder Categories in Library", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Display indexed folder filter pills in the Library screen",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = readerSettings.showLibraryFolders,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        prefsRepo.updateReaderSettings { it.copy(showLibraryFolders = checked) }
                                    }
                                },
                                modifier = Modifier.testTag("settings_show_library_folders_switch")
                            )
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Volume Keys Turn Pages", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Use Volume Down / Up to turn pages or scroll while reading",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = readerSettings.volumeKeysTurnPages,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        prefsRepo.updateReaderSettings { it.copy(volumeKeysTurnPages = checked) }
                                    }
                                },
                                modifier = Modifier.testTag("settings_volume_keys_switch")
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Show System Notification Bar", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Keep the status bar visible while reading. Turn it off for an edge-to-edge page.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = readerSettings.showSystemStatusBar,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        prefsRepo.updateReaderSettings { it.copy(showSystemStatusBar = checked) }
                                    }
                                },
                                modifier = Modifier.testTag("settings_status_bar_switch")
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Keep Screen Awake", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Stop the screen from sleeping while the reader is open",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = readerSettings.keepScreenAwake,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        prefsRepo.updateReaderSettings { it.copy(keepScreenAwake = checked) }
                                    }
                                },
                                modifier = Modifier.testTag("settings_keep_awake_switch")
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Permissions & Background Access",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                SettingsPermissionCard(
                    title = "Notifications (Media & Batch Export)",
                    subtitle = if (hasNotificationPerm) "Granted — lock-screen media controls & dual-progress export notifications active"
                    else "Needed for Android 13+ background playback & export progress notifications",
                    isGranted = hasNotificationPerm,
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }

            if (hasAllFilesScanCapability) {
                item {
                    SettingsPermissionCard(
                        title = "Full-Device Book Scanning",
                        subtitle = if (hasAllFilesAccess)
                            "Granted — EPUBs anywhere on this device are found automatically"
                        else "Allow LexiVoice Pro to scan all folders for EPUBs automatically",
                        isGranted = hasAllFilesAccess,
                        icon = { Icon(Icons.Default.Security, contentDescription = null) },
                        onGrant = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            }
                        }
                    )
                }
            }

            item {
                SettingsPermissionCard(
                    title = "Audiobook Auto-Discovery",
                    subtitle = if (hasAudioMediaAccess) "Granted — .m4b and .mp3 audiobooks are found automatically"
                    else "Allow LexiVoice to find audiobooks on this device automatically",
                    isGranted = hasAudioMediaAccess,
                    icon = { Icon(Icons.Default.Security, contentDescription = null) },
                    onGrant = {
                        audioMediaPermLauncher.launch(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Manifest.permission.READ_MEDIA_AUDIO
                            } else {
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            }
                        )
                    }
                )
            }

            item {
                SettingsPermissionCard(
                    title = "Floating TTS Overlay",
                    subtitle = if (hasOverlayPerm) "Granted — compact T2S reading bar can float above other apps"
                    else "Optional: allow compact reading bar to float over browsers & other apps",
                    isGranted = hasOverlayPerm,
                    icon = { Icon(Icons.Default.PictureInPictureAlt, contentDescription = null) },
                    onGrant = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )
            }

            item {
                SettingsPermissionCard(
                    title = "Battery Optimization (Unrestricted Background)",
                    subtitle = if (isIgnoringBatteryOptimizations) {
                        "Unrestricted — TTS playback and batch MP3/M4B export stay alive in background"
                    } else {
                        "Recommended: allow unrestricted background use so screen-off TTS & exports don't pause"
                    },
                    isGranted = isIgnoringBatteryOptimizations,
                    actionLabel = "Allow",
                    icon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null) },
                    onGrant = {
                        @SuppressLint("BatteryLife")
                        val requestIntent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        val launched = runCatching { context.startActivity(requestIntent) }.isSuccess
                        if (!launched) {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    }
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Backup & Sync (Rules & Highlights)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "LexiVoice automatically mirrors ${BackupSyncRepository.AUTO_MIRROR_FILENAME} to your configured audio export folder whenever rules, bookmarks, or highlights change.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    exportBackupLauncher.launch(BackupSyncRepository.AUTO_MIRROR_FILENAME)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_export_backup_button")
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export (.json)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Button(
                                onClick = {
                                    importBackupLauncher.launch(arrayOf("application/json", "*/*"))
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_import_backup_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Import / Merge", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            if (savedExportFolders.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Saved Audio Export Folders (${savedExportFolders.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(savedExportFolders, key = { it.uri }) { folder ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = folder.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    scope.launch { prefsRepo.removeSavedExportFolder(folder.uri) }
                                }
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove Saved Export Folder")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTtsSheet) {
        TtsControlsModalSheet(
            ttsState = ttsState,
            onDismiss = { showTtsSheet = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloatingOverlayControlsCard(
    settings: FloatingOverlaySettings,
    onMasterEnabledChange: (Boolean) -> Unit,
    onUpdateSettings: ((FloatingOverlaySettings) -> FloatingOverlaySettings) -> Unit
) {
    val resolved = remember(settings) { OverlayStyleHelper.resolveColors(settings) }
    val bgColor = Color(resolved.backgroundArgb)
    val borderColor = Color(resolved.borderArgb)
    val primaryTextColor = Color(resolved.primaryTextArgb)
    val secondaryIconColor = Color(resolved.secondaryIconArgb)

    var bgHexInput by remember(settings.backgroundColorArgb) {
        mutableStateOf(formatArgbHex(settings.backgroundColorArgb))
    }
    var borderHexInput by remember(settings.borderColorArgb) {
        mutableStateOf(formatArgbHex(settings.borderColorArgb))
    }

    val currentAlpha = remember(settings.backgroundColorArgb) {
        (AndroidColor.alpha(settings.backgroundColorArgb) / 255f).coerceIn(0.15f, 1f)
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_floating_overlay_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Floating Playback Overlay", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (settings.masterEnabled) "Enabled — popup bar & 48dp bubble active" else "Disabled globally",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = settings.masterEnabled,
                    onCheckedChange = onMasterEnabledChange,
                    modifier = Modifier.testTag("settings_overlay_master_switch")
                )
            }

            // Live preview of compact bar + minimized 48dp circular bubble
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val strokeMod = if (resolved.borderWidthDp > 0) {
                    Modifier.border(resolved.borderWidthDp.dp, borderColor, RoundedCornerShape(24.dp))
                } else {
                    Modifier
                }
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = bgColor,
                    modifier = Modifier
                        .weight(1f)
                        .then(strokeMod)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = primaryTextColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Preview • Floating Bar",
                            style = MaterialTheme.typography.bodySmall,
                            color = primaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text("—", color = secondaryIconColor, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("✕", color = secondaryIconColor, fontWeight = FontWeight.Bold)
                    }
                }

                val bubbleBorderMod = if (resolved.borderWidthDp > 0) {
                    Modifier.border(resolved.borderWidthDp.dp, borderColor, CircleShape)
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(bgColor)
                        .then(bubbleBorderMod),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Minimized Bubble Preview",
                        tint = primaryTextColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            HorizontalDivider()

            Text("Appearance Mode", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.appearanceMode == FloatingAppearanceMode.DEFAULT_THEME,
                    onClick = {
                        onUpdateSettings { it.copy(appearanceMode = FloatingAppearanceMode.DEFAULT_THEME) }
                    },
                    label = { Text("Default Theme Look") },
                    modifier = Modifier.testTag("overlay_mode_default_chip")
                )
                FilterChip(
                    selected = settings.appearanceMode == FloatingAppearanceMode.CUSTOM,
                    onClick = {
                        onUpdateSettings { it.copy(appearanceMode = FloatingAppearanceMode.CUSTOM) }
                    },
                    label = { Text("Custom Colors") },
                    modifier = Modifier.testTag("overlay_mode_custom_chip")
                )
            }

            if (settings.appearanceMode == FloatingAppearanceMode.CUSTOM) {
                Text("Background Color", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OVERLAY_BG_SWATCHES.forEach { swatchArgb ->
                        val isSelected = (settings.backgroundColorArgb and 0x00FFFFFF) == (swatchArgb and 0x00FFFFFF)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(swatchArgb))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                                .clickable {
                                    val alpha = AndroidColor.alpha(settings.backgroundColorArgb).coerceAtLeast(38)
                                    val combined = (alpha shl 24) or (swatchArgb and 0x00FFFFFF)
                                    onUpdateSettings {
                                        it.copy(
                                            appearanceMode = FloatingAppearanceMode.CUSTOM,
                                            backgroundColorArgb = combined
                                        )
                                    }
                                }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = bgHexInput,
                        onValueChange = { bgHexInput = it },
                        label = { Text("Background Hex (#AARRGGBB)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            parseHexColorOrNull(bgHexInput)?.let { parsed ->
                                onUpdateSettings {
                                    it.copy(
                                        appearanceMode = FloatingAppearanceMode.CUSTOM,
                                        backgroundColorArgb = parsed
                                    )
                                }
                            }
                        }
                    ) {
                        Text("Apply")
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Background Opacity", style = MaterialTheme.typography.labelMedium)
                        Text("${(currentAlpha * 100f).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = currentAlpha,
                        onValueChange = { newAlpha ->
                            val alphaInt = (newAlpha.coerceIn(0.15f, 1f) * 255f).roundToInt() and 0xFF
                            val updatedArgb = (alphaInt shl 24) or (settings.backgroundColorArgb and 0x00FFFFFF)
                            onUpdateSettings {
                                it.copy(
                                    appearanceMode = FloatingAppearanceMode.CUSTOM,
                                    backgroundColorArgb = updatedArgb
                                )
                            }
                        },
                        valueRange = 0.15f..1.0f
                    )
                }

                HorizontalDivider()

                Text("Border Color", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OVERLAY_BORDER_SWATCHES.forEach { swatchArgb ->
                        val isSelected = settings.borderColorArgb == swatchArgb
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(swatchArgb))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                                .clickable {
                                    onUpdateSettings {
                                        it.copy(
                                            appearanceMode = FloatingAppearanceMode.CUSTOM,
                                            borderColorArgb = swatchArgb
                                        )
                                    }
                                }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = borderHexInput,
                        onValueChange = { borderHexInput = it },
                        label = { Text("Border Hex (#AARRGGBB)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            parseHexColorOrNull(borderHexInput)?.let { parsed ->
                                onUpdateSettings {
                                    it.copy(
                                        appearanceMode = FloatingAppearanceMode.CUSTOM,
                                        borderColorArgb = parsed
                                    )
                                }
                            }
                        }
                    ) {
                        Text("Apply")
                    }
                }

                Text("Border Width", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("None (0dp)" to 0, "Thin (1dp)" to 1, "Medium (2dp)" to 2).forEach { (label, dpVal) ->
                        FilterChip(
                            selected = settings.borderWidthDp == dpVal,
                            onClick = {
                                onUpdateSettings {
                                    it.copy(
                                        appearanceMode = FloatingAppearanceMode.CUSTOM,
                                        borderWidthDp = dpVal
                                    )
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    }
}

private fun formatArgbHex(argb: Int): String = String.format("#%08X", argb)

private fun parseHexColorOrNull(raw: String): Int? {
    val clean = raw.trim().removePrefix("#")
    return when (clean.length) {
        6 -> clean.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        8 -> clean.toLongOrNull(16)?.toInt()
        else -> null
    }
}

@Composable
private fun SettingsPermissionCard(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    icon: @Composable () -> Unit,
    actionLabel: String = "Grant",
    onGrant: () -> Unit
) {
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
            icon()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isGranted) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colorScheme.secondary
                )
            } else {
                Button(onClick = onGrant) {
                    Text(actionLabel)
                }
            }
        }
    }
}

