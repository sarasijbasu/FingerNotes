import { deriveSyncMaterial, type SyncMaterial } from '../core/mnemonic';
import type { Platform } from '../core/model';
import type { Settings } from '../core/platform';
import type { Repo } from '../core/repo';
import { Peer, type Channel } from './peer';
import { runSyncSession } from './session';
import { fetchIceServers, RelaySignaling, type SignalMessage } from './signal';

/**
 * Pure device-to-device sync over the internet (docs/PROTOCOL.md §7.1).
 *
 * Every device on the chain joins the worker room for its chainId. Devices discover each other with
 * `announce`; the device with the greater deviceId sends a directed WebRTC offer; data then flows over
 * the encrypted DataChannel (TURN-relayed only when a direct path is impossible). No data is stored
 * anywhere except on the devices. Sync happens on start, after local edits, every few minutes, and on
 * pull-to-refresh.
 */
export type AutoStatus = 'off' | 'no-chain' | 'no-relay' | 'connecting' | 'online' | 'syncing' | 'standby';

export interface OnlinePeer {
  name: string;
  platform: Platform;
  seen: number;
}

export interface AutoState {
  status: AutoStatus;
  online: Record<string, OnlinePeer>;
  lastResult?: string;
  lastSyncAt?: number;
  log: string[];
}

interface PeerRecord {
  deviceId: string;
  name: string;
  platform: Platform;
  lastSync: number;
}

const ANNOUNCE_EVERY_MS = 4 * 60_000;
const PRESENCE_TTL_MS = 10 * 60_000;
const OFFER_TIMEOUT_MS = 30_000;

export class AutoSync {
  state: AutoState = { status: 'off', online: {}, log: [] };
  private listeners = new Set<() => void>();
  private relay?: RelaySignaling;
  private material?: SyncMaterial;
  private ice: RTCIceServer[] = [];
  private busy = false;
  private quietUntil = 0;
  private pending = new Map<string, Peer>();
  private timers: ReturnType<typeof setInterval>[] = [];
  private unsub?: () => void;
  private release?: () => void;
  private waiters: ((s: string) => void)[] = [];
  private stopped = false;

  constructor(private repo: Repo, private settings: Settings) {}

  subscribe(fn: () => void) {
    this.listeners.add(fn);
    return () => void this.listeners.delete(fn);
  }
  private set(patch: Partial<AutoState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((f) => f());
  }
  private log(s: string) {
    this.set({ log: [...this.state.log.slice(-40), `${new Date().toLocaleTimeString()}  ${s}`] });
  }
  private self() {
    return { deviceId: this.repo.deviceId, deviceName: this.settings.deviceName, platform: 'chrome' as const };
  }

  async start() {
    this.stopped = false;
    const chain = await this.repo.getSecret<{ mnemonic: string }>('chain');
    if (!chain) return this.set({ status: 'no-chain' });
    if (!this.settings.signalingUrl) return this.set({ status: 'no-relay' });
    const go = () => this.run(chain.mnemonic);
    // Only one surface (side panel or tab) talks to the relay at a time: they share one deviceId.
    if (typeof navigator !== 'undefined' && navigator.locks) {
      void navigator.locks.request('fingernotes-autosync', { ifAvailable: true }, async (lock) => {
        if (!lock || this.stopped) {
          this.set({ status: 'standby' });
          return;
        }
        await go();
        await new Promise<void>((r) => (this.release = r));
      });
    } else await go();
  }

  private async run(mnemonic: string) {
    this.material = await deriveSyncMaterial(mnemonic);
    this.ice = await fetchIceServers(this.settings.signalingUrl, this.material.chainId);
    if (this.stopped) return;
    const r = new RelaySignaling(this.settings.signalingUrl, this.material.chainId, this.material.key);
    this.relay = r;
    r.onStatus = (s) => this.set({ status: s === 'online' ? (this.busy ? 'syncing' : 'online') : 'connecting' });
    r.onOpen = () => this.announce();
    r.onSignal = (m) => void this.onSignal(m).catch((e) => this.log('Error: ' + (e as Error).message));
    this.set({ status: 'connecting' });
    r.connect();
    this.timers.push(setInterval(() => this.announce(), ANNOUNCE_EVERY_MS));
    let debounce: ReturnType<typeof setTimeout> | undefined;
    this.unsub = this.repo.subscribe(() => {
      if (this.busy || Date.now() < this.quietUntil) return; // ignore changes applied by sync itself
      clearTimeout(debounce);
      debounce = setTimeout(() => this.announce(), 3000);
    });
  }

