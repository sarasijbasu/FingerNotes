import { useEffect, useRef, useState } from 'react';
import { BackupError, createBackup, mergeBackup, readBackup, type BackupContents } from '../core/backup';
import type { MediaBlob } from '../core/merge';
import { saveSettings, type Settings } from '../core/platform';
import type { Repo } from '../core/repo';
import { changePin, enrollWebAuthn, hasHiddenPin, hasWebAuthn, removeWebAuthn, setHiddenPin, verifyPin, webauthnSupported, type Unlocked } from '../core/vault';
import { download, snack } from './common';

export function SettingsView({ repo, settings, unlocked, onSettings, onLock }: { repo: Repo; settings: Settings; unlocked: Unlocked; onSettings: (s: Settings) => void; onLock: () => void }) {
  const [bio, setBio] = useState(false);
  const [hidden, setHidden] = useState(false);
  const db = repo.db;
  useEffect(() => {
    hasWebAuthn(db).then(setBio);
    hasHiddenPin(db).then(setHidden);
  }, [db]);
  const set = async (p: Partial<Settings>) => onSettings(await saveSettings(p));

  return (
    <div className="settings" style={{ display: 'grid', gap: 14, maxWidth: 720 }}>
      <section className="card-panel">
        <h2>Security</h2>
        <div className="form-grid">
          <label className="field">Auto-lock after inactivity
            <select value={settings.lockTimeoutMs} onChange={(e) => set({ lockTimeoutMs: Number(e.target.value) })}>
              <option value={0}>Immediately (when hidden)</option>
              <option value={60_000}>1 minute</option>
              <option value={300_000}>5 minutes</option>
            </select>
          </label>
          <label className="field">Privacy blur
            <select value={settings.blurOnFocusLoss ? '1' : '0'} onChange={(e) => set({ blurOnFocusLoss: e.target.value === '1' })}>
              <option value="1">Blur when window loses focus</option>
              <option value="0">Only in Hidden vault / lock screen</option>
            </select>
          </label>
        </div>
        <div className="row" style={{ marginTop: 12 }}>
          {webauthnSupported() &&
            (bio ? (
              <button className="btn" onClick={async () => { await removeWebAuthn(db); setBio(false); }}>Remove biometric / passkey unlock</button>
            ) : (
              <button className="btn" onClick={async () => {
                try { await enrollWebAuthn(db, unlocked); setBio(true); snack({ text: 'Biometric unlock enabled' }); }
                catch (e) { snack({ text: (e as Error).message }); }
              }}>Enable biometric / passkey unlock</button>
            ))}
          <PinChanger label="Change app PIN" onSave={async (p) => changePin(db, unlocked, p)} />
          <PinChanger label={hidden ? 'Change Hidden-vault PIN' : 'Set Hidden-vault PIN'} onSave={async (p) => { await setHiddenPin(db, p); setHidden(true); }} />
          <button className="btn" onClick={onLock}>Lock now</button>
        </div>
      </section>

      <BackupSection repo={repo} />

      <section className="card-panel">
        <h2>Sync & device</h2>
        <div className="form-grid">
          <label className="field">Device name<input type="text" value={settings.deviceName} onChange={(e) => set({ deviceName: e.target.value })} /></label>
          <label className="field">Default currency<input type="text" maxLength={4} value={settings.defaultCurrency} onChange={(e) => set({ defaultCurrency: e.target.value })} /></label>
          <label className="field" style={{ gridColumn: '1 / -1' }}>
            Optional relay URL (Cloudflare Worker, see /signaling-worker) — leave empty for 100% offline QR pairing
            <input type="url" placeholder="https://fingernotes-signal.<you>.workers.dev" value={settings.signalingUrl} onChange={(e) => set({ signalingUrl: e.target.value.trim() })} />
          </label>
          <label className="field">NAT traversal
            <select value={settings.useStun ? '1' : '0'} onChange={(e) => set({ useStun: e.target.value === '1' })}>
              <option value="1">Use free public STUN when online</option>
              <option value="0">LAN only (air-gapped)</option>
            </select>
          </label>
        </div>
      </section>

      <section className="card-panel">
        <h2>Labels</h2>
        {repo.labels.size === 0 && <p className="muted">Create labels from the note editor.</p>}
        {[...repo.labels.values()].map((l) => (
          <div key={l.id} className="row" style={{ padding: '3px 0' }}>
            <input type="text" defaultValue={l.name} onBlur={(e) => e.target.value.trim() && e.target.value !== l.name && repo.saveLabel(e.target.value.trim(), l.id)} style={{ maxWidth: 260 }} />
            <button className="btn small danger" onClick={() => confirm(`Delete label "${l.name}"?`) && repo.deleteLabel(l.id)}>Delete</button>
          </div>
        ))}
      </section>
    </div>
  );
}

