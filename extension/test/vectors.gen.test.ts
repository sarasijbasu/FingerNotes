/**
 * Generates shared/test-vectors.json and shared/fixtures/web-backup.zip.
 * The Android unit tests (CrossPlatformVectorsTest) must reproduce / open these byte-for-byte,
 * which is what proves Chrome <-> Android interoperability without a device.
 */
import 'fake-indexeddb/auto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { createBackup } from '../src/core/backup';
import { b64url, hex, te } from '../src/core/bytes';
import { aesEncrypt, importAesKey, openBytes } from '../src/core/crypto';
import { deriveSyncMaterial } from '../src/core/mnemonic';
import { encodeSignal } from '../src/sync/signal';

const MNEMONIC =
  'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon ' +
  'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art';

describe('cross-platform vectors', () => {
  it('writes vectors', async () => {
    const sm = await deriveSyncMaterial(MNEMONIC);
    const iv = new Uint8Array(12).map((_, i) => i);
    const plaintext = '{"t":"hello","v":1}';
    const sealed = 'v1.' + b64url(await aesEncrypt(sm.key, te.encode(plaintext), iv));
    expect(new TextDecoder().decode(await openBytes(sm.key, sealed))).toBe(plaintext);
    const signal = await encodeSignal(sm.key, { type: 'offer', sdp: 'v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n', deviceId: 'dev1', deviceName: 'Test', platform: 'chrome' });

    const note = {
      id: '11111111-1111-4111-8111-111111111111', kind: 'checklist', title: 'Cross-platform', body: '**hi**',
      items: [{ id: 'c1', text: 'from chrome', checked: true, position: 0 }], color: 'yellow', labelIds: [], attachments: [
        { id: 'm1', kind: 'image', mime: 'image/png', size: 4, name: 'a.png', createdAt: 1 },
      ], isPinned: true, isArchived: false, isHidden: false, isTrashed: false, deletedAt: null,
      createdAt: 1, updatedAt: 2, hlc: '000001763000000000-00000-aaaaaaaaaaaaaaaa'.replace('000001763000000000', '001763000000000'),
    };
    const zip = await createBackup(
      { notes: [note as never], labels: [], expenses: [], categories: [], tombstones: [{ id: 'gone', entityType: 'note', purgedAt: 5, hlc: '000000000000005-00000-aaaaaaaaaaaaaaaa' }] },
      [{ id: 'm1', ownerId: note.id, mime: 'image/png', bytes: new Uint8Array([137, 80, 78, 71]) }],
      'cross-platform-pw',
    );

    mkdirSync('../shared/fixtures', { recursive: true });
    writeFileSync('../shared/fixtures/web-backup.zip', new Uint8Array(await zip.arrayBuffer()));
    const raw = await importAesKey(sm.rawKey, true).then((k) => crypto.subtle.exportKey('raw', k));
    writeFileSync(
      '../shared/test-vectors.json',
      JSON.stringify(
        {
          mnemonic: MNEMONIC,
          syncKeyHex: hex(new Uint8Array(raw)),
          chainId: sm.chainId,
          aesGcm: { ivHex: hex(iv), plaintext, envelope: sealed },
          signal: { code: signal, sdp: 'v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n' },
          backup: { file: 'fixtures/web-backup.zip', password: 'cross-platform-pw', noteId: note.id, mediaId: 'm1' },
          hlcOrder: ['000000000001000-00000-ffffffffffffffff', '000000000001000-00001-0000000000000000', '000000000001001-00000-0000000000000000'],
        },
        null,
        2,
      ) + '\n',
    );
  });
});
