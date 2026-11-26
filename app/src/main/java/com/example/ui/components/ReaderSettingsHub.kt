package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.prefs.ReaderSettings

/** The four panels the reader's own settings hub opens. */
enum class ReaderSettingsPanel(val title: String, val subtitle: String) {
    VISUAL("Visual", "Theme, font, size, spacing, reading mode"),
    CONTROL("Control", "Page turn sound, volume keys, screen behaviour"),
    METADATA("Metadata", "Title, author and synopsis for the exported EPUB"),
    MISCELLANEOUS("Miscellaneous", "Status bar, keep awake")
}

/**
 * The reader's settings hub, opened from the three-dot menu and the headphones
 * area. Deliberately separate from the app's Settings screen: everything here is
 * about reading, and the metadata panel only affects an export, never the file on
 * disk or the library record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsHubSheet(
    onDismiss: () -> Unit,
    onOpenPanel: (ReaderSettingsPanel) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Reader Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            ReaderSettingsPanel.values().forEach { panel ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPanel(panel) }
                        .padding(vertical = 12.dp)
                        .testTag("reader_settings_${panel.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (panel) {
                            ReaderSettingsPanel.VISUAL -> Icons.Default.Palette
                            ReaderSettingsPanel.CONTROL -> Icons.Default.Tune
                            ReaderSettingsPanel.METADATA -> Icons.Default.Edit
                            ReaderSettingsPanel.MISCELLANEOUS -> Icons.Default.Settings
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(panel.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = panel.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Screen-behaviour switches: everything that is not typography. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderControlSheet(
    readerSettings: ReaderSettings,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Control", style = MaterialTheme.typography.titleLarge)
            ReaderSwitchRow(
                title = "Volume Keys Turn Pages",
                subtitle = "Use Volume Down / Up to turn pages or scroll while reading",
                checked = readerSettings.volumeKeysTurnPages,
                testTag = "reader_control_volume_keys",
                onCheckedChange = { checked ->
                    onUpdateSettings { it.copy(volumeKeysTurnPages = checked) }
                }
            )
            ReaderSwitchRow(
                title = "Page Turn Sound",
                subtitle = "A short paper rustle when a page turns",
                checked = readerSettings.pageTurnSound,
                testTag = "reader_control_page_turn_sound",
                onCheckedChange = { checked ->
                    onUpdateSettings { it.copy(pageTurnSound = checked) }
                }
            )
        }
    }
}

/** Status bar and screen-on, kept out of the typography panel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderMiscSheet(
    readerSettings: ReaderSettings,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Miscellaneous", style = MaterialTheme.typography.titleLarge)
            ReaderSwitchRow(
                title = "Show System Notification Bar",
                subtitle = "Keep the status bar visible while reading. Off hides it for the page only.",
                checked = readerSettings.showSystemStatusBar,
                testTag = "reader_misc_status_bar",
                onCheckedChange = { checked ->
                    onUpdateSettings { it.copy(showSystemStatusBar = checked) }
                }
            )
            ReaderSwitchRow(
                title = "Keep Screen Awake",
                subtitle = "Stop the screen from sleeping while a book is open",
                checked = readerSettings.keepScreenAwake,
                testTag = "reader_misc_keep_awake",
                onCheckedChange = { checked ->
                    onUpdateSettings { it.copy(keepScreenAwake = checked) }
                }
            )
        }
    }
}

@Composable
private fun ReaderSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

/** Everything the metadata editor can set. Only non-blank values reach the OPF. */
data class ReaderMetadataDraft(
    val title: String = "",
    val subtitle: String = "",
    val author: String = "",
    val series: String = "",
    val seriesIndex: String = "",
    val publisher: String = "",
    val language: String = "",
    val date: String = "",
    val identifier: String = "",
    val rights: String = "",
    val subjects: String = "",
    val synopsis: String = ""
)

/**
 * Metadata for the exported copy only. The EPUB in your library is never
 * rewritten: these values are written into the OPF of the file produced by
 * "Export Edited EPUB", so the original stays exactly as downloaded.
 */
