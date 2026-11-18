package app.fingernotes

import app.fingernotes.core.Bip39
import app.fingernotes.core.Bytes
import app.fingernotes.core.Category
import app.fingernotes.core.Coll
import app.fingernotes.core.Crypto
import app.fingernotes.core.Expense
import app.fingernotes.core.Hlc
import app.fingernotes.core.Label
import app.fingernotes.core.MediaBlob
import app.fingernotes.core.Merge
import app.fingernotes.core.Note
import app.fingernotes.core.Backup
import app.fingernotes.core.BackupException
import app.fingernotes.core.RecordsPayload
import app.fingernotes.core.Signal
import app.fingernotes.core.SignalMessage
import app.fingernotes.core.SyncStore
import app.fingernotes.core.Tombstone
import app.fingernotes.core.Versioned
import app.fingernotes.core.XlsxExporter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Proves Android <-> Chrome interoperability against fixtures produced by the extension
 * (`npm run vectors` → shared/test-vectors.json, shared/fixtures/web-backup.zip).
 */
class CrossPlatformTest {
    private val shared = File("../../shared")
    private val vectors = Json.parseToJsonElement(File(shared, "test-vectors.json").readText()).jsonObject
    private fun v(vararg path: String): String {
        var o = vectors
        for (p in path.dropLast(1)) o = o[p]!!.jsonObject
        return o[path.last()]!!.jsonPrimitive.content
    }
    private val bip39 = Bip39(File(shared, "bip39-english.txt").readLines().filter { it.isNotBlank() })

    @Test fun mnemonicDerivesSameKeyAsChrome() {
        val m = v("mnemonic")
        assertTrue(bip39.isValid(m))
        val sm = Bip39.deriveSyncMaterial(m)
        assertEquals(v("syncKeyHex"), Bytes.hex(sm.rawKey))
        assertEquals(v("chainId"), sm.chainId)
    }

    @Test fun generatedMnemonicsAreValid() {
        repeat(20) { assertTrue(bip39.isValid(bip39.generate())) }
        assertFalse(bip39.isValid(v("mnemonic").replace(" art", " abandon")))
    }

    @Test fun aesGcmEnvelopeMatchesChrome() {
        val key = Crypto.aesKey(Bytes.fromHex(v("syncKeyHex")))
        val iv = Bytes.fromHex(v("aesGcm", "ivHex"))
        val env = Crypto.ENVELOPE_PREFIX + Bytes.b64url(Crypto.encrypt(key, v("aesGcm", "plaintext").toByteArray(), iv))
        assertEquals(v("aesGcm", "envelope"), env)
        assertEquals(v("aesGcm", "plaintext"), Crypto.openText(key, v("aesGcm", "envelope")))
    }

    @Test fun decodesChromeSignalAndRoundTrips() {
        val key = Crypto.aesKey(Bytes.fromHex(v("syncKeyHex")))
        val sig = Signal.decode(key, v("signal", "code"))
        assertEquals("offer", sig.type)
        assertEquals(v("signal", "sdp"), sig.sdp)
        val back = Signal.decode(key, Signal.encode(key, SignalMessage("answer", "x", "d", "n")))
        assertEquals("answer", back.type)
    }

