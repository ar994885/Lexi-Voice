package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.LexiVoiceApplication
import com.example.tts.SleepTimerMode
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsPlaybackState
import com.example.tts.TtsSourceType
import com.example.tts.TtsStateHolder
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TtsDockedBar(
    ttsState: TtsPlaybackState,
    onOpenControlsSheet: () -> Unit,
    onPlayClick: () -> Unit,
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
                            if (ttsState.selectedEnginePackage.isNotBlank()) {
                                val engLabel = ttsState.availableEngines
                                    .firstOrNull { it.packageName == ttsState.selectedEnginePackage }
                                    ?.label
                                if (!engLabel.isNullOrBlank()) append(" • $engLabel")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val ctx = LocalContext.current
                if (ttsState.sourceType == TtsSourceType.BOOK) {
                    IconButton(
                        onClick = { TtsPlaybackService.sendAction(ctx, TtsPlaybackService.ACTION_PREV_CHAPTER) },
                        modifier = Modifier.testTag("tts_prev_chapter_button")
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Chapter")
                    }
                }
                IconButton(
                    onClick = { TtsPlaybackService.sendAction(ctx, TtsPlaybackService.ACTION_PREV_SENTENCE) },
                    modifier = Modifier.testTag("tts_prev_sentence_button")
                ) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Previous Sentence")
                }

                FilledIconButton(
                    onClick = onPlayClick,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("tts_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (ttsState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (ttsState.isPlaying) "Pause TTS" else "Play TTS"
                    )
                }

                IconButton(
                    onClick = { TtsPlaybackService.sendAction(ctx, TtsPlaybackService.ACTION_NEXT_SENTENCE) },
                    modifier = Modifier.testTag("tts_next_sentence_button")
                ) {
                    Icon(Icons.Default.FastForward, contentDescription = "Next Sentence")
                }

                if (ttsState.sourceType == TtsSourceType.BOOK) {
                    IconButton(
                        onClick = { TtsPlaybackService.sendAction(ctx, TtsPlaybackService.ACTION_NEXT_CHAPTER) },
                        modifier = Modifier.testTag("tts_next_chapter_button")
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter")
                    }
                }

                IconButton(
                    onClick = onOpenControlsSheet,
                    modifier = Modifier.testTag("tts_open_settings_sheet_button")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Voice, Rate & Sleep Timer")
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

    var showEngineMenu by remember { mutableStateOf(false) }
    var showVoiceMenu by remember { mutableStateOf(false) }
    var customMinutesInput by remember { mutableStateOf("20") }
    val canDrawOverlay = remember(ttsState.floatingPopupVisible) {
        Settings.canDrawOverlays(context)
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
                    onClick = { TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TOGGLE_PLAY_PAUSE) },
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

            // TTS Engine & Voice Selector
            Text("TTS Engine & Voice", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            Box {
                val selectedEngineLabel = ttsState.availableEngines
                    .firstOrNull { it.packageName == ttsState.selectedEnginePackage }
                    ?.label ?: ttsState.selectedEnginePackage.ifBlank { "System Default TTS Engine" }
                OutlinedButton(
                    onClick = {
                        TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_INIT_ENGINES)
                        showEngineMenu = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(selectedEngineLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = showEngineMenu, onDismissRequest = { showEngineMenu = false }) {
                    if (ttsState.availableEngines.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("System Default Engine") },
                            onClick = { showEngineMenu = false }
                        )
                    }
                    ttsState.availableEngines.forEach { engine ->
                        DropdownMenuItem(
                            text = { Text("${engine.label} (${engine.packageName})") },
                            onClick = {
                                showEngineMenu = false
                                TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_ENGINE) {
                                    putExtra(TtsPlaybackService.EXTRA_STRING, engine.packageName)
                                }
                                scope.launch {
                                    prefsRepo.updateTtsSettings { it.copy(enginePackage = engine.packageName, voiceName = "") }
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Box {
                val voiceDisplay = ttsState.availableVoices
                    .firstOrNull { it.name == ttsState.selectedVoiceName }
                    ?.let { "${it.displayLocale} (${it.name.takeLast(14)})" }
                    ?: "Default Voice (${ttsState.availableVoices.size} available)"
                OutlinedButton(
                    onClick = { showVoiceMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(voiceDisplay, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(
                    expanded = showVoiceMenu,
                    onDismissRequest = { showVoiceMenu = false },
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    ttsState.availableVoices.take(80).forEach { voice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(voice.displayLocale, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = if (voice.isNetworkRequired) "${voice.name} (Online)" else "${voice.name} (Offline)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                showVoiceMenu = false
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
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            // Sleep Timer: End of Chapter, Custom Minutes entry by typing, No Timer
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
                    label = { Text("No Timer") }
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

            // Floating Popup Mode
            Text("Floating TTS Popup", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_SET_FLOATING_POPUP) {
                            putExtra(TtsPlaybackService.EXTRA_BOOL, !ttsState.floatingPopupVisible)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("toggle_floating_popup_button")
                ) {
                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (ttsState.floatingPopupVisible) "Hide Floating Popup" else "Show Floating Popup")
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
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Overlay Perm")
                    }
                }
            }
        }
    }
}

/**
 * In-app draggable T2S Floating Popup showing:
 * - Current reading paragraph (with active sentence highlighted)
 * - Play / Pause button
 * - Back (prev sentence) button
 * - Front (next sentence) button
 * - Close button
 * - Temporarily Hide button (collapses to a tiny draggable pill until tapped)
 */
@Composable
fun InAppFloatingTtsPopup(
    ttsState: TtsPlaybackState,
    modifier: Modifier = Modifier
) {
    if (!ttsState.floatingPopupVisible) return
    val context = LocalContext.current
    // If system overlay is active and granted, avoid duplicating inside the app unless user isn't granted overlay perm
    if (Settings.canDrawOverlays(context)) return

    var offsetX by remember { mutableFloatStateOf(24f) }
    var offsetY by remember { mutableFloatStateOf(160f) }

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
        if (ttsState.floatingPopupTemporarilyHidden) {
            AssistChip(
                onClick = {
                    TtsStateHolder.updateState { it.copy(floatingPopupTemporarilyHidden = false) }
                },
                label = {
                    Text(if (ttsState.isPlaying) "TTS Speaking • Tap to show" else "TTS Hidden • Tap to show")
                },
                leadingIcon = {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = "Restore Floating Popup")
                }
            )
        } else {
            Card(
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .width(320.dp)
                    .testTag("in_app_floating_tts_popup")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Floating Reader",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                TtsStateHolder.updateState { it.copy(floatingPopupTemporarilyHidden = true) }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("floating_popup_temp_hide_button")
                        ) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = "Temporarily hide popup",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                TtsStateHolder.updateState {
                                    it.copy(floatingPopupVisible = false, floatingPopupTemporarilyHidden = false)
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("floating_popup_close_button")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close popup",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    val para = ttsState.currentParagraphText.ifBlank { "Tap Play to begin speaking." }
                    val sent = ttsState.currentSentenceText
                    val highlightColor = MaterialTheme.colorScheme.primaryContainer
                    val annotated = remember(para, sent, highlightColor) {
                        buildAnnotatedString {
                            val idx = if (sent.isNotBlank()) para.indexOf(sent) else -1
                            if (idx >= 0) {
                                append(para.substring(0, idx))
                                withStyle(
                                    SpanStyle(
                                        background = highlightColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append(para.substring(idx, idx + sent.length))
                                }
                                append(para.substring(idx + sent.length))
                            } else {
                                append(para)
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp, max = 120.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 6.dp)
                    ) {
                        Text(text = annotated, style = MaterialTheme.typography.bodyMedium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_PREV_SENTENCE) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("floating_popup_back_button")
                        ) {
                            Text("Back")
                        }
                        Button(
                            onClick = { TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TOGGLE_PLAY_PAUSE) },
                            modifier = Modifier
                                .weight(1.1f)
                                .testTag("floating_popup_play_button")
                        ) {
                            Text(if (ttsState.isPlaying) "Pause" else "Play")
                        }
                        OutlinedButton(
                            onClick = { TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_NEXT_SENTENCE) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("floating_popup_front_button")
                        ) {
                            Text("Front")
                        }
                    }
                }
            }
        }
    }
}
