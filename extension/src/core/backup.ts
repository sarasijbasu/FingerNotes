import { BlobReader, BlobWriter, Uint8ArrayReader, Uint8ArrayWriter, ZipReader, ZipWriter, configure } from '@zip.js/zip.js';
import { td, te } from './bytes';
import { sha256Hex } from './crypto';
import { applyRecords, type MediaBlob, type SyncStore } from './merge';
import { APP_VERSION, SCHEMA_VERSION, type Platform } from './model';
import type { Snapshot } from './repo';

// MV3 CSP forbids blob: workers; compression runs inline via native CompressionStream.
configure({ useWebWorkers: false });

/** WinZip AES-256 (AE-2) — byte-compatible with Zip4j AesKeyStrength.KEY_STRENGTH_256. */
const AES256 = { encryptionStrength: 3 as const, zipCrypto: false };

export interface BackupMetadata {
  app: 'FingerNotes';
  appVersion: string;
  schemaVersion: number;
  createdAt: number;
  platform: Platform;
  counts: Record<string, number>;
  checksums: Record<string, string>;
}

export interface BackupContents {
  metadata: BackupMetadata;
  snapshot: Snapshot;
  media: MediaBlob[];
}

export class BackupError extends Error {
  constructor(public code: 'BAD_PASSWORD' | 'CHECKSUM' | 'FORMAT' | 'VERSION', message: string) {
    super(message);
  }
}

export async function createBackup(snapshot: Snapshot, media: MediaBlob[], password: string, platform: Platform = 'chrome'): Promise<Blob> {
  if (!password) throw new Error('A backup password is required');
  const files: Record<string, Uint8Array> = {
    'notes.json': te.encode(JSON.stringify({ notes: snapshot.notes, labels: snapshot.labels })),
    'expenses.json': te.encode(JSON.stringify({ expenses: snapshot.expenses, categories: snapshot.categories })),
    'tombstones.json': te.encode(JSON.stringify({ tombstones: snapshot.tombstones })),
  };
  const mediaIndex = media.map((m) => ({ id: m.id, ownerId: m.ownerId, mime: m.mime }));
  files['media/index.json'] = te.encode(JSON.stringify({ media: mediaIndex }));
  for (const m of media) files[`media/${m.id}`] = m.bytes;

  const checksums: Record<string, string> = {};
  for (const [name, bytes] of Object.entries(files)) checksums[name] = await sha256Hex(bytes);
  const metadata: BackupMetadata = {
    app: 'FingerNotes',
    appVersion: APP_VERSION,
    schemaVersion: SCHEMA_VERSION,
    createdAt: Date.now(),
    platform,
    counts: {
      notes: snapshot.notes.length, labels: snapshot.labels.length, expenses: snapshot.expenses.length,
      categories: snapshot.categories.length, tombstones: snapshot.tombstones.length, media: media.length,
    },
    checksums,
  };

  const zip = new ZipWriter(new BlobWriter('application/zip'), { password, ...AES256 });
  await zip.add('metadata.json', new Uint8ArrayReader(te.encode(JSON.stringify(metadata, null, 2))));
  for (const [name, bytes] of Object.entries(files)) await zip.add(name, new Uint8ArrayReader(bytes));
  return zip.close();
}

export async function readBackup(file: Blob, password: string): Promise<BackupContents> {
  const reader = new ZipReader(new BlobReader(file));
  let entries;
  try {
    entries = await reader.getEntries();
  } catch {
    throw new BackupError('FORMAT', 'Not a valid ZIP archive');
  }
  const files: Record<string, Uint8Array> = {};
  for (const e of entries) {
    if (e.directory || !e.getData) continue;
    try {
      files[e.filename] = await e.getData(new Uint8ArrayWriter(), { password });
    } catch (err) {
      const msg = String((err as Error)?.message ?? err);
      if (/password|encrypted/i.test(msg)) throw new BackupError('BAD_PASSWORD', 'Wrong backup password');
      throw new BackupError('FORMAT', msg);
    }
  }
  await reader.close();

  if (!files['metadata.json']) throw new BackupError('FORMAT', 'metadata.json missing');
  const metadata = JSON.parse(td.decode(files['metadata.json'])) as BackupMetadata;
  if (metadata.app !== 'FingerNotes') throw new BackupError('FORMAT', 'Not a FingerNotes backup');
  if (metadata.schemaVersion > SCHEMA_VERSION) throw new BackupError('VERSION', 'Backup was made by a newer app version');
  for (const [name, sum] of Object.entries(metadata.checksums)) {
    if (!files[name] || (await sha256Hex(files[name])) !== sum) throw new BackupError('CHECKSUM', `Checksum mismatch: ${name}`);
  }

  const json = <T>(n: string): T => JSON.parse(td.decode(files[n]));
  const notes = json<Pick<Snapshot, 'notes' | 'labels'>>('notes.json');
  const exp = json<Pick<Snapshot, 'expenses' | 'categories'>>('expenses.json');
  const tombs = json<Pick<Snapshot, 'tombstones'>>('tombstones.json');
  const index = files['media/index.json'] ? json<{ media: Omit<MediaBlob, 'bytes'>[] }>('media/index.json').media : [];
  const media = index.filter((m) => files[`media/${m.id}`]).map((m) => ({ ...m, bytes: files[`media/${m.id}`] }));
  return { metadata, snapshot: { ...notes, ...exp, ...tombs }, media };
}

/** Merge by UUID: same LWW + tombstone rules as sync. */
export async function mergeBackup(store: SyncStore, b: BackupContents) {
  const res = await applyRecords(store, b.snapshot);
  const have = new Set(store.mediaIds());
  for (const m of b.media) if (!have.has(m.id) && !store.hasTombstone(m.ownerId)) await store.putMedia(m);
  return res;
}
