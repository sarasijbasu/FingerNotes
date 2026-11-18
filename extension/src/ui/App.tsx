import { useCallback, useEffect, useRef, useState } from 'react';
import { getDb } from '../core/db';
import { getSettings, saveSettings, type Settings } from '../core/platform';
import { Repo } from '../core/repo';
import { clearSession, isSetUp, rememberSession, restoreSession, touchSession, verifyHiddenPin, hasHiddenPin, setHiddenPin, type Unlocked } from '../core/vault';
import { Icon, SnackbarHost, useRepo, snack } from './common';
import { ExpensesView } from './Expenses';
import { LockScreen, PinPrompt } from './Lock';
import { NotesView } from './Notes';
import { SettingsView } from './Settings';
import { SyncView } from './Sync';
import { TrashView } from './Trash';
import { Popup } from './Popup';
import { AutoSync } from '../sync/auto';
import { AutoSyncContext, PullToRefresh } from './PullToRefresh';

type Route = 'notes' | 'archive' | 'vault' | 'expenses' | 'trash' | 'sync' | 'settings';
const NAV: Array<[Route, string, string]> = [
  ['notes', 'Notes', 'note'],
  ['expenses', 'Expenses', 'money'],
  ['archive', 'Archive', 'archive'],
  ['vault', 'Hidden', 'hide'],
  ['trash', 'Trash', 'trash'],
  ['sync', 'Sync', 'sync'],
  ['settings', 'Settings', 'settings'],
];

export function App({ mode }: { mode: 'popup' | 'sidepanel' | 'app' }) {
  const db = getDb();
  const [settings, setSettings] = useState<Settings | null>(null);
  const [setUp, setSetUp] = useState<boolean | null>(null);
  const [session, setSession] = useState<{ repo: Repo; unlocked: Unlocked } | null>(null);

  useEffect(() => {
    (async () => {
      const s = await getSettings();
      setSettings(s);
      setSetUp(await isSetUp(db));
      const u = await restoreSession(s.lockTimeoutMs);
      if (u) setSession({ repo: await Repo.open(db, u.dek), unlocked: u });
    })();
  }, [db]);

  const onUnlock = useCallback(
    async (u: Unlocked) => {
      const s = await getSettings();
      await rememberSession(u, s.lockTimeoutMs);
      const repo = await Repo.open(db, u.dek);
      setSetUp(true);
      setSession({ repo, unlocked: u });
      repo.runPurge().catch(() => {});
    },
    [db],
  );

  const lock = useCallback(async () => {
    await clearSession();
    setSession(null);
  }, []);

  // Inactivity auto-lock + "Immediately" lock-on-hide.
  const lastActive = useRef(Date.now());
  useEffect(() => {
    if (!session || !settings) return;
    let lastTouch = 0;
    const activity = () => {
      lastActive.current = Date.now();
      if (Date.now() - lastTouch > 15_000) {
        lastTouch = Date.now();
        void touchSession();
      }
    };
    const vis = () => {
      if (document.hidden && settings.lockTimeoutMs === 0) void lock();
    };
    const iv = setInterval(() => {
      if (settings.lockTimeoutMs > 0 && Date.now() - lastActive.current > settings.lockTimeoutMs) void lock();
    }, 5000);
    ['pointerdown', 'keydown', 'wheel', 'touchstart'].forEach((e) => window.addEventListener(e, activity, { passive: true }));
    document.addEventListener('visibilitychange', vis);
    return () => {
      clearInterval(iv);
      ['pointerdown', 'keydown', 'wheel', 'touchstart'].forEach((e) => window.removeEventListener(e, activity));
      document.removeEventListener('visibilitychange', vis);
    };
  }, [session, settings, lock]);

  // Background purge / context-menu clips / lock from another surface.
  useEffect(() => {
    if (!session || typeof chrome === 'undefined' || !chrome.runtime?.onMessage) return;
    const onMsg = (m: { type?: string }) => {
      if (m?.type === 'db-changed') void session.repo.reload();
      if (m?.type === 'inbox') void session.repo.importClips();
    };
    chrome.runtime.onMessage.addListener(onMsg);
    const onStore = (changes: Record<string, chrome.storage.StorageChange>, area: string) => {
      if (area === 'session' && 'unlock' in changes && !changes.unlock.newValue && settings!.lockTimeoutMs > 0) setSession(null);
    };
    chrome.storage.onChanged.addListener(onStore);
    return () => {
      chrome.runtime.onMessage.removeListener(onMsg);
      chrome.storage.onChanged.removeListener(onStore);
    };
  }, [session, settings]);

  if (!settings || setUp === null) return null;
  if (!session) return <ObscureGuard always><LockScreen db={db} isSetUp={setUp} onUnlock={onUnlock} /></ObscureGuard>;
  if (mode === 'popup') return <><Popup repo={session.repo} settings={settings} /><SnackbarHost /></>;
  return <Main repo={session.repo} unlocked={session.unlocked} settings={settings} onSettings={setSettings} onLock={lock} />;
}

