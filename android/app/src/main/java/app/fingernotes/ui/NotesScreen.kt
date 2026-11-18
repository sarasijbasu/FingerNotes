package app.fingernotes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fingernotes.core.Label
import app.fingernotes.core.Note
import app.fingernotes.core.daysUntilPurge

enum class NotesFilter { Main, Archive, Hidden }

private fun Note.matches(q: String, labels: Map<String, Label>): Boolean {
    if (q.isBlank()) return true
    val hay = (listOf(title, body) + items.map { it.text } + labelIds.mapNotNull { labels[it]?.name }).joinToString("\n").lowercase()
    return q.lowercase().split(Regex("\\s+")).all { it in hay }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotesScreen(vm: AppViewModel, filter: NotesFilter, onEdit: (Note, Boolean) -> Unit) {
    val all by vm.notes.collectAsState()
    val labels by vm.labels.collectAsState()
    val settings by vm.settings.collectAsState()
    var q by remember { mutableStateOf("") }
    var label by remember { mutableStateOf<String?>(null) }
    val labelMap = labels.associateBy { it.id }

    val visible = all.asSequence()
        .filter { !it.isTrashed }
        .filter {
            when (filter) {
                NotesFilter.Hidden -> it.isHidden
                NotesFilter.Archive -> !it.isHidden && it.isArchived
                NotesFilter.Main -> !it.isHidden && !it.isArchived
            }
        }
        .filter { it.matches(q, labelMap) && (label == null || label in it.labelIds) }
        .sortedByDescending { it.updatedAt }
        .toList()
    val pinned = visible.filter { it.isPinned }
    val others = visible.filter { !it.isPinned }

    fun create(kind: String) {
        var n = vm.repo.newNote(kind)
        if (filter == NotesFilter.Hidden) n = n.copy(isHidden = true)
        label?.let { n = n.copy(labelIds = listOf(it)) }
        onEdit(n, true)
    }

    Box(Modifier.fillMaxSize()) {
        Column {
            OutlinedTextField(
                q, { q = it }, placeholder = { Text(if (filter == NotesFilter.Hidden) "Search hidden notes" else "Search notes") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (labels.isNotEmpty()) {
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEach { l ->
                        FilterChip(selected = label == l.id, onClick = { label = if (label == l.id) null else l.id }, label = { Text(l.name) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(16.dp)) })
                    }
                }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            q.isNotBlank() -> "No matching notes"
                            filter == NotesFilter.Archive -> "Archived notes appear here"
                            filter == NotesFilter.Hidden -> "Hidden notes appear here"
                            else -> "Notes you add appear here"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = if (settings.gridView) StaggeredGridCells.Adaptive(165.dp) else StaggeredGridCells.Fixed(1),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp,
                ) {
                    if (pinned.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) { SectionTitle("Pinned") }
                    items(pinned.size, key = { pinned[it].id }) { i -> SwipeableNote(vm, pinned[i], labelMap) { onEdit(pinned[i], false) } }
                    if (pinned.isNotEmpty() && others.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) { SectionTitle("Others") }
                    items(others.size, key = { others[it].id }) { i -> SwipeableNote(vm, others[i], labelMap) { onEdit(others[i], false) } }
                }
            }
        }
        if (filter != NotesFilter.Archive) {
            Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallFloatingActionButton(onClick = { create("checklist") }) { Icon(Icons.Default.CheckBox, "New checklist") }
                FloatingActionButton(onClick = { create("text") }) { Icon(Icons.Default.Edit, "New note") }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) =
    Text(t.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))

/** Swipe right → archive, swipe left → trash (both with Undo snackbar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNote(vm: AppViewModel, note: Note, labels: Map<String, Label>, onClick: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        when (it) {
            SwipeToDismissBoxValue.StartToEnd -> { vm.archiveNote(note); true }
            SwipeToDismissBoxValue.EndToStart -> { vm.trashNote(note); true }
            else -> false
        }
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toTrash = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(if (toTrash) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)).padding(16.dp),
                contentAlignment = if (toTrash) Alignment.CenterEnd else Alignment.CenterStart,
            ) { Icon(if (toTrash) Icons.Default.Delete else Icons.Default.Archive, null) }
        },
    ) {
        NoteCard(vm, note, labels, onClick = onClick)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NoteCard(vm: AppViewModel, note: Note, labels: Map<String, Label>, trash: Boolean = false, onClick: () -> Unit = {}) {
    val dark = isSystemInDarkTheme()
    val bg = noteColor(note.color, dark) ?: MaterialTheme.colorScheme.surface
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(enabled = !trash, onClick = onClick)
            .padding(12.dp),
    ) {
        note.attachments.firstOrNull { it.kind == "image" }?.let { MediaImage(vm.repo, it.id, Modifier.padding(bottom = 6.dp), maxHeight = 160) }
        Row(verticalAlignment = Alignment.Top) {
            if (note.title.isNotBlank()) Text(note.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            if (note.isPinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (note.kind == "checklist") {
            note.items.filter { !it.checked }.take(8).forEach { Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckBoxOutlineBlank, null, Modifier.size(14.dp)); Text(" ${it.text}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
            val done = note.items.count { it.checked }
            if (done > 0) Text("+ $done checked item${if (done > 1) "s" else ""}", style = MaterialTheme.typography.labelSmall, textDecoration = TextDecoration.None, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (note.body.isNotBlank()) Markdown(note.body, maxLines = 12)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            note.labelIds.mapNotNull { labels[it] }.forEach { SuggestionChip(onClick = {}, label = { Text(it.name, style = MaterialTheme.typography.labelSmall) }) }
            val audio = note.attachments.count { it.kind == "audio" }
            if (audio > 0) SuggestionChip(onClick = {}, icon = { Icon(Icons.Default.Mic, null, Modifier.size(14.dp)) }, label = { Text("$audio") })
            if (trash && note.deletedAt != null) {
                Text("Deletes in ${daysUntilPurge(note.deletedAt!!)} days", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        if (trash) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton({ vm.io { restoreNote(note.id) } }) { Icon(Icons.Default.Restore, "Restore") }
            IconButton({ confirmDelete = true }) { Icon(Icons.Default.DeleteForever, "Delete forever") }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete forever?") },
        text = { Text("This note will be permanently deleted on all synced devices.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.io { deleteForever("note", note.id) } }) { Text("Delete", color = Color.Red) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}
