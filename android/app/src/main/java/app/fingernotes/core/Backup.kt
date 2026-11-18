package app.fingernotes.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.AesVersion
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Encrypted backup — WinZip AES-256 (AE-2) via Zip4j, byte-compatible with @zip.js/zip.js
 * (extension/src/core/backup.ts, docs/PROTOCOL.md §8).
 */
@Serializable
data class BackupMetadata(
    val app: String = "FingerNotes",
    val appVersion: String = APP_VERSION,
    val schemaVersion: Int = SCHEMA_VERSION,
    val createdAt: Long,
    val platform: String = "android",
    val counts: Map<String, Int>,
    val checksums: Map<String, String>,
)

@Serializable private data class NotesFile(val notes: List<Note> = emptyList(), val labels: List<Label> = emptyList())
@Serializable private data class ExpensesFile(val expenses: List<Expense> = emptyList(), val categories: List<Category> = emptyList())
@Serializable private data class TombstonesFile(val tombstones: List<Tombstone> = emptyList())
@Serializable private data class MediaIndexEntry(val id: String, val ownerId: String, val mime: String)
@Serializable private data class MediaIndex(val media: List<MediaIndexEntry> = emptyList())

class BackupContents(val metadata: BackupMetadata, val records: RecordsPayload, val media: List<MediaBlob>)

class BackupException(val code: Code, message: String) : Exception(message) {
    enum class Code { BAD_PASSWORD, CHECKSUM, FORMAT, VERSION }
}

object Backup {
    private fun params(name: String) = ZipParameters().apply {
        fileNameInZip = name
        isEncryptFiles = true
        encryptionMethod = EncryptionMethod.AES
        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        aesVersion = AesVersion.TWO
        compressionMethod = CompressionMethod.DEFLATE
    }

    fun create(out: File, snapshot: RecordsPayload, media: List<MediaBlob>, password: CharArray) {
        require(password.isNotEmpty()) { "A backup password is required" }
        val files = linkedMapOf(
            "notes.json" to WireJson.encodeToString(NotesFile(snapshot.notes, snapshot.labels)).toByteArray(),
            "expenses.json" to WireJson.encodeToString(ExpensesFile(snapshot.expenses, snapshot.categories)).toByteArray(),
            "tombstones.json" to WireJson.encodeToString(TombstonesFile(snapshot.tombstones)).toByteArray(),
            "media/index.json" to WireJson.encodeToString(MediaIndex(media.map { MediaIndexEntry(it.id, it.ownerId, it.mime) })).toByteArray(),
        )
        media.forEach { files["media/${it.id}"] = it.bytes }
        val meta = BackupMetadata(
            createdAt = System.currentTimeMillis(),
            counts = mapOf(
                "notes" to snapshot.notes.size, "labels" to snapshot.labels.size, "expenses" to snapshot.expenses.size,
                "categories" to snapshot.categories.size, "tombstones" to snapshot.tombstones.size, "media" to media.size,
            ),
            checksums = files.mapValues { Crypto.sha256Hex(it.value) },
        )
        if (out.exists()) out.delete()
        ZipFile(out, password).use { zip ->
            zip.addStream(ByteArrayInputStream(WireJson.encodeToString(meta).toByteArray()), params("metadata.json"))
            for ((name, bytes) in files) zip.addStream(ByteArrayInputStream(bytes), params(name))
        }
    }

    fun read(file: File, password: CharArray): BackupContents {
        val files = HashMap<String, ByteArray>()
        try {
            ZipFile(file, password).use { zip ->
                if (!zip.isValidZipFile) throw BackupException(BackupException.Code.FORMAT, "Not a valid ZIP archive")
                for (h in zip.fileHeaders) {
                    if (h.isDirectory) continue
                    files[h.fileName] = zip.getInputStream(h).use { it.readBytes() }
                }
            }
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", true) == true)
                throw BackupException(BackupException.Code.BAD_PASSWORD, "Wrong backup password")
            throw BackupException(BackupException.Code.FORMAT, e.message ?: "Corrupt archive")
        }
        val metaBytes = files["metadata.json"] ?: throw BackupException(BackupException.Code.FORMAT, "metadata.json missing")
        val meta = WireJson.decodeFromString<BackupMetadata>(metaBytes.decodeToString())
        if (meta.app != "FingerNotes") throw BackupException(BackupException.Code.FORMAT, "Not a FingerNotes backup")
        if (meta.schemaVersion > SCHEMA_VERSION) throw BackupException(BackupException.Code.VERSION, "Backup was made by a newer app version")
        for ((name, sum) in meta.checksums) {
            val b = files[name]
            if (b == null || Crypto.sha256Hex(b) != sum) throw BackupException(BackupException.Code.CHECKSUM, "Checksum mismatch: $name")
        }
        fun text(n: String) = files[n]?.decodeToString() ?: "{}"
        val notes = WireJson.decodeFromString<NotesFile>(text("notes.json"))
        val exp = WireJson.decodeFromString<ExpensesFile>(text("expenses.json"))
        val tombs = WireJson.decodeFromString<TombstonesFile>(text("tombstones.json"))
        val index = WireJson.decodeFromString<MediaIndex>(text("media/index.json"))
        val media = index.media.mapNotNull { e -> files["media/${e.id}"]?.let { MediaBlob(e.id, e.ownerId, e.mime, it) } }
        return BackupContents(meta, RecordsPayload(notes.notes, notes.labels, exp.expenses, exp.categories, tombs.tombstones), media)
    }

    /** Merge by UUID with the same LWW + tombstone rules as sync. */
    suspend fun merge(store: SyncStore, b: BackupContents): ApplyResult {
        val r = Merge.applyRecords(store, b.records)
        val have = store.mediaIds().toSet()
        for (m in b.media) if (m.id !in have && !store.hasTombstone(m.ownerId)) store.putMedia(m)
        return r
    }
}
