import { useCallback, useEffect, useRef, useState } from 'react';
import { deriveSyncMaterial, isValidMnemonic, newMnemonic, normalizeMnemonic, PAIR_PREFIX, type SyncMaterial } from '../core/mnemonic';
import type { Platform } from '../core/model';
import type { Settings } from '../core/platform';
import type { Repo } from '../core/repo';
import { Peer, type Channel } from '../sync/peer';
import { runSyncSession } from '../sync/session';
import { decodeSignal, encodeSignal, RelaySignaling, type SignalMessage } from '../sync/signal';
import { QrImage, QrScanner, snack } from './common';

interface Chain {
  mnemonic: string;
  createdAt: number;
}
interface PeerRecord {
  deviceId: string;
  name: string;
  platform: Platform;
  lastSync: number;
}

export function SyncView({ repo, settings }: { repo: Repo; settings: Settings }) {
  const [chain, setChain] = useState<Chain | null | undefined>(undefined);
  const [material, setMaterial] = useState<SyncMaterial | null>(null);
  const [peers, setPeers] = useState<PeerRecord[]>([]);

  useEffect(() => {
    repo.getSecret<Chain>('chain').then((c) => setChain(c));
    repo.getSecret<PeerRecord[]>('peers').then((p) => setPeers(p ?? []));
  }, [repo]);
  useEffect(() => {
    if (chain) deriveSyncMaterial(chain.mnemonic).then(setMaterial);
    else setMaterial(null);
  }, [chain]);

  const saveChain = async (c: Chain | null) => {
    await repo.setSecret('chain', c);
    setChain(c);
  };

  if (chain === undefined) return null;
  if (!chain) return <ChainSetup onDone={saveChain} />;

  const recordPeer = async (p: PeerRecord) => {
    const next = [p, ...peers.filter((x) => x.deviceId !== p.deviceId)];
    setPeers(next);
    await repo.setSecret('peers', next);
  };

  return (
    <div style={{ display: 'grid', gap: 14, maxWidth: 720 }}>
      <div className="card-panel">
        <h2>Sync chain</h2>
        <p className="muted">
          Chain <code>{material?.chainId.slice(0, 8) ?? '…'}</code> · this device: <b>{settings.deviceName}</b>. Data travels device-to-device over an
          end-to-end encrypted WebRTC channel — no cloud storage.
        </p>
        <ChainSecret chain={chain} />
        <div className="row" style={{ marginTop: 10 }}>
          <button className="btn danger" onClick={() => confirm('Leave this sync chain? Local data stays on this device.') && saveChain(null)}>
            Leave chain
          </button>
        </div>
      </div>
      {material && <Connect repo={repo} material={material} settings={settings} onSynced={recordPeer} />}
      {peers.length > 0 && (
        <div className="card-panel">
          <h3 style={{ marginTop: 0 }}>Devices in this chain</h3>
          {peers.map((p) => (
            <div key={p.deviceId} className="row" style={{ padding: '4px 0' }}>
              <span className="grow">
                {p.name} <span className="chip">{p.platform}</span>
              </span>
              <span className="muted">last sync {new Date(p.lastSync).toLocaleString()}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function ChainSecret({ chain }: { chain: Chain }) {
  const [show, setShow] = useState(false);
  if (!show)
    return (
      <button className="btn" onClick={() => setShow(true)}>
        Show 24 words / pairing QR to add a device
      </button>
    );
  return (
    <div style={{ display: 'grid', gap: 10 }}>
      <p className="error">Anyone with these words can join your chain and read your data. Never share them.</p>
      <div className="words">{chain.mnemonic.split(' ').map((w, i) => <span key={i}>{w}</span>)}</div>
      <QrImage text={PAIR_PREFIX + chain.mnemonic} />
      <div>
        <button className="btn" onClick={() => setShow(false)}>Hide</button>
      </div>
    </div>
  );
}

function ChainSetup({ onDone }: { onDone: (c: Chain) => void }) {
  const [mode, setMode] = useState<'choose' | 'create' | 'join'>('choose');
  const [words] = useState(newMnemonic);
  const [input, setInput] = useState('');
  const [scan, setScan] = useState(false);
  const [err, setErr] = useState('');

  if (mode === 'choose')
    return (
      <div className="card-panel" style={{ maxWidth: 560 }}>
        <h2>Peer-to-peer sync</h2>
        <p className="muted">Sync Android phones and Chrome browsers directly with each other. A 24-word phrase is the only key — no account, no server.</p>
        <div className="row">
          <button className="btn primary" onClick={() => setMode('create')}>Start a new sync chain</button>
          <button className="btn" onClick={() => setMode('join')}>Join an existing chain</button>
        </div>
      </div>
    );

  if (mode === 'create')
    return (
      <div className="card-panel" style={{ maxWidth: 640, display: 'grid', gap: 12 }}>
        <h2>Your sync chain code</h2>
        <p className="muted">On your other device choose <b>Join an existing chain</b> and scan this QR or type the 24 words. Write them down — they are also your recovery phrase.</p>
        <div className="words">{words.split(' ').map((w, i) => <span key={i}>{w}</span>)}</div>
        <QrImage text={PAIR_PREFIX + words} />
        <div className="row">
          <button className="btn" onClick={() => setMode('choose')}>Back</button>
          <button className="btn primary" onClick={() => onDone({ mnemonic: words, createdAt: Date.now() })}>I've saved it — create chain</button>
        </div>
      </div>
    );

  const join = (text: string) => {
    const n = normalizeMnemonic(text);
    if (!isValidMnemonic(n)) return setErr('That is not a valid 24-word sync code (check spelling and word order).');
    onDone({ mnemonic: n, createdAt: Date.now() });
  };
  return (
    <div className="card-panel" style={{ maxWidth: 640, display: 'grid', gap: 10 }}>
      <h2>Join a sync chain</h2>
      <textarea rows={4} placeholder="Enter the 24 words separated by spaces" value={input} onChange={(e) => setInput(e.target.value)} />
      {err && <p className="error">{err}</p>}
      {scan && <QrScanner onResult={(t) => { setScan(false); join(t); }} onCancel={() => setScan(false)} />}
      <div className="row">
        <button className="btn" onClick={() => setMode('choose')}>Back</button>
        <button className="btn" onClick={() => setScan(true)}>Scan pairing QR</button>
        <button className="btn primary" onClick={() => join(input)}>Join</button>
      </div>
    </div>
  );
}

type Stage = { kind: 'idle' } | { kind: 'offer'; code: string; peer: Peer } | { kind: 'answer-in' } | { kind: 'answer-out'; code: string } | { kind: 'syncing' } | { kind: 'relay' };

function Connect({ repo, material, settings, onSynced }: { repo: Repo; material: SyncMaterial; settings: Settings; onSynced: (p: PeerRecord) => void }) {
  const [stage, setStage] = useState<Stage>({ kind: 'idle' });
  const [log, setLog] = useState<string[]>([]);
  const [paste, setPaste] = useState('');
  const [scan, setScan] = useState(false);
  const relay = useRef<RelaySignaling | null>(null);
  const self = { deviceId: repo.deviceId, deviceName: settings.deviceName, platform: 'chrome' as const };
  const add = useCallback((s: string) => setLog((l) => [...l.slice(-40), `${new Date().toLocaleTimeString()}  ${s}`]), []);

  useEffect(() => () => relay.current?.close(), []);

  async function run(channel: Promise<Channel>, peerId: string) {
    try {
      const ch = await channel;
      setStage({ kind: 'syncing' });
      add('Peer connected — exchanging changes');
      const r = await runSyncSession(repo, material.key, self, ch, add);
      onSynced({ deviceId: peerId, name: r.peerName, platform: r.peerPlatform, lastSync: Date.now() });
      snack({ text: `Synced with ${r.peerName}: ↑${r.sent} ↓${r.received} (${r.mediaIn + r.mediaOut} files)` });
      setTimeout(() => ch.close(), 1500);
    } catch (e) {
      add('Error: ' + (e as Error).message);
    } finally {
      setStage(relay.current ? { kind: 'relay' } : { kind: 'idle' });
    }
  }

  async function startOffer() {
    add('Gathering network candidates…');
    const peer = new Peer(settings.useStun);
    const { sdp, channel } = await peer.createOffer();
    const code = await encodeSignal(material.key, { type: 'offer', sdp, ...self });
    setStage({ kind: 'offer', code, peer });
    add('Show this code to the other device, then scan/paste its reply.');
    void run(channel, 'pending');
  }

  async function handleCode(text: string) {
    setScan(false);
    setPaste('');
    try {
      const sig = await decodeSignal(material.key, text);
      if (sig.type === 'answer' && stage.kind === 'offer') {
        await stage.peer.acceptAnswer(sig.sdp!);
        add(`Answer from ${sig.deviceName} accepted — connecting…`);
      } else if (sig.type === 'offer') {
        const peer = new Peer(settings.useStun);
        const { sdp, channel } = await peer.answerOffer(sig.sdp!);
        setStage({ kind: 'answer-out', code: await encodeSignal(material.key, { type: 'answer', sdp, ...self }) });
        add(`Offer from ${sig.deviceName}. Show this reply code to it.`);
        void run(channel, sig.deviceId);
      } else add('Unexpected code type: ' + sig.type);
    } catch (e) {
      add('Error: ' + (e as Error).message);
    }
  }

  async function startRelay() {
    const r = new RelaySignaling(settings.signalingUrl, material.chainId, material.key);
    relay.current = r;
    let pending: Peer | null = null;
    const seen = new Set<string>();
    r.onStatus = add;
    r.onSignal = async (m: SignalMessage) => {
      if (m.deviceId === self.deviceId) return;
      if (m.type === 'announce') {
        add(`${m.deviceName} is online`);
        if (self.deviceId > m.deviceId) {
          pending = new Peer(settings.useStun);
          const { sdp, channel } = await pending.createOffer();
          await r.send({ type: 'offer', sdp, ...self });
          void run(channel, m.deviceId);
        } else if (!seen.has(m.deviceId)) {
          seen.add(m.deviceId);
          await r.send({ type: 'announce', ...self });
        }
      } else if (m.type === 'offer') {
        const p = new Peer(settings.useStun);
        const { sdp, channel } = await p.answerOffer(m.sdp!);
        await r.send({ type: 'answer', sdp, ...self });
        void run(channel, m.deviceId);
      } else if (m.type === 'answer' && pending) {
        await pending.acceptAnswer(m.sdp!);
        pending = null;
      }
    };
    try {
      await r.connect();
      setStage({ kind: 'relay' });
      await r.send({ type: 'announce', ...self });
      add('Waiting for other devices on the relay…');
    } catch (e) {
      relay.current = null;
      add('Error: ' + (e as Error).message);
    }
  }

  return (
    <div className="card-panel" style={{ display: 'grid', gap: 10 }}>
      <h2>Sync now</h2>
      {stage.kind === 'idle' && (
        <>
          <p className="muted">Offline QR handshake: one device shows a code, the other scans it and shows a reply. Works on the same Wi-Fi/LAN with no internet.</p>
          <div className="row">
            <button className="btn primary" onClick={startOffer}>Show my code (start)</button>
            <button className="btn" onClick={() => setStage({ kind: 'answer-in' })}>Scan their code (join)</button>
            {settings.signalingUrl && <button className="btn" onClick={startRelay}>Auto-connect via relay</button>}
          </div>
        </>
      )}
      {stage.kind === 'offer' && (
        <>
          <p><b>Step 1.</b> Scan this with the other device (Sync → Scan their code).</p>
          <QrImage text={stage.code} />
          <CopyBox text={stage.code} />
          <p><b>Step 2.</b> Scan or paste the reply code it shows.</p>
        </>
      )}
      {stage.kind === 'answer-out' && (
        <>
          <p>Scan this reply code with the device that started:</p>
          <QrImage text={stage.code} />
          <CopyBox text={stage.code} />
        </>
      )}
      {(stage.kind === 'offer' || stage.kind === 'answer-in') && (
        <>
          {scan ? (
            <QrScanner onResult={handleCode} onCancel={() => setScan(false)} />
          ) : (
            <div className="row">
              <button className="btn" onClick={() => setScan(true)}>Scan with camera</button>
              <input type="text" className="grow" placeholder="…or paste FN1: code" value={paste} onChange={(e) => setPaste(e.target.value)} style={{ minWidth: 160 }} />
              <button className="btn primary" onClick={() => handleCode(paste)}>Use code</button>
            </div>
          )}
        </>
      )}
      {stage.kind === 'syncing' && <p>Syncing…</p>}
      {stage.kind === 'relay' && (
        <div className="row">
          <span className="grow">Relay active — other devices on this chain connect automatically.</span>
          <button className="btn" onClick={() => { relay.current?.close(); relay.current = null; setStage({ kind: 'idle' }); }}>Stop</button>
        </div>
      )}
      {stage.kind !== 'idle' && stage.kind !== 'relay' && stage.kind !== 'syncing' && (
        <div>
          <button className="btn" onClick={() => { if (stage.kind === 'offer') stage.peer.close(); setStage({ kind: 'idle' }); }}>Cancel</button>
        </div>
      )}
      {log.length > 0 && <div className="log">{log.join('\n')}</div>}
    </div>
  );
}

function CopyBox({ text }: { text: string }) {
  return (
    <div className="row">
      <input type="text" readOnly value={text} className="grow" onFocus={(e) => e.target.select()} style={{ minWidth: 160 }} />
      <button className="btn small" onClick={() => navigator.clipboard.writeText(text).then(() => snack({ text: 'Copied' }))}>Copy</button>
    </div>
  );
}
