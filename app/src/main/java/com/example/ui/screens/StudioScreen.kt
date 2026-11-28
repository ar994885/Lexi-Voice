package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.db.BookEntity
import com.example.ui.components.LexiCard
import com.example.ui.components.LexiScreenScaffold
import com.example.ui.components.LexiSectionHeader

/**
 * S11 — Studio (landing).
 *
 * Phase 1 placeholder. The three tiles are the final labels, wording and order from
 * section 8.11, and the Recent section is present but empty. The two book-dependent
 * tiles open a book picker and then the Book screen, because neither workflow can start
 * without a book and the Studio step-1 picker does not exist until Phase 5. Phase 5
 * replaces [StudioBookPicker] with step 1 of S12 and points the tiles at the workflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    books: List<BookEntity>,
    onOpenBook: (Long) -> Unit,
    onBuildFromAudio: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LexiScreenScaffold(title = stringResource(R.string.studio_title)) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StudioTile(
                    icon = Icons.Filled.AutoAwesome,
                    title = stringResource(R.string.studio_tile_create_title),
                    body = stringResource(R.string.studio_tile_create_body),
                    testTag = "studio_tile_create",
                    onClick = { pickerOpen = true }
                )
            }
            item {
                StudioTile(
                    icon = Icons.Filled.Edit,
                    title = stringResource(R.string.studio_tile_export_title),
                    body = stringResource(R.string.studio_tile_export_body),
                    testTag = "studio_tile_export_epub",
                    onClick = { pickerOpen = true }
                )
            }
            item {
                StudioTile(
                    icon = Icons.Filled.Build,
                    title = stringResource(R.string.studio_tile_build_title),
                    body = stringResource(R.string.studio_tile_build_body),
                    testTag = "studio_tile_build",
                    onClick = onBuildFromAudio
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                LexiSectionHeader(
                    title = stringResource(R.string.studio_recent_title),
                    modifier = Modifier.testTag("studio_recent_section")
                )
            }
            item {
                Text(
                    text = stringResource(R.string.studio_recent_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (pickerOpen) {
        ModalBottomSheet(
            onDismissRequest = { pickerOpen = false },
            sheetState = sheetState
        ) {
            StudioBookPicker(
                books = books,
                onOpenBook = { bookId ->
                    pickerOpen = false
                    onOpenBook(bookId)
                },
                onOpenLibrary = {
                    pickerOpen = false
                    onOpenLibrary()
                }
            )
        }
    }
}

@Composable
private fun StudioTile(
    icon: ImageVector,
    title: String,
    body: String,
    testTag: String,
    onClick: () -> Unit
) {
    LexiCard(
        modifier = Modifier.testTag(testTag),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Temporary: choose the book a Studio workflow applies to. Deleted in Phase 5, when
 * S12 step 1 does this properly.
 */
@Composable
private fun StudioBookPicker(
    books: List<BookEntity>,
    onOpenBook: (Long) -> Unit,
    onOpenLibrary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = stringResource(R.string.studio_pick_book_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        if (books.isEmpty()) {
            Text(
                text = stringResource(R.string.studio_pick_book_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.studio_pick_book_library),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenLibrary)
                        .padding(vertical = 8.dp)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(items = books, key = { it.id }) { book ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenBook(book.id) }
                            .padding(vertical = 8.dp)
                    ) {
                        BookCoverImage(
                            coverPath = book.coverPath,
                            title = book.title,
                            modifier = Modifier
                                .width(40.dp)
                                .height(60.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = book.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = book.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
