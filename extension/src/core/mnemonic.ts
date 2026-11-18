import { generateMnemonic, validateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import { hex, te } from './bytes';
import { importAesKey, pbkdf2, sha256 } from './crypto';

export const SYNC_SALT = 'fingernotes/sync/v1';
export const SYNC_ITERATIONS = 200_000;
export const PAIR_PREFIX = 'FNPAIR:';

export const newMnemonic = (): string => generateMnemonic(wordlist, 256);

export function normalizeMnemonic(input: string): string {
  return input.replace(PAIR_PREFIX, '').trim().toLowerCase().split(/\s+/).join(' ').normalize('NFKD');
}

export function isValidMnemonic(m: string): boolean {
  const n = normalizeMnemonic(m);
  return n.split(' ').length === 24 && validateMnemonic(n, wordlist);
}

export interface SyncMaterial {
  key: CryptoKey;
  rawKey: Uint8Array;
  chainId: string;
}

export async function deriveSyncMaterial(mnemonic: string): Promise<SyncMaterial> {
  const root = await pbkdf2(normalizeMnemonic(mnemonic), te.encode(SYNC_SALT), SYNC_ITERATIONS, 512);
  const rawKey = root.slice(0, 32);
  const chainId = hex(await sha256(root.slice(32, 64))).slice(0, 32);
  return { key: await importAesKey(rawKey), rawKey, chainId };
}
