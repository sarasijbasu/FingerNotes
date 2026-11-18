import { bs, td, te } from './bytes';
import { aesDecrypt, aesEncrypt, openBytes } from './crypto';
import type { FingerNotesDB } from './db';

/**
 * "Save selection to Notes" runs in the service worker, which never holds the DEK.
 * It encrypts the clip with a fresh AES key wrapped by the inbox RSA-OAEP public key;
 * the private key is itself sealed under the DEK, so clips are readable only after unlock.
 */
export interface Clip {
  text: string;
  url: string;
  title: string;
  createdAt: number;
}

export async function pushClip(db: FingerNotesDB, clip: Clip): Promise<boolean> {
  const jwk = await db.getMeta<JsonWebKey>('inboxPub');
  if (!jwk) return false;
  const pub = await crypto.subtle.importKey('jwk', jwk, { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt']);
  const aes = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt']);
  const rawAes = new Uint8Array(await crypto.subtle.exportKey('raw', aes));
  const wrappedKey = new Uint8Array(await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, pub, bs(rawAes)));
  const data = await aesEncrypt(aes, te.encode(JSON.stringify(clip)));
  await db.inbox.add({ wrappedKey, data, createdAt: clip.createdAt });
  return true;
}

export async function drainClips(db: FingerNotesDB, dek: CryptoKey): Promise<Clip[]> {
  const rows = await db.inbox.toArray();
  if (!rows.length) return [];
  const sealed = await db.getMeta<string>('inboxPriv');
  if (!sealed) return [];
  const priv = await crypto.subtle.importKey('pkcs8', bs(await openBytes(dek, sealed)), { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['decrypt']);
  const clips: Clip[] = [];
  for (const r of rows) {
    const raw = new Uint8Array(await crypto.subtle.decrypt({ name: 'RSA-OAEP' }, priv, bs(r.wrappedKey)));
    const key = await crypto.subtle.importKey('raw', bs(raw), 'AES-GCM', false, ['decrypt']);
    clips.push(JSON.parse(td.decode(await aesDecrypt(key, r.data))));
  }
  await db.inbox.bulkDelete(rows.map((r) => r.seq!));
  return clips;
}
