import { td, te, uuid } from './bytes';
import { aesDecrypt, aesEncrypt, open, seal } from './crypto';
import type { FingerNotesDB } from './db';
import type { Hlc } from './hlc';
import { drainClips } from './inbox';
import { COLL_OF, type Coll, type MediaBlob, type SyncStore } from './merge';
import {
  BUILTIN_CATEGORIES, emptyNote, type Attachment, type Category, type ChecklistItem, type EntityType,
  type Expense, type Label, type Note, type Tombstone, type Versioned,
} from './model';
import { loadHlc, purgeExpired, purgeRecord } from './purge';

interface MediaInfo {
  ownerId: string;
  mime: string;
}

export interface Snapshot {
  notes: Note[];
  labels: Label[];
  expenses: Expense[];
  categories: Category[];
  tombstones: Tombstone[];
}

/**
 * Decrypted in-memory view of the encrypted IndexedDB, plus all mutations.
 * Every write re-encrypts the touched row with the DEK.
 */
export class Repo implements SyncStore {
  notes = new Map<string, Note>();
  labels = new Map<string, Label>();
  expenses = new Map<string, Expense>();
  categories = new Map<string, Category>();
  tombs = new Map<string, Tombstone>();
  media = new Map<string, MediaInfo>();
  version = 0;
  private listeners = new Set<() => void>();
  private urlCache = new Map<string, string>();

  private constructor(public db: FingerNotesDB, private key: CryptoKey, public hlc: Hlc) {}

  get deviceId() {
    return this.hlc.node;
  }

  static async open(db: FingerNotesDB, key: CryptoKey): Promise<Repo> {
    const repo = new Repo(db, key, await loadHlc(db));
    await repo.reload();
    await repo.ensureBuiltins();
    await repo.importClips();
    return repo;
  }

  subscribe(fn: () => void) {
    this.listeners.add(fn);
    return () => void this.listeners.delete(fn);
  }
  private emit() {
    this.version++;
    this.listeners.forEach((f) => f());
  }

  async reload() {
    const { db, key } = this;
    const dec = <T>(rows: { data: string }[]) => Promise.all(rows.map((r) => open<T>(key, r.data)));
    const items = await dec<ChecklistItem & { noteId: string }>(await db.checklistItems.toArray());
    const byNote = new Map<string, ChecklistItem[]>();
    for (const { noteId, ...it } of items) {
      if (!byNote.has(noteId)) byNote.set(noteId, []);
      byNote.get(noteId)!.push(it);
    }
    const notes = await dec<Omit<Note, 'items'>>(await db.notes.toArray());
    this.notes = new Map(notes.map((n) => [n.id, { ...n, items: (byNote.get(n.id) ?? []).sort((a, b) => a.position - b.position) }]));
    this.labels = new Map((await dec<Label>(await db.labels.toArray())).map((x) => [x.id, x]));
    this.expenses = new Map((await dec<Expense>(await db.expenses.toArray())).map((x) => [x.id, x]));
    this.categories = new Map((await dec<Category>(await db.categories.toArray())).map((x) => [x.id, x]));
    this.tombs = new Map((await db.tombstones.toArray()).map((t) => [t.id, t]));
    this.media = new Map();
    await db.media.each((m) => this.media.set(m.id, { ownerId: m.ownerId, mime: '' }));
    this.emit();
  }

  private async ensureBuiltins() {
    for (const b of BUILTIN_CATEGORIES) {
      if (!this.categories.has(b.id) && !this.tombs.has(b.id)) {
        // Fixed epoch HLC so every device's seed loses to any real edit.
        const c: Category = { ...b, builtIn: true, createdAt: 0, updatedAt: 0, hlc: `${'0'.repeat(15)}-00000-0000000000000000` };
        await this.write('categories', c);
      }
    }
  }

  async importClips() {
    const clips = await drainClips(this.db, this.key);
    for (const c of clips) {
      const n = this.newNote();
      n.title = c.title.slice(0, 120);
      n.body = `${c.text}\n\n${c.url}`;
      n.createdAt = c.createdAt;
      await this.saveNote(n);
    }
  }

  // ---------------- persistence ----------------
  private tick() {
    const h = this.hlc.tick();
    void this.db.setMeta('hlc', h);
    return h;
  }

