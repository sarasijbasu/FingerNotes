/**
 * Cross-platform checks.
 *  - Always: the committed vectors still match this implementation.
 *  - In CI (ANDROID_BACKUP set): a Zip4j backup written by the Android unit tests opens here.
 */
import 'fake-indexeddb/auto';
import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { readBackup } from '../src/core/backup';
import { hex } from '../src/core/bytes';
import { deriveSyncMaterial } from '../src/core/mnemonic';
import { decodeSignal } from '../src/sync/signal';

const vectors = JSON.parse(readFileSync('../shared/test-vectors.json', 'utf8'));

describe('shared vectors', () => {
  it('mnemonic → sync key / chain id', async () => {
    const sm = await deriveSyncMaterial(vectors.mnemonic);
    expect(hex(sm.rawKey)).toBe(vectors.syncKeyHex);
    expect(sm.chainId).toBe(vectors.chainId);
    expect((await decodeSignal(sm.key, vectors.signal.code)).sdp).toBe(vectors.signal.sdp);
  });

  it('committed zip.js fixture still decrypts', async () => {
    const b = await readBackup(new Blob([readFileSync('../shared/' + vectors.backup.file)]), vectors.backup.password);
    expect(b.snapshot.notes[0].id).toBe(vectors.backup.noteId);
  });
});

const androidZip = process.env.ANDROID_BACKUP;
describe.skipIf(!androidZip || !existsSync(androidZip))('Android → Chrome backup', () => {
  it('opens a Zip4j AES-256 archive', async () => {
    const b = await readBackup(new Blob([readFileSync(androidZip!)]), 'android-pw');
    expect(b.metadata.platform).toBe('android');
    expect(b.snapshot.notes[0].title).toBe('From Android');
    expect(b.snapshot.expenses[0].amount).toBe(12.5);
    expect(Array.from(b.media[0].bytes)).toEqual([9, 9, 9]);
  });
});
