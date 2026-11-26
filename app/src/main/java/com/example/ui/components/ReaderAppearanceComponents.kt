package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import com.example.data.db.BookmarkEntity
import com.example.data.prefs.ReaderFontFamily
import com.example.data.prefs.ReaderMode
import com.example.data.prefs.ReaderSettings
import com.example.data.prefs.ReaderTextAlign
import com.example.epub.EpubParser
import com.example.tts.TtsPlaybackState
import com.example.ui.theme.ReaderThemeMode
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ReaderAppearanceBottomBar(
    readerSettings: ReaderSettings,
    currentChapterIndex: Int,
    totalChapters: Int,
    currentPage: Int,
    totalPages: Int,
    isTtsActive: Boolean,
    onSelectChapter: (Int) -> Unit,
    onJumpToPage: (Int) -> Unit,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onOpenTypographySheet: () -> Unit,
    onOpenCustomThemeDialog: () -> Unit,
    onSwitchToAudioBar: () -> Unit,
    onOpenReaderSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val theme = readerSettings.themeMode
    val isCustom = theme == ReaderThemeMode.CUSTOM
    val effectiveChromeBg = remember(theme, readerSettings.customThemeBgHex) {
        if (isCustom) {
            runCatching { Color(android.graphics.Color.parseColor(readerSettings.customThemeBgHex)) }
                .getOrDefault(theme.chromeBg)
        } else theme.chromeBg
    }
    val effectiveText = remember(theme, readerSettings.customThemeTextHex) {
        if (isCustom) {
            runCatching { Color(android.graphics.Color.parseColor(readerSettings.customThemeTextHex)) }
                .getOrDefault(theme.text)
        } else theme.text
    }
    val isPaged = readerSettings.readingMode == ReaderMode.PAGED
    val scrubByPage = isPaged && totalPages > 1

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(28.dp),
        color = effectiveChromeBg,
        contentColor = effectiveText,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("reader_appearance_bottom_bar")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. Progress Scrubber with < and > step buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (currentChapterIndex > 0) onSelectChapter(currentChapterIndex - 1) },
                    enabled = currentChapterIndex > 0,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("reader_prev_chapter_button")
                ) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Chapter")
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val progressLabel = if (isPaged) {
                        "Page ${currentPage + 1} / ${totalPages.coerceAtLeast(1)}  •  Ch ${currentChapterIndex + 1}/${totalChapters.coerceAtLeast(1)}"
                    } else {
                        "Chapter ${currentChapterIndex + 1} / ${totalChapters.coerceAtLeast(1)}"
                    }
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("reader_progress_label")
                    )

                    if (scrubByPage) {
                        var dragPage by remember(currentPage, totalPages) { mutableFloatStateOf(currentPage.toFloat()) }
                        Slider(
                            value = dragPage.coerceIn(0f, (totalPages - 1).coerceAtLeast(1).toFloat()),
                            onValueChange = { dragPage = it },
                            onValueChangeFinished = {
                                val target = dragPage.roundToInt().coerceIn(0, totalPages - 1)
                                if (target != currentPage) onJumpToPage(target)
                            },
                            valueRange = 0f..(totalPages - 1).coerceAtLeast(1).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = theme.secondaryText.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .testTag("reader_progress_slider")
                        )
                    } else if (totalChapters > 1) {
                        var dragChapter by remember(currentChapterIndex, totalChapters) {
                            mutableFloatStateOf(currentChapterIndex.toFloat())
                        }
                        Slider(
                            value = dragChapter.coerceIn(0f, (totalChapters - 1).toFloat()),
                            onValueChange = { dragChapter = it },
                            onValueChangeFinished = {
                                val target = dragChapter.roundToInt().coerceIn(0, totalChapters - 1)
                                if (target != currentChapterIndex) onSelectChapter(target)
                            },
                            valueRange = 0f..(totalChapters - 1).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = theme.secondaryText.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .testTag("reader_progress_slider")
                        )
                    }
                }

                IconButton(
                    onClick = { if (currentChapterIndex < totalChapters - 1) onSelectChapter(currentChapterIndex + 1) },
                    enabled = currentChapterIndex < totalChapters - 1,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("reader_next_chapter_button")
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next Chapter")
                }
            }

            // 2. Single Compact Row: Circular Theme Color Dots + Custom Theme '+' Dot + Aa + Headphones
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    ReaderThemeMode.entries.forEach { mode ->
                        val selected = readerSettings.themeMode == mode
                        val swatchColor = if (mode == ReaderThemeMode.CUSTOM) {
                            runCatching { Color(android.graphics.Color.parseColor(readerSettings.customThemeBgHex)) }
                                .getOrDefault(mode.background)
                        } else mode.background
                        Box(
                            modifier = Modifier
                                .size(if (selected) 24.dp else 20.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else theme.secondaryText.copy(alpha = 0.45f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    onUpdateSettings { it.copy(themeMode = mode) }
                                }
                                .testTag("reader_theme_preset_${mode.name.lowercase()}")
                        )
                    }
                    // Custom Theme '+' Dot
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable(onClick = onOpenCustomThemeDialog)
                            .testTag("reader_custom_theme_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create Custom Theme",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onOpenTypographySheet,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("reader_bottom_appearance_sheet_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatSize,
                            contentDescription = "Typography & Layout"
                        )
                    }

                    IconButton(
                        onClick = onSwitchToAudioBar,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("reader_bottom_read_aloud_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = if (isTtsActive) "Audio Player" else "Read Aloud",
                            tint = if (isTtsActive) MaterialTheme.colorScheme.primary else theme.text
                        )
                    }

                    // Reader settings, beside the headphones: Visual, Control,
                    // Metadata, Miscellaneous.
                    IconButton(
                        onClick = onOpenReaderSettings,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("reader_bottom_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Reader Settings",
                            tint = theme.text
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderAudioPlayerDockedBar(
    ttsState: TtsPlaybackState,
    onOpenControlsSheet: () -> Unit,
    onPlayClick: () -> Unit,
    onCollapseToAppearance: () -> Unit,
    modifier: Modifier = Modifier
) {
    TtsDockedBar(
        ttsState = ttsState,
        onOpenControlsSheet = onOpenControlsSheet,
        onPlayClick = onPlayClick,
        onClose = onCollapseToAppearance,
        modifier = modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderTypographySheet(
    readerSettings: ReaderSettings,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onOpenCustomCssDialog: () -> Unit,
    onPickCustomFont: () -> Unit,
    importedFonts: List<String> = emptyList(),
    onSelectImportedFont: (String) -> Unit = {},
    onDeleteImportedFont: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Typography & Layout", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }

            // Follow Original Book Styling toggle
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Follow Original Book Styling",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Keep EPUB CSS, embedded fonts, drop caps & tables while adapting theme colors",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = readerSettings.usePublisherStyling,
                        onCheckedChange = { checked ->
                            onUpdateSettings { it.copy(usePublisherStyling = checked) }
                        },
                        modifier = Modifier.testTag("reader_publisher_styling_switch")
                    )
                }
            }

            // Screen and system-bar behaviour, next to the other reading settings.



            HorizontalDivider()

            // Font Size Slider + Steppers (12sp..36sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Font Size", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            onUpdateSettings { it.copy(fontSizeSp = (it.fontSizeSp - 1f).coerceIn(12f, 36f)) }
                        },
                        modifier = Modifier.testTag("sheet_font_minus")
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease Font Size")
                    }
                    Text(
                        text = "${readerSettings.fontSizeSp.roundToInt()} sp",
                        style = MaterialTheme.typography.labelLarge
                    )
                    IconButton(
                        onClick = {
                            onUpdateSettings { it.copy(fontSizeSp = (it.fontSizeSp + 1f).coerceIn(12f, 36f)) }
                        },
                        modifier = Modifier.testTag("sheet_font_plus")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase Font Size")
                    }
                }
            }
            Slider(
                value = readerSettings.fontSizeSp.coerceIn(12f, 36f),
                onValueChange = { sz -> onUpdateSettings { it.copy(fontSizeSp = sz) } },
                valueRange = 12f..36f,
                modifier = Modifier.testTag("sheet_font_size_slider")
            )

            // Font Family & Custom Font Picker Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Font Family", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(
                    onClick = onPickCustomFont,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("pick_custom_font_button")
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import .ttf / .otf")
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderFontFamily.entries.forEach { family ->
                    FilterChip(
                        selected = readerSettings.fontFamily == family,
                        onClick = { onUpdateSettings { it.copy(fontFamily = family) } },
                        leadingIcon = if (readerSettings.fontFamily == family) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        label = {
                            Text(
                                if (family == ReaderFontFamily.CUSTOM && readerSettings.customFontName.isNotBlank())
                                    readerSettings.customFontName
                                else family.label
                            )
                        },
                        modifier = Modifier.testTag("font_family_${family.name.lowercase()}")
                    )
                }
            }
            if (importedFonts.isNotEmpty()) {
                Text(
                    text = "Imported Font Files",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    importedFonts.forEach { fontName ->
                        val isActiveFont = readerSettings.fontFamily == ReaderFontFamily.CUSTOM &&
                            readerSettings.customFontName == fontName
                        FilterChip(
                            selected = isActiveFont,
                            onClick = { onSelectImportedFont(fontName) },
                            label = { Text(fontName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete $fontName",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onDeleteImportedFont(fontName) }
                                )
                            },
                            modifier = Modifier.testTag("imported_font_chip_$fontName")
                        )
                    }
                }
            }

            // Line Spacing (1.0x..2.2x)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Line Spacing", style = MaterialTheme.typography.titleSmall)
                Text(String.format(Locale.US, "%.2fx", readerSettings.lineSpacing), style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = readerSettings.lineSpacing.coerceIn(1.0f, 2.2f),
                onValueChange = { sp -> onUpdateSettings { it.copy(lineSpacing = sp) } },
                valueRange = 1.0f..2.2f
            )

            // Paragraph Spacing (0.0em..2.0em)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Paragraph Spacing", style = MaterialTheme.typography.titleSmall)
                Text(String.format(Locale.US, "%.2f em", readerSettings.paragraphSpacingEm), style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = readerSettings.paragraphSpacingEm.coerceIn(0f, 2.0f),
                onValueChange = { ps -> onUpdateSettings { it.copy(paragraphSpacingEm = ps) } },
                valueRange = 0.0f..2.0f
            )

            // Horizontal Margins (8dp..48dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Horizontal Margins", style = MaterialTheme.typography.titleSmall)
                Text("${readerSettings.horizontalMarginDp} dp", style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = readerSettings.horizontalMarginDp.toFloat().coerceIn(8f, 48f),
                onValueChange = { m -> onUpdateSettings { it.copy(horizontalMarginDp = m.roundToInt()) } },
                valueRange = 8f..48f
            )

            // Text Alignment
            Text("Text Alignment", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderTextAlign.entries.forEach { align ->
                    FilterChip(
                        selected = readerSettings.textAlign == align,
                        onClick = { onUpdateSettings { it.copy(textAlign = align) } },
                        leadingIcon = {
                            val icon = when (align) {
                                ReaderTextAlign.JUSTIFY -> Icons.Default.FormatAlignJustify
                                else -> Icons.AutoMirrored.Filled.FormatAlignLeft
                            }
                            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text(align.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider()

            // Screen Brightness
            val isSystemBrightness = readerSettings.brightnessLevel < 0f
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isSystemBrightness) Icons.Default.BrightnessAuto else Icons.Default.BrightnessHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Brightness", style = MaterialTheme.typography.titleSmall)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isSystemBrightness) "System" else "${(readerSettings.brightnessLevel * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FilterChip(
                        selected = isSystemBrightness,
                        onClick = {
                            onUpdateSettings {
                                it.copy(brightnessLevel = if (isSystemBrightness) 0.7f else -1f)
                            }
                        },
                        label = { Text("Auto") }
                    )
                }
            }
            Slider(
                value = if (isSystemBrightness) 0.7f else readerSettings.brightnessLevel.coerceIn(0.05f, 1.0f),
                onValueChange = { b -> onUpdateSettings { it.copy(brightnessLevel = b) } },
                valueRange = 0.05f..1.0f
            )

            // Custom CSS Editor Button
            OutlinedButton(
                onClick = onOpenCustomCssDialog,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("reader_open_custom_css_button")
            ) {
                Icon(Icons.Default.Code, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                val hasCss = readerSettings.customCss.isNotBlank()
                Text(if (hasCss) "Edit Custom CSS (Active)" else "Custom CSS Editor")
            }
        }
    }
}

@Composable
fun CustomThemeCreatorDialog(
    initialBg: String,
    initialText: String,
    initialLink: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var bgHex by remember { mutableStateOf(initialBg) }
    var textHex by remember { mutableStateOf(initialText) }
    var linkHex by remember { mutableStateOf(initialLink) }

    val bgPresets = listOf("#FFFDF9", "#F7F1E3", "#FAF5E8", "#E6EFE2", "#1B1E24", "#000000")
    val textPresets = listOf("#171A21", "#2B2118", "#37474F", "#E6EAF0", "#D7DCE2", "#C9D1D9")
    val linkPresets = listOf("#1D60A6", "#8B4513", "#2E7D32", "#7C4DFF", "#8CC4FF", "#FFB74D")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Custom Theme") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = bgHex,
                    onValueChange = { bgHex = it },
                    label = { Text("Background Hex (e.g. #FFFDF9)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bgPresets.forEach { hex ->
                        val c = remember(hex) { Color(android.graphics.Color.parseColor(hex)) }
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (bgHex.equals(hex, ignoreCase = true)) 2.5.dp else 1.dp,
                                    color = if (bgHex.equals(hex, ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable { bgHex = hex }
                        )
                    }
                }
                OutlinedTextField(
                    value = textHex,
                    onValueChange = { textHex = it },
                    label = { Text("Text Color Hex (e.g. #171A21)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    textPresets.forEach { hex ->
                        val c = remember(hex) { Color(android.graphics.Color.parseColor(hex)) }
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (textHex.equals(hex, ignoreCase = true)) 2.5.dp else 1.dp,
                                    color = if (textHex.equals(hex, ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable { textHex = hex }
                        )
                    }
                }
                OutlinedTextField(
                    value = linkHex,
                    onValueChange = { linkHex = it },
                    label = { Text("Link Color Hex (e.g. #1D60A6)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    linkPresets.forEach { hex ->
                        val c = remember(hex) { Color(android.graphics.Color.parseColor(hex)) }
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (linkHex.equals(hex, ignoreCase = true)) 2.5.dp else 1.dp,
                                    color = if (linkHex.equals(hex, ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable { linkHex = hex }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Preview:", style = MaterialTheme.typography.labelMedium)
                val previewBg = runCatching { Color(android.graphics.Color.parseColor(bgHex.ifBlank { "#FFFDF9" })) }.getOrDefault(Color(0xFFFFFDF9))
                val previewText = runCatching { Color(android.graphics.Color.parseColor(textHex.ifBlank { "#171A21" })) }.getOrDefault(Color(0xFF171A21))
                val previewLink = runCatching { Color(android.graphics.Color.parseColor(linkHex.ifBlank { "#1D60A6" })) }.getOrDefault(Color(0xFF1D60A6))

                Surface(
                    color = previewBg,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, previewText.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Sample Heading", style = MaterialTheme.typography.titleMedium, color = previewText)
                        Spacer(Modifier.height(4.dp))
                        Text("This is how body text and links will look in your custom reading theme.", style = MaterialTheme.typography.bodyMedium, color = previewText)
                        Spacer(Modifier.height(4.dp))
                        Text("Sample Link", style = MaterialTheme.typography.bodyMedium, color = previewLink)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(bgHex, textHex, linkHex) },
                modifier = Modifier.testTag("custom_theme_save_button")
            ) {
                Text("Save & Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomCssEditorDialog(
    initialCss: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var draftCss by remember(initialCss) { mutableStateOf(initialCss) }

    val snippets = listOf(
        "Paragraph Indent" to "p {\n  text-indent: 1.5em;\n  margin-bottom: 0.35em;\n}",
        "Drop Caps" to "p:first-of-type::first-letter {\n  font-size: 2.8em;\n  float: left;\n  line-height: 0.9;\n  padding-right: 6px;\n  font-weight: bold;\n}",
        "Justify Text" to "p, li {\n  text-align: justify;\n  hyphens: auto;\n}",
        "Image Max-Width" to "img, svg {\n  max-width: 100% !important;\n  height: auto !important;\n}"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Code, contentDescription = null) },
        title = { Text("Custom Reader CSS") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Applied as high-priority styles on top of every chapter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Quick Snippets:", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    snippets.forEach { (label, snippet) ->
                        AssistChip(
                            onClick = {
                                draftCss = if (draftCss.isBlank()) snippet else "$draftCss\n\n$snippet"
                            },
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }
                OutlinedTextField(
                    value = draftCss,
                    onValueChange = { draftCss = it },
                    placeholder = { Text("/* e.g. p { text-indent: 1.5em; } */") },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_css_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draftCss) },
                modifier = Modifier.testTag("custom_css_save_button")
            ) {
                Text("Save CSS")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (draftCss.isNotBlank()) {
                    TextButton(
                        onClick = { draftCss = "" },
                        modifier = Modifier.testTag("custom_css_clear_button")
                    ) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSelectionActionBar(
    selectedText: String,
    canReadFromHere: Boolean,
    onHighlightColor: (String) -> Unit,
    onAddNote: () -> Unit = {},
    onReadAloudFromHere: () -> Unit,
    onDefineSelection: () -> Unit,
    onAddPronunciationRule: () -> Unit,
    onCopySelection: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onShareSelection: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val highlightSwatches = listOf(
        "#FFEB3B" to "Yellow",
        "#A5D6A7" to "Green",
        "#90CAF9" to "Blue",
        "#F48FB1" to "Pink"
    )
    var showMoreMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 10.dp,
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .widthIn(max = 360.dp)
            .testTag("reader_text_selection_bar")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            highlightSwatches.forEach { (hex, label) ->
                val swatchColor = remember(hex) { Color(android.graphics.Color.parseColor(hex)) }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { onHighlightColor(hex) }
                        .testTag("highlight_color_${label.lowercase()}")
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), CircleShape)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(1.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            if (canReadFromHere) {
                // Note sits next to Highlight, the way Moon+ orders its menu:
                // the colour swatches highlight, this one highlights and opens a note.
                TextButton(
                    onClick = onAddNote,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("selection_add_note_button")
                ) {
                    Text("Note", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }

                Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { onReadAloudFromHere() }
            .padding(horizontal = 6.dp, vertical = 5.dp) // Total width = 27.dp (~2 letters wide)
            .testTag("selection_read_aloud_button")
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Read from here",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(15.dp)
        )
                    }
                
            }

            if (selectedText.length <= 280) {
                TextButton(
                    onClick = onDefineSelection,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("selection_define_button")
                ) {
                    Text("Define", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }

                TextButton(
                    onClick = onAddPronunciationRule,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("reader_add_rule_from_selection_button")
                ) {
                    Text("Edit", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }

            Box {
                IconButton(
                    onClick = { showMoreMenu = true },
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("selection_more_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More selection actions",
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                        },
                        onClick = {
                            showMoreMenu = false
                            onCopySelection()
                        },
                        modifier = Modifier.testTag("selection_copy_item")
                    )
                    DropdownMenuItem(
                        text = { Text("Select All") },
                        leadingIcon = {
                            Icon(Icons.Default.SelectAll, contentDescription = null)
                        },
                        onClick = {
                            showMoreMenu = false
                            onSelectAll()
                        },
                        modifier = Modifier.testTag("selection_select_all_item")
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = {
                            Icon(Icons.Default.Share, contentDescription = null)
                        },
                        onClick = {
                            showMoreMenu = false
                            onShareSelection()
                        },
                        modifier = Modifier.testTag("selection_share_item")
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksAndHighlightsSheet(
    items: List<BookmarkEntity>,
    onJumpToItem: (BookmarkEntity) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Bookmarks & Highlights"
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "$title (${items.size})",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            if (items.isEmpty()) {
                Text(
                    text = "Nothing here yet. Select a passage in the text and tap Highlight or Note.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(min = 120.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.id }) { entry ->
                        val accent = remember(entry.colorHex) {
                            runCatching { Color(android.graphics.Color.parseColor(entry.colorHex)) }
                                .getOrDefault(Color(0xFFFFEB3B))
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpToItem(entry) }
                                .testTag("bookmark_item_${entry.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(6.dp)
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(accent)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (entry.type == "BOOKMARK") {
                                            Icon(
                                                imageVector = Icons.Default.Bookmark,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                        Text(
                                            text = "Ch ${entry.chapterIndex + 1} • ${entry.note.ifBlank { entry.type }}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = entry.selectedText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteItem(entry.id) },
                                    modifier = Modifier.testTag("delete_bookmark_${entry.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InBookSearchSheet(
    query: String,
    isSearching: Boolean,
    results: List<EpubParser.InBookSearchResult>,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onJumpToResult: (EpubParser.InBookSearchResult) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Search in Book",
                style = MaterialTheme.typography.titleLarge
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Enter at least 2 characters...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("in_book_search_input")
                )
                Button(
                    onClick = onSearchSubmit,
                    enabled = query.trim().length >= 2 && !isSearching,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("in_book_search_submit_button")
                ) {
                    Text("Find")
                }
            }
            if (isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!isSearching && results.isEmpty() && query.trim().length >= 2) {
                Text(
                    text = "No matches found in this book.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else if (results.isNotEmpty()) {
                Text(
                    text = "${results.size} match(es)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                LazyColumn(
                    modifier = Modifier.heightIn(min = 120.dp, max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results) { hit ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpToResult(hit) }
                                .testTag("search_hit_${hit.chapterIndex}_${hit.sentenceIndex}")
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Ch ${hit.chapterIndex + 1} • ${hit.chapterTitle}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = hit.snippet,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
