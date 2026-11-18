package app.fingernotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fingernotes.core.TRASH_RETENTION_DAYS
import app.fingernotes.core.daysUntilPurge
import java.text.DateFormat
import java.util.Date

@Composable
fun TrashScreen(vm: AppViewModel) {
    val notes by vm.notes.collectAsState()
    val expenses by vm.expenses.collectAsState()
    val labels by vm.labels.collectAsState()
    val tNotes = notes.filter { it.isTrashed }.sortedByDescending { it.deletedAt }
    val tExp = expenses.filter { it.isTrashed }.sortedByDescending { it.deletedAt }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val labelMap = labels.associateBy { it.id }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Items are deleted forever after $TRASH_RETENTION_DAYS days — on every synced device.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (tNotes.isNotEmpty() || tExp.isNotEmpty()) OutlinedButton({ confirm = { vm.io { emptyTrash() } } }) { Text("Empty Trash") }
            }
        }
        if (tNotes.isEmpty() && tExp.isEmpty()) item { Text("Trash is empty", Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (tNotes.isNotEmpty()) item { Text("NOTES", style = MaterialTheme.typography.labelSmall) }
        items(tNotes, key = { it.id }) { NoteCard(vm, it, labelMap, trash = true) }
        if (tExp.isNotEmpty()) item { Text("EXPENSES", style = MaterialTheme.typography.labelSmall) }
        items(tExp, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title.ifBlank { "(untitled)" })
                        Text(DateFormat.getDateInstance().format(Date(e.occurredAt)) + " · ${e.currency}${money(e.amount)}", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "Deletes in ${daysUntilPurge(e.deletedAt ?: 0)} days", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp).background(MaterialTheme.colorScheme.errorContainer.copy(alpha = .5f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    IconButton({ vm.io { restoreExpense(e.id) } }) { Icon(Icons.Default.Restore, "Restore") }
                    IconButton({ confirm = { vm.io { deleteForever("expense", e.id) } } }) { Icon(Icons.Default.DeleteForever, "Delete forever") }
                }
            }
        }
    }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete forever?") },
            text = { Text("This permanently deletes the item(s) on all synced devices. It cannot be undone.") },
            confirmButton = { TextButton({ action(); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}
