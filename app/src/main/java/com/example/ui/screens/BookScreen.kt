package com.example.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.db.BookEntity
import com.example.ui.LibraryViewModel
import com.example.ui.components.LexiCard
import com.example.ui.components.LexiPrimaryButton
import com.example.ui.components.LexiScreenScaffold
import com.example.ui.components.LexiSecondaryButton
import kotlin.math.roundToInt

/**
 * S4 — Book. Phase 1 stub.
 *
 * Shows cover, title, author, the chapter/size caption, reading progress and the three
 * primary actions from section 8.4. Every action here already exists elsewhere in the
 * app; this screen only routes to it. Phase 4 adds the sections list, the management
 * rows, the linked-audiobook card and the "file is gone" banner.
 *
 * Two deliberate Phase 1 simplifications, both replaced in Phase 4:
 *  - a null book renders a spinner, not the "no longer on this device" banner, because
 *    the repository gives no way to tell "still loading" from "deleted" here;
 *  - the middle button falls back to Create audiobook when no audiobook is linked, which
 *    is the final behaviour, but the audio export it opens is still the old activity.
 */
@Composable
fun BookScreen(
    bookId: Long,
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpenReader: (Long) -> Unit,
    onOpenAudiobook: (Long) -> Unit,
    onCreateAudiobook: (BookEntity) -> Unit,
    onExportEditedBook: (BookEntity) -> Unit
) {
    val context = LocalContext.current
    val bookFlow = remember(bookId) { viewModel.observeBook(bookId) }
    val book by bookFlow.collectAsStateWithLifecycle(initialValue = null)
    val audiobooks by viewModel.audiobooks.collectAsStateWithLifecycle()
    var exportMenuOpen by remember { mutableStateOf(false) }

    val linkedAudiobook = remember(audiobooks, book?.fileKey) {
        val key = book?.fileKey
        if (key.isNullOrBlank()) {
            null
        } else {
            audiobooks.firstOrNull { it.sourceEpubKey == key }
        }
    }

    LexiScreenScaffold(
        title = book?.title.orEmpty(),
        onBack = onBack,
        backContentDescription = stringResource(R.string.cd_back),
        modifier = Modifier.testTag("book_screen")
    ) { innerPadding ->
        val current = book
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row {
                    BookCoverImage(
                        coverPath = current.coverPath,
                        title = current.title,
                        isFinished = current.isFinished,
                        modifier = Modifier
                            .width(96.dp)
                            .height(144.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = current.author,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.book_meta,
                                current.chapterCount,
                                Formatter.formatShortFileSize(context, current.fileSizeBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                LexiCard {
                    Text(
                        text = stringResource(
                            R.string.book_progress,
                            (current.progressPercent * 100f).roundToInt(),
                            current.currentChapterIndex + 1,
                            current.chapterCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { current.progressPercent.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LexiPrimaryButton(
                        text = stringResource(R.string.book_read),
                        onClick = { onOpenReader(current.id) },
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("book_primary_read")
                    )
                    if (linkedAudiobook != null) {
                        LexiSecondaryButton(
                            text = stringResource(R.string.book_listen),
                            onClick = { onOpenAudiobook(linkedAudiobook.id) },
                            icon = Icons.Filled.Headphones,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("book_primary_listen")
                        )
                    } else {
                        LexiSecondaryButton(
                            text = stringResource(R.string.book_create_audiobook),
                            onClick = { onCreateAudiobook(current) },
                            icon = Icons.Filled.Headphones,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("book_primary_listen")
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        LexiSecondaryButton(
                            text = stringResource(R.string.book_export),
                            onClick = { exportMenuOpen = true },
                            icon = Icons.Filled.FileDownload,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("book_primary_export")
                        )
                        DropdownMenu(
                            expanded = exportMenuOpen,
                            onDismissRequest = { exportMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.book_create_audiobook)) },
                                onClick = {
                                    exportMenuOpen = false
                                    onCreateAudiobook(current)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.book_export_edited)) },
                                onClick = {
                                    exportMenuOpen = false
                                    onExportEditedBook(current)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
