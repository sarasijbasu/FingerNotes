import { useMemo, useRef, useState } from 'react';
import { uuid } from '../core/bytes';
import { DAY_MS, PAYMENT_LABELS, type Expense, type PaymentMethod } from '../core/model';
import type { Repo } from '../core/repo';
import { buildExpenseWorkbook, filterByRange } from '../core/xlsx';
import { download, fmtMoney, IconButton, Modal, snack } from './common';
import { Attachments } from './Editor';

const startOfDay = (t: number) => new Date(new Date(t).setHours(0, 0, 0, 0)).getTime();
const startOfWeek = (t: number) => {
  const d = new Date(startOfDay(t));
  return d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
};
const startOfMonth = (t: number) => new Date(new Date(t).getFullYear(), new Date(t).getMonth(), 1).getTime();
const toLocalInput = (t: number) => new Date(t - new Date(t).getTimezoneOffset() * 60000).toISOString().slice(0, 16);
const dateInput = (t: number) => toLocalInput(t).slice(0, 10);

function totals(list: Expense[]) {
  const by = new Map<string, number>();
  for (const e of list) by.set(e.currency, (by.get(e.currency) ?? 0) + e.amount);
  return [...by.entries()].map(([c, v]) => `${c}${fmtMoney(v)}`).join(' · ') || '0.00';
}

