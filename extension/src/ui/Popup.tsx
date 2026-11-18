import { useEffect, useState } from 'react';
import { uuid } from '../core/bytes';
import type { Settings } from '../core/platform';
import type { Repo } from '../core/repo';
import { fmtMoney, snack, useRepo } from './common';

/** Toolbar popup: quick capture + launchers for the side panel and full-tab dashboard. */
export function Popup({ repo, settings }: { repo: Repo; settings: Settings }) {
  useRepo(repo);
  const [windowId, setWindowId] = useState<number | null>(null);
  const [text, setText] = useState('');
  const [amount, setAmount] = useState('');
  const [title, setTitle] = useState('');

  useEffect(() => {
    chrome.windows?.getCurrent().then((w) => setWindowId(w.id ?? null));
  }, []);

  const today = [...repo.expenses.values()].filter((e) => !e.isTrashed && new Date(e.occurredAt).toDateString() === new Date().toDateString());
  const recent = [...repo.notes.values()].filter((n) => !n.isTrashed && !n.isHidden && !n.isArchived).sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 3);

  return (
    <div style={{ padding: 14, display: 'grid', gap: 12 }}>
      <div className="row">
        <b className="grow" style={{ fontSize: 16 }}>FingerNotes</b>
        <button className="btn small" onClick={() => windowId != null && chrome.sidePanel.open({ windowId }).then(() => window.close())}>Side panel</button>
        <button className="btn small" onClick={() => chrome.tabs.create({ url: chrome.runtime.getURL('app.html') })}>Full app</button>
      </div>
      <div className="card-panel" style={{ display: 'grid', gap: 8, padding: 12 }}>
        <textarea rows={3} placeholder="Quick note…" value={text} onChange={(e) => setText(e.target.value)} autoFocus />
        <button
          className="btn primary"
          disabled={!text.trim()}
          onClick={async () => {
            const [first, ...rest] = text.trim().split('\n');
            await repo.saveNote({ ...repo.newNote(), title: rest.length ? first : '', body: rest.length ? rest.join('\n') : first });
            setText('');
            snack({ text: 'Note saved' });
          }}
        >
          Save note
        </button>
      </div>
      <div className="card-panel" style={{ display: 'grid', gap: 8, padding: 12 }}>
        <div className="row">
          <input type="number" placeholder={`Amount (${settings.defaultCurrency})`} value={amount} onChange={(e) => setAmount(e.target.value)} style={{ width: 120 }} />
          <input type="text" placeholder="Merchant" value={title} onChange={(e) => setTitle(e.target.value)} className="grow" style={{ width: 'auto' }} />
        </div>
        <button
          className="btn"
          disabled={!(parseFloat(amount) > 0)}
          onClick={async () => {
            const now = Date.now();
            await repo.saveExpense({
              id: uuid(), amount: Math.round(parseFloat(amount) * 100) / 100, currency: settings.defaultCurrency, title, categoryId: 'food',
              paymentMethod: 'UPI_BANK', occurredAt: now, notes: '', receipt: null, isTrashed: false, deletedAt: null, createdAt: now, updatedAt: now, hlc: '',
            });
            setAmount('');
            setTitle('');
            snack({ text: 'Expense added' });
          }}
        >
          Add expense
        </button>
        <div className="muted" style={{ fontSize: 12 }}>
          Today: {settings.defaultCurrency}
          {fmtMoney(today.reduce((s, e) => s + e.amount, 0))} across {today.length} expenses
        </div>
      </div>
      {recent.length > 0 && (
        <div>
          <div className="section-title">Recent</div>
          {recent.map((n) => (
            <div key={n.id} className="muted" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              • {n.title || n.body.split('\n')[0] || n.items[0]?.text || '(empty)'}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
