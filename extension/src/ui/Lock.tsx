import { useEffect, useState } from 'react';
import type { FingerNotesDB } from '../core/db';
import { hasWebAuthn, setupVault, unlockWithPin, unlockWithWebAuthn, type Unlocked } from '../core/vault';
import { Icon } from './common';

export function LockScreen({ db, isSetUp, onUnlock, title = 'FingerNotes is locked' }: { db: FingerNotesDB; isSetUp: boolean; onUnlock: (u: Unlocked) => void; title?: string }) {
  const [pin, setPin] = useState('');
  const [pin2, setPin2] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [bio, setBio] = useState(false);

  useEffect(() => {
    if (isSetUp) hasWebAuthn(db).then(setBio);
  }, [db, isSetUp]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr('');
    setBusy(true);
    try {
      if (!isSetUp) {
        if (pin !== pin2) throw new Error('PINs do not match');
        onUnlock(await setupVault(db, pin));
      } else onUnlock(await unlockWithPin(db, pin));
    } catch (x) {
      setErr((x as Error).message);
      setPin('');
    } finally {
      setBusy(false);
    }
  }

  async function biometric() {
    setErr('');
    try {
      onUnlock(await unlockWithWebAuthn(db));
    } catch (x) {
      setErr((x as Error).message);
    }
  }

  return (
    <div className="lock">
      <form className="box" onSubmit={submit}>
        <span className="logo"><Icon name="fingerprint" /></span>
        <h2>{isSetUp ? title : 'Welcome to FingerNotes'}</h2>
        {!isSetUp && <p className="muted">Choose a 4–6 digit PIN or a password. It encrypts everything stored in this browser. It cannot be recovered — keep a backup.</p>}
        <input className="pin-input" type="password" autoFocus inputMode="numeric" placeholder="PIN or password" value={pin} onChange={(e) => setPin(e.target.value)} autoComplete="off" />
        {!isSetUp && <input className="pin-input" type="password" placeholder="Repeat" value={pin2} onChange={(e) => setPin2(e.target.value)} autoComplete="off" />}
        {err && <div className="error">{err}</div>}
        <button className="btn primary" disabled={busy || pin.length < 4}>{busy ? 'Deriving key…' : isSetUp ? 'Unlock' : 'Set PIN & start'}</button>
        {isSetUp && bio && (
          <button type="button" className="btn" onClick={biometric}>Unlock with biometrics / passkey</button>
        )}
      </form>
    </div>
  );
}

export function PinPrompt({ title, verify, onOk, onCancel }: { title: string; verify: (pin: string) => Promise<boolean>; onOk: () => void; onCancel?: () => void }) {
  const [pin, setPin] = useState('');
  const [err, setErr] = useState('');
  return (
    <div className="lock" style={{ minHeight: '60vh' }}>
      <form
        className="box"
        onSubmit={async (e) => {
          e.preventDefault();
          if (await verify(pin)) onOk();
          else {
            setErr('Wrong PIN');
            setPin('');
          }
        }}
      >
        <span className="logo"><Icon name="lock" /></span>
        <h2>{title}</h2>
        <input className="pin-input" type="password" autoFocus placeholder="PIN" value={pin} onChange={(e) => setPin(e.target.value)} />
        {err && <div className="error">{err}</div>}
        <button className="btn primary">Open</button>
        {onCancel && <button type="button" className="btn" onClick={onCancel}>Cancel</button>}
      </form>
    </div>
  );
}
