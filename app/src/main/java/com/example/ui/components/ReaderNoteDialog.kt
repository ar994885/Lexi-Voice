package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

/**
 * The note attached to a highlight: text, an optional image, and whether every
 * occurrence of the selected word should carry the same note.
 *
 * Delete / Cancel / OK, matching the dialog in Moon+ Reader.
 */
@Composable
fun ReaderNoteDialog(
    quotedText: String,
    initialNote: String,
    initialHighlightAll: Boolean,
    imageBase64: String,
    isNew: Boolean = false,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (note: String, highlightAll: Boolean) -> Unit
) {
    var note by remember { mutableStateOf(initialNote) }
    var highlightAll by remember { mutableStateOf(initialHighlightAll) }
    val preview = remember(imageBase64) { NoteImages.decodeBitmap(imageBase64) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (quotedText.isNotBlank()) {
                    Text(
                        text = "“" + quotedText.trim() + "”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reader_note_text")
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = highlightAll,
                        onCheckedChange = { highlightAll = it },
                        modifier = Modifier.testTag("reader_note_highlight_all")
                    )
                    Text(
                        text = "Highlight all “" + quotedText.trim().take(24) + "” in this book",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider()

                if (preview != null) {
                    Box {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "Note image",
                            modifier = Modifier
                                .heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onClearImage() }
                        )
                    }
                    Text(
                        text = "Tap the image to remove it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onPickImage,
                        modifier = Modifier.testTag("reader_note_pick_image")
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (preview == null) "Add image" else "Change image")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Stored as a small JPEG",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(note, highlightAll) },
                modifier = Modifier.testTag("reader_note_ok")
            ) { Text("OK") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("reader_note_delete")
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("reader_note_cancel")
                ) { Text("Cancel") }
            }
        }
    )
}

/**
 * Note images are stored as a base64 JPEG, not as a file, so that they travel
 * inside the backup JSON with the rest of the note. Downscaling to 512 px at
 * quality 60 keeps a typical image around 20-40 KB, which is small enough for a
 * JSON export and still legible on a phone.
 */
object NoteImages {

    private const val MAX_DIMENSION = 512
    private const val QUALITY = 60

    /** Reads [uri], downscales and encodes it. Returns "" when it cannot be read. */
    fun encode(context: Context, uri: Uri): String {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ""

            var sample = 1
            while (bounds.outWidth / sample > MAX_DIMENSION * 2 || bounds.outHeight / sample > MAX_DIMENSION * 2) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return ""

            val scale = minOf(
                1f,
                MAX_DIMENSION.toFloat() / decoded.width.coerceAtLeast(1),
                MAX_DIMENSION.toFloat() / decoded.height.coerceAtLeast(1)
            )
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                decoded
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun decodeBitmap(base64: String): Bitmap? {
        if (base64.isBlank()) return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}
