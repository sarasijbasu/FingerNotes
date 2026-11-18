import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import XLSX from 'xlsx-js-style';
import { createBackup, mergeBackup, readBackup } from '../src/core/backup';
import { FingerNotesDB } from '../src/core/db';
import { Hlc, compareHlc } from '../src/core/hlc';
import { applyRecords, buildManifest, diffForPeer } from '../src/core/merge';
import { deriveSyncMaterial, isValidMnemonic, newMnemonic } from '../src/core/mnemonic';
import { DAY_MS, type Expense } from '../src/core/model';
import { purgeExpired } from '../src/core/purge';
import { Repo } from '../src/core/repo';
import { setupVault, unlockWithPin } from '../src/core/vault';
import { buildExpenseWorkbook } from '../src/core/xlsx';
import { runSyncSession } from '../src/sync/session';
import { decodeSignal, encodeSignal } from '../src/sync/signal';
import type { Channel } from '../src/sync/peer';

let dbn = 0;
async function freshRepo(pin = '1234') {
  const db = new FingerNotesDB(`test-${dbn++}`);
  const u = await setupVault(db, pin);
  return Repo.open(db, u.dek);
}

function channelPair(): [Channel, Channel] {
  const mk = () => ({ msg: [] as ((s: string) => void)[], close: [] as (() => void)[] });
  const a = mk(), b = mk();
  const ch = (me: typeof a, other: typeof a): Channel => ({
    send: (t) => setTimeout(() => other.msg.forEach((f) => f(t)), 0),
    onMessage: (f) => me.msg.push(f),
    onClose: (f) => me.close.push(f),
    bufferedAmount: () => 0,
    close: () => {},
  });
  return [ch(a, b), ch(b, a)];
}

describe('HLC', () => {
  it('is monotonic and ordered', () => {
    let t = 1000;
    const h = new Hlc('aaaaaaaaaaaaaaaa', undefined, () => t);
    const a = h.tick();
    const b = h.tick();
    expect(compareHlc(a, b)).toBe(-1);
    h.receive('000000000005000-00003-bbbbbbbbbbbbbbbb');
    const c = h.tick();
    expect(c > '000000000005000-00003-bbbbbbbbbbbbbbbb').toBe(true);
  });
});

describe('vault', () => {
  it('rejects a wrong PIN', async () => {
    const db = new FingerNotesDB(`vault-${dbn++}`);
    await setupVault(db, '4321');
    await expect(unlockWithPin(db, '0000')).rejects.toThrow('Wrong PIN');
    await expect(unlockWithPin(db, '4321')).resolves.toBeTruthy();
  });
});

describe('mnemonic + signaling', () => {
  it('derives the same chain from the same words', async () => {
    const m = newMnemonic();
    expect(m.split(' ')).toHaveLength(24);
    expect(isValidMnemonic(m)).toBe(true);
    const a = await deriveSyncMaterial(m);
    const b = await deriveSyncMaterial('  ' + m.toUpperCase().replace(/ /g, '   ') + '\n');
    expect(a.chainId).toBe(b.chainId);
    const code = await encodeSignal(a.key, { type: 'offer', sdp: 'v=0\r\n'.repeat(50), deviceId: 'x', deviceName: 'X', platform: 'chrome' });
    expect((await decodeSignal(b.key, code)).sdp).toContain('v=0');
    const other = await deriveSyncMaterial(newMnemonic());
    await expect(decodeSignal(other.key, code)).rejects.toThrow();
  });
});

