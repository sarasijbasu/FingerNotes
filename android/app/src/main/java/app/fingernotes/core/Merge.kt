package app.fingernotes.core

import kotlinx.serialization.Serializable

/** HLC/LWW + tombstone merge engine — mirrors extension/src/core/merge.ts (docs/PROTOCOL.md §5). */
enum class Coll(val entity: String) {
    notes("note"), labels("label"), expenses("expense"), categories("category");

    companion object {
        fun ofEntity(e: String) = entries.first { it.entity == e }
    }
}

class MediaBlob(val id: String, val ownerId: String, val mime: String, val bytes: ByteArray)

interface SyncStore {
    suspend fun allNotes(): List<Note>
    suspend fun allLabels(): List<Label>
    suspend fun allExpenses(): List<Expense>
    suspend fun allCategories(): List<Category>
    suspend fun tombstones(): List<Tombstone>
    suspend fun hlcOf(c: Coll, id: String): String?
    suspend fun hasTombstone(id: String): Boolean
    suspend fun putRemote(c: Coll, rec: Versioned)
    suspend fun applyTombstone(t: Tombstone)
    suspend fun mediaIds(): List<String>
    suspend fun getMedia(id: String): MediaBlob?
    suspend fun putMedia(m: MediaBlob)
    fun observeHlc(hlc: String)
}

@Serializable
data class Manifest(
    val notes: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val expenses: Map<String, String> = emptyMap(),
    val categories: Map<String, String> = emptyMap(),
    val tombstones: List<String> = emptyList(),
    val media: List<String> = emptyList(),
)

@Serializable
data class RecordsPayload(
    val notes: List<Note> = emptyList(),
    val labels: List<Label> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val categories: List<Category> = emptyList(),
    val tombstones: List<Tombstone> = emptyList(),
) {
    val size get() = notes.size + labels.size + expenses.size + categories.size + tombstones.size
}

data class ApplyResult(var applied: Int = 0, var purged: Int = 0, var skipped: Int = 0)

object Merge {
    suspend fun buildManifest(s: SyncStore) = Manifest(
        notes = s.allNotes().associate { it.id to it.hlc },
        labels = s.allLabels().associate { it.id to it.hlc },
        expenses = s.allExpenses().associate { it.id to it.hlc },
        categories = s.allCategories().associate { it.id to it.hlc },
        tombstones = s.tombstones().map { it.id },
        media = s.mediaIds(),
    )

    private fun <T : Versioned> newer(local: List<T>, theirs: Map<String, String>, tombs: Set<String>) =
        local.filter { it.id !in tombs && (theirs[it.id] == null || it.hlc > theirs[it.id]!!) }

    suspend fun diffForPeer(s: SyncStore, peer: Manifest): RecordsPayload {
        val tombs = peer.tombstones.toSet()
        return RecordsPayload(
            notes = newer(s.allNotes(), peer.notes, tombs),
            labels = newer(s.allLabels(), peer.labels, tombs),
            expenses = newer(s.allExpenses(), peer.expenses, tombs),
            categories = newer(s.allCategories(), peer.categories, tombs),
            tombstones = s.tombstones().filter { it.id !in tombs },
        )
    }

    suspend fun mediaMissingAtPeer(s: SyncStore, peer: Manifest): List<String> {
        val theirs = peer.media.toSet(); val tombs = peer.tombstones.toSet()
        return s.mediaIds().filter { it !in theirs && it !in tombs }
    }

    suspend fun applyRecords(s: SyncStore, p: RecordsPayload): ApplyResult {
        val r = ApplyResult()
        for (t in p.tombstones) {
            s.observeHlc(t.hlc)
            if (!s.hasTombstone(t.id)) { s.applyTombstone(t); r.purged++ }
        }
        suspend fun apply(c: Coll, recs: List<Versioned>) {
            for (rec in recs) {
                s.observeHlc(rec.hlc)
                val local = s.hlcOf(c, rec.id)
                if (s.hasTombstone(rec.id) || (local != null && local >= rec.hlc)) { r.skipped++; continue }
                s.putRemote(c, rec); r.applied++
            }
        }
        apply(Coll.notes, p.notes); apply(Coll.labels, p.labels)
        apply(Coll.expenses, p.expenses); apply(Coll.categories, p.categories)
        return r
    }
}