export function ExpensesView({ repo, currency }: { repo: Repo; currency: string }) {
  const now = Date.now();
  const [from, setFrom] = useState(dateInput(startOfMonth(now)));
  const [to, setTo] = useState(dateInput(now));
  const [cat, setCat] = useState('');
  const [method, setMethod] = useState<'' | PaymentMethod>('');
  const [editing, setEditing] = useState<Expense | null>(null);
  const [exportScope, setExportScope] = useState<'month' | 'range' | 'all'>('month');

  const active = [...repo.expenses.values()].filter((e) => !e.isTrashed);
  const fromMs = new Date(from + 'T00:00').getTime();
  const toMs = new Date(to + 'T23:59:59').getTime();
  const filtered = active
    .filter((e) => e.occurredAt >= fromMs && e.occurredAt <= toMs && (!cat || e.categoryId === cat) && (!method || e.paymentMethod === method))
    .sort((a, b) => b.occurredAt - a.occurredAt);

  const byCat = useMemo(() => {
    const m = new Map<string, number>();
    for (const e of filtered) m.set(e.categoryId, (m.get(e.categoryId) ?? 0) + e.amount);
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  }, [filtered]);
  const max = Math.max(1, ...byCat.map((x) => x[1]));

  const groups = new Map<string, Expense[]>();
  for (const e of filtered) {
    const k = new Date(e.occurredAt).toDateString();
    groups.set(k, [...(groups.get(k) ?? []), e]);
  }

  const newExpense = (): Expense => ({
    id: uuid(), amount: 0, currency, title: '', categoryId: 'food', paymentMethod: 'UPI_BANK', occurredAt: Date.now(), notes: '',
    receipt: null, isTrashed: false, deletedAt: null, createdAt: Date.now(), updatedAt: 0, hlc: '',
  });

  function exportXlsx() {
    const [f, t, name] =
      exportScope === 'month' ? [startOfMonth(now), now, new Date(now).toISOString().slice(0, 7)] : exportScope === 'range' ? [fromMs, toMs, `${from}_to_${to}`] : [undefined, undefined, 'all-time'];
    const rows = filterByRange(active, f, t);
    download(buildExpenseWorkbook(rows, repo.categories), `fingernotes-expenses-${name}.xlsx`, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
    snack({ text: `Exported ${rows.length} expenses` });
  }

  return (
    <div>
      <div className="stats">
        <div className="stat"><div className="k">Today</div><div className="v">{totals(active.filter((e) => e.occurredAt >= startOfDay(now)))}</div></div>
        <div className="stat"><div className="k">This week</div><div className="v">{totals(active.filter((e) => e.occurredAt >= startOfWeek(now)))}</div></div>
        <div className="stat"><div className="k">This month</div><div className="v">{totals(active.filter((e) => e.occurredAt >= startOfMonth(now)))}</div></div>
        <div className="stat"><div className="k">Filtered ({filtered.length})</div><div className="v">{totals(filtered)}</div></div>
      </div>

      <div className="row" style={{ marginBottom: 12 }}>
        <button className="btn primary" onClick={() => setEditing(newExpense())}>+ Add expense</button>
        <span className="grow" />
        <select value={exportScope} onChange={(e) => setExportScope(e.target.value as typeof exportScope)} style={{ width: 'auto' }}>
          <option value="month">Current month</option>
          <option value="range">Filtered date range</option>
          <option value="all">All time</option>
        </select>
        <button className="btn" onClick={exportXlsx}>Export .xlsx</button>
      </div>

      <div className="card-panel" style={{ marginBottom: 12 }}>
        <div className="form-grid">
          <label className="field">From<input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          <label className="field">To<input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
          <label className="field">Category
            <select value={cat} onChange={(e) => setCat(e.target.value)}>
              <option value="">All</option>
              {[...repo.categories.values()].map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </label>
          <label className="field">Payment
            <select value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod | '')}>
              <option value="">All</option>
              {Object.entries(PAYMENT_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
        </div>
        <div className="row" style={{ marginTop: 8 }}>
          <button className="btn small" onClick={() => { setFrom(dateInput(now)); setTo(dateInput(now)); }}>Today</button>
          <button className="btn small" onClick={() => { setFrom(dateInput(startOfWeek(now))); setTo(dateInput(now)); }}>This week</button>
          <button className="btn small" onClick={() => { setFrom(dateInput(startOfMonth(now))); setTo(dateInput(now)); }}>This month</button>
          <button className="btn small" onClick={() => { setFrom(dateInput(now - 365 * DAY_MS)); setTo(dateInput(now)); }}>Last 12 months</button>
        </div>
      </div>

      {byCat.length > 0 && (
        <div className="card-panel" style={{ marginBottom: 12 }}>
          <h3 style={{ marginTop: 0 }}>By category</h3>
          <div className="bars">
            {byCat.map(([id, v]) => {
              const c = repo.categories.get(id);
              return (
                <div className="bar" key={id}>
                  <span>{c?.name ?? 'Uncategorized'}</span>
                  <div className="track"><div className="fill" style={{ width: `${(v / max) * 100}%`, background: c?.color ?? '#888' }} /></div>
                  <span className="amt">{fmtMoney(v)}</span>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {filtered.length === 0 && <div className="empty">No expenses in this range</div>}
      {[...groups.entries()].map(([day, list]) => (
        <div key={day}>
          <div className="exp-day"><span>{day}</span><span>{totals(list)}</span></div>
          {list.map((e) => {
            const c = repo.categories.get(e.categoryId);
            return (
              <div key={e.id} className="exp" onClick={() => setEditing(e)}>
                <span className="dot" style={{ background: c?.color ?? '#888' }} />
                <div>
                  <div>{e.title || '(untitled)'}</div>
                  <div className="muted" style={{ fontSize: 12 }}>
                    {c?.name ?? 'Uncategorized'} · {PAYMENT_LABELS[e.paymentMethod]} · {new Date(e.occurredAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                    {e.receipt ? ' · 🧾' : ''}
                  </div>
                </div>
                <span className="amt">{e.currency}{fmtMoney(e.amount)}</span>
                <IconButton icon="trash" title="Move to Trash" onClick={async () => {
                  await repo.trashExpense(e.id);
                  snack({ text: 'Expense moved to Trash', action: { label: 'Undo', run: () => repo.restoreExpense(e.id) } });
                }} />
              </div>
            );
          })}
        </div>
      ))}

      {editing && <ExpenseForm repo={repo} expense={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ExpenseForm({ repo, expense, onClose }: { repo: Repo; expense: Expense; onClose: () => void }) {
  const [e, setE] = useState(expense);
  const [amount, setAmount] = useState(expense.amount ? String(expense.amount) : '');
  const [newCat, setNewCat] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);
  const isNew = !repo.expenses.has(expense.id);

  async function save() {
    const amt = Math.round(parseFloat(amount) * 100) / 100;
    if (!isFinite(amt) || amt <= 0) return snack({ text: 'Enter an amount greater than 0' });
    await repo.saveExpense({ ...e, amount: amt });
    if (expense.receipt && expense.receipt.id !== e.receipt?.id) await repo.removeMedia(expense.receipt.id);
    onClose();
  }

  async function attach(files: FileList | null) {
    const f = files?.[0];
    if (!f) return;
    const att = await repo.addMedia(e.id, f, 'image', f.name);
    setE({ ...e, receipt: att });
  }

  return (
    <Modal onClose={onClose}>
      <div style={{ padding: 16 }}>
        <h2>{isNew ? 'New expense' : 'Edit expense'}</h2>
        <div className="form-grid">
          <label className="field">Amount<input type="number" inputMode="decimal" step="0.01" min="0" autoFocus value={amount} onChange={(x) => setAmount(x.target.value)} /></label>
          <label className="field">Currency<input type="text" maxLength={4} value={e.currency} onChange={(x) => setE({ ...e, currency: x.target.value })} /></label>
          <label className="field" style={{ gridColumn: '1 / -1' }}>Title / Merchant<input type="text" value={e.title} onChange={(x) => setE({ ...e, title: x.target.value })} /></label>
          <label className="field">Category
            <select value={e.categoryId} onChange={(x) => setE({ ...e, categoryId: x.target.value })}>
              {[...repo.categories.values()].map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </label>
          <label className="field">Payment method
            <select value={e.paymentMethod} onChange={(x) => setE({ ...e, paymentMethod: x.target.value as PaymentMethod })}>
              {Object.entries(PAYMENT_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
          <label className="field">Date & time<input type="datetime-local" value={toLocalInput(e.occurredAt)} onChange={(x) => setE({ ...e, occurredAt: new Date(x.target.value).getTime() || e.occurredAt })} /></label>
          <label className="field" style={{ gridColumn: '1 / -1' }}>Notes<textarea rows={2} value={e.notes} onChange={(x) => setE({ ...e, notes: x.target.value })} /></label>
        </div>
        <div className="row" style={{ marginTop: 10 }}>
          <input type="text" placeholder="New custom category" value={newCat} onChange={(x) => setNewCat(x.target.value)} style={{ maxWidth: 220 }} />
          <button className="btn small" onClick={async () => {
            if (!newCat.trim()) return;
            const c = await repo.saveCategory(newCat.trim(), `hsl(${Math.floor(Math.random() * 360)} 60% 50%)`);
            setE({ ...e, categoryId: c.id });
            setNewCat('');
          }}>Add category</button>
        </div>
        <h3>Receipt</h3>
        {e.receipt ? <Attachments repo={repo} items={[e.receipt]} onRemove={() => setE({ ...e, receipt: null })} /> : <button className="btn small" onClick={() => fileRef.current?.click()}>Attach receipt image</button>}
        <input ref={fileRef} type="file" accept="image/*" hidden onChange={(x) => attach(x.target.files)} />
        <div className="row" style={{ marginTop: 16, justifyContent: 'flex-end' }}>
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn primary" onClick={save}>Save</button>
        </div>
      </div>
    </Modal>
  );
}