  private async write(c: Coll, rec: Versioned) {
    const { db, key } = this;
    if (c === 'notes') {
      const { items, ...rest } = rec as Note;
      const row = { id: rest.id, deletedAt: rest.deletedAt, data: await seal(key, rest) };
      const itemRows = await Promise.all(items.map(async (it) => ({ id: it.id, noteId: rest.id, data: await seal(key, { ...it, noteId: rest.id }) })));
      await db.transaction('rw', db.notes, db.checklistItems, async () => {
        await db.notes.put(row);
        await db.checklistItems.where('noteId').equals(rest.id).delete();
        await db.checklistItems.bulkPut(itemRows);
      });
      this.notes.set(rec.id, rec as Note);
    } else if (c === 'expenses') {
      const e = rec as Expense;
      await db.expenses.put({ id: e.id, deletedAt: e.deletedAt, data: await seal(key, e) });
      this.expenses.set(e.id, e);
    } else {
      await db[c].put({ id: rec.id, data: await seal(key, rec) });
      (this[c] as Map<string, Versioned>).set(rec.id, rec);
    }
  }

  private stamp<T extends Versioned>(rec: T): T {
    return { ...rec, updatedAt: Date.now(), hlc: this.tick() };
  }

  // ---------------- notes ----------------
  newNote(kind: Note['kind'] = 'text'): Note {
    return emptyNote(uuid(), Date.now(), '', kind);
  }

  async saveNote(n: Note) {
    const s = this.stamp({ ...n, items: n.items.map((it, i) => ({ ...it, position: i })) });
    await this.write('notes', s);
    this.emit();
    return s;
  }

  async updateNote(id: string, patch: Partial<Note>) {
    const n = this.notes.get(id);
    if (n) return this.saveNote({ ...n, ...patch });
  }

  trashNote = (id: string) => this.updateNote(id, { isTrashed: true, deletedAt: Date.now(), isPinned: false });
  restoreNote = (id: string) => this.updateNote(id, { isTrashed: false, deletedAt: null });

  async deleteForever(entityType: EntityType, id: string) {
    const t = await purgeRecord(this.db, entityType, id, this.hlc);
    this.dropLocal(t);
    this.emit();
  }

  private dropLocal(t: Tombstone) {
    (this[COLL_OF[t.entityType]] as Map<string, Versioned>).delete(t.id);
    this.tombs.set(t.id, t);
    for (const [mid, m] of this.media) if (m.ownerId === t.id) this.dropMediaCache(mid);
  }

  async emptyTrash() {
    for (const n of [...this.notes.values()].filter((x) => x.isTrashed)) await purgeRecord(this.db, 'note', n.id, this.hlc);
    for (const e of [...this.expenses.values()].filter((x) => x.isTrashed)) await purgeRecord(this.db, 'expense', e.id, this.hlc);
    await this.reload();
  }

  async runPurge(now = Date.now()) {
    const n = await purgeExpired(this.db, now);
    if (n) {
      this.hlc = await loadHlc(this.db);
      await this.reload();
    }
    return n;
  }

  // ---------------- labels ----------------
  async saveLabel(name: string, id?: string) {
    const now = Date.now();
    const prev = id ? this.labels.get(id) : undefined;
    const l = this.stamp<Label>(prev ? { ...prev, name } : { id: uuid(), name, createdAt: now, updatedAt: now, hlc: '' });
    await this.write('labels', l);
    this.emit();
    return l;
  }

  async deleteLabel(id: string) {
    for (const n of this.notes.values()) {
      if (n.labelIds.includes(id)) await this.saveNote({ ...n, labelIds: n.labelIds.filter((x) => x !== id) });
    }
    await this.deleteForever('label', id);
  }

  // ---------------- expenses & categories ----------------
  async saveExpense(e: Expense) {
    const s = this.stamp(e);
    await this.write('expenses', s);
    this.emit();
    return s;
  }
  async updateExpense(id: string, patch: Partial<Expense>) {
    const e = this.expenses.get(id);
    if (e) return this.saveExpense({ ...e, ...patch });
  }
  trashExpense = (id: string) => this.updateExpense(id, { isTrashed: true, deletedAt: Date.now() });
  restoreExpense = (id: string) => this.updateExpense(id, { isTrashed: false, deletedAt: null });

  async saveCategory(name: string, color: string) {
    const now = Date.now();
    const c = this.stamp<Category>({ id: uuid(), name, color, builtIn: false, createdAt: now, updatedAt: now, hlc: '' });
    await this.write('categories', c);
    this.emit();
    return c;
  }

