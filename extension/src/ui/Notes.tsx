import { useEffect, useRef, useState } from 'react';
import { daysUntilPurge, type Note } from '../core/model';
import type { Repo } from '../core/repo';
import { Icon, IconButton, snack } from './common';
import { Attachments, NoteEditor } from './Editor';
import { Markdown } from './Markdown';
import { useSyncNow } from './PullToRefresh';

export type NotesFilter = 'main' | 'archive' | 'hidden';

function matches(n: Note, q: string, repo: Repo) {
  if (!q) return true;
  const hay = [n.title, n.body, ...n.items.map((i) => i.text), ...n.labelIds.map((id) => repo.labels.get(id)?.name ?? '')].join('\n').toLowerCase();
  return q.toLowerCase().split(/\s+/).every((w) => hay.includes(w));
}

export function NotesView({ repo, filter, view, onView }: { repo: Repo; filter: NotesFilter; view: 'grid' | 'list'; onView: (v: 'grid' | 'list') => void }) {
  const [q, setQ] = useState('');
  const [label, setLabel] = useState<string>('');
  const [editing, setEditing] = useState<{ note: Note; isNew: boolean } | null>(null);
  const [syncing, setSyncing] = useState(false);
  const syncNow = useSyncNow(() => repo.reload());

  const all = [...repo.notes.values()].filter((n) => !n.isTrashed);
  const visible = all
    .filter((n) => (filter === 'hidden' ? n.isHidden : !n.isHidden && (filter === 'archive' ? n.isArchived : !n.isArchived)))
    .filter((n) => matches(n, q, repo) && (!label || n.labelIds.includes(label)))
    .sort((a, b) => b.updatedAt - a.updatedAt);
  const pinned = visible.filter((n) => n.isPinned);
  const others = visible.filter((n) => !n.isPinned);

  const create = (kind: Note['kind']) => {
    const n = repo.newNote(kind);
    if (filter === 'hidden') n.isHidden = true;
    if (label) n.labelIds = [label];
    setEditing({ note: n, isNew: true });
  };

  return (
    <div>
      <div className="toolbar">
        <input type="search" placeholder={filter === 'hidden' ? 'Search hidden notes' : 'Search notes'} value={q} onChange={(e) => setQ(e.target.value)} />
        {repo.labels.size > 0 && (
          <select value={label} onChange={(e) => setLabel(e.target.value)} style={{ width: 'auto' }}>
            <option value="">All labels</option>
            {[...repo.labels.values()].map((l) => (
              <option key={l.id} value={l.id}>{l.name}</option>
            ))}
          </select>
        )}
        <IconButton
          icon="sync"
          title={syncing ? 'Syncing…' : 'Sync now (or pull down)'}
          on={syncing}
          onClick={async () => {
            if (syncing) return;
            setSyncing(true);
            await syncNow();
            setSyncing(false);
          }}
        />
        <IconButton icon={view === 'grid' ? 'list' : 'grid'} title={view === 'grid' ? 'List view' : 'Grid view'} onClick={() => onView(view === 'grid' ? 'list' : 'grid')} />
      </div>

      {filter !== 'archive' && (
        <div className="card-panel composer row" onClick={() => create('text')}>
          <span className="grow">Take a note…</span>
          <IconButton icon="check" title="New checklist" onClick={() => create('checklist')} />
          <IconButton icon="image" title="New note with image" onClick={() => create('text')} />
        </div>
      )}

      {visible.length === 0 && <div className="empty">{q ? 'No matching notes' : filter === 'archive' ? 'Archived notes appear here' : filter === 'hidden' ? 'Hidden notes appear here' : 'Notes you add appear here'}</div>}

      <div className={'grid' + (view === 'list' ? ' list' : '')}>
        {pinned.length > 0 && <div className="section-title">Pinned</div>}
        {pinned.map((n) => <NoteCard key={n.id} repo={repo} note={n} onOpen={() => setEditing({ note: n, isNew: false })} />)}
        {pinned.length > 0 && others.length > 0 && <div className="section-title">Others</div>}
        {others.map((n) => <NoteCard key={n.id} repo={repo} note={n} onOpen={() => setEditing({ note: n, isNew: false })} />)}
      </div>

      {editing && <NoteEditor repo={repo} note={editing.note} isNew={editing.isNew} onClose={() => setEditing(null)} />}
    </div>
  );
}

