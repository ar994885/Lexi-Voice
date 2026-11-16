package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.LexiVoiceApplication
import com.example.epub.EpubParser
import com.example.export.ExportChaptersActivity
import com.example.tts.TtsPlaybackService
import com.example.tts.TtsSourceType
import com.example.tts.TtsStateHolder
import com.example.ui.components.TtsControlsModalSheet
import com.example.ui.components.TtsDockedBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteAndSpeakScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as LexiVoiceApplication
    val prefsRepo = app.preferencesRepository
    val scope = rememberCoroutineScope()

    val savedTitle by prefsRepo.scratchpadTitle.collectAsStateWithLifecycle(initialValue = "Quick Read")
    val savedText by prefsRepo.scratchpadText.collectAsStateWithLifecycle(initialValue = "")
    val ttsState by TtsStateHolder.state.collectAsStateWithLifecycle()

    var title by remember(savedTitle) { mutableStateOf(savedTitle) }
    var text by remember(savedText) { mutableStateOf(savedText) }
    var readAlongMode by remember { mutableStateOf(false) }
    var showTtsSheet by remember { mutableStateOf(false) }

    val parsedChapter = remember(title, text) {
        EpubParser.parsePlainTextToChapter(title.ifBlank { "Quick Read" }, text)
    }
    val listState = rememberLazyListState()

    val isSpeakingScratchpad = ttsState.isActive && ttsState.sourceType == TtsSourceType.SCRATCHPAD

    LaunchedEffect(ttsState.sentenceIndex, isSpeakingScratchpad, readAlongMode) {
        if (isSpeakingScratchpad && readAlongMode) {
            val activePara = ttsState.currentParagraphIndex
            if (activePara in parsedChapter.paragraphs.indices) {
                listState.animateScrollToItem(activePara)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Paste & Speak") },
                actions = {
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (clip.isNotBlank()) {
                                text = clip
                                scope.launch { prefsRepo.saveScratchpad(title, clip) }
                            }
                        },
                        modifier = Modifier.testTag("scratchpad_paste_button")
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste from Clipboard")
                    }

                    IconButton(
                        onClick = {
                            text = ""
                            readAlongMode = false
                            scope.launch { prefsRepo.saveScratchpad(title, "") }
                        },
                        modifier = Modifier.testTag("scratchpad_clear_button")
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Text")
                    }

                    IconButton(
                        onClick = {
                            if (text.isNotBlank()) {
                                ExportChaptersActivity.launchForScratchpad(context, title, text)
                            }
                        },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.testTag("scratchpad_export_mp3_button")
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = "Export Audio (MP3)")
                    }
                }
            )
        },
        bottomBar = {
            TtsDockedBar(
                ttsState = ttsState,
                onOpenControlsSheet = { showTtsSheet = true },
                onPlayClick = {
                    if (isSpeakingScratchpad) {
                        TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
                    } else if (text.isNotBlank()) {
                        readAlongMode = true
                        scope.launch { prefsRepo.saveScratchpad(title, text) }
                        TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_PLAY_SCRATCHPAD) {
                            putExtra(TtsPlaybackService.EXTRA_TITLE, title.ifBlank { "Quick Read" })
                            putExtra(TtsPlaybackService.EXTRA_TEXT, text)
                            putExtra(TtsPlaybackService.EXTRA_SENTENCE_INDEX, 0)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !readAlongMode,
                        onClick = { readAlongMode = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    ) {
                        Text("Edit")
                    }
                    SegmentedButton(
                        selected = readAlongMode,
                        onClick = { readAlongMode = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                    ) {
                        Text("Read Along")
                    }
                }

                val wordCount = remember(text) {
                    text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
                }
                Text(
                    text = "$wordCount words",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            if (!readAlongMode) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        scope.launch { prefsRepo.saveScratchpad(it, text) }
                    },
                    label = { Text("Audio / Clip Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        scope.launch { prefsRepo.saveScratchpad(title, it) }
                    },
                    placeholder = {
                        Text("Paste articles, notes, or copied text here to listen aloud or export to MP3 / M4A...")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("scratchpad_text_input")
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (text.isBlank()) return@Button
                            readAlongMode = true
                            scope.launch { prefsRepo.saveScratchpad(title, text) }
                            TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_PLAY_SCRATCHPAD) {
                                putExtra(TtsPlaybackService.EXTRA_TITLE, title.ifBlank { "Quick Read" })
                                putExtra(TtsPlaybackService.EXTRA_TEXT, text)
                                putExtra(TtsPlaybackService.EXTRA_SENTENCE_INDEX, 0)
                            }
                        },
                        enabled = text.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("scratchpad_speak_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Speak Aloud")
                    }
                    OutlinedButton(
                        onClick = {
                            if (text.isNotBlank()) {
                                ExportChaptersActivity.launchForScratchpad(context, title, text)
                            }
                        },
                        enabled = text.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Export MP3")
                    }
                }
                Spacer(Modifier.height(10.dp))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(parsedChapter.paragraphs, key = { it.index }) { para ->
                        val activeGlobalIdx = if (isSpeakingScratchpad) ttsState.sentenceIndex else -1
                        val highlightBg = MaterialTheme.colorScheme.primaryContainer
                        val highlightFg = MaterialTheme.colorScheme.onPrimaryContainer

                        val annotated = remember(para, activeGlobalIdx, highlightBg, highlightFg) {
                            buildAnnotatedString {
                                var cursor = 0
                                for (s in para.sentences) {
                                    if (s.startInParagraph > cursor) {
                                        append(para.text.substring(cursor, s.startInParagraph))
                                    }
                                    if (s.globalIndex == activeGlobalIdx) {
                                        withStyle(
                                            SpanStyle(
                                                background = highlightBg,
                                                color = highlightFg,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        ) {
                                            append(para.text.substring(s.startInParagraph, s.endInParagraph))
                                        }
                                    } else {
                                        append(para.text.substring(s.startInParagraph, s.endInParagraph))
                                    }
                                    cursor = s.endInParagraph
                                }
                                if (cursor < para.text.length) {
                                    append(para.text.substring(cursor))
                                }
                            }
                        }

                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val firstSentence = para.sentences.firstOrNull()?.globalIndex ?: 0
                                    TtsPlaybackService.sendAction(context, TtsPlaybackService.ACTION_PLAY_SCRATCHPAD) {
                                        putExtra(TtsPlaybackService.EXTRA_TITLE, title.ifBlank { "Quick Read" })
                                        putExtra(TtsPlaybackService.EXTRA_TEXT, text)
                                        putExtra(TtsPlaybackService.EXTRA_SENTENCE_INDEX, firstSentence)
                                    }
                                }
                        )
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
