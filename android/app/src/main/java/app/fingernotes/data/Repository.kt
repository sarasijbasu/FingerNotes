package app.fingernotes.data

import androidx.room.withTransaction
import app.fingernotes.core.Attachment
import app.fingernotes.core.BUILTIN_CATEGORIES
import app.fingernotes.core.Category
import app.fingernotes.core.ChecklistItem
import app.fingernotes.core.Coll
import app.fingernotes.core.Crypto
import app.fingernotes.core.Expense
import app.fingernotes.core.Hlc
import app.fingernotes.core.Label
import app.fingernotes.core.MediaBlob
import app.fingernotes.core.Note
import app.fingernotes.core.RecordsPayload
import app.fingernotes.core.SyncStore
import app.fingernotes.core.TRASH_RETENTION_MS
import app.fingernotes.core.Tombstone
import app.fingernotes.core.Versioned
import app.fingernotes.data.db.AppDatabase
import app.fingernotes.data.db.CategoryEntity
import app.fingernotes.data.db.ChecklistItemEntity
import app.fingernotes.data.db.ExpenseEntity
import app.fingernotes.data.db.LabelEntity
import app.fingernotes.data.db.MediaEntity
import app.fingernotes.data.db.MetaEntity
import app.fingernotes.data.db.NoteEntity
import app.fingernotes.data.db.SyncTombstoneEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/* ---------- entity <-> domain mapping ---------- */
fun NoteEntity.toDomain(items: List<ChecklistItemEntity>) = Note(
    id, kind, title, body, items.sortedBy { it.position }.map { ChecklistItem(it.id, it.text, it.checked, it.position) },
    color, labelIds, attachments, isPinned, isArchived, isHidden, isTrashed, deletedAt, createdAt, updatedAt, hlc,
)
fun Note.toEntity() = NoteEntity(id, kind, title, body, color, labelIds, attachments, isPinned, isArchived, isHidden, isTrashed, deletedAt, createdAt, updatedAt, hlc)
fun Note.itemEntities() = items.mapIndexed { i, it -> ChecklistItemEntity(it.id, id, it.text, it.checked, i) }
fun ExpenseEntity.toDomain() = Expense(id, amount, currency, title, categoryId, paymentMethod, occurredAt, notes, receipt, isTrashed, deletedAt, createdAt, updatedAt, hlc)
fun Expense.toEntity() = ExpenseEntity(id, amount, currency, title, categoryId, paymentMethod, occurredAt, notes, receipt, isTrashed, deletedAt, createdAt, updatedAt, hlc)
fun LabelEntity.toDomain() = Label(id, name, createdAt, updatedAt, hlc)
fun Label.toEntity() = LabelEntity(id, name, createdAt, updatedAt, hlc)
fun CategoryEntity.toDomain() = Category(id, name, color, builtIn, createdAt, updatedAt, hlc)
fun Category.toEntity() = CategoryEntity(id, name, color, builtIn, createdAt, updatedAt, hlc)

fun newId() = UUID.randomUUID().toString()

/**
 * Single source of truth over the SQLCipher Room DB + encrypted media files.
 * Implements [SyncStore] so the same object is used by P2P sync and backup restore.
 */
class Repository(private val db: AppDatabase, private val keys: KeyManager, filesDir: File) : SyncStore {
    private val noteDao = db.notes()
    private val expenseDao = db.expenses()
    private val tax = db.taxonomy()
    private val sync = db.sync()
    private val mediaDir = File(filesDir, "media").apply { mkdirs() }

    lateinit var hlc: Hlc
        private set
    val deviceId get() = hlc.node

