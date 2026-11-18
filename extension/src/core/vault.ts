import { b64url, bs, fromB64url, randomBytes, te } from './bytes';
import { importAesKey, openBytes, pbkdf2, PIN_ITERATIONS, sealBytes, sha256 } from './crypto';
import type { FingerNotesDB } from './db';
import { storageGet, storageRemove, storageSet } from './platform';

/**
 * Key hierarchy:
 *   PIN/password --PBKDF2(310k)--> KEK --AES-GCM--> DEK (random 256-bit) --> every IndexedDB row
 *   WebAuthn PRF output (optional) --------------> second wrap of the same DEK
 * The DEK only lives in memory (and in chrome.storage.session — RAM only — when the
 * inactivity timeout is > 0, so popup / side panel / tab share one unlock).
 */
interface PinRecord {
  salt: string;
  iterations: number;
}
interface VaultMeta extends PinRecord {
  v: 1;
  wrappedDek: string;
  webauthn?: { credentialId: string; prfSalt: string; wrappedDek: string };
  hiddenPin?: PinRecord & { hash: string };
}

export interface Unlocked {
  dek: CryptoKey;
  raw: Uint8Array;
}

const getVault = (db: FingerNotesDB) => db.getMeta<VaultMeta>('vault');
export const isSetUp = async (db: FingerNotesDB) => !!(await getVault(db));

async function kekFromPin(pin: string, salt: string, iterations: number) {
  return importAesKey(await pbkdf2(pin, fromB64url(salt), iterations, 256));
}

export async function setupVault(db: FingerNotesDB, pin: string): Promise<Unlocked> {
  validatePin(pin);
  const raw = randomBytes(32);
  const salt = b64url(randomBytes(16));
  const kek = await kekFromPin(pin, salt, PIN_ITERATIONS);
  const meta: VaultMeta = { v: 1, salt, iterations: PIN_ITERATIONS, wrappedDek: await sealBytes(kek, raw) };
  await db.setMeta('vault', meta);
  const dek = await importAesKey(raw);
  await createInboxKeys(db, dek);
  return { dek, raw };
}

export function validatePin(pin: string) {
  if (pin.length < 4) throw new Error('PIN/password must be at least 4 characters');
  if (/^\d+$/.test(pin) && pin.length > 6) throw new Error('Numeric PINs are 4–6 digits (or use a password)');
}

export async function unlockWithPin(db: FingerNotesDB, pin: string): Promise<Unlocked> {
  const v = await getVault(db);
  if (!v) throw new Error('Not set up');
  try {
    const raw = await openBytes(await kekFromPin(pin, v.salt, v.iterations), v.wrappedDek);
    return { dek: await importAesKey(raw), raw };
  } catch {
    throw new Error('Wrong PIN');
  }
}

export async function verifyPin(db: FingerNotesDB, pin: string): Promise<boolean> {
  try {
    await unlockWithPin(db, pin);
    return true;
  } catch {
    return false;
  }
}

export async function changePin(db: FingerNotesDB, u: Unlocked, newPin: string) {
  validatePin(newPin);
  const v = (await getVault(db))!;
  const salt = b64url(randomBytes(16));
  const kek = await kekFromPin(newPin, salt, PIN_ITERATIONS);
  await db.setMeta('vault', { ...v, salt, iterations: PIN_ITERATIONS, wrappedDek: await sealBytes(kek, u.raw) });
}

// ---------- Hidden-notes vault (secondary PIN) ----------
export async function setHiddenPin(db: FingerNotesDB, pin: string) {
  validatePin(pin);
  const v = (await getVault(db))!;
  const salt = b64url(randomBytes(16));
  const hash = b64url(await sha256(await pbkdf2(pin, fromB64url(salt), PIN_ITERATIONS, 256)));
  await db.setMeta('vault', { ...v, hiddenPin: { salt, iterations: PIN_ITERATIONS, hash } });
}
export const hasHiddenPin = async (db: FingerNotesDB) => !!(await getVault(db))?.hiddenPin;
export async function verifyHiddenPin(db: FingerNotesDB, pin: string): Promise<boolean> {
  const h = (await getVault(db))?.hiddenPin;
  if (!h) return false;
  const got = b64url(await sha256(await pbkdf2(pin, fromB64url(h.salt), h.iterations, 256)));
  return got === h.hash;
}

// ---------- WebAuthn / Passkey (PRF extension wraps the DEK) ----------
export const webauthnSupported = () => typeof PublicKeyCredential !== 'undefined';
export const hasWebAuthn = async (db: FingerNotesDB) => !!(await getVault(db))?.webauthn;