function Main({ repo, unlocked, settings, onSettings, onLock }: { repo: Repo; unlocked: Unlocked; settings: Settings; onSettings: (s: Settings) => void; onLock: () => void }) {
  useRepo(repo);
  const [route, setRoute] = useState<Route>(() => (location.hash.slice(1) as Route) || 'notes');
  const [vaultOpen, setVaultOpen] = useState(false);
  const [vaultHasPin, setVaultHasPin] = useState<boolean | null>(null);
  const [chainVersion, setChainVersion] = useState(0);
  const [auto, setAuto] = useState<AutoSync | null>(null);

  // Internet device-to-device sync: runs while this surface is unlocked.
  useEffect(() => {
    const a = new AutoSync(repo, settings);
    setAuto(a);
    void a.start();
    return () => a.stop();
  }, [repo, settings.signalingUrl, settings.deviceName, settings.useStun, chainVersion]);

  useEffect(() => {
    location.hash = route;
    if (route !== 'vault') setVaultOpen(false);
    if (route === 'vault') hasHiddenPin(repo.db).then(setVaultHasPin);
  }, [route, repo.db]);

  const go = (r: Route) => setRoute(r);
  const secure = route === 'vault';

  const reload = () => repo.reload();

  return (
    <AutoSyncContext.Provider value={auto}>
    <ObscureGuard always={secure} onBlur={settings.blurOnFocusLoss}>
      <div className="shell">
        <nav className="nav">
          <div className="brand"><span style={{ width: 22, color: 'var(--accent)' }}><Icon name="fingerprint" /></span>FingerNotes</div>
          {NAV.map(([r, label, icon]) => (
            <button key={r} className={route === r ? 'active' : ''} onClick={() => go(r)} title={label}>
              <Icon name={icon} />
              <span className="lbl">{label}</span>
            </button>
          ))}
          <button onClick={onLock} title="Lock"><Icon name="lock" /><span className="lbl">Lock</span></button>
        </nav>
        <main className="main">
          {route === 'notes' && <PullToRefresh onRefreshed={reload}><NotesView repo={repo} filter="main" view={settings.view} onView={async (view) => onSettings(await saveSettings({ view }))} /></PullToRefresh>}
          {route === 'archive' && <PullToRefresh onRefreshed={reload}><NotesView repo={repo} filter="archive" view={settings.view} onView={async (view) => onSettings(await saveSettings({ view }))} /></PullToRefresh>}
          {route === 'vault' &&
            (vaultHasPin === null ? null : !vaultHasPin ? (
              <SetVaultPin onDone={async (pin) => { await setHiddenPin(repo.db, pin); setVaultHasPin(true); setVaultOpen(true); }} />
            ) : vaultOpen ? (
              <PullToRefresh onRefreshed={reload}><NotesView repo={repo} filter="hidden" view={settings.view} onView={async (view) => onSettings(await saveSettings({ view }))} /></PullToRefresh>
            ) : (
              <PinPrompt title="Hidden notes" verify={(p) => verifyHiddenPin(repo.db, p)} onOk={() => setVaultOpen(true)} />
            ))}
          {route === 'expenses' && <PullToRefresh onRefreshed={reload}><ExpensesView repo={repo} currency={settings.defaultCurrency} /></PullToRefresh>}
          {route === 'trash' && <TrashView repo={repo} />}
          {route === 'sync' && <SyncView repo={repo} settings={settings} onChainChanged={() => setChainVersion((v) => v + 1)} />}
          {route === 'settings' && <SettingsView repo={repo} settings={settings} unlocked={unlocked} onSettings={onSettings} onLock={onLock} />}
        </main>
      </div>
      <SnackbarHost />
    </ObscureGuard>
    </AutoSyncContext.Provider>
  );
}

function SetVaultPin({ onDone }: { onDone: (pin: string) => void }) {
  const [a, setA] = useState('');
  const [b, setB] = useState('');
  return (
    <div className="lock" style={{ minHeight: '60vh' }}>
      <div className="box">
        <h2>Set up the Hidden vault</h2>
        <p className="muted">Hidden notes are removed from the main grid and search, and need this second PIN to view.</p>
        <input className="pin-input" type="password" placeholder="Vault PIN" value={a} onChange={(e) => setA(e.target.value)} />
        <input className="pin-input" type="password" placeholder="Repeat" value={b} onChange={(e) => setB(e.target.value)} />
        <button className="btn primary" disabled={a.length < 4} onClick={() => (a === b ? onDone(a) : snack({ text: 'PINs do not match' }))}>Create vault</button>
      </div>
    </div>
  );
}

/** Blur/mask overlay while the window is unfocused or hidden (Chrome analogue of FLAG_SECURE). */
function ObscureGuard({ children, always, onBlur }: { children: React.ReactNode; always?: boolean; onBlur?: boolean }) {
  useEffect(() => {
    if (!always && !onBlur) return;
    const set = (on: boolean) => document.body.classList.toggle('obscured', on);
    const blur = () => set(true);
    const focus = () => set(false);
    const vis = () => set(document.hidden);
    window.addEventListener('blur', blur);
    window.addEventListener('focus', focus);
    document.addEventListener('visibilitychange', vis);
    return () => {
      set(false);
      window.removeEventListener('blur', blur);
      window.removeEventListener('focus', focus);
      document.removeEventListener('visibilitychange', vis);
    };
  }, [always, onBlur]);
  return <>{children}</>;
}