    @Test fun hlcOrderingAndFormat() {
        val order = vectors["hlcOrder"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(order, order.sorted())
        var t = 1000L
        val h = Hlc("aaaaaaaaaaaaaaaa", now = { t })
        val a = h.tick(); val b = h.tick()
        assertTrue(a < b)
        assertEquals("000000000001000-00000-aaaaaaaaaaaaaaaa", a)
        h.receive("000000000005000-00003-bbbbbbbbbbbbbbbb"); t = 10
        assertTrue(h.tick() > "000000000005000-00003-bbbbbbbbbbbbbbbb")
    }

    @Test fun readsZipJsBackup() {
        val b = Backup.read(File(shared, v("backup", "file")), v("backup", "password").toCharArray())
        assertEquals("chrome", b.metadata.platform)
        val note = b.records.notes.single()
        assertEquals(v("backup", "noteId"), note.id)
        assertEquals("from chrome", note.items.single().text)
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71), b.media.single { it.id == v("backup", "mediaId") }.bytes)
        assertEquals(1, b.records.tombstones.size)
        try {
            Backup.read(File(shared, v("backup", "file")), "nope".toCharArray()); fail()
        } catch (e: BackupException) { assertEquals(BackupException.Code.BAD_PASSWORD, e.code) }
    }

    @Test fun writesBackupForChrome() {
        val out = File(System.getProperty("interopOut") ?: "build/interop").apply { mkdirs() }
        val f = File(out, "android-backup.zip")
        val note = Note("22222222-2222-4222-8222-222222222222", kind = "text", title = "From Android", body = "*hi*", createdAt = 1, updatedAt = 2, hlc = "000000000000002-00000-bbbbbbbbbbbbbbbb")
        val exp = Expense("e1", 12.5, "₹", "Tea", "food", "UPI_BANK", 1_700_000_000_000, createdAt = 1, updatedAt = 1, hlc = "000000000000001-00000-bbbbbbbbbbbbbbbb")
        Backup.create(f, RecordsPayload(notes = listOf(note), expenses = listOf(exp)), listOf(MediaBlob("m9", note.id, "audio/mp4", byteArrayOf(9, 9, 9))), "android-pw".toCharArray())
        val back = Backup.read(f, "android-pw".toCharArray())
        assertEquals("From Android", back.records.notes.single().title)
        assertEquals(12.5, back.records.expenses.single().amount, 0.0)
    }

    @Test fun mergeConvergesWithTombstones() = runTest {
        val a = MemStore("aaaaaaaaaaaaaaaa"); val b = MemStore("bbbbbbbbbbbbbbbb")
        val n = Note("n1", title = "v1", hlc = a.hlc.tick())
        a.notes[n.id] = n
        Merge.applyRecords(b, Merge.diffForPeer(a, Merge.buildManifest(b)))
        assertEquals("v1", b.notes["n1"]!!.title)
        b.notes["n1"] = b.notes["n1"]!!.copy(title = "v2", hlc = b.hlc.tick())
        Merge.applyRecords(a, Merge.diffForPeer(b, Merge.buildManifest(a)))
        assertEquals("v2", a.notes["n1"]!!.title)
        // purge on B; stale edit on A must not resurrect it
        b.applyTombstone(Tombstone("n1", "note", 5, b.hlc.tick()))
        a.notes["n1"] = a.notes["n1"]!!.copy(title = "zombie", hlc = a.hlc.tick())
        Merge.applyRecords(a, Merge.diffForPeer(b, Merge.buildManifest(a)))
        Merge.applyRecords(b, Merge.diffForPeer(a, Merge.buildManifest(b)))
        assertFalse(a.notes.containsKey("n1")); assertFalse(b.notes.containsKey("n1"))
    }

    @Test fun xlsxHasHeadersAndSumFormula() {
        val ex = listOf(
            Expense("1", 10.5, "₹", "Tea", "food", "CASH", 1_767_000_000_000),
            Expense("2", 20.0, "₹", "Bus", "transport", "UPI_BANK", 1_767_100_000_000),
        )
        // FastExcel streams zip64 data descriptors, which ZipInputStream rejects; Excel (and ZipFile)
        // read the central directory instead, so inspect it the same way.
        val f = File.createTempFile("expenses", ".xlsx").apply { deleteOnExit() }
        f.outputStream().use { XlsxExporter.write(it, ex, mapOf("food" to Category("food", "Food", "#F00", true, 0, 0, ""))) }
        var sheet = ""; var strings = ""
        ZipFile(f).use { z ->
            for (e in z.entries()) {
                val t = z.getInputStream(e).use { it.readBytes() }.decodeToString()
                if (e.name.endsWith("sheet1.xml")) sheet = t
                if (e.name.endsWith("sharedStrings.xml")) strings = t
            }
        }
        assertTrue(sheet.contains("SUM(G2:G3)"))
        assertTrue(strings.contains("Title / Merchant") && strings.contains("UPI/Bank") && strings.contains("TOTAL"))
        assertEquals(30.5, XlsxExporter.total(ex), 0.0)
    }
}

/** In-memory SyncStore for engine tests. */
private class MemStore(node: String) : SyncStore {
    val hlc = Hlc(node)
    val notes = LinkedHashMap<String, Note>()
    val tombs = LinkedHashMap<String, Tombstone>()
    val media = LinkedHashMap<String, MediaBlob>()
    override suspend fun allNotes() = notes.values.toList()
    override suspend fun allLabels() = emptyList<Label>()
    override suspend fun allExpenses() = emptyList<Expense>()
    override suspend fun allCategories() = emptyList<Category>()
    override suspend fun tombstones() = tombs.values.toList()
    override suspend fun hlcOf(c: Coll, id: String) = if (c == Coll.notes) notes[id]?.hlc else null
    override suspend fun hasTombstone(id: String) = id in tombs
    override suspend fun putRemote(c: Coll, rec: Versioned) { if (c == Coll.notes) notes[rec.id] = rec as Note }
    override suspend fun applyTombstone(t: Tombstone) { tombs[t.id] = t; notes.remove(t.id) }
    override suspend fun mediaIds() = media.keys.toList()
    override suspend fun getMedia(id: String) = media[id]
    override suspend fun putMedia(m: MediaBlob) { media[m.id] = m }
    override fun observeHlc(hlc: String) = this.hlc.receive(hlc)
}
