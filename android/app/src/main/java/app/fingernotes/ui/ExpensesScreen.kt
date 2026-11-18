package app.fingernotes.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.fingernotes.core.Category
import app.fingernotes.core.Expense
import app.fingernotes.core.PaymentMethods
import app.fingernotes.data.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale

private val zone get() = ZoneId.systemDefault()
private fun LocalDate.ms() = atStartOfDay(zone).toInstant().toEpochMilli()
private fun startOfToday() = LocalDate.now().ms()
private fun startOfWeek() = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).ms()
private fun startOfMonth() = LocalDate.now().withDayOfMonth(1).ms()
fun money(v: Double) = String.format(Locale.getDefault(), "%,.2f", v)

private fun totals(list: List<Expense>) =
    list.groupBy { it.currency }.entries.joinToString(" · ") { (c, l) -> c + money(l.sumOf { it.amount }) }.ifEmpty { "0.00" }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpensesScreen(vm: AppViewModel) {
    val all by vm.expenses.collectAsState()
    val cats by vm.categories.collectAsState()
    val settings by vm.settings.collectAsState()
    val catMap = cats.associateBy { it.id }
    val active = all.filter { !it.isTrashed }

    var range by remember { mutableStateOf("month") } // today | week | month | year | custom
    var custom by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var cat by remember { mutableStateOf<String?>(null) }
    var method by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    var pickRange by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<Long?, Long?>?>(null) }

    val now = System.currentTimeMillis()
    val (from, to) = when (range) {
        "today" -> startOfToday() to now
        "week" -> startOfWeek() to now
        "year" -> LocalDate.now().minusYears(1).ms() to now
        "custom" -> custom ?: (startOfMonth() to now)
        else -> startOfMonth() to now
    }
    val filtered = active.filter { it.occurredAt in from..to && (cat == null || it.categoryId == cat) && (method == null || it.paymentMethod == method) }
        .sortedByDescending { it.occurredAt }
    val byCat = filtered.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amount } }.entries.sortedByDescending { it.value }
    val max = byCat.maxOfOrNull { it.value } ?: 1.0

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri: Uri? ->
        val r = pendingExport
        if (uri != null && r != null) vm.exportXlsx(uri, r.first, r.second)
    }
    fun export(scope: String) {
        exportMenu = false
        val (f, t, name) = when (scope) {
            "month" -> Triple(startOfMonth(), now, SimpleDateFormat("yyyy-MM", Locale.ROOT).format(Date()))
            "range" -> Triple(from, to, "range")
            else -> Triple(null, null, "all-time")
        }
        pendingExport = f to t
        exporter.launch("fingernotes-expenses-$name.xlsx")
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Stat("Today", totals(active.filter { it.occurredAt >= startOfToday() }))
                    Stat("This week", totals(active.filter { it.occurredAt >= startOfWeek() }))
                    Stat("This month", totals(active.filter { it.occurredAt >= startOfMonth() }))
                    Stat("Filtered (${filtered.size})", totals(filtered))
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("today" to "Today", "week" to "Week", "month" to "Month", "year" to "12 months").forEach { (k, l) ->
                        FilterChip(range == k, { range = k }, { Text(l) })
                    }
                    FilterChip(range == "custom", { pickRange = true }, {
                        Text(custom?.let { "${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it.first))}–${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it.second))}" } ?: "Custom…")
                    })
                    Box {
                        OutlinedButton({ exportMenu = true }) { Icon(Icons.Default.FileDownload, null); Text(" Export .xlsx") }
                        DropdownMenu(exportMenu, { exportMenu = false }) {
                            DropdownMenuItem({ Text("Current month") }, { export("month") })
                            DropdownMenuItem({ Text("Selected date range") }, { export("range") })
                            DropdownMenuItem({ Text("All time") }, { export("all") })
                        }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(cat == null, { cat = null }, { Text("All categories") })
                    cats.forEach { c -> FilterChip(cat == c.id, { cat = if (cat == c.id) null else c.id }, { Text(c.name) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(method == null, { method = null }, { Text("All payments") })
                    PaymentMethods.labels.forEach { (k, l) -> FilterChip(method == k, { method = if (method == k) null else k }, { Text(l) }) }
                }
            }
            if (byCat.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("By category", style = MaterialTheme.typography.titleSmall)
                        byCat.forEach { (id, v) ->
                            val c = catMap[id]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c?.name ?: "Uncategorized", Modifier.width(96.dp), maxLines = 1)
                                Box(Modifier.weight(1f).height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth((v / max).toFloat()).background(parseColor(c?.color ?: "#888888"), RoundedCornerShape(50)))
                                }
                                Text(money(v), Modifier.width(90.dp).padding(start = 8.dp), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty()) item { Text("No expenses in this range", Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            filtered.groupBy { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.occurredAt), zone).toLocalDate() }.forEach { (day, list) ->
                item(key = day.toString()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(day.ms())), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text(totals(list), style = MaterialTheme.typography.labelMedium)
                    }
                }
                list.forEach { e -> item(key = e.id) { ExpenseRow(vm, e, catMap[e.categoryId]) { editing = e } } }
            }
        }
        FloatingActionButton(
            onClick = {
                val t = System.currentTimeMillis()
                editing = Expense(newId(), 0.0, settings.defaultCurrency, "", "food", "UPI_BANK", t, createdAt = t)
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add expense") }
    }

    if (pickRange) {
        val st = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickRange = false },
            confirmButton = {
                TextButton({
                    val s = st.selectedStartDateMillis; val e = st.selectedEndDateMillis
                    if (s != null && e != null) {
                        // picker returns UTC midnight; convert to local day bounds
                        val ls = java.time.Instant.ofEpochMilli(s).atZone(ZoneId.of("UTC")).toLocalDate()
                        val le = java.time.Instant.ofEpochMilli(e).atZone(ZoneId.of("UTC")).toLocalDate()
                        custom = ls.ms() to le.plusDays(1).ms() - 1
                        range = "custom"
                    }
                    pickRange = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton({ pickRange = false }) { Text("Cancel") } },
        ) { DateRangePicker(st, Modifier.height(480.dp)) }
    }
    editing?.let { ExpenseDialog(vm, it, cats, isNew = all.none { x -> x.id == it.id }) { editing = null } }
}

