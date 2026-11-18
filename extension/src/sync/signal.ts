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

/**
 * Optional free relay (Cloudflare Worker, see /signaling-worker). It only ever sees FN1: ciphertext.
 */
export class RelaySignaling {
  private ws?: WebSocket;
  onSignal: (m: SignalMessage) => void = () => {};
  onStatus: (s: string) => void = () => {};

  constructor(private baseUrl: string, private chainId: string, private key: CryptoKey) {}

  connect(): Promise<void> {
    const url = `${this.baseUrl.replace(/\/$/, '').replace(/^http/, 'ws')}/room/${this.chainId}`;
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url);
      this.ws = ws;
      ws.onopen = () => {
        this.onStatus('Relay connected');
        resolve();
      };
      ws.onerror = () => reject(new Error('Relay unreachable'));
      ws.onclose = () => this.onStatus('Relay closed');
      ws.onmessage = async (ev) => {
        try {
          this.onSignal(await decodeSignal(this.key, String(ev.data)));
        } catch {
          /* foreign / garbage frame */
        }
      };
    });
  }

  async send(m: SignalMessage) {
    this.ws?.send(await encodeSignal(this.key, m));
  }

  close() {
    this.ws?.close();
  }
}