@Composable
fun ReaderMetadataDialog(
    initial: ReaderMetadataDraft,
    coverPreviewModel: Any?,
    hasPickedCover: Boolean,
    onPickCover: () -> Unit,
    onClearCover: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (ReaderMetadataDraft) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Bookmarks, contentDescription = null) },
        title = { Text("Export Metadata") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Written into the exported EPUB only. The file in your library is not changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (coverPreviewModel != null) {
                        coil.compose.AsyncImage(
                            model = coverPreviewModel,
                            contentDescription = "Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cover Artwork", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = onPickCover,
                                modifier = Modifier.testTag("reader_metadata_pick_cover")
                            ) { Text(if (coverPreviewModel != null) "Change" else "Choose") }
                            if (hasPickedCover) {
                                TextButton(
                                    onClick = onClearCover,
                                    modifier = Modifier.testTag("reader_metadata_clear_cover")
                                ) { Text("Undo") }
                            }
                        }
                    }
                }

                HorizontalDivider()

                ReaderMetadataField("Title", draft.title, "reader_metadata_title") {
                    draft = draft.copy(title = it)
                }
                ReaderMetadataField("Subtitle", draft.subtitle, "reader_metadata_subtitle") {
                    draft = draft.copy(subtitle = it)
                }
                ReaderMetadataField("Author", draft.author, "reader_metadata_author") {
                    draft = draft.copy(author = it)
                }
                ReaderMetadataField("Series", draft.series, "reader_metadata_series") {
                    draft = draft.copy(series = it)
                }
                ReaderMetadataField("Series Number", draft.seriesIndex, "reader_metadata_series_index") {
                    draft = draft.copy(seriesIndex = it)
                }
                ReaderMetadataField("Publisher", draft.publisher, "reader_metadata_publisher") {
                    draft = draft.copy(publisher = it)
                }
                ReaderMetadataField("Language", draft.language, "reader_metadata_language") {
                    draft = draft.copy(language = it)
                }
                ReaderMetadataField("Published Date", draft.date, "reader_metadata_date") {
                    draft = draft.copy(date = it)
                }
                ReaderMetadataField("Identifier (ISBN / UUID)", draft.identifier, "reader_metadata_identifier") {
                    draft = draft.copy(identifier = it)
                }
                ReaderMetadataField("Rights", draft.rights, "reader_metadata_rights") {
                    draft = draft.copy(rights = it)
                }
                ReaderMetadataField("Subjects (comma separated)", draft.subjects, "reader_metadata_subjects") {
                    draft = draft.copy(subjects = it)
                }
                OutlinedTextField(
                    value = draft.synopsis,
                    onValueChange = { draft = draft.copy(synopsis = it) },
                    label = { Text("Synopsis") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reader_metadata_synopsis")
                )
                Text(
                    text = "Blank fields leave the original value untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft) },
                modifier = Modifier.testTag("reader_metadata_export")
            ) { Text("Export") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ReaderMetadataField(
    label: String,
    value: String,
    tag: String,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    )
}

/**
 * Result of the post-export structural check. Errors are shown first, because
 * they are the ones that make a reader refuse the file.
 */
@Composable
fun ReaderValidationDialog(
    report: com.example.export.EpubValidationReport,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (report.isClean) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (report.isClean) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        },
        title = { Text(if (report.isClean) "Export looks valid" else "Export has problems") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(report.summary(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Checked with " + report.engine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                report.errors.take(25).forEach { issue ->
                    Text(
                        text = "• " + issue.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                report.warnings.take(25).forEach { issue ->
                    Text(
                        text = "• " + issue.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (report.errors.size > 25 || report.warnings.size > 25) {
                    Text(
                        text = "… more findings not shown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("reader_validation_ok")
            ) { Text("OK") }
        }
    )
}

/**
 * The EPUBCheck opt-in. The official engine is 21 MB, so it is never shipped in
 * the APK: the reader is offered it once, and the built-in structural checker
 * remains the default for anyone who declines.
 */
@Composable
fun ReaderEpubCheckPromptDialog(
    sizeLabel: String,
    busy: Boolean,
    progressText: String,
    onDownloadAndCheck: () -> Unit,
    onUseBuiltIn: () -> Unit,
    onSkip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onSkip() },
        icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
        title = { Text("Validate with EPUBCheck?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "EPUBCheck is the official W3C validator. It is downloaded once (" +
                        sizeLabel + ") and kept on this device, then reused for every export.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Optional. The built-in checker is instant and covers the structural " +
                        "basics — a broken manifest, a bad spine, malformed XHTML — but it is not " +
                        "a conformance check.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy || progressText.isNotBlank()) {
                    HorizontalDivider()
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (!busy) {
                    TextButton(
                        onClick = onUseBuiltIn,
                        modifier = Modifier.testTag("epubcheck_use_builtin")
                    ) { Text("Use the built-in checker instead") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDownloadAndCheck,
                enabled = !busy,
                modifier = Modifier.testTag("epubcheck_download")
            ) { Text(if (busy) "Downloading…" else "Download & check") }
        },
        dismissButton = {
            TextButton(
                onClick = onSkip,
                enabled = !busy,
                modifier = Modifier.testTag("epubcheck_skip")
            ) { Text("Skip") }
        }
    )
}