    suspend fun init() {
        var id = sync.meta("deviceId")
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").take(16)
            sync.putMeta(MetaEntity("deviceId", id))
        }
        hlc = Hlc(id, sync.meta("hlc"))
        val have = tax.categories().map { it.id }.toSet()
        for (c in BUILTIN_CATEGORIES) if (c.id !in have && sync.tombstoneCount(c.id) == 0) tax.upsertCategory(c.toEntity())
    }

    /* ---------- observable state ---------- */
    val notes: Flow<List<Note>> = combine(noteDao.observeNotes(), noteDao.observeItems()) { ns, items ->
        val by = items.groupBy { it.noteId }
        ns.map { it.toDomain(by[it.id].orEmpty()) }
    }
    val labels: Flow<List<Label>> = tax.observeLabels().map { l -> l.map { it.toDomain() } }
    val expenses: Flow<List<Expense>> = expenseDao.observe().map { l -> l.map { it.toDomain() } }
    val categories: Flow<List<Category>> = tax.observeCategories().map { l -> l.map { it.toDomain() } }

    private suspend fun tick(): String {
        val h = hlc.tick()
        sync.putMeta(MetaEntity("hlc", h))
        return h
    }

    /* ---------- notes ---------- */
    fun newNote(kind: String = "text") = Note(id = newId(), kind = kind, createdAt = System.currentTimeMillis())

    suspend fun saveNote(n: Note): Note {
        val s = n.copy(items = n.items.mapIndexed { i, it -> it.copy(position = i) }, updatedAt = System.currentTimeMillis(), hlc = tick())
        noteDao.saveWithItems(s.toEntity(), s.itemEntities())
        return s
    }

    suspend fun note(id: String): Note? = noteDao.get(id)?.toDomain(noteDao.items(id))

    suspend fun updateNote(id: String, f: (Note) -> Note): Note? = note(id)?.let { saveNote(f(it)) }

    suspend fun trashNote(id: String) = updateNote(id) { it.copy(isTrashed = true, deletedAt = System.currentTimeMillis(), isPinned = false) }
    suspend fun restoreNote(id: String) = updateNote(id) { it.copy(isTrashed = false, deletedAt = null) }

    /** Permanent delete + SyncTombstone so every paired device purges it too. */
    suspend fun deleteForever(entityType: String, id: String, now: Long = System.currentTimeMillis()) {
        applyTombstone(Tombstone(id, entityType, now, tick()))
    }

    suspend fun emptyTrash() {
        noteDao.allNotes().filter { it.isTrashed }.forEach { deleteForever("note", it.id) }
        expenseDao.all().filter { it.isTrashed }.forEach { deleteForever("expense", it.id) }
    }

    /** The 30-day purge (WorkManager daily + on unlock). */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - TRASH_RETENTION_MS
        val notes = noteDao.expired(cutoff)
        val expenses = expenseDao.expired(cutoff)
        notes.forEach { deleteForever("note", it, now) }
        expenses.forEach { deleteForever("expense", it, now) }
        return notes.size + expenses.size
    }

    /* ---------- labels / categories ---------- */
    suspend fun saveLabel(name: String, id: String? = null): Label {
        val now = System.currentTimeMillis()
        val prev = id?.let { i -> tax.labels().firstOrNull { it.id == i } }
        val l = Label(prev?.id ?: newId(), name, prev?.createdAt ?: now, now, tick())
        tax.upsertLabel(l.toEntity())
        return l
    }

    suspend fun deleteLabel(id: String) {
        noteDao.allNotes().filter { id in it.labelIds }.forEach { e -> updateNote(e.id) { n -> n.copy(labelIds = n.labelIds - id) } }
        deleteForever("label", id)
    }

    suspend fun saveCategory(name: String, color: String): Category {
        val now = System.currentTimeMillis()
        val c = Category(newId(), name, color, false, now, now, tick())
        tax.upsertCategory(c.toEntity())
        return c
    }

    /* ---------- expenses ---------- */
    suspend fun saveExpense(e: Expense): Expense {
        val s = e.copy(updatedAt = System.currentTimeMillis(), hlc = tick())
        expenseDao.upsert(s.toEntity())
        return s
    }
    suspend fun updateExpense(id: String, f: (Expense) -> Expense) = expenseDao.get(id)?.toDomain()?.let { saveExpense(f(it)) }
    suspend fun trashExpense(id: String) = updateExpense(id) { it.copy(isTrashed = true, deletedAt = System.currentTimeMillis()) }
    suspend fun restoreExpense(id: String) = updateExpense(id) { it.copy(isTrashed = false, deletedAt = null) }

    /* ---------- media (AES-GCM files in app-private storage) ---------- */
    suspend fun addMedia(ownerId: String, bytes: ByteArray, mime: String, kind: String, name: String): Attachment {
        val id = newId()
        putMedia(MediaBlob(id, ownerId, mime, bytes))
        return Attachment(id, kind, mime, bytes.size.toLong(), name, System.currentTimeMillis())
    }

    suspend fun mediaBytes(id: String): ByteArray? = getMedia(id)?.bytes

    suspend fun removeMedia(id: String) = withContext(Dispatchers.IO) {
        sync.deleteMedia(id)
        File(mediaDir, id).delete()
    }

    /* ---------- secrets & misc (DB is SQLCipher-encrypted) ---------- */
    suspend fun getMeta(key: String) = sync.meta(key)
    suspend fun setMeta(key: String, value: String?) = sync.putMeta(MetaEntity(key, value))
    fun observeMeta(key: String) = sync.observeMeta(key)

    suspend fun snapshot() = RecordsPayload(allNotes(), allLabels(), allExpenses(), allCategories(), tombstones())

    suspend fun allMedia(): List<MediaBlob> = mediaIds().mapNotNull { getMedia(it) }

    suspend fun wipe() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        mediaDir.listFiles()?.forEach { it.delete() }
        sync.putMeta(MetaEntity("deviceId", hlc.node))
        sync.putMeta(MetaEntity("hlc", hlc.current()))
    }

    /* ---------- SyncStore ---------- */
    override suspend fun allNotes(): List<Note> {
        val by = noteDao.allItems().groupBy { it.noteId }
        return noteDao.allNotes().map { it.toDomain(by[it.id].orEmpty()) }
    }
    override suspend fun allLabels() = tax.labels().map { it.toDomain() }
    override suspend fun allExpenses() = expenseDao.all().map { it.toDomain() }
    override suspend fun allCategories() = tax.categories().map { it.toDomain() }
    override suspend fun tombstones() = sync.tombstones().map { Tombstone(it.id, it.entityType, it.purgedAt, it.hlc) }
    override suspend fun hasTombstone(id: String) = sync.tombstoneCount(id) > 0

    override suspend fun hlcOf(c: Coll, id: String): String? = when (c) {
        Coll.notes -> noteDao.hlc(id)
        Coll.labels -> tax.labelHlc(id)
        Coll.expenses -> expenseDao.hlc(id)
        Coll.categories -> tax.categoryHlc(id)
    }

    override suspend fun putRemote(c: Coll, rec: Versioned) {
        when (c) {
            Coll.notes -> (rec as Note).let { noteDao.saveWithItems(it.toEntity(), it.itemEntities()) }
            Coll.labels -> tax.upsertLabel((rec as Label).toEntity())
            Coll.expenses -> expenseDao.upsert((rec as Expense).toEntity())
            Coll.categories -> tax.upsertCategory((rec as Category).toEntity())
        }
    }

    override suspend fun applyTombstone(t: Tombstone) {
        val media = sync.mediaByOwner(t.id)
        db.withTransaction {
            when (Coll.ofEntity(t.entityType)) {
                Coll.notes -> noteDao.delete(t.id) // items cascade
                Coll.labels -> tax.deleteLabel(t.id)
                Coll.expenses -> expenseDao.delete(t.id)
                Coll.categories -> tax.deleteCategory(t.id)
            }
            media.forEach { sync.deleteMedia(it.id) }
            sync.putTombstone(SyncTombstoneEntity(t.id, t.entityType, t.purgedAt, t.hlc))
            sync.putMeta(MetaEntity("hlc", hlc.current()))
        }
        withContext(Dispatchers.IO) { media.forEach { File(mediaDir, it.id).delete() } }
    }

    override suspend fun mediaIds() = sync.media().map { it.id }

    override suspend fun getMedia(id: String): MediaBlob? = withContext(Dispatchers.IO) {
        val m = sync.mediaById(id) ?: return@withContext null
        val f = File(mediaDir, id)
        if (!f.exists()) return@withContext null
        MediaBlob(id, m.ownerId, m.mime, Crypto.decrypt(keys.mediaKey, f.readBytes()))
    }

    override suspend fun putMedia(m: MediaBlob) = withContext(Dispatchers.IO) {
        File(mediaDir, m.id).writeBytes(Crypto.encrypt(keys.mediaKey, m.bytes))
        sync.putMedia(MediaEntity(m.id, m.ownerId, m.mime, m.bytes.size.toLong()))
    }

    override fun observeHlc(hlc: String) = this.hlc.receive(hlc)
}