  // ---------------- media ----------------
  async addMedia(ownerId: string, blob: Blob, kind: Attachment['kind'], name: string): Promise<Attachment> {
    const id = uuid();
    const bytes = new Uint8Array(await blob.arrayBuffer());
    await this.putMedia({ id, ownerId, mime: blob.type || 'application/octet-stream', bytes });
    return { id, kind, mime: blob.type, size: bytes.length, name, createdAt: Date.now() };
  }

  async mediaUrl(id: string): Promise<string | null> {
    if (this.urlCache.has(id)) return this.urlCache.get(id)!;
    const m = await this.getMedia(id);
    if (!m) return null;
    const url = URL.createObjectURL(new Blob([m.bytes as unknown as ArrayBuffer], { type: m.mime }));
    this.urlCache.set(id, url);
    return url;
  }
  private dropMediaCache(id: string) {
    const u = this.urlCache.get(id);
    if (u) URL.revokeObjectURL(u);
    this.urlCache.delete(id);
    this.media.delete(id);
  }

  async removeMedia(id: string) {
    await this.db.media.delete(id);
    this.dropMediaCache(id);
  }

  // ---------------- SyncStore ----------------
  list(c: Coll): Versioned[] {
    return [...(this[c] as Map<string, Versioned>).values()];
  }
  get(c: Coll, id: string) {
    return (this[c] as Map<string, Versioned>).get(id);
  }
  async putRemote(c: Coll, rec: Versioned) {
    await this.write(c, rec);
    this.emit();
  }
  tombstoneList() {
    return [...this.tombs.values()];
  }
  hasTombstone(id: string) {
    return this.tombs.has(id);
  }
  async applyTombstone(t: Tombstone) {
    const { db } = this;
    await db.transaction('rw', [db.notes, db.checklistItems, db.labels, db.expenses, db.categories, db.media, db.tombstones], async () => {
      await db[COLL_OF[t.entityType]].delete(t.id);
      if (t.entityType === 'note') await db.checklistItems.where('noteId').equals(t.id).delete();
      await db.media.where('ownerId').equals(t.id).delete();
      await db.tombstones.put(t);
    });
    this.dropLocal(t);
    this.emit();
  }
  mediaIds() {
    return [...this.media.keys()];
  }
  async getMedia(id: string): Promise<MediaBlob | undefined> {
    const row = await this.db.media.get(id);
    if (!row) return undefined;
    const meta = await open<{ mime: string }>(this.key, row.meta);
    return { id, ownerId: row.ownerId, mime: meta.mime, bytes: await aesDecrypt(this.key, row.data) };
  }
  async putMedia(m: MediaBlob) {
    const row = { id: m.id, ownerId: m.ownerId, meta: await seal(this.key, { mime: m.mime }), data: await aesEncrypt(this.key, m.bytes) };
    await this.db.media.put(row);
    this.media.set(m.id, { ownerId: m.ownerId, mime: m.mime });
  }
  observeHlc(h: string) {
    this.hlc.receive(h);
    void this.db.setMeta('hlc', this.hlc.current());
  }

  // ---------------- encrypted settings (sync chain etc.) ----------------
  async setSecret(name: string, value: unknown) {
    await this.db.setMeta(`secret:${name}`, value == null ? null : await seal(this.key, value));
    this.emit();
  }
  async getSecret<T>(name: string): Promise<T | null> {
    const v = await this.db.getMeta<string | null>(`secret:${name}`);
    return v ? open<T>(this.key, v) : null;
  }

  // ---------------- backup ----------------
  snapshot(): Snapshot {
    return {
      notes: [...this.notes.values()],
      labels: [...this.labels.values()],
      expenses: [...this.expenses.values()],
      categories: [...this.categories.values()],
      tombstones: this.tombstoneList(),
    };
  }

  async wipe() {
    const { db } = this;
    await db.transaction('rw', [db.notes, db.checklistItems, db.labels, db.expenses, db.categories, db.media, db.tombstones], async () => {
      await Promise.all([db.notes.clear(), db.checklistItems.clear(), db.labels.clear(), db.expenses.clear(), db.categories.clear(), db.media.clear(), db.tombstones.clear()]);
    });
    for (const id of [...this.urlCache.keys()]) this.dropMediaCache(id);
    await this.reload();
  }

  /** Raw JSON helpers for media-free text export. */
  static encodeJson = (o: unknown) => te.encode(JSON.stringify(o, null, 2));
  static decodeJson = <T>(b: Uint8Array): T => JSON.parse(td.decode(b));
}
