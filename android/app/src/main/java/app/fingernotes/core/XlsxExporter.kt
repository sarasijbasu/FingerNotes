package app.fingernotes.core

import org.dhatim.fastexcel.Workbook
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/** FastExcel exporter — column-for-column match of extension/src/core/xlsx.ts (docs/PROTOCOL.md §9). */
object XlsxExporter {
    val HEADERS = listOf("Date", "Time", "Title / Merchant", "Category", "Payment Method", "Currency", "Amount", "Notes")
    private val WIDTHS = listOf(12.0, 8.0, 28.0, 14.0, 16.0, 10.0, 14.0, 36.0)
    private val EPOCH = LocalDate.of(1899, 12, 30)

    fun excelDate(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Double =
        ChronoUnit.DAYS.between(EPOCH, Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()).toDouble()

    fun excelTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Double {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return (t.hour * 3600 + t.minute * 60) / 86_400.0
    }

    fun filterByRange(expenses: List<Expense>, from: Long?, to: Long?) = expenses
        .filter { !it.isTrashed && (from == null || it.occurredAt >= from) && (to == null || it.occurredAt <= to) }
        .sortedBy { it.occurredAt }

    fun write(out: OutputStream, expenses: List<Expense>, categories: Map<String, Category>) {
        val wb = Workbook(out, "FingerNotes", "1.0")
        val ws = wb.newWorksheet("Expenses")
        HEADERS.forEachIndexed { c, h ->
            ws.value(0, c, h)
            ws.width(c, WIDTHS[c])
        }
        ws.range(0, 0, 0, HEADERS.size - 1).style().bold().fontColor("FFFFFF").fillColor("37474F").horizontalAlignment("center").set()
        expenses.forEachIndexed { i, e ->
            val r = i + 1
            ws.value(r, 0, excelDate(e.occurredAt)); ws.style(r, 0).format("yyyy-mm-dd").set()
            ws.value(r, 1, excelTime(e.occurredAt)); ws.style(r, 1).format("hh:mm").set()
            ws.value(r, 2, e.title)
            ws.value(r, 3, categories[e.categoryId]?.name ?: "Uncategorized")
            ws.value(r, 4, PaymentMethods.labels[e.paymentMethod] ?: e.paymentMethod)
            ws.value(r, 5, e.currency)
            ws.value(r, 6, e.amount); ws.style(r, 6).format("#,##0.00").set()
            ws.value(r, 7, e.notes)
        }
        val totalRow = expenses.size + 1
        ws.value(totalRow, 5, "TOTAL"); ws.style(totalRow, 5).bold().set()
        ws.formula(totalRow, 6, "SUM(G2:G${expenses.size + 1})")
        ws.style(totalRow, 6).bold().format("#,##0.00").set()
        ws.freezePane(0, 1)
        wb.finish()
    }

    /** Pre-computed total (shown in UI and used by tests; the sheet itself uses a live SUM formula). */
    fun total(expenses: List<Expense>) = (expenses.sumOf { it.amount } * 100).roundToLong() / 100.0
}
