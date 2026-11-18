package app.fingernotes.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.fingernotes.core.Attachment
import app.fingernotes.core.ChecklistItem
import app.fingernotes.core.NOTE_COLORS
import app.fingernotes.core.Note
import app.fingernotes.data.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

private data class Snapshot(val title: String, val body: TextFieldValue, val items: List<ChecklistItem>, val kind: String)

/** Snapshot undo/redo; edits within 700 ms coalesce into one step. */
private class History(initial: Snapshot) {
    var state by mutableStateOf(initial)
    private val past = ArrayDeque<Snapshot>()
    private val future = ArrayDeque<Snapshot>()
    private var last = 0L
    fun set(s: Snapshot, coalesce: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!coalesce || now - last > 700) past.addLast(state)
        last = now; future.clear(); state = s
    }
    fun undo() { past.removeLastOrNull()?.let { future.addLast(state); state = it; last = 0 } }
    fun redo() { future.removeLastOrNull()?.let { past.addLast(state); state = it; last = 0 } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditor(vm: AppViewModel, note: Note, isNew: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val labels by vm.labels.collectAsState()
    val h = remember { History(Snapshot(note.title, TextFieldValue(note.body), note.items, note.kind)) }
    var color by remember { mutableStateOf(note.color) }
    var pinned by remember { mutableStateOf(note.isPinned) }
    var labelIds by remember { mutableStateOf(note.labelIds) }
    val attachments = remember { mutableStateListOf<Attachment>().apply { addAll(note.attachments) } }
    var panel by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<Pair<MediaRecorder, File>?>(null) }
    var newLabel by remember { mutableStateOf("") }
    val s = h.state
    val dark = isSystemInDarkTheme()

    fun close() {
        recorder?.let { runCatching { it.first.stop() }; it.first.release(); it.second.delete() }
        val merged = note.copy(title = s.title, body = s.body.text, items = s.items, kind = s.kind, color = color, isPinned = pinned, labelIds = labelIds, attachments = attachments.toList())
        val empty = s.title.isBlank() && s.body.text.isBlank() && s.items.isEmpty() && attachments.isEmpty()
        vm.io {
            if (isNew && empty) { note.attachments.forEach { removeMedia(it.id) }; return@io }
            if (merged != note) saveNote(merged)
            note.attachments.filter { a -> attachments.none { it.id == a.id } }.forEach { removeMedia(it.id) }
        }
        onClose()
    }
    BackHandler { close() }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return@launch
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            attachments += withContext(Dispatchers.IO) { vm.repo.addMedia(note.id, bytes, mime, "image", uri.lastPathSegment ?: "image") }
        }
    }

    fun startRecording() {
        val f = File(ctx.cacheDir, "rec-${System.currentTimeMillis()}.m4a")
        val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(f.absolutePath)
            prepare(); start()
        }
        recorder = r to f
    }
    fun stopRecording() {
        val (r, f) = recorder ?: return
        recorder = null
        runCatching { r.stop() }; r.release()
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { f.readBytes().also { f.delete() } }
            attachments += withContext(Dispatchers.IO) { vm.repo.addMedia(note.id, bytes, "audio/mp4", "audio", "Voice memo ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}.m4a") }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRecording() else vm.snack("Microphone permission denied") }

    fun wrap(marker: String) {
        val v = s.body; val a = v.selection.min; val b = v.selection.max
        val text = v.text.substring(0, a) + marker + v.text.substring(a, b) + marker + v.text.substring(b)
        h.set(s.copy(body = TextFieldValue(text, TextRange(a + marker.length, b + marker.length))), coalesce = false)
    }
    fun bullet() {
        val v = s.body; val start = v.text.lastIndexOf('\n', (v.selection.min - 1).coerceAtLeast(0)).let { if (it < 0 || v.selection.min == 0) 0 else it + 1 }
        h.set(s.copy(body = TextFieldValue(v.text.substring(0, start) + "- " + v.text.substring(start), TextRange(v.selection.min + 2))), coalesce = false)
    }
    fun toggleKind() {
        if (s.kind == "text") h.set(s.copy(kind = "checklist", body = TextFieldValue(""), items = s.body.text.lines().filter { it.isNotBlank() }.mapIndexed { i, l -> ChecklistItem(newId(), l.replace(Regex("""^\s*[-•]\s+"""), ""), false, i) }), false)
        else h.set(s.copy(kind = "text", items = emptyList(), body = TextFieldValue(s.items.joinToString("\n") { "- ${it.text}" })), false)
    }
    fun setItems(items: List<ChecklistItem>) = h.set(s.copy(items = items.filter { !it.checked } + items.filter { it.checked }))

    val unchecked = s.items.filter { !it.checked }
    val checked = s.items.filter { it.checked }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val list = unchecked.toMutableList()
        val fi = list.indexOfFirst { it.id == from.key }; val ti = list.indexOfFirst { it.id == to.key }
        if (fi >= 0 && ti >= 0) { list.add(ti, list.removeAt(fi)); h.set(s.copy(items = list + checked)) }
    }

    Surface(Modifier.fillMaxSize(), color = noteColor(color, dark) ?: MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                Box(Modifier.weight(1f))
                IconButton({ h.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton({ h.redo() }) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                IconButton({ pinned = !pinned }) { Icon(if (pinned) Icons.Default.PushPin else Icons.Outlined.PushPin, "Pin") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState) {
                item {
                    BasicTextField(
                        s.title, { h.set(s.copy(title = it)) },
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner -> Box { if (s.title.isEmpty()) Text("Title", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
                if (attachments.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        attachments.toList().forEach { a ->
                            Box {
                                if (a.kind == "image") MediaImage(vm.repo, a.id, maxHeight = 260) else AudioChip(vm.repo, a)
                                IconButton({ attachments.remove(a) }, Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.Close, "Remove attachment") }
                            }
                        }
                    }
                }
                if (s.kind == "text") {
                    item {
                        Row {
                            IconButton({ wrap("**") }) { Icon(Icons.Default.FormatBold, "Bold") }
                            IconButton({ wrap("*") }) { Icon(Icons.Default.FormatItalic, "Italic") }
                            IconButton(::bullet) { Icon(Icons.Default.FormatListBulleted, "Bullet") }
                            IconButton({ panel = if (panel == "link") "" else "link" }) { Icon(Icons.Default.Link, "Link") }
                            Box(Modifier.weight(1f))
                            TextButton({ preview = !preview }) { Text(if (preview) "Edit" else "Preview") }
                        }
                    }
                    item {
                        if (preview) Box(Modifier.padding(vertical = 8.dp)) { Markdown(s.body.text) }
                        else BasicTextField(
                            s.body, { h.set(s.copy(body = it)) },
                            textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner -> Box { if (s.body.text.isEmpty()) Text("Note", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }
                } else {
                    items(unchecked.size, key = { unchecked[it].id }) { i ->
                        val it = unchecked[i]
                        ReorderableItem(reorder, key = it.id) { _ ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DragHandle, "Reorder", Modifier.draggableHandle().padding(4.dp))
                                Checkbox(false, { _ -> setItems(s.items.map { x -> if (x.id == it.id) x.copy(checked = true) else x }) })
                                BasicTextField(it.text, { t -> h.set(s.copy(items = s.items.map { x -> if (x.id == it.id) x.copy(text = t) else x })) },
                                    textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.weight(1f))
                                IconButton({ h.set(s.copy(items = s.items.filter { x -> x.id != it.id }), false) }) { Icon(Icons.Default.Close, "Remove") }
                            }
                        }
                    }
                    item {
                        var draft by remember { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, null, Modifier.padding(start = 4.dp, end = 12.dp))
                            OutlinedTextField(draft, { draft = it }, placeholder = { Text("List item") }, singleLine = true, modifier = Modifier.weight(1f))
                            IconButton({ if (draft.isNotBlank()) { setItems(unchecked + ChecklistItem(newId(), draft.trim(), false, 0) + checked); draft = "" } }) { Icon(Icons.Default.Add, "Add item") }
                        }
                    }
                    if (checked.isNotEmpty()) item { Text("${checked.size} completed", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp)) }
                    items(checked.size, key = { "c" + checked[it].id }) { i ->
                        val it = checked[i]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp))
                            Checkbox(true, { _ -> setItems(s.items.map { x -> if (x.id == it.id) x.copy(checked = false) else x }) })
                            Text(it.text, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            IconButton({ h.set(s.copy(items = s.items.filter { x -> x.id != it.id }), false) }) { Icon(Icons.Default.Close, "Remove") }
                        }
                    }
                }
            }
            if (panel == "link") Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                var url by remember { mutableStateOf("https://") }
                OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Link URL") })
                TextButton({
                    if (Regex("^https?://.+").matches(url)) h.set(s.copy(body = TextFieldValue(s.body.text + (if (s.body.text.isEmpty() || s.body.text.endsWith(" ")) "" else " ") + url)), false)
                    panel = ""
                }) { Text("Insert") }
            }
            if (panel == "color") FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NOTE_COLORS.forEach { c ->
                    Box(
                        Modifier.size(32.dp).background(noteColor(c, dark) ?: MaterialTheme.colorScheme.surface, CircleShape)
                            .border(2.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { color = c },
                    )
                }
            }
            if (panel == "labels") Column(Modifier.padding(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEach { l -> FilterChip(l.id in labelIds, { labelIds = if (l.id in labelIds) labelIds - l.id else labelIds + l.id }, { Text(l.name) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newLabel, { newLabel = it }, singleLine = true, placeholder = { Text("New label") }, modifier = Modifier.weight(1f))
                    TextButton({
                        val name = newLabel.trim(); if (name.isEmpty()) return@TextButton
                        scope.launch { val l = withContext(Dispatchers.IO) { vm.repo.saveLabel(name) }; labelIds = labelIds + l.id; newLabel = "" }
                    }) { Text("Add") }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ panel = if (panel == "color") "" else "color" }) { Icon(Icons.Default.Palette, "Color") }
                IconButton({ panel = if (panel == "labels") "" else "labels" }) { Icon(Icons.AutoMirrored.Filled.Label, "Labels") }
                IconButton({ pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.Image, "Add image") }
                IconButton({
                    when {
                        recorder != null -> stopRecording()
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startRecording()
                        else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) { Icon(if (recorder != null) Icons.Default.Stop else Icons.Default.Mic, "Voice memo", tint = if (recorder != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                IconButton(::toggleKind) { Icon(Icons.Default.CheckBox, "Toggle checklist") }
                Box(Modifier.weight(1f))
                if (recorder != null) Text("● REC  ", color = MaterialTheme.colorScheme.error)
                TextButton(::close) { Text("Done") }
            }
        }
    }
}
