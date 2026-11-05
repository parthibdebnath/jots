package io.github.parthibdebnath.jots.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.UsbOff
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.parthibdebnath.jots.JotsApp
import io.github.parthibdebnath.jots.notes.NoteMeta
import io.github.parthibdebnath.jots.tablet.TabletStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(app: JotsApp, onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onTablet: () -> Unit) {
    val notes by app.notes.notes.collectAsState()
    val status by app.driver.status.collectAsState()
    val overlays = LocalOverlays.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) notes else notes.filter { it.displayTitle.contains(query.trim(), ignoreCase = true) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding().windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TabletChip(status, onTablet)
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search")
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                    }
                    Text(
                        "All notes",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, top = 28.dp),
                    )
                    Text(
                        "${notes.size} note${if (notes.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 18.dp),
                    )
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search titles") },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (notes.isEmpty()) "No notes yet.\nTap the pen button to start writing." else "Nothing matches “$query”.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 80.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            items(shown, key = { it.id }) { note ->
                NoteCard(
                    app, note,
                    modifier = Modifier.animateItem(),
                    onClick = { onOpen(note.id) },
                    onLongClick = { overlays.show { NoteActions(app, note) } },
                )
            }
        }

        FloatingActionButton(
            onClick = onNew,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(22.dp).size(62.dp),
        ) { Icon(Icons.Outlined.Edit, "New note", modifier = Modifier.size(26.dp)) }
    }
}

@Composable
private fun TabletChip(status: TabletStatus, onClick: () -> Unit) {
    val (label, ok) = when (status) {
        is TabletStatus.Active -> "Tablet" to true
        is TabletStatus.NeedsPermission, is TabletStatus.PermissionDenied -> "Allow tablet" to false
        is TabletStatus.Failed -> "Tablet problem" to false
        else -> return
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Outlined.Usb else Icons.Outlined.UsbOff, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(app: JotsApp, note: NoteMeta, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, note.id, note.thumbVersion) {
        value = withContext(Dispatchers.IO) { runCatching { app.notes.thumb(note.id) }.getOrNull() }
    }
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .clip(RoundedCornerShape(14.dp))
                .background(if (note.darkPaper) androidx.compose.ui.graphics.Color(0xFF1C1D21) else androidx.compose.ui.graphics.Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
        ) {
            thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            note.displayTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp),
        )
        Text(
            DateUtils.getRelativeTimeSpanString(note.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.NoteActions(app: JotsApp, note: NoteMeta) {
    val overlays = LocalOverlays.current
    InlineMenu(Alignment.Center) {
        Text(
            note.displayTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
        MenuItem("Rename", Icons.Outlined.Edit) {
            overlays.show {
                var text by remember { mutableStateOf(note.title) }
                InlineDialog("Note title", onDismiss = { overlays.dismiss() }, confirmLabel = "Save", onConfirm = {
                    app.notes.saveMeta(note.copy(title = text.trim()))
                    overlays.dismiss()
                }) {
                    OutlinedTextField(text, { text = it.take(120) }, singleLine = true, placeholder = { Text("Untitled note") }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        MenuItem("Duplicate", Icons.Outlined.ContentCopy) {
            app.notes.duplicate(note.id)
            overlays.dismiss()
        }
        MenuItem("Delete", Icons.Outlined.DeleteOutline, danger = true) {
            overlays.show {
                InlineDialog("Delete “${note.displayTitle}”?", onDismiss = { overlays.dismiss() }, confirmLabel = "Delete", destructive = true, onConfirm = {
                    app.notes.delete(note.id)
                    overlays.dismiss()
                }) { Text("This can't be undone.") }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
