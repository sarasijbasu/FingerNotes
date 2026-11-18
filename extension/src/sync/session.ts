import { b64url, concat, fromB64url } from '../core/bytes';
import { open, seal } from '../core/crypto';
import { applyRecords, buildManifest, diffForPeer, mediaMissingAtPeer, type Manifest, type RecordsPayload, type SyncStore } from '../core/merge';
import type { Platform } from '../core/model';
import type { Channel } from './peer';

export const MEDIA_CHUNK = 32 * 1024;

type Msg =
  | { t: 'hello'; v: number; deviceId: string; deviceName: string; platform: Platform }
  | ({ t: 'manifest' } & Manifest)
  | ({ t: 'records' } & RecordsPayload)
  | { t: 'media'; id: string; ownerId: string; mime: string; seq: number; total: number; data: string }
  | { t: 'done' };

export interface SyncResult {
  peerName: string;
  peerPlatform: Platform;
  sent: number;
  received: number;
  purged: number;
  mediaIn: number;
  mediaOut: number;
}

export interface SelfInfo {
  deviceId: string;
  deviceName: string;
  platform: Platform;
}

/** One sync exchange over an opened channel — docs/PROTOCOL.md §6. Symmetric on both peers. */
export function runSyncSession(store: SyncStore, key: CryptoKey, self: SelfInfo, ch: Channel, log: (s: string) => void = () => {}): Promise<SyncResult> {
  const result: SyncResult = { peerName: '?', peerPlatform: 'chrome', sent: 0, received: 0, purged: 0, mediaIn: 0, mediaOut: 0 };
  const parts = new Map<string, Uint8Array[]>();
  let sentDone = false;
  let gotDone = false;

  const send = async (m: Msg) => {
    while (ch.bufferedAmount() > 1_000_000) await new Promise((r) => setTimeout(r, 20));
    ch.send(await seal(key, m));
  };

  return new Promise<SyncResult>((resolve, reject) => {
    let queue = Promise.resolve();
    const finish = () => {
      if (sentDone && gotDone) {
        log('Sync complete');
        resolve(result);
      }
    };
    const fail = (e: unknown) => reject(e instanceof Error ? e : new Error(String(e)));

    const handle = async (m: Msg) => {
      switch (m.t) {
        case 'hello':
          result.peerName = m.deviceName;
          result.peerPlatform = m.platform;
          log(`Connected to ${m.deviceName} (${m.platform})`);
          break;
        case 'manifest': {
          const diff = diffForPeer(store, m);
          const count = diff.notes.length + diff.labels.length + diff.expenses.length + diff.categories.length + diff.tombstones.length;
          await send({ t: 'records', ...diff });
          result.sent = count;
          log(`Sent ${count} changes`);
          for (const id of mediaMissingAtPeer(store, m)) {
            const blob = await store.getMedia(id);
            if (!blob) continue;
            const total = Math.max(1, Math.ceil(blob.bytes.length / MEDIA_CHUNK));
            for (let seq = 0; seq < total; seq++) {
              const data = b64url(blob.bytes.subarray(seq * MEDIA_CHUNK, (seq + 1) * MEDIA_CHUNK));
              await send({ t: 'media', id, ownerId: blob.ownerId, mime: blob.mime, seq, total, data });
            }
            result.mediaOut++;
          }
          await send({ t: 'done' });
          sentDone = true;
          finish();
          break;
        }
        case 'records': {
          const r = await applyRecords(store, m);
          result.received += r.applied;
          result.purged += r.purged;
          log(`Applied ${r.applied} changes, ${r.purged} purges`);
          break;
        }
        case 'media': {
          const arr = parts.get(m.id) ?? [];
          arr[m.seq] = fromB64url(m.data);
          parts.set(m.id, arr);
          if (arr.filter(Boolean).length === m.total) {
            parts.delete(m.id);
            if (!store.hasTombstone(m.ownerId)) await store.putMedia({ id: m.id, ownerId: m.ownerId, mime: m.mime, bytes: concat(...arr) });
            result.mediaIn++;
          }
          break;
        }
        case 'done':
          gotDone = true;
          finish();
          break;
      }
    };

    ch.onMessage((text) => {
      queue = queue
        .then(async () => handle(await open<Msg>(key, text)))
        .catch((e) => {
          fail(e);
          ch.close();
        });
    });
    ch.onClose(() => {
      if (!(sentDone && gotDone)) fail(new Error('Connection closed before sync finished'));
    });

    (async () => {
      await send({ t: 'hello', v: 1, ...self });
      await send({ t: 'manifest', ...buildManifest(store) });
    })().catch(fail);
  });
}
