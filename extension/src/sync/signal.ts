import { deflateRaw, inflateRaw, td, te } from '../core/bytes';
import { openBytes, sealBytes } from '../core/crypto';
import type { Platform } from '../core/model';

export const SIGNAL_PREFIX = 'FN1:';

export interface SignalMessage {
  type: 'offer' | 'answer' | 'announce';
  sdp?: string;
  deviceId: string;
  deviceName: string;
  platform: Platform;
  /** Recipient deviceId; absent = broadcast to the whole chain room. */
  to?: string;
}

/** Encrypted + deflated SDP for QR codes, copy/paste or the relay (docs/PROTOCOL.md §7). */
export async function encodeSignal(key: CryptoKey, msg: SignalMessage): Promise<string> {
  return SIGNAL_PREFIX + (await sealBytes(key, await deflateRaw(te.encode(JSON.stringify(msg)))));
}

export async function decodeSignal(key: CryptoKey, text: string): Promise<SignalMessage> {
  const t = text.trim();
  if (!t.startsWith(SIGNAL_PREFIX)) throw new Error('Not a FingerNotes pairing code');
  try {
    return JSON.parse(td.decode(await inflateRaw(await openBytes(key, t.slice(SIGNAL_PREFIX.length)))));
  } catch {
    throw new Error('Code belongs to a different sync chain');
  }
}

/** Drop TCP candidates to keep the payload inside a single QR code. */
export const compactSdp = (sdp: string) =>
  sdp.split('\r\n').filter((l) => !(l.startsWith('a=candidate') && / tcp /i.test(l))).join('\r\n');

/** Base URL of the sync helper worker (no trailing slash). */
export const relayBase = (u: string) => u.trim().replace(/\/+$/, '');

let iceCache: { key: string; at: number; servers: RTCIceServer[] } | null = null;
/** STUN + short-lived TURN credentials from the worker's /turn endpoint (cached 6 h). */
export async function fetchIceServers(baseUrl: string, chainId: string): Promise<RTCIceServer[]> {
  const key = baseUrl + chainId;
  if (iceCache && iceCache.key === key && Date.now() - iceCache.at < 6 * 3600_000) return iceCache.servers;
  try {
    const r = await fetch(`${relayBase(baseUrl).replace(/^ws/, 'http')}/turn?room=${chainId}`);
    if (!r.ok) return [];
    const j = (await r.json()) as { iceServers?: RTCIceServer[] };
    const servers = Array.isArray(j.iceServers) ? j.iceServers : [];
    iceCache = { key, at: Date.now(), servers };
    return servers;
  } catch {
    return [];
  }
}

/**
 * WebSocket room on the sync helper worker (see /signaling-worker). It only ever sees FN1: ciphertext.
 * Reconnects with backoff until close() is called.
 */
export class RelaySignaling {
  private ws?: WebSocket;
  private closed = false;
  private retry = 0;
  private timer?: ReturnType<typeof setTimeout>;
  onSignal: (m: SignalMessage) => void = () => {};
  onStatus: (s: 'online' | 'offline') => void = () => {};
  onOpen: () => void = () => {};

  constructor(private baseUrl: string, private chainId: string, private key: CryptoKey) {}

  get connected() {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  connect() {
    this.closed = false;
    this.open();
  }

  private open() {
    const url = `${relayBase(this.baseUrl).replace(/^http/, 'ws')}/room/${this.chainId}`;
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch {
      return this.schedule();
    }
    this.ws = ws;
    ws.onopen = () => {
      this.retry = 0;
      this.onStatus('online');
      this.onOpen();
    };
    ws.onclose = () => {
      this.onStatus('offline');
      this.schedule();
    };
    ws.onmessage = async (ev) => {
      try {
        this.onSignal(await decodeSignal(this.key, String(ev.data)));
      } catch {
        /* foreign / garbage frame */
      }
    };
  }

  private schedule() {
    if (this.closed) return;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.open(), Math.min(30_000, 1000 * 2 ** this.retry++));
  }

  async send(m: SignalMessage) {
    if (this.connected) this.ws!.send(await encodeSignal(this.key, m));
  }

  close() {
    this.closed = true;
    clearTimeout(this.timer);
    this.ws?.close();
  }
}
