import { daysUntilPurge, PAYMENT_LABELS, TRASH_RETENTION_DAYS } from '../core/model';
import type { Repo } from '../core/repo';
import { fmtMoney, IconButton } from './common';
import { NoteCard } from './Notes';

export function TrashView({ repo }: { repo: Repo }) {
  const notes = [...repo.notes.values()].filter((n) => n.isTrashed).sort((a, b) => (b.deletedAt ?? 0) - (a.deletedAt ?? 0));
  const expenses = [...repo.expenses.values()].filter((e) => e.isTrashed).sort((a, b) => (b.deletedAt ?? 0) - (a.deletedAt ?? 0));
  const empty = !notes.length && !expenses.length;
  return (
    <div>
      <div className="row" style={{ marginBottom: 12 }}>
        <span className="muted grow">Items in Trash are deleted forever after {TRASH_RETENTION_DAYS} days — on every synced device.</span>
        {!empty && (
          <button className="btn danger" onClick={() => confirm('Permanently delete everything in Trash on all synced devices?') && repo.emptyTrash()}>
            Empty Trash
          </button>
        )}
      </div>
      {empty && <div className="empty">Trash is empty</div>}
      {notes.length > 0 && <h3>Notes</h3>}
      <div className="grid">
        {notes.map((n) => <NoteCard key={n.id} repo={repo} note={n} trash />)}
      </div>
      {expenses.length > 0 && <h3>Expenses</h3>}
      {expenses.map((e) => (
        <div key={e.id} className="exp">
          <div>
            <div>{e.title || '(untitled)'}</div>
            <div className="muted" style={{ fontSize: 12 }}>
              {new Date(e.occurredAt).toLocaleDateString()} · {PAYMENT_LABELS[e.paymentMethod]}
            </div>
          </div>
          <span className="badge">Deletes in {daysUntilPurge(e.deletedAt!)} days</span>
          <span className="amt">{e.currency}{fmtMoney(e.amount)}</span>
          <IconButton icon="restore" title="Restore" onClick={() => repo.restoreExpense(e.id)} />
          <IconButton icon="trash" title="Delete forever" onClick={() => confirm('Delete forever on all synced devices?') && repo.deleteForever('expense', e.id)} />
        </div>
      ))}
    </div>
  );
}
