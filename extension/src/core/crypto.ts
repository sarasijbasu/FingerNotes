import { b64url, bs, concat, fromB64url, hex, randomBytes, td, te } from './bytes';

export const ENVELOPE_PREFIX = 'v1.';
export const PIN_ITERATIONS = 310_000;

export async function pbkdf2(password: string | Uint8Array, salt: Uint8Array, iterations: number, bits: number): Promise<Uint8Array> {
  const pw = typeof password === 'string' ? te.encode(password) : password;
  const base = await crypto.subtle.importKey('raw', bs(pw), 'PBKDF2', false, ['deriveBits']);
  const out = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: bs(salt), iterations }, base, bits);
  return new Uint8Array(out);
}

export async function sha256(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bs(data)));
}
export const sha256Hex = async (data: Uint8Array) => hex(await sha256(data));

export function importAesKey(raw: Uint8Array, extractable = false): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', bs(raw), { name: 'AES-GCM' }, extractable, ['encrypt', 'decrypt']);
}

/** AES-256-GCM, returns iv(12) ‖ ciphertext ‖ tag(16). */
export async function aesEncrypt(key: CryptoKey, plain: Uint8Array, iv: Uint8Array = randomBytes(12)): Promise<Uint8Array> {
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: bs(iv) }, key, bs(plain));
  return concat(iv, new Uint8Array(ct));
}

export async function aesDecrypt(key: CryptoKey, data: Uint8Array): Promise<Uint8Array> {
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bs(data.subarray(0, 12)) }, key, bs(data.subarray(12)));
  return new Uint8Array(pt);
}

export async function sealBytes(key: CryptoKey, plain: Uint8Array): Promise<string> {
  return ENVELOPE_PREFIX + b64url(await aesEncrypt(key, plain));
}
export async function openBytes(key: CryptoKey, env: string): Promise<Uint8Array> {
  if (!env.startsWith(ENVELOPE_PREFIX)) throw new Error('Bad envelope');
  return aesDecrypt(key, fromB64url(env.slice(ENVELOPE_PREFIX.length)));
}
export const seal = (key: CryptoKey, obj: unknown) => sealBytes(key, te.encode(JSON.stringify(obj)));
export const open = async <T>(key: CryptoKey, env: string): Promise<T> => JSON.parse(td.decode(await openBytes(key, env)));