describe('merge + sync session', () => {
  it('converges two devices including trash, restore and purge tombstones', async () => {
    const A = await freshRepo();
    const B = await freshRepo();
    const n1 = await A.saveNote({ ...A.newNote(), title: 'Groceries', body: '- milk' });
    const n2 = await A.saveNote({ ...A.newNote('checklist'), title: 'Todo', items: [{ id: 'i1', text: 'a', checked: false, position: 0 }] });
    await A.addMedia(n1.id, new Blob([new Uint8Array(100_000).fill(7)], { type: 'image/png' }), 'image', 'x.png').then((att) =>
      A.updateNote(n1.id, { attachments: [att] }),
    );
    const { key } = await deriveSyncMaterial(newMnemonic());
    const [ca, cb] = channelPair();
    const self = (r: Repo, name: string) => ({ deviceId: r.deviceId, deviceName: name, platform: 'chrome' as const });
    await Promise.all([runSyncSession(A, key, self(A, 'A'), ca), runSyncSession(B, key, self(B, 'B'), cb)]);
    expect(B.notes.get(n1.id)?.title).toBe('Groceries');
    expect(B.notes.get(n2.id)?.items[0].text).toBe('a');
    expect((await B.getMedia(B.notes.get(n1.id)!.attachments[0].id))?.bytes.length).toBe(100_000);

    // concurrent edits: B edits later → wins; A trashes n2, B purges n1
    await A.updateNote(n1.id, { title: 'A edit' });
    await new Promise((r) => setTimeout(r, 5));
    await B.updateNote(n1.id, { title: 'B edit' });
    await A.trashNote(n2.id);
    await applyRecords(B, diffForPeer(A, buildManifest(B)));
    await applyRecords(A, diffForPeer(B, buildManifest(A)));
    expect(A.notes.get(n1.id)?.title).toBe('B edit');
    expect(B.notes.get(n2.id)?.isTrashed).toBe(true);

    await B.deleteForever('note', n1.id);
    await A.updateNote(n1.id, { title: 'zombie edit' }); // must not resurrect
    await applyRecords(A, diffForPeer(B, buildManifest(A)));
    await applyRecords(B, diffForPeer(A, buildManifest(B)));
    expect(A.notes.has(n1.id)).toBe(false);
    expect(B.notes.has(n1.id)).toBe(false);
    expect(A.hasTombstone(n1.id)).toBe(true);
  });

  it('30-day purge writes tombstones', async () => {
    const A = await freshRepo();
    const n = await A.saveNote({ ...A.newNote(), title: 'old' });
    await A.updateNote(n.id, { isTrashed: true, deletedAt: Date.now() - 31 * DAY_MS });
    const keep = await A.saveNote({ ...A.newNote(), title: 'recent' });
    await A.trashNote(keep.id);
    expect(await purgeExpired(A.db)).toBe(1);
    await A.reload();
    expect(A.notes.has(n.id)).toBe(false);
    expect(A.notes.has(keep.id)).toBe(true);
    expect(A.hasTombstone(n.id)).toBe(true);
  });
});

describe('backup', () => {
  it('round-trips through AES-256 zip and detects a wrong password', async () => {
    const A = await freshRepo();
    const n = await A.saveNote({ ...A.newNote(), title: 'secret' });
    const att = await A.addMedia(n.id, new Blob([new Uint8Array([1, 2, 3])], { type: 'audio/webm' }), 'audio', 'memo.webm');
    await A.updateNote(n.id, { attachments: [att] });
    const media = await Promise.all(A.mediaIds().map((id) => A.getMedia(id)));
    const zip = await createBackup(A.snapshot(), media.map((m) => m!), 'hunter22');
    await expect(readBackup(zip, 'wrong')).rejects.toMatchObject({ code: 'BAD_PASSWORD' });
    const b = await readBackup(zip, 'hunter22');
    expect(b.metadata.counts.notes).toBe(1);
    const B = await freshRepo();
    await mergeBackup(B, b);
    expect(B.notes.get(n.id)?.title).toBe('secret');
    expect((await B.getMedia(att.id))?.bytes).toEqual(new Uint8Array([1, 2, 3]));
  });
});

describe('xlsx', () => {
  it('writes headers, rows and a SUM total', () => {
    const base = { currency: '₹', categoryId: 'food', paymentMethod: 'UPI_BANK', notes: '', receipt: null, isTrashed: false, deletedAt: null, createdAt: 0, updatedAt: 0, hlc: '' };
    const ex = [
      { ...base, id: '1', amount: 10.5, title: 'Tea', occurredAt: new Date(2026, 0, 2, 9, 30).getTime() },
      { ...base, id: '2', amount: 20, title: 'Bus', occurredAt: new Date(2026, 0, 3, 18, 5).getTime() },
    ] as Expense[];
    const bytes = buildExpenseWorkbook(ex, new Map([['food', { id: 'food', name: 'Food', color: '', builtIn: true, createdAt: 0, updatedAt: 0, hlc: '' }]]));
    const wb = XLSX.read(bytes, { type: 'array', cellFormula: true });
    const ws = wb.Sheets.Expenses;
    expect(ws.A1.v).toBe('Date');
    expect(ws.C2.v).toBe('Tea');
    expect(ws.E2.v).toBe('UPI/Bank');
    expect(ws.G4.f).toBe('SUM(G2:G3)');
    expect(ws.G4.v).toBe(30.5);
  });
});