async function prfKey(credentialId: Uint8Array | null, prfSalt: Uint8Array, create?: { userId: Uint8Array }) {
  let cred: PublicKeyCredential | null;
  const prf = { eval: { first: bs(prfSalt) } };
  if (create) {
    cred = (await navigator.credentials.create({
      publicKey: {
        rp: { name: 'FingerNotes' },
        user: { id: bs(create.userId), name: 'FingerNotes', displayName: 'FingerNotes unlock' },
        challenge: bs(randomBytes(32)),
        pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
        authenticatorSelection: { userVerification: 'required', residentKey: 'preferred' },
        extensions: { prf } as AuthenticationExtensionsClientInputs,
      },
    })) as PublicKeyCredential | null;
    if (!cred) throw new Error('Passkey creation cancelled');
    const ext = cred.getClientExtensionResults() as { prf?: { enabled?: boolean; results?: { first?: ArrayBuffer } } };
    if (!ext.prf?.enabled) throw new Error('This authenticator does not support the WebAuthn PRF extension');
    if (ext.prf.results?.first) return { id: new Uint8Array(cred.rawId), key: await importAesKey(new Uint8Array(ext.prf.results.first)) };
    credentialId = new Uint8Array(cred.rawId);
  }
  cred = (await navigator.credentials.get({
    publicKey: {
      challenge: bs(randomBytes(32)),
      allowCredentials: [{ type: 'public-key', id: bs(credentialId!) }],
      userVerification: 'required',
      extensions: { prf } as AuthenticationExtensionsClientInputs,
    },
  })) as PublicKeyCredential | null;
  if (!cred) throw new Error('Biometric unlock cancelled');
  const ext = cred.getClientExtensionResults() as { prf?: { results?: { first?: ArrayBuffer } } };
  const first = ext.prf?.results?.first;
  if (!first) throw new Error('Authenticator returned no PRF output');
  const secret = await sha256(new Uint8Array(first)); // normalise length to 32 bytes
  return { id: credentialId!, key: await importAesKey(secret) };
}

export async function enrollWebAuthn(db: FingerNotesDB, u: Unlocked) {
  const prfSalt = await sha256(te.encode('fingernotes/webauthn-prf/v1'));
  const { id, key } = await prfKey(null, prfSalt, { userId: randomBytes(16) });
  // Re-derive through get() so create()/get() code paths produce the same normalised key.
  const again = (await prfKey(id, prfSalt)).key;
  const v = (await getVault(db))!;
  await db.setMeta('vault', {
    ...v,
    webauthn: { credentialId: b64url(id), prfSalt: b64url(prfSalt), wrappedDek: await sealBytes(again ?? key, u.raw) },
  });
}

export async function unlockWithWebAuthn(db: FingerNotesDB): Promise<Unlocked> {
  const w = (await getVault(db))?.webauthn;
  if (!w) throw new Error('Biometric unlock not enrolled');
  const { key } = await prfKey(fromB64url(w.credentialId), fromB64url(w.prfSalt));
  const raw = await openBytes(key, w.wrappedDek);
  return { dek: await importAesKey(raw), raw };
}

export async function removeWebAuthn(db: FingerNotesDB) {
  const v = (await getVault(db))!;
  delete v.webauthn;
  await db.setMeta('vault', v);
}

// ---------- Session (shared unlock across popup / side panel / tab) ----------
interface SessionState {
  raw: string;
  lastActive: number;
}
export async function rememberSession(u: Unlocked, timeoutMs: number) {
  if (timeoutMs <= 0) return storageRemove('session', 'unlock');
  await storageSet('session', 'unlock', { raw: b64url(u.raw), lastActive: Date.now() } satisfies SessionState);
}
export async function touchSession() {
  const s = await storageGet<SessionState>('session', 'unlock');
  if (s) await storageSet('session', 'unlock', { ...s, lastActive: Date.now() });
}
export async function restoreSession(timeoutMs: number): Promise<Unlocked | null> {
  const s = await storageGet<SessionState>('session', 'unlock');
  if (!s || timeoutMs <= 0 || Date.now() - s.lastActive > timeoutMs) {
    await storageRemove('session', 'unlock');
    return null;
  }
  const raw = fromB64url(s.raw);
  return { dek: await importAesKey(raw), raw };
}
export const clearSession = () => storageRemove('session', 'unlock');

// ---------- Inbox keypair (context menu capture while locked) ----------
async function createInboxKeys(db: FingerNotesDB, dek: CryptoKey) {
  const kp = await crypto.subtle.generateKey(
    { name: 'RSA-OAEP', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true,
    ['encrypt', 'decrypt'],
  );
  await db.setMeta('inboxPub', await crypto.subtle.exportKey('jwk', kp.publicKey));
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', kp.privateKey));
  await db.setMeta('inboxPriv', await sealBytes(dek, pkcs8));
}
