import { uuid } from './bytes';
import type { FingerNotesDB } from './db';
import { Hlc } from './hlc';
import { TRASH_RETENTION_MS, type EntityType, type Tombstone } from './model';

export async function loadHlc(db: FingerNotesDB): Promise<Hlc> {
  let deviceId = await db.getMeta<string>('deviceId');
  if (!deviceId) {
    deviceId = uuid().replace(/-/g, '').slice(0, 16);
    await db.setMeta('deviceId', deviceId);
  }
  return new Hlc(deviceId, await db.getMeta<string>('hlc'));
}

/** Permanently delete one record (and its children/media) and write a SyncTombstone. */
export async function purgeRecord(db: FingerNotesDB, entityType: EntityType, id: string, hlc: Hlc, now = Date.now()): Promise<Tombstone> {
  const t: Tombstone = { id, entityType, purgedAt: now, hlc: hlc.tick() };
  await db.transaction('rw', [db.notes, db.checklistItems, db.labels, db.expenses, db.categories, db.media, db.tombstones, db.meta], async () => {
    if (entityType === 'note') {
      await db.notes.delete(id);
      await db.checklistItems.where('noteId').equals(id).delete();
    } else if (entityType === 'expense') await db.expenses.delete(id);
    else if (entityType === 'label') await db.labels.delete(id);
    else await db.categories.delete(id);
    await db.media.where('ownerId').equals(id).delete();
    await db.tombstones.put(t);
    await db.meta.put({ key: 'hlc', value: hlc.current() });
  });
  return t;
}

/** The daily 30-day trash purge. Works on plaintext `deletedAt`, so it runs while the app is locked. */
export async function purgeExpired(db: FingerNotesDB, now = Date.now()): Promise<number> {
  const cutoff = now - TRASH_RETENTION_MS;
  const notes = await db.notes.where('deletedAt').belowOrEqual(cutoff).primaryKeys();
  const expenses = await db.expenses.where('deletedAt').belowOrEqual(cutoff).primaryKeys();
  if (!notes.length && !expenses.length) return 0;
  const hlc = await loadHlc(db);
  for (const id of notes) await purgeRecord(db, 'note', id, hlc, now);
  for (const id of expenses) await purgeRecord(db, 'expense', id, hlc, now);
  return notes.length + expenses.length;
}
