package app.fingernotes

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fingernotes.core.Bip39
import app.fingernotes.data.KeyManager
import app.fingernotes.data.Repository
import app.fingernotes.data.SettingsStore
import app.fingernotes.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Manual DI container (Clean Architecture: core = domain, data = repositories, ui = MVVM). */
class AppContainer(val context: Context) {
    val keys = KeyManager(context)
    val settings = SettingsStore(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bip39 by lazy { Bip39(context.assets.open("bip39-english.txt").bufferedReader().readLines().filter { it.isNotBlank() }) }

    private val initLock = Mutex()
    private var repo: Repository? = null

    /** Opens the SQLCipher DB on first use (works while the UI is locked — see KeyManager). */
    suspend fun repository(): Repository = initLock.withLock {
        repo ?: Repository(AppDatabase.open(context, keys.dbPassphrase), keys, context.filesDir).also {
            it.init()
            repo = it
        }
    }

    fun repositoryBlocking() = runBlocking(Dispatchers.IO) { repository() }
}

class FingerNotesApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "trash-purge",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

/** Daily job: permanently delete items trashed ≥ 30 days ago and write SyncTombstones. */
class TrashPurgeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = runCatching {
        (applicationContext as FingerNotesApp).container.repository().purgeExpired()
        Result.success()
    }.getOrElse { Result.retry() }
}