  stop() {
    this.stopped = true;
    this.relay?.close();
    this.relay = undefined;
    this.timers.forEach(clearInterval);
    this.timers = [];
    this.unsub?.();
    this.pending.forEach((p) => p.close());
    this.pending.clear();
    this.release?.();
    this.set({ status: 'off', online: {} });
  }

  private announce(to?: string) {
    void this.relay?.send({ type: 'announce', to, ...this.self() });
  }

  private async onSignal(m: SignalMessage) {
    const me = this.repo.deviceId;
    if (m.deviceId === me || (m.to && m.to !== me)) return;
    const now = Date.now();
    const online = Object.fromEntries(Object.entries(this.state.online).filter(([, p]) => now - p.seen < PRESENCE_TTL_MS));
    online[m.deviceId] = { name: m.deviceName, platform: m.platform, seen: now };
    this.set({ online });

    if (m.type === 'announce') {
      if (me > m.deviceId) await this.offer(m.deviceId);
      else if (!m.to) this.announce(m.deviceId); // tell the greater device we're here; it will offer
    } else if (m.type === 'offer' && m.sdp) {
      if (this.busy) return; // the offerer retries on the next announce
      const p = new Peer(this.settings.useStun, this.ice);
      const { sdp, channel } = await p.answerOffer(m.sdp);
      await this.relay?.send({ type: 'answer', sdp, to: m.deviceId, ...this.self() });
      void this.session(channel, p, m.deviceId);
    } else if (m.type === 'answer' && m.sdp) {
      const p = this.pending.get(m.deviceId);
      if (!p) return;
      this.pending.delete(m.deviceId);
      await p.acceptAnswer(m.sdp);
    }
  }

  private async offer(peerId: string) {
    if (this.busy || this.pending.has(peerId)) return;
    const p = new Peer(this.settings.useStun, this.ice);
    this.pending.set(peerId, p);
    const { sdp, channel } = await p.createOffer();
    await this.relay?.send({ type: 'offer', sdp, to: peerId, ...this.self() });
    setTimeout(() => {
      if (this.pending.get(peerId) === p) {
        this.pending.delete(peerId);
        p.close();
      }
    }, OFFER_TIMEOUT_MS);
    void this.session(channel, p, peerId);
  }

  private async session(channel: Promise<Channel>, p: Peer, peerId: string) {
    let ch: Channel | undefined;
    try {
      ch = await channel;
      if (this.busy) return ch.close();
      this.busy = true;
      this.set({ status: 'syncing' });
      const r = await runSyncSession(this.repo, this.material!.key, this.self(), ch, (s) => this.log(s));
      const summary = `Synced with ${r.peerName}: ↑${r.sent} ↓${r.received}${r.mediaIn + r.mediaOut ? ` · ${r.mediaIn + r.mediaOut} files` : ''}`;
      this.set({ lastResult: summary, lastSyncAt: Date.now() });
      const peers = (await this.repo.getSecret<PeerRecord[]>('peers')) ?? [];
      await this.repo.setSecret('peers', [
        { deviceId: peerId, name: r.peerName, platform: r.peerPlatform, lastSync: Date.now() },
        ...peers.filter((x) => x.deviceId !== peerId),
      ]);
      this.waiters.splice(0).forEach((w) => w(summary));
      setTimeout(() => ch?.close(), 1500);
    } catch (e) {
      this.log('Sync failed: ' + (e as Error).message);
      p.close();
    } finally {
      if (ch) {
        this.busy = false;
        this.quietUntil = Date.now() + 2500;
      }
      this.set({ status: this.relay?.connected ? 'online' : 'connecting' });
    }
  }

  /** Pull-to-refresh / "Sync now": ask every online chain device to sync, resolve with a summary. */
  refresh(timeoutMs = 12_000): Promise<string> {
    const s = this.state.status;
    if (s === 'no-chain') return Promise.resolve('Set up a sync chain first (Sync page)');
    if (s === 'no-relay') return Promise.resolve('Add your relay URL in Settings to sync over the internet');
    if (s === 'standby') return Promise.resolve('Syncing runs in your other open FingerNotes window');
    if (s === 'off') return Promise.resolve('Sync is not running');
    return new Promise((resolve) => {
      const done = (msg: string) => {
        clearTimeout(t);
        resolve(msg);
      };
      const t = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w !== done);
        const others = Object.values(this.state.online).filter((p) => Date.now() - p.seen < PRESENCE_TTL_MS);
        resolve(!this.relay?.connected ? 'Relay unreachable — check your internet connection' : others.length ? 'Other devices did not respond in time' : 'No other device on your chain is online');
      }, timeoutMs);
      this.waiters.push(done);
      this.announce();
    });
  }
}