export function NoteCard({ repo, note, onOpen, trash }: { repo: Repo; note: Note; onOpen?: () => void; trash?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; dx: number } | null>(null);

  const archive = async () => {
    await repo.updateNote(note.id, { isArchived: !note.isArchived, isPinned: false });
    snack({ text: note.isArchived ? 'Note unarchived' : 'Note archived', action: { label: 'Undo', run: () => repo.updateNote(note.id, { isArchived: note.isArchived, isPinned: note.isPinned }) } });
  };
  const toTrash = async () => {
    await repo.trashNote(note.id);
    snack({ text: 'Note moved to Trash', action: { label: 'Undo', run: () => repo.updateNote(note.id, { isTrashed: false, deletedAt: null, isPinned: note.isPinned }) } });
  };
  const hide = async () => {
    await repo.updateNote(note.id, { isHidden: !note.isHidden, isPinned: false });
    snack({ text: note.isHidden ? 'Note moved out of the vault' : 'Note hidden in the vault', action: { label: 'Undo', run: () => repo.updateNote(note.id, { isHidden: note.isHidden }) } });
  };

  // Swipe right → archive, swipe left → trash (touch & mouse).
  useEffect(() => {
    const el = ref.current;
    if (!el || trash) return;
    const down = (e: PointerEvent) => {
      if ((e.target as HTMLElement).closest('button, a, audio')) return;
      drag.current = { x: e.clientX, dx: 0 };
    };
    const move = (e: PointerEvent) => {
      if (!drag.current) return;
      drag.current.dx = e.clientX - drag.current.x;
      if (Math.abs(drag.current.dx) > 10) {
        el.style.transform = `translateX(${drag.current.dx}px)`;
        el.style.opacity = String(1 - Math.min(Math.abs(drag.current.dx) / 300, 0.6));
      }
    };
    const up = () => {
      const d = drag.current;
      drag.current = null;
      el.style.transform = '';
      el.style.opacity = '';
      if (!d) return;
      if (d.dx > 120) void archive();
      else if (d.dx < -120) void toTrash();
      else if (Math.abs(d.dx) < 6) onOpen?.();
    };
    el.addEventListener('pointerdown', down);
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    return () => {
      el.removeEventListener('pointerdown', down);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
  });

  const images = note.attachments.filter((a) => a.kind === 'image').slice(0, 1);
  const audio = note.attachments.filter((a) => a.kind === 'audio').length;
  const unchecked = note.items.filter((i) => !i.checked);
  const checked = note.items.filter((i) => i.checked);

  return (
    <div ref={ref} className="note" style={{ background: `var(--n-${note.color})` }} tabIndex={0} onKeyDown={(e) => e.key === 'Enter' && onOpen?.()}>
      {!trash && (
        <span className="pin">
          <IconButton icon="pin" title={note.isPinned ? 'Unpin' : 'Pin'} on={note.isPinned} onClick={() => repo.updateNote(note.id, { isPinned: !note.isPinned })} />
        </span>
      )}
      {images.length > 0 && <Attachments repo={repo} items={images} />}
      {note.title && <div className="title" style={{ paddingRight: 28 }}>{note.title}</div>}
      <div className="body">
        {note.kind === 'text' ? (
          <Markdown text={note.body.length > 800 ? note.body.slice(0, 800) + '…' : note.body} />
        ) : (
          <ul className="check">
            {unchecked.slice(0, 8).map((i) => <li key={i.id}>☐ {i.text}</li>)}
            {checked.length > 0 && <li className="muted">+ {checked.length} checked item{checked.length > 1 ? 's' : ''}</li>}
          </ul>
        )}
      </div>
      <div className="labels">
        {note.labelIds.map((id) => repo.labels.get(id) && <span key={id} className="chip">{repo.labels.get(id)!.name}</span>)}
        {audio > 0 && <span className="chip"><Icon name="mic" /> {audio}</span>}
        {trash && note.deletedAt && <span className="badge">Deletes in {daysUntilPurge(note.deletedAt)} days</span>}
      </div>
      <div className="actions">
        {trash ? (
          <>
            <IconButton icon="restore" title="Restore" onClick={() => repo.restoreNote(note.id)} />
            <IconButton icon="trash" title="Delete forever" onClick={() => confirm('Delete this note forever on all synced devices?') && repo.deleteForever('note', note.id)} />
          </>
        ) : (
          <>
            <IconButton icon="archive" title={note.isArchived ? 'Unarchive' : 'Archive'} onClick={archive} />
            <IconButton icon="hide" title={note.isHidden ? 'Unhide' : 'Hide note'} onClick={hide} />
            <IconButton icon="trash" title="Move to Trash" onClick={toTrash} />
          </>
        )}
      </div>
    </div>
  );
}