@Composable
private fun Stat(label: String, value: String) = Card {
    Column(Modifier.padding(12.dp).width(140.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

@Composable
private fun ExpenseRow(vm: AppViewModel, e: Expense, c: Category?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(parseColor(c?.color ?: "#888888"), CircleShape))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(e.title.ifBlank { "(untitled)" })
                Text(
                    "${c?.name ?: "Uncategorized"} · ${PaymentMethods.labels[e.paymentMethod]} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.occurredAt))}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (e.receipt != null) Icon(Icons.Default.Receipt, "Has receipt", Modifier.size(16.dp))
            Text("${e.currency}${money(e.amount)}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp))
            IconButton({
                vm.io { trashExpense(e.id) }
                vm.snack("Expense moved to Trash", "Undo") { vm.repo.restoreExpense(e.id) }
            }) { Icon(Icons.Default.Delete, "Move to Trash") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpenseDialog(vm: AppViewModel, initial: Expense, cats: List<Category>, isNew: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var e by remember { mutableStateOf(initial) }
    var amount by remember { mutableStateOf(if (initial.amount > 0) initial.amount.toString() else "") }
    var newCat by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(initial.occurredAt))) }
    val pickReceipt = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return@launch
            val att = withContext(Dispatchers.IO) { vm.repo.addMedia(e.id, bytes, ctx.contentResolver.getType(uri) ?: "image/jpeg", "image", "receipt.jpg") }
            e = e.copy(receipt = att)
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (isNew) "New expense" else "Edit expense") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(2f))
                        OutlinedTextField(e.currency, { e = e.copy(currency = it.take(4)) }, label = { Text("Currency") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                item { OutlinedTextField(e.title, { e = e.copy(title = it) }, label = { Text("Title / Merchant") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    Text("Category", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cats.forEach { c -> FilterChip(e.categoryId == c.id, { e = e.copy(categoryId = c.id) }, { Text(c.name) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newCat, { newCat = it }, placeholder = { Text("Custom category") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton({
                            val n = newCat.trim(); if (n.isEmpty()) return@TextButton
                            scope.launch {
                                val c = withContext(Dispatchers.IO) { vm.repo.saveCategory(n, "hsl(${(0..359).random()} 60% 50%)") }
                                e = e.copy(categoryId = c.id); newCat = ""
                            }
                        }) { Text("Add") }
                    }
                }
                item {
                    Text("Payment method", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaymentMethods.labels.forEach { (k, l) -> FilterChip(e.paymentMethod == k, { e = e.copy(paymentMethod = k) }, { Text(l) }) }
                    }
                }
                item { OutlinedTextField(dateText, { dateText = it }, label = { Text("Date & time (yyyy-MM-dd HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(e.notes, { e = e.copy(notes = it) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    e.receipt?.let { r ->
                        MediaImage(vm.repo, r.id, maxHeight = 160)
                        TextButton({ e = e.copy(receipt = null) }) { Text("Remove receipt") }
                    } ?: OutlinedButton({ pickReceipt.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Default.Receipt, null); Text(" Attach receipt")
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val amt = amount.replace(',', '.').toDoubleOrNull()
                val at = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse(dateText)!!.time }.getOrNull()
                when {
                    amt == null || amt <= 0 -> vm.snack("Enter an amount greater than 0")
                    at == null -> vm.snack("Date must look like 2026-01-31 18:30")
                    else -> {
                        val rounded = Math.round(amt * 100) / 100.0
                        vm.io {
                            saveExpense(e.copy(amount = rounded, occurredAt = at))
                            initial.receipt?.let { old -> if (old.id != e.receipt?.id) removeMedia(old.id) }
                        }
                        onClose()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
