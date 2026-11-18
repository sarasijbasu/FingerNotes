package app.fingernotes.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import app.fingernotes.core.Attachment
import app.fingernotes.core.WireJson
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/* ---------------- Entities (Room + SQLCipher) — see docs/PROTOCOL.md §4 ---------------- */

@Entity(tableName = "notes", indices = [Index("deletedAt")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val color: String,
    val labelIds: List<String>,
    val attachments: List<Attachment>,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isHidden: Boolean,
    val isTrashed: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val hlc: String,
)

@Entity(
    tableName = "checklist_items",
    indices = [Index("noteId")],
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
)
data class ChecklistItemEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val text: String,
    val checked: Boolean,
    val position: Int,
)

@Entity(tableName = "labels")
data class LabelEntity(@PrimaryKey val id: String, val name: String, val createdAt: Long, val updatedAt: Long, val hlc: String)

@Entity(tableName = "expenses", indices = [Index("deletedAt"), Index("occurredAt")])
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val amount: Double,
    val currency: String,
    val title: String,
    val categoryId: String,
    val paymentMethod: String,
    val occurredAt: Long,
    val notes: String,
    val receipt: Attachment?,
    val isTrashed: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val hlc: String,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val builtIn: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val hlc: String,
)

@Entity(tableName = "sync_tombstones")
data class SyncTombstoneEntity(@PrimaryKey val id: String, val entityType: String, val purgedAt: Long, val hlc: String)

/** Metadata for an encrypted media file stored at filesDir/media/<id>. */
@Entity(tableName = "media", indices = [Index("ownerId")])
data class MediaEntity(@PrimaryKey val id: String, val ownerId: String, val mime: String, val size: Long)

@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, @ColumnInfo val value: String?)

/* ---------------- Converters ---------------- */

class Converters {
    @TypeConverter fun fromList(v: List<String>): String = WireJson.encodeToString(v)
    @TypeConverter fun toList(v: String): List<String> = WireJson.decodeFromString(v)
    @TypeConverter fun fromAttachments(v: List<Attachment>): String = WireJson.encodeToString(v)
    @TypeConverter fun toAttachments(v: String): List<Attachment> = WireJson.decodeFromString(v)
    @TypeConverter fun fromAttachment(v: Attachment?): String? = v?.let { WireJson.encodeToString(it) }
    @TypeConverter fun toAttachment(v: String?): Attachment? = v?.let { WireJson.decodeFromString<Attachment>(it) }
}

/* ---------------- DAOs ---------------- */

@Dao
abstract class NoteDao {
    @Query("SELECT * FROM notes") abstract fun observeNotes(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM checklist_items ORDER BY position") abstract fun observeItems(): Flow<List<ChecklistItemEntity>>
    @Query("SELECT * FROM notes") abstract suspend fun allNotes(): List<NoteEntity>
    @Query("SELECT * FROM checklist_items ORDER BY position") abstract suspend fun allItems(): List<ChecklistItemEntity>
    @Query("SELECT * FROM notes WHERE id = :id") abstract suspend fun get(id: String): NoteEntity?
    @Query("SELECT * FROM checklist_items WHERE noteId = :id ORDER BY position") abstract suspend fun items(id: String): List<ChecklistItemEntity>
    @Query("SELECT hlc FROM notes WHERE id = :id") abstract suspend fun hlc(id: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertNote(n: NoteEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertItems(items: List<ChecklistItemEntity>)
    @Query("DELETE FROM checklist_items WHERE noteId = :id") abstract suspend fun deleteItems(id: String)
    @Query("DELETE FROM notes WHERE id = :id") abstract suspend fun delete(id: String)
    @Query("SELECT id FROM notes WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff") abstract suspend fun expired(cutoff: Long): List<String>

    @Transaction
    open suspend fun saveWithItems(n: NoteEntity, items: List<ChecklistItemEntity>) {
        // REPLACE on notes would cascade-delete items, so rewrite them inside the same transaction.
        upsertNote(n); deleteItems(n.id); upsertItems(items)
    }
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses") fun observe(): Flow<List<ExpenseEntity>>
    @Query("SELECT * FROM expenses") suspend fun all(): List<ExpenseEntity>
    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun get(id: String): ExpenseEntity?
    @Query("SELECT hlc FROM expenses WHERE id = :id") suspend fun hlc(id: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: ExpenseEntity)
    @Query("DELETE FROM expenses WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT id FROM expenses WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff") suspend fun expired(cutoff: Long): List<String>
}

@Dao
interface TaxonomyDao {
    @Query("SELECT * FROM labels ORDER BY name") fun observeLabels(): Flow<List<LabelEntity>>
    @Query("SELECT * FROM labels") suspend fun labels(): List<LabelEntity>
    @Query("SELECT hlc FROM labels WHERE id = :id") suspend fun labelHlc(id: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLabel(l: LabelEntity)
    @Query("DELETE FROM labels WHERE id = :id") suspend fun deleteLabel(id: String)

    @Query("SELECT * FROM categories ORDER BY builtIn DESC, name") fun observeCategories(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT hlc FROM categories WHERE id = :id") suspend fun categoryHlc(id: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCategory(c: CategoryEntity)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun deleteCategory(id: String)
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_tombstones") suspend fun tombstones(): List<SyncTombstoneEntity>
    @Query("SELECT COUNT(*) FROM sync_tombstones WHERE id = :id") suspend fun tombstoneCount(id: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTombstone(t: SyncTombstoneEntity)

    @Query("SELECT * FROM media") suspend fun media(): List<MediaEntity>
    @Query("SELECT * FROM media WHERE id = :id") suspend fun mediaById(id: String): MediaEntity?
    @Query("SELECT * FROM media WHERE ownerId = :owner") suspend fun mediaByOwner(owner: String): List<MediaEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMedia(m: MediaEntity)
    @Query("DELETE FROM media WHERE id = :id") suspend fun deleteMedia(id: String)

    @Query("SELECT value FROM meta WHERE `key` = :key") suspend fun meta(key: String): String?
    @Query("SELECT value FROM meta WHERE `key` = :key") fun observeMeta(key: String): Flow<String?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(m: MetaEntity)
}

@Database(
    entities = [
        NoteEntity::class, ChecklistItemEntity::class, LabelEntity::class, ExpenseEntity::class,
        CategoryEntity::class, SyncTombstoneEntity::class, MediaEntity::class, MetaEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun expenses(): ExpenseDao
    abstract fun taxonomy(): TaxonomyDao
    abstract fun sync(): SyncDao

    companion object {
        fun open(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AppDatabase::class.java, "fingernotes.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
