package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.LexiVoiceApplication
import com.example.export.ExportStateHolder
import com.example.overlay.FloatingOverlayController
import com.example.overlay.FloatingOverlayState
import com.example.overlay.OverlayStyleHelper
import com.example.overlay.UnifiedOverlaySource
import com.example.tts.SleepTimerMode
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsPlaybackState
import com.example.tts.TtsSourceType
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TtsDockedBar(
    ttsState: TtsPlaybackState,
    onOpenControlsSheet: () -> Unit,
    onPlayClick: () -> Unit,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (ttsState.totalSentencesInChapter > 0) {
                LinearProgressIndicator(
                    progress = {
                        ((ttsState.sentenceIndex + 1).toFloat() / ttsState.totalSentencesInChapter.toFloat())
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenControlsSheet() }
                ) {
                    Text(
                        text = ttsState.chapterTitle.ifBlank { ttsState.bookTitle.ifBlank { "Ready to Speak" } },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(String.format(Locale.US, "%.1fx", ttsState.speechRate))
                            if (ttsState.sleepTimerMode == SleepTimerMode.END_OF_CHAPTER) {
                                append(" • Sleep: Ch End")
                            } else if (ttsState.sleepTimerMode == SleepTimerMode.MINUTES) {
                                val mins = (ttsState.sleepRemainingMs / 60000L) + 1
                                append(" • Sleep: ${mins}m")
                            }
                            if (ttsState.selectedLocaleTag.isNotBlank()) {
                                append(" • ${ttsState.selectedLocaleTag}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val ctx = LocalContext.current
                val compactBtnMod = if (onClose != null) Modifier.size(34.dp) else Modifier
                if (ttsState.sourceType == TtsSourceType.BOOK) {
                    IconButton(
                        onClick = { TtsPlaybackService.sendAction(ctx, TtsPlaybackService.ACTION_PREV_CHAPTER) },
                        modifier = compactBtnMod.testTag("tts_prev_chapter_button")
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Chapter")
                    }
                }
                IconButton(
                    onClick = { TtsPlaybackService.sendAction(ctx, TtsPlaybackService.ACTION_PREV_SENTENCE) },
                    modifier = compactBtnMod.testTag("tts_prev_sentence_button")
                ) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Previous Sentence")
                }

                FilledIconButton(
                    onClick = onPlayClick,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("tts_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (ttsState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (ttsState.isPlaying) "Pause TTS" else "Play TTS"
                    )
                }

                IconButton(
                    onClick = { TtsPlaybackService.sendAction(ctx, TtsPlaybackService.ACTION_NEXT_SENTENCE) },
                    modifier = compactBtnMod.testTag("tts_next_sentence_button")
                ) {
                    Icon(Icons.Default.FastForward, contentDescription = "Next Sentence")
                }

                if (ttsState.sourceType == TtsSourceType.BOOK) {
                    IconButton(
                        onClick = { TtsPlaybackService.sendAction(ctx, TtsPlaybackService.ACTION_NEXT_CHAPTER) },
                        modifier = compactBtnMod.testTag("tts_next_chapter_button")
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter")
                    }
                }

                IconButton(
                    onClick = onOpenControlsSheet,
                    modifier = compactBtnMod.testTag("tts_open_settings_sheet_button")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Engine, Language, Voice & Sleep Timer")
                }

                if (onClose != null) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("reader_close_audio_bar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Return to Reader Appearance Bar"
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TtsControlsModalSheet(
    ttsState: TtsPlaybackState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as LexiVoiceApplication
    val prefsRepo = app.preferencesRepository
    val scope = rememberCoroutineScope()

    var showEngineDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showExportTtsConflictDialog by remember { mutableStateOf(false) }
    var customMinutesInput by remember { mutableStateOf("20") }
    val overlayState by FloatingOverlayController.state.collectAsStateWithLifecycle()
    val exportProgress by ExportStateHolder.state.collectAsStateWithLifecycle()
    val canDrawOverlay = remember(overlayState.isVisible) {
        Settings.canDrawOverlays(context)
    }

    // Filtered voices strictly matching the chosen Language / Locale
    val filteredVoices = remember(ttsState.availableVoices, ttsState.selectedLocaleTag) {
        val tag = ttsState.selectedLocaleTag
        if (tag.isBlank()) {
            ttsState.availableVoices
        } else {
            val exact = ttsState.availableVoices.filter { it.localeTag.equals(tag, ignoreCase = true) }
            if (exact.isNotEmpty()) exact
            else {
                val baseLang = tag.substringBefore('-').substringBefore('_')
                ttsState.availableVoices.filter {
                    it.localeTag.startsWith(baseLang, ignoreCase = true)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Speech Engine & Playback Controls",
                style = MaterialTheme.typography.titleLarge
            )
            if (exportProgress.isRunning) {
                Spacer(Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tts_sheet_export_warning_card")
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Audio export running (${exportProgress.batchProgressPercent}%) — avoid playing TTS so both don't collide on the speech engine.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // Transport row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_PREV_CHAPTER) }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Prev Chapter")
                }
                IconButton(onClick = { TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_PREV_SENTENCE) }) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Back Sentence")
                }
                FilledIconButton(
                    onClick = {
                        if (exportProgress.isRunning && !ttsState.isPlaying) {
                            showExportTtsConflictDialog = true
                        } else {
                            TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
                        }
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        if (ttsState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play or Pause"
                    )
                }
                IconButton(onClick = { TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_NEXT_SENTENCE) }) {
                    Icon(Icons.Default.FastForward, contentDescription = "Next Sentence")
                }
                IconButton(onClick = { TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_NEXT_CHAPTER) }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter")
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            // Speech Rate
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Speech Rate", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = String.format(Locale.US, "%.2fx", ttsState.speechRate),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = ttsState.speechRate,
                onValueChange = { rate ->
                    TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_RATE) {
                        putExtra(TtsPlaybackService.EXTRA_FLOAT, rate)
                    }
                    scope.launch { prefsRepo.updateTtsSettings { it.copy(speechRate = rate) } }
                },
                valueRange = 0.5f..3.0f,
                modifier = Modifier.testTag("tts_rate_slider")
            )

            // Pitch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Voice Pitch", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = String.format(Locale.US, "%.2fx", ttsState.pitch),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = ttsState.pitch,
                onValueChange = { pitch ->
                    TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_PITCH) {
                        putExtra(TtsPlaybackService.EXTRA_FLOAT, pitch)
                    }
                    scope.launch { prefsRepo.updateTtsSettings { it.copy(pitch = pitch) } }
                },
                valueRange = 0.5f..2.0f,
                modifier = Modifier.testTag("tts_pitch_slider")
            )

            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // Step 1 -> Step 2 -> Step 3: Engine -> Language -> Voice Hierarchy
            Text("1. Speech Engine", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val selectedEngineLabel = ttsState.availableEngines
                .firstOrNull { it.packageName == ttsState.selectedEnginePackage }
                ?.label ?: ttsState.selectedEnginePackage.ifBlank { "System Default TTS Engine" }
            OutlinedButton(
                onClick = {
                    TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_INIT_ENGINES)
                    showEngineDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tts_select_engine_button")
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(selectedEngineLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(10.dp))
            Text("2. Language / Locale", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val selectedLangLabel = ttsState.availableLanguages
                .firstOrNull { it.localeTag.equals(ttsState.selectedLocaleTag, ignoreCase = true) }
                ?.let { "${it.displayName} (${it.voiceCount} voices)" }
                ?: ttsState.selectedLocaleTag.ifBlank { "Choose Language" }
            OutlinedButton(
                onClick = { showLanguageDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tts_select_language_button")
            ) {
                Icon(Icons.Default.Language, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(selectedLangLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(10.dp))
            Text("3. Voice (${filteredVoices.size} in selected language)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val voiceDisplay = filteredVoices
                .firstOrNull { it.name == ttsState.selectedVoiceName }
                ?.let { "${it.displayLocale} • ${it.name.takeLast(18)}" }
                ?: "Default Voice (${filteredVoices.size} available)"
            OutlinedButton(
                onClick = { showVoiceDialog = true },
                enabled = filteredVoices.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tts_select_voice_button")
            ) {
                Text(voiceDisplay, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            // Sleep Timer
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Sleep Timer", style = MaterialTheme.typography.titleSmall)
                if (ttsState.sleepTimerMode == SleepTimerMode.MINUTES) {
                    Spacer(Modifier.width(8.dp))
                    val mins = ttsState.sleepRemainingMs / 60000L
                    val secs = (ttsState.sleepRemainingMs % 60000L) / 1000L
                    Text(
                        text = String.format(Locale.US, "(%02d:%02d left)", mins, secs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = ttsState.sleepTimerMode == SleepTimerMode.OFF,
                    onClick = {
                        TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_SLEEP_TIMER) {
                            putExtra(TtsPlaybackService.EXTRA_STRING, SleepTimerMode.OFF.name)
                        }
                    },
                    label = { Text("Off") }
                )
                FilterChip(
                    selected = ttsState.sleepTimerMode == SleepTimerMode.END_OF_CHAPTER,
                    onClick = {
                        TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_SLEEP_TIMER) {
                            putExtra(TtsPlaybackService.EXTRA_STRING, SleepTimerMode.END_OF_CHAPTER.name)
                        }
                    },
                    label = { Text("End of Chapter") }
                )
                listOf(15, 30, 45).forEach { mins ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            customMinutesInput = mins.toString()
                            TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_SLEEP_TIMER) {
                                putExtra(TtsPlaybackService.EXTRA_STRING, SleepTimerMode.MINUTES.name)
                                putExtra(TtsPlaybackService.EXTRA_INT, mins)
                            }
                        },
                        label = { Text("${mins}m") }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customMinutesInput,
                    onValueChange = { input ->
                        customMinutesInput = input.filter { it.isDigit() }.take(3)
                    },
                    label = { Text("Custom minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sleep_timer_minutes_input")
                )
                Button(
                    onClick = {
                        val mins = customMinutesInput.toIntOrNull()?.coerceIn(1, 600) ?: 15
                        TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_SLEEP_TIMER) {
                            putExtra(TtsPlaybackService.EXTRA_STRING, SleepTimerMode.MINUTES.name)
                            putExtra(TtsPlaybackService.EXTRA_INT, mins)
                        }
                    },
                    modifier = Modifier.testTag("sleep_timer_set_button")
                ) {
                    Text("Set Timer")
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            // Compact Floating Overlay Mode
            Text("Compact Floating Overlay", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        FloatingOverlayController.setVisible(context, !overlayState.isVisible)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("toggle_floating_popup_button")
                ) {
                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (overlayState.isVisible) "Hide Floating Bar" else "Show Floating Bar")
                }

                if (!canDrawOverlay) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Text("Allow Overlay")
                    }
                }
            }
        }
    }

    // Step 1 Dialog: Engine
    if (showEngineDialog) {
        AlertDialog(
            onDismissRequest = { showEngineDialog = false },
            title = { Text("Select TTS Engine") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(ttsState.availableEngines, key = { it.packageName }) { engine ->
                        val selected = engine.packageName == ttsState.selectedEnginePackage
                        ListItem(
                            headlineContent = { Text(engine.label) },
                            supportingContent = { Text(engine.packageName, style = MaterialTheme.typography.bodySmall) },
                            trailingContent = {
                                if (selected) Icon(Icons.Default.Check, contentDescription = "Selected")
                            },
                            modifier = Modifier.clickable {
                                showEngineDialog = false
                                TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_ENGINE) {
                                    putExtra(TtsPlaybackService.EXTRA_STRING, engine.packageName)
                                }
                                scope.launch {
                                    prefsRepo.updateTtsSettings {
                                        it.copy(enginePackage = engine.packageName, voiceName = "", localeTag = "")
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEngineDialog = false }) { Text("Close") }
            }
        )
    }

    // Step 2 Dialog: Scrollable Language / Locale Picker (Device default + English pinned at top)
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("Select Language") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(ttsState.availableLanguages, key = { it.localeTag }) { lang ->
                        val selected = lang.localeTag.equals(ttsState.selectedLocaleTag, ignoreCase = true)
                        ListItem(
                            headlineContent = { Text(lang.displayName) },
                            supportingContent = {
                                Text("${lang.localeTag} • ${lang.voiceCount} voice(s)")
                            },
                            trailingContent = {
                                if (selected) Icon(Icons.Default.Check, contentDescription = "Selected")
                            },
                            modifier = Modifier.clickable {
                                showLanguageDialog = false
                                TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_LANGUAGE) {
                                    putExtra(TtsPlaybackService.EXTRA_LOCALE, lang.localeTag)
                                }
                                scope.launch {
                                    prefsRepo.updateTtsSettings {
                                        it.copy(localeTag = lang.localeTag, voiceName = "")
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text("Close") }
            }
        )
    }

    // Step 3 Dialog: Scrollable Voice Picker strictly filtered to selected Language
    if (showVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false },
            title = { Text("Select Voice (${ttsState.selectedLocaleTag})") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(filteredVoices, key = { it.name }) { voice ->
                        val selected = voice.name == ttsState.selectedVoiceName
                        ListItem(
                            headlineContent = { Text(voice.name) },
                            supportingContent = {
                                Text(
                                    "${voice.displayLocale} • " +
                                        if (voice.isNetworkRequired) "Network voice" else "Offline local voice"
                                )
                            },
                            trailingContent = {
                                if (selected) Icon(Icons.Default.Check, contentDescription = "Selected")
                            },
                            modifier = Modifier.clickable {
                                showVoiceDialog = false
                                TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_VOICE) {
                                    putExtra(TtsPlaybackService.EXTRA_STRING, voice.name)
                                    putExtra(TtsPlaybackService.EXTRA_LOCALE, voice.localeTag)
                                }
                                scope.launch {
                                    prefsRepo.updateTtsSettings {
                                        it.copy(voiceName = voice.name, localeTag = voice.localeTag)
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoiceDialog = false }) { Text("Close") }
            }
        )
    }

    if (showExportTtsConflictDialog) {
        AlertDialog(
            onDismissRequest = { showExportTtsConflictDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Audio Export in Progress") },
            text = {
                Text(
                    "Background audio export and live TTS playback share the Android TextToSpeech engine. " +
                        "Starting TTS now may degrade exported audio quality or skip sentences."
                )
            },
            confirmButton = {
                TextButton(onClick = { showExportTtsConflictDialog = false }) {
                    Text("Wait for Export")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportTtsConflictDialog = false
                        TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
                    }
                ) {
                    Text("Play Anyway")
                }
            }
        )
    }
}

/**
 * Compact T2S-style in-app floating overlay fallback when SYSTEM_ALERT_WINDOW is not granted:
 * Universal across live TTS and Audiobook playback, supports custom background/border colors,
 * draggable 48dp circular Bubble Minimize mode, clickable 2-line sentence preview (12sp)
 * that jumps to Reader or Synced Audiobook Read-Along, Minimize + Close, and 36dp controls.
 */
@Composable
fun InAppFloatingTtsPopup(
    overlayState: FloatingOverlayState,
    onTapSentence: (FloatingOverlayState) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!overlayState.isVisible) return
    val context = LocalContext.current
    if (Settings.canDrawOverlays(context)) return

    val resolvedColors = remember(overlayState.appearanceSettings) {
        OverlayStyleHelper.resolveColors(overlayState.appearanceSettings)
    }
    val bgColor = Color(resolvedColors.backgroundArgb)
    val borderStroke = if (resolvedColors.borderWidthDp > 0) {
        BorderStroke(resolvedColors.borderWidthDp.dp, Color(resolvedColors.borderArgb))
    } else null
    val primaryTextColor = Color(resolvedColors.primaryTextArgb)
    val secondaryIconColor = Color(resolvedColors.secondaryIconArgb)

    var offsetX by remember { mutableFloatStateOf(24f) }
    var offsetY by remember { mutableFloatStateOf(140f) }
    var bubbleOffsetX by remember { mutableFloatStateOf(24f) }
    var bubbleOffsetY by remember { mutableFloatStateOf(220f) }

    if (overlayState.isTemporarilyHidden) {
        Box(
            modifier = modifier
                .offset { IntOffset(bubbleOffsetX.roundToInt(), bubbleOffsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        bubbleOffsetX += dragAmount.x
                        bubbleOffsetY += dragAmount.y
                    }
                }
        ) {
            Surface(
                color = bgColor,
                shape = CircleShape,
                border = borderStroke,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .size(48.dp)
                    .clickable {
                        FloatingOverlayController.setTemporarilyHidden(false)
                    }
                    .testTag("floating_popup_restore_tab")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (overlayState.isPlaying) Icons.Default.GraphicEq else Icons.Rounded.PlayArrow,
                        contentDescription = "Expand floating playback bubble",
                        tint = primaryTextColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    } else {
        Box(
            modifier = modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = bgColor,
                border = borderStroke,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .width(260.dp)
                    .testTag("in_app_floating_tts_popup")
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val canJump = overlayState.activeSource != UnifiedOverlaySource.NONE
                        Text(
                            text = overlayState.displayText,
                            color = primaryTextColor,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = canJump) {
                                    onTapSentence(overlayState)
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .testTag("floating_popup_sentence_text")
                        )
                        IconButton(
                            onClick = {
                                FloatingOverlayController.setTemporarilyHidden(true)
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("floating_popup_temp_hide_button")
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = "Minimize to floating bubble",
                                tint = secondaryIconColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                FloatingOverlayController.setVisible(context, false)
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("floating_popup_close_button")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close overlay",
                                tint = secondaryIconColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { FloatingOverlayController.skipPrevious(context) },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("floating_popup_back_button")
                        ) {
                            Icon(
                                Icons.Rounded.SkipPrevious,
                                contentDescription = "Previous",
                                tint = primaryTextColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD97736))
                                .clickable {
                                    FloatingOverlayController.togglePlayPause(context)
                                }
                                .testTag("floating_popup_play_button")
                        ) {
                            Icon(
                                imageVector = if (overlayState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (overlayState.isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(
                            onClick = { FloatingOverlayController.skipNext(context) },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("floating_popup_front_button")
                        ) {
                            Icon(
                                Icons.Rounded.SkipNext,
                                contentDescription = "Next",
                                tint = primaryTextColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
