package app.fingernotes.ui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.fingernotes.core.Note
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Dest(val title: String, val icon: ImageVector) {
    Notes("Notes", Icons.Default.StickyNote2),
    Expenses("Expenses", Icons.Default.Payments),
    Archive("Archive", Icons.Default.Archive),
    Hidden("Hidden notes", Icons.Default.VisibilityOff),
    Trash("Trash", Icons.Default.Delete),
    Sync("Sync", Icons.Default.Sync),
    Settings("Settings", Icons.Default.Settings),
}

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()
    private val processObserver = LifecycleEventObserver { _, e ->
        when (e) {
            Lifecycle.Event.ON_STOP -> vm.lock.onBackground()
            Lifecycle.Event.ON_START -> vm.lock.onForeground()
            else -> {}
        }
    }
    private var sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        handleShare(intent)
        setContent { FingerNotesTheme { Root(vm, this, sharedText.value) { sharedText.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) sharedText.value = i.getStringExtra(Intent.EXTRA_TEXT)
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        vm.lock.touch()
        return super.dispatchTouchEvent(ev)
    }

    /** FLAG_SECURE: blocks screenshots/recents thumbnails on the lock screen and in the Hidden vault. */
    fun setSecure(on: Boolean) {
        if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

@Composable
fun Root(vm: AppViewModel, activity: MainActivity, shared: String?, onSharedConsumed: () -> Unit) {
    val ready by vm.ready.collectAsState()
    val locked by vm.lock.locked.collectAsState()
    LaunchedEffect(Unit) {
        while (true) { delay(5_000); vm.lock.checkIdle() }
    }
    Surface(Modifier.fillMaxSize()) {
        when {
            !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            locked -> { activity.setSecure(true); LockScreen(vm, activity) }
            else -> MainScaffold(vm, activity, shared, onSharedConsumed)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(vm: AppViewModel, activity: MainActivity, shared: String?, onSharedConsumed: () -> Unit) {
    var dest by rememberSaveable { mutableStateOf(Dest.Notes) }
    var editing by remember { mutableStateOf<Pair<Note, Boolean>?>(null) }
    var vaultOpen by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackHost = remember { SnackbarHostState() }

    LaunchedEffect(dest, vaultOpen) {
        if (dest != Dest.Hidden) vaultOpen = false
        activity.setSecure(dest == Dest.Hidden)
    }
    LaunchedEffect(Unit) {
        vm.snacks.collect { s ->
            val r = snackHost.showSnackbar(s.text, s.actionLabel, withDismissAction = s.actionLabel == null)
            if (r == SnackbarResult.ActionPerformed) s.action?.let { a -> vm.io { a() } }
        }
    }
    LaunchedEffect(shared) {
        if (shared != null) {
            editing = vm.repo.newNote().copy(body = shared) to true
            onSharedConsumed()
        }
    }

    editing?.let { (note, isNew) ->
        BackHandler { /* handled inside editor */ }
        NoteEditor(vm, note, isNew) { editing = null }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("FingerNotes", Modifier.padding(24.dp), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                Dest.entries.forEach { d ->
                    NavigationDrawerItem(
                        label = { Text(d.title) }, icon = { Icon(d.icon, null) }, selected = d == dest,
                        onClick = { dest = d; scope.launch { drawer.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                NavigationDrawerItem(
                    label = { Text("Lock now") }, icon = { Icon(Icons.Default.Lock, null) }, selected = false,
                    onClick = { vm.lock.lock() }, modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(dest.title) },
                    navigationIcon = { IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") } },
                    actions = {
                        if (dest in setOf(Dest.Notes, Dest.Archive, Dest.Hidden)) {
                            IconButton({ vm.updateSettings { it.copy(gridView = !it.gridView) } }) {
                                Icon(if (settings.gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "Toggle layout")
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackHost) },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (dest) {
                    Dest.Notes -> NotesScreen(vm, NotesFilter.Main) { n, isNew -> editing = n to isNew }
                    Dest.Archive -> NotesScreen(vm, NotesFilter.Archive) { n, isNew -> editing = n to isNew }
                    Dest.Hidden -> HiddenVault(vm, activity, vaultOpen, { vaultOpen = true }) { n, isNew -> editing = n to isNew }
                    Dest.Expenses -> ExpensesScreen(vm)
                    Dest.Trash -> TrashScreen(vm)
                    Dest.Sync -> SyncScreen(vm)
                    Dest.Settings -> SettingsScreen(vm, activity)
                }
            }
        }
    }
}
