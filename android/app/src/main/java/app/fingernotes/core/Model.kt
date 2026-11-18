package app.fingernotes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire/domain models — field-for-field identical to extension/src/core/model.ts (docs/PROTOCOL.md §4). */
val WireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

const val SCHEMA_VERSION = 1
const val APP_VERSION = "1.0.0"
const val DAY_MS = 86_400_000L
const val TRASH_RETENTION_DAYS = 30
const val TRASH_RETENTION_MS = TRASH_RETENTION_DAYS * DAY_MS

interface Versioned {
    val id: String
    val hlc: String
}

@Serializable
data class ChecklistItem(val id: String, val text: String, val checked: Boolean, val position: Int)

@Serializable
data class Attachment(val id: String, val kind: String, val mime: String, val size: Long, val name: String, val createdAt: Long)

@Serializable
data class Note(
    override val id: String,
    val kind: String = "text",
    val title: String = "",
    val body: String = "",
    val items: List<ChecklistItem> = emptyList(),
    val color: String = "default",
    val labelIds: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isHidden: Boolean = false,
    val isTrashed: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    override val hlc: String = "",
) : Versioned

@Serializable
data class Label(override val id: String, val name: String, val createdAt: Long, val updatedAt: Long, override val hlc: String) : Versioned

@Serializable
data class Expense(
    override val id: String,
    val amount: Double,
    val currency: String,
    val title: String,
    val categoryId: String,
    val paymentMethod: String,
    val occurredAt: Long,
    val notes: String = "",
    val receipt: Attachment? = null,
    val isTrashed: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    override val hlc: String = "",
) : Versioned

@Serializable
data class Category(
    override val id: String,
    val name: String,
    val color: String,
    val builtIn: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    override val hlc: String,
) : Versioned

@Serializable
data class Tombstone(val id: String, val entityType: String, val purgedAt: Long, val hlc: String)

object PaymentMethods {
    val labels = linkedMapOf("CASH" to "Cash", "CARD" to "Card", "UPI_BANK" to "UPI/Bank")
}

val NOTE_COLORS = listOf("default", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "brown", "gray")

const val SEED_HLC = "000000000000000-00000-0000000000000000"
val BUILTIN_CATEGORIES = listOf(
    Category("food", "Food", "#F4511E", true, 0, 0, SEED_HLC),
    Category("transport", "Transport", "#039BE5", true, 0, 0, SEED_HLC),
    Category("bills", "Bills", "#8E24AA", true, 0, 0, SEED_HLC),
    Category("shopping", "Shopping", "#43A047", true, 0, 0, SEED_HLC),
    Category("health", "Health", "#E53935", true, 0, 0, SEED_HLC),
)

fun daysUntilPurge(deletedAt: Long, now: Long = System.currentTimeMillis()): Long =
    maxOf(0L, Math.ceil((deletedAt + TRASH_RETENTION_MS - now).toDouble() / DAY_MS).toLong())