function PinChanger({ label, onSave }: { label: string; onSave: (pin: string) => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [a, setA] = useState('');
  const [b, setB] = useState('');
  if (!open) return <button className="btn" onClick={() => setOpen(true)}>{label}</button>;
  return (
    <div className="row card-panel" style={{ width: '100%' }}>
      <input type="password" placeholder="New PIN / password" value={a} onChange={(e) => setA(e.target.value)} style={{ maxWidth: 200 }} />
      <input type="password" placeholder="Repeat" value={b} onChange={(e) => setB(e.target.value)} style={{ maxWidth: 200 }} />
      <button className="btn primary" onClick={async () => {
        if (a !== b) return snack({ text: 'PINs do not match' });
        try { await onSave(a); snack({ text: 'Saved' }); setOpen(false); setA(''); setB(''); }
        catch (e) { snack({ text: (e as Error).message }); }
      }}>Save</button>
      <button className="btn" onClick={() => setOpen(false)}>Cancel</button>
    </div>
  );
}

function BackupSection({ repo }: { repo: Repo }) {
  const [useAppPin, setUseAppPin] = useState(true);
  const [pw, setPw] = useState('');
  const [busy, setBusy] = useState('');
  const [restore, setRestore] = useState<{ file: File; pw: string; mode: 'merge' | 'overwrite'; preview?: BackupContents; err?: string } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  async function doBackup() {
    if (!pw) return snack({ text: 'Enter a password' });
    if (useAppPin && !(await verifyPin(repo.db, pw))) return snack({ text: 'That is not your App Lock PIN' });
    setBusy('Encrypting backup…');
    try {
      const media = (await Promise.all(repo.mediaIds().map((id) => repo.getMedia(id)))).filter(Boolean) as MediaBlob[];
      const zip = await createBackup(repo.snapshot(), media, pw, 'chrome');
      download(zip, `fingernotes-backup-${new Date().toISOString().slice(0, 10)}.zip`, 'application/zip');
      setPw('');
    } finally {
      setBusy('');
    }
  }

  async function decrypt() {
    if (!restore) return;
    setBusy('Decrypting & verifying checksums…');
    try {
      setRestore({ ...restore, preview: await readBackup(restore.file, restore.pw), err: undefined });
    } catch (e) {
      setRestore({ ...restore, err: e instanceof BackupError ? e.message : String(e) });
    } finally {
      setBusy('');
    }
  }

  async function apply() {
    if (!restore?.preview) return;
    if (restore.mode === 'overwrite' && !confirm('Overwrite ALL local data with this backup?')) return;
    setBusy('Restoring…');
    try {
      if (restore.mode === 'overwrite') await repo.wipe();
      const r = await mergeBackup(repo, restore.preview);
      await repo.reload();
      snack({ text: `Restore complete: ${r.applied} records applied, ${r.skipped} kept (newer locally)` });
      setRestore(null);
    } finally {
      setBusy('');
    }
  }

  return (
    <section className="card-panel">
      <h2>Encrypted backup</h2>
      <p className="muted">AES-256 ZIP compatible with the Android app (Zip4j). Includes notes, hidden & trashed notes, expenses, labels, tombstones and all media.</p>
      <div className="row">
        <select value={useAppPin ? 'pin' : 'custom'} onChange={(e) => setUseAppPin(e.target.value === 'pin')} style={{ width: 'auto' }}>
          <option value="pin">Encrypt with my App Lock PIN</option>
          <option value="custom">Use a custom backup password</option>
        </select>
        <input type="password" placeholder={useAppPin ? 'App Lock PIN' : 'Backup password'} value={pw} onChange={(e) => setPw(e.target.value)} style={{ maxWidth: 220 }} />
        <button className="btn primary" disabled={!!busy} onClick={doBackup}>Create backup</button>
      </div>
      <h3>Restore</h3>
      <input ref={fileRef} type="file" accept=".zip,application/zip" onChange={(e) => e.target.files?.[0] && setRestore({ file: e.target.files[0], pw: '', mode: 'merge' })} />
      {restore && (
        <div style={{ display: 'grid', gap: 8, marginTop: 8 }}>
          <div className="row">
            <input type="password" placeholder="Backup password" value={restore.pw} onChange={(e) => setRestore({ ...restore, pw: e.target.value })} style={{ maxWidth: 220 }} />
            <button className="btn" onClick={decrypt}>Decrypt & verify</button>
          </div>
          {restore.err && <p className="error">{restore.err}</p>}
          {restore.preview && (
            <>
              <p>
                ✔ Checksums verified. Backup from <b>{restore.preview.metadata.platform}</b>, {new Date(restore.preview.metadata.createdAt).toLocaleString()} —{' '}
                {Object.entries(restore.preview.metadata.counts).map(([k, v]) => `${v} ${k}`).join(', ')}
              </p>
              <div className="row">
                <label><input type="radio" checked={restore.mode === 'merge'} onChange={() => setRestore({ ...restore, mode: 'merge' })} /> Merge by UUID (newest wins)</label>
                <label><input type="radio" checked={restore.mode === 'overwrite'} onChange={() => setRestore({ ...restore, mode: 'overwrite' })} /> Overwrite local data</label>
                <button className="btn primary" onClick={apply}>Restore</button>
              </div>
            </>
          )}
        </div>
      )}
      {busy && <p className="muted">{busy}</p>}
    </section>
  );
}
