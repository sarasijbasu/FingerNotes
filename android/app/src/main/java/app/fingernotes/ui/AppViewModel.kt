package app.fingernotes.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fingernotes.FingerNotesApp
import app.fingernotes.core.Backup
import app.fingernotes.core.BackupContents
import app.fingernotes.core.Bip39
import app.fingernotes.core.Category
import app.fingernotes.core.Expense
import app.fingernotes.core.Label
import app.fingernotes.core.Note
import app.fingernotes.core.SignalMessage
import app.fingernotes.core.Signal
import app.fingernotes.core.SyncMaterial
import app.fingernotes.core.WireJson
import app.fingernotes.core.XlsxExporter
import app.fingernotes.data.AppSettings
import app.fingernotes.data.Repository
import app.fingernotes.sync.MessageChannel
import app.fingernotes.sync.Peer
import app.fingernotes.sync.AutoState
import app.fingernotes.sync.AutoSync
import app.fingernotes.sync.PeerRecord
import app.fingernotes.sync.SelfInfo
import app.fingernotes.sync.SyncSession
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

data class Snack(val text: String, val actionLabel: String? = null, val action: (suspend () -> Unit)? = null)


sealed interface SyncStage {
    data object Idle : SyncStage
    data class ShowOffer(val code: String) : SyncStage
    data object AwaitOffer : SyncStage
    data class ShowAnswer(val code: String) : SyncStage
    data object Syncing : SyncStage
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as FingerNotesApp).container
    val keys = container.keys
    val settings: StateFlow<AppSettings> = container.settings.settings
    val lock = AppLock { settings.value.lockTimeoutMs }

    private val repoFlow = MutableStateFlow<Repository?>(null)
    val repo get() = repoFlow.value!!

    private fun <T> fromRepo(f: (Repository) -> kotlinx.coroutines.flow.Flow<List<T>>): StateFlow<List<T>> =
        repoFlow.flatMapLatest { r -> r?.let(f) ?: emptyFlow() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val notes: StateFlow<List<Note>> = fromRepo { it.notes }
    val labels: StateFlow<List<Label>> = fromRepo { it.labels }
    val expenses: StateFlow<List<Expense>> = fromRepo { it.expenses }
    val categories: StateFlow<List<Category>> = fromRepo { it.categories }
    val ready = MutableStateFlow(false)

    val snacks = MutableSharedFlow<Snack>(extraBufferCapacity = 4)

    init {
        viewModelScope.launch {
            repoFlow.value = withContext(Dispatchers.IO) { container.repository() }
            ready.value = true
        }
        // Internet auto-sync runs only while unlocked; restart it when the relay URL changes.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(lock.locked, settings) { locked, s -> locked to s.signalingUrl }
                .distinctUntilChanged()
                .collect { (locked, url) -> if (locked || !ready.value) autoSync?.stop() else restartAutoSync(url) }
        }
    }

    fun snack(text: String, actionLabel: String? = null, action: (suspend () -> Unit)? = null) {
        snacks.tryEmit(Snack(text, actionLabel, action))
    }

    fun io(block: suspend Repository.() -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { repo.block() }.onFailure { snack(it.message ?: "Error") }
    }

    fun onUnlocked() {
        lock.unlock()
        io { purgeExpired() } // auto-sync starts from the lock-state collector in init
    }

    fun updateSettings(f: (AppSettings) -> AppSettings) = container.settings.update(f)

    /* ---------------- notes ---------------- */
    fun trashNote(n: Note) = io {
        trashNote(n.id)
        snack("Note moved to Trash", "Undo") { repo.updateNote(n.id) { it.copy(isTrashed = false, deletedAt = null, isPinned = n.isPinned) } }
    }
    fun archiveNote(n: Note) = io {
        updateNote(n.id) { it.copy(isArchived = !n.isArchived, isPinned = false) }
        snack(if (n.isArchived) "Note unarchived" else "Note archived", "Undo") { repo.updateNote(n.id) { it.copy(isArchived = n.isArchived, isPinned = n.isPinned) } }
    }
    fun hideNote(n: Note) = io {
        updateNote(n.id) { it.copy(isHidden = !n.isHidden, isPinned = false) }
        snack(if (n.isHidden) "Moved out of the vault" else "Note hidden in the vault", "Undo") { repo.updateNote(n.id) { it.copy(isHidden = n.isHidden) } }
    }

    /* ---------------- export / backup ---------------- */
    fun exportXlsx(uri: Uri, from: Long?, to: Long?) = io {
        val rows = XlsxExporter.filterByRange(allExpenses(), from, to)
        val cats = allCategories().associateBy { it.id }
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { XlsxExporter.write(it, rows, cats) }
        snack("Exported ${rows.size} expenses")
    }

    fun createBackup(uri: Uri, password: String) = io {
        val tmp = File(getApplication<Application>().cacheDir, "backup.zip")
        Backup.create(tmp, snapshot(), allMedia(), password.toCharArray())
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        tmp.delete()
        snack("Encrypted backup saved")
    }

    suspend fun readBackup(uri: Uri, password: String): BackupContents = withContext(Dispatchers.IO) {
        val tmp = File(getApplication<Application>().cacheDir, "restore.zip")
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        try { Backup.read(tmp, password.toCharArray()) } finally { tmp.delete() }
    }

    fun applyBackup(b: BackupContents, overwrite: Boolean) = io {
        if (overwrite) wipe()
        val r = Backup.merge(this, b)
        snack("Restore complete: ${r.applied} applied, ${r.skipped} kept (newer locally)")
    }

    /* ---------------- sync ---------------- */
    val bip39: Bip39 get() = container.bip39
    val chain = MutableStateFlow<String?>(null)
    val material = MutableStateFlow<SyncMaterial?>(null)
    val peers = MutableStateFlow<List<PeerRecord>>(emptyList())
    val syncStage = MutableStateFlow<SyncStage>(SyncStage.Idle)
    val syncLog = MutableStateFlow<List<String>>(emptyList())
    private var pendingPeer: Peer? = null

    /* ---- internet device-to-device auto sync ---- */
    private var autoSync: AutoSync? = null
    val autoState = MutableStateFlow(AutoState())
    private var autoStateJob: kotlinx.coroutines.Job? = null

    private fun restartAutoSync(url: String) {
        val r = repoFlow.value ?: return
        val a = autoSync ?: AutoSync(getApplication(), r, { settings.value.deviceName }, { settings.value.useStun }) { peers.value = it }
            .also { created ->
                autoSync = created
                autoStateJob = viewModelScope.launch { created.state.collect { autoState.value = it } }
            }
        a.start(url)
    }

    /** Pull-to-refresh: sync with every online chain device, then report. */
    suspend fun refreshSync(): String = autoSync?.refresh() ?: "Sync is not running — unlock the app first"

    fun loadChain() = io {
        val m = getMeta("chain")
        chain.value = m
        peers.value = getMeta("peers")?.let { WireJson.decodeFromString<List<PeerRecord>>(it) } ?: emptyList()
        material.value = m?.let { Bip39.deriveSyncMaterial(it) }
    }

    fun setChain(mnemonic: String?) = io {
        setMeta("chain", mnemonic?.let { Bip39.normalize(it) })
        loadChain()
        restartAutoSync(settings.value.signalingUrl)
    }

    private fun log(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        syncLog.value = (syncLog.value + "$t  $s").takeLast(40)
    }

    private val self get() = SelfInfo(repo.deviceId, settings.value.deviceName)
    private fun signal(type: String, sdp: String? = null) = SignalMessage(type, sdp, self.deviceId, self.deviceName, "android")

    private fun runChannel(channel: Deferred<MessageChannel>) = viewModelScope.launch(Dispatchers.IO) {
        val m = material.value ?: return@launch
        try {
            val ch = withTimeout(60_000) { channel.await() }
            syncStage.value = SyncStage.Syncing
            log("Peer connected — exchanging changes")
            val r = SyncSession.run(repo, m.key, self, ch, ::log)
            val list = listOf(PeerRecord(r.peerId, r.peerName, r.peerPlatform, System.currentTimeMillis())) + peers.value.filter { it.deviceId != r.peerId }
            peers.value = list
            repo.setMeta("peers", WireJson.encodeToString(list))
            snack("Synced with ${r.peerName}: ↑${r.sent} ↓${r.received} (${r.mediaIn + r.mediaOut} files)")
            kotlinx.coroutines.delay(1500)
            ch.close()
        } catch (e: Exception) {
            log("Error: ${e.message}")
        } finally {
            syncStage.value = SyncStage.Idle
        }
    }

    fun startOffer() = viewModelScope.launch(Dispatchers.IO) {
        val m = material.value ?: return@launch
        log("Gathering network candidates…")
        val peer = Peer(Peer.factory(getApplication()), settings.value.useStun).also { pendingPeer = it }
        peer.onFailure { log(it) }
        val (sdp, channel) = peer.createOffer()
        syncStage.value = SyncStage.ShowOffer(Signal.encode(m.key, signal("offer", sdp)))
        log("Show this code to the other device, then scan its reply.")
        runChannel(channel)
    }

    fun awaitOffer() { syncStage.value = SyncStage.AwaitOffer }

    fun handleCode(text: String) = viewModelScope.launch(Dispatchers.IO) {
        val m = material.value ?: return@launch
        try {
            val sig = Signal.decode(m.key, text)
            val stage = syncStage.value
            when {
                sig.type == "answer" && stage is SyncStage.ShowOffer -> {
                    pendingPeer?.acceptAnswer(sig.sdp!!)
                    log("Answer from ${sig.deviceName} accepted — connecting…")
                }
                sig.type == "offer" -> {
                    val peer = Peer(Peer.factory(getApplication()), settings.value.useStun)
                    peer.onFailure { log(it) }
                    val (sdp, channel) = peer.answerOffer(sig.sdp!!)
                    syncStage.value = SyncStage.ShowAnswer(Signal.encode(m.key, signal("answer", sdp)))
                    log("Offer from ${sig.deviceName}. Show this reply code to it.")
                    runChannel(channel)
                }
                else -> log("Unexpected code type: ${sig.type}")
            }
        } catch (e: Exception) {
            log("Error: ${e.message}")
        }
    }

    fun cancelSync() {
        pendingPeer?.close(); pendingPeer = null
        syncStage.value = SyncStage.Idle
    }

    override fun onCleared() {
        cancelSync()
        autoSync?.stop()
        super.onCleared()
    }
}
