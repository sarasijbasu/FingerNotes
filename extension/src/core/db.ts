import Dexie, { type Table } from 'dexie';
import type { Tombstone } from './model';

/**
 * IndexedDB schema (Dexie). Every user-content column is an AES-256-GCM envelope (`data`)
 * encrypted with the Data Encryption Key. Only routing fields stay in plaintext:
 * ids, foreign keys and `deletedAt` (so the service worker can run the 30-day purge while locked).
 */
export interface EncRow {
  id: string;
  data: string;
}
export interface TrashableRow extends EncRow {
  deletedAt: number | null;
}
export interface ChecklistRow extends EncRow {
  noteId: string;
}
export interface MediaRow {
  id: string;
  ownerId: string;
  /** envelope of {mime, kind, name, size} */
  meta: string;
  /** iv ‖ ciphertext ‖ tag */
  data: Uint8Array;
}
export interface MetaRow {
  key: string;
  value: unknown;
}
export interface InboxRow {
  seq?: number;
  wrappedKey: Uint8Array;
  data: Uint8Array;
  createdAt: number;
}

export class FingerNotesDB extends Dexie {
  notes!: Table<TrashableRow, string>;
  checklistItems!: Table<ChecklistRow, string>;
  labels!: Table<EncRow, string>;
  expenses!: Table<TrashableRow, string>;
  categories!: Table<EncRow, string>;
  media!: Table<MediaRow, string>;
  tombstones!: Table<Tombstone, string>;
  meta!: Table<MetaRow, string>;
  inbox!: Table<InboxRow, number>;

  constructor(name = 'fingernotes') {
    super(name);
    this.version(1).stores({
      notes: 'id, deletedAt',
      checklistItems: 'id, noteId',
      labels: 'id',
      expenses: 'id, deletedAt',
      categories: 'id',
      media: 'id, ownerId',
      tombstones: 'id, purgedAt',
      meta: 'key',
      inbox: '++seq',
    });
  }

  async getMeta<T>(key: string): Promise<T | undefined> {
    return (await this.meta.get(key))?.value as T | undefined;
  }
  setMeta(key: string, value: unknown) {
    return this.meta.put({ key, value });
  }
}

let shared: FingerNotesDB | null = null;
export const getDb = () => (shared ??= new FingerNotesDB());
