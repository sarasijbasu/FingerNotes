import type { Category, EntityType, Expense, Label, Note, Tombstone, Versioned } from './model';

/** Transport-agnostic HLC/LWW + tombstone merge engine — docs/PROTOCOL.md §5. */
export type Coll = 'notes' | 'labels' | 'expenses' | 'categories';
export const COLLS: Coll[] = ['notes', 'labels', 'expenses', 'categories'];
export const ENTITY_OF: Record<Coll, EntityType> = { notes: 'note', labels: 'label', expenses: 'expense', categories: 'category' };
export const COLL_OF: Record<EntityType, Coll> = { note: 'notes', label: 'labels', expense: 'expenses', category: 'categories' };

export interface MediaBlob {
  id: string;
  ownerId: string;
  mime: string;
  bytes: Uint8Array;
}

export interface SyncStore {
  list(c: Coll): Versioned[];
  get(c: Coll, id: string): Versioned | undefined;
  /** Persist a remote record verbatim (no local tick). */
  putRemote(c: Coll, rec: Versioned): Promise<void>;
  tombstoneList(): Tombstone[];
  hasTombstone(id: string): boolean;
  /** Store the tombstone and delete the record + children + media. */
  applyTombstone(t: Tombstone): Promise<void>;
  mediaIds(): string[];
  getMedia(id: string): Promise<MediaBlob | undefined>;
  putMedia(m: MediaBlob): Promise<void>;
  observeHlc(hlc: string): void;
}

export interface Manifest {
  notes: Record<string, string>;
  labels: Record<string, string>;
  expenses: Record<string, string>;
  categories: Record<string, string>;
  tombstones: string[];
  media: string[];
}

export interface RecordsPayload {
  notes: Note[];
  labels: Label[];
  expenses: Expense[];
  categories: Category[];
  tombstones: Tombstone[];
}

export function buildManifest(store: SyncStore): Manifest {
  const m = { tombstones: store.tombstoneList().map((t) => t.id), media: store.mediaIds() } as Manifest;
  for (const c of COLLS) m[c] = Object.fromEntries(store.list(c).map((r) => [r.id, r.hlc]));
  return m;
}

/** Everything the peer is missing or holds an older version of. */
export function diffForPeer(store: SyncStore, peer: Manifest): RecordsPayload {
  const peerTombs = new Set(peer.tombstones);
  const out = { tombstones: store.tombstoneList().filter((t) => !peerTombs.has(t.id)) } as RecordsPayload;
  for (const c of COLLS) {
    const theirs = peer[c] ?? {};
    (out[c] as Versioned[]) = store.list(c).filter((r) => !peerTombs.has(r.id) && (!theirs[r.id] || r.hlc > theirs[r.id]));
  }
  return out;
}

export function mediaMissingAtPeer(store: SyncStore, peer: Manifest): string[] {
  const theirs = new Set(peer.media);
  const tombs = new Set(peer.tombstones);
  return store.mediaIds().filter((id) => !theirs.has(id) && !tombs.has(id));
}

export interface ApplyResult {
  applied: number;
  purged: number;
  skipped: number;
}

export async function applyRecords(store: SyncStore, p: Partial<RecordsPayload>): Promise<ApplyResult> {
  const res: ApplyResult = { applied: 0, purged: 0, skipped: 0 };
  for (const t of p.tombstones ?? []) {
    store.observeHlc(t.hlc);
    if (!store.hasTombstone(t.id)) {
      await store.applyTombstone(t);
      res.purged++;
    }
  }
  for (const c of COLLS) {
    for (const rec of (p[c] ?? []) as Versioned[]) {
      store.observeHlc(rec.hlc);
      const local = store.get(c, rec.id);
      if (store.hasTombstone(rec.id) || (local && local.hlc >= rec.hlc)) {
        res.skipped++;
        continue;
      }
      await store.putRemote(c, rec);
      res.applied++;
    }
  }
  return res;
}
