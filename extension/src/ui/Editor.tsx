import { useCallback, useEffect, useRef, useState } from 'react';
import { uuid } from '../core/bytes';
import { NOTE_COLORS, type Attachment, type ChecklistItem, type Note } from '../core/model';
import type { Repo } from '../core/repo';
import { IconButton, Modal, snack } from './common';
import { Markdown } from './Markdown';

type Content = Pick<Note, 'title' | 'body' | 'items' | 'kind'>;

/** Snapshot-based undo/redo; typing within 700 ms coalesces into one step. */
function useHistory(initial: Content) {
  const [state, setState] = useState(initial);
  const past = useRef<Content[]>([]);
  const future = useRef<Content[]>([]);
  const last = useRef(0);
  const set = useCallback((next: Content) => {
    setState((cur) => {
      const now = Date.now();
      if (now - last.current > 700) past.current.push(cur);
      last.current = now;
      future.current = [];
      return next;
    });
  }, []);
  const undo = () =>
    setState((cur) => {
      const p = past.current.pop();
      if (!p) return cur;
      future.current.push(cur);
      last.current = 0;
      return p;
    });
  const redo = () =>
    setState((cur) => {
      const f = future.current.pop();
      if (!f) return cur;
      past.current.push(cur);
      last.current = 0;
      return f;
    });
  return { state, set, undo, redo, canUndo: past.current.length > 0, canRedo: future.current.length > 0 };
}

export function NoteEditor({ repo, note, isNew, onClose }: { repo: Repo; note: Note; isNew: boolean; onClose: () => void }) {
  const h = useHistory({ title: note.title, body: note.body, items: note.items, kind: note.kind });
  const [meta, setMeta] = useState({ color: note.color, labelIds: note.labelIds, attachments: note.attachments, isPinned: note.isPinned });
  const [preview, setPreview] = useState(false);
  const [panel, setPanel] = useState<'none' | 'color' | 'labels'>('none');
  const [recording, setRecording] = useState<MediaRecorder | null>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const s = h.state;

  const close = async () => {
    recording?.stop();
    const merged: Note = { ...note, ...s, ...meta };
    const empty = !s.title.trim() && !s.body.trim() && !s.items.length && !meta.attachments.length;
    if (isNew && empty) {
      for (const a of note.attachments) await repo.removeMedia(a.id);
      onClose();
      return;
    }
    const changed = JSON.stringify(merged) !== JSON.stringify(note);
    if (changed) await repo.saveNote(merged);
    // remove media detached during this edit
    for (const a of note.attachments) if (!meta.attachments.some((x) => x.id === a.id)) await repo.removeMedia(a.id);
    onClose();
  };

  useEffect(() => {
    const k = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey)) return;
      if (e.key.toLowerCase() === 'z' && !e.shiftKey) {
        e.preventDefault();
        h.undo();
      } else if (e.key.toLowerCase() === 'y' || (e.key.toLowerCase() === 'z' && e.shiftKey)) {
        e.preventDefault();
        h.redo();
      } else if (e.key.toLowerCase() === 'b') {
        e.preventDefault();
        wrap('**');
      } else if (e.key.toLowerCase() === 'i') {
        e.preventDefault();
        wrap('*');
      }
    };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  });

  function wrap(marker: string) {
    const ta = bodyRef.current;
    if (!ta) return;
    const { selectionStart: a, selectionEnd: b, value } = ta;
    h.set({ ...s, body: value.slice(0, a) + marker + value.slice(a, b) + marker + value.slice(b) });
    requestAnimationFrame(() => ta.setSelectionRange(a + marker.length, b + marker.length));
  }
  function bullet() {
    const ta = bodyRef.current;
    if (!ta) return;
    const a = ta.value.lastIndexOf('\n', ta.selectionStart - 1) + 1;
    h.set({ ...s, body: ta.value.slice(0, a) + '- ' + ta.value.slice(a) });
  }
  function link() {
    const url = prompt('Link URL (https://…)');
    if (url && /^https?:\/\//.test(url)) h.set({ ...s, body: s.body + (s.body.endsWith(' ') || !s.body ? '' : ' ') + url });
  }

  function toggleKind() {
    if (s.kind === 'text') {
      const items: ChecklistItem[] = s.body.split('\n').filter((l) => l.trim()).map((l, i) => ({ id: uuid(), text: l.replace(/^\s*[-•]\s+/, ''), checked: false, position: i }));
      h.set({ ...s, kind: 'checklist', items, body: '' });
    } else {
      h.set({ ...s, kind: 'text', body: s.items.map((i) => `- ${i.text}`).join('\n'), items: [] });
    }
  }

  async function addFiles(files: FileList | null) {
    for (const f of Array.from(files ?? [])) {
      const att = await repo.addMedia(note.id, f, f.type.startsWith('audio') ? 'audio' : 'image', f.name);
      setMeta((m) => ({ ...m, attachments: [...m.attachments, att] }));
    }
  }

  async function toggleRecord() {
    if (recording) {
      recording.stop();
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const rec = new MediaRecorder(stream);
      const chunks: Blob[] = [];
      rec.ondataavailable = (e) => chunks.push(e.data);
      rec.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        setRecording(null);
        const blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' });
        const att = await repo.addMedia(note.id, blob, 'audio', `Voice memo ${new Date().toLocaleString()}.webm`);
        setMeta((m) => ({ ...m, attachments: [...m.attachments, att] }));
      };
      rec.start();
      setRecording(rec);
    } catch {
      snack({ text: 'Microphone unavailable here — open the full-tab app to grant mic access.' });
    }
  }

  const unchecked = s.items.filter((i) => !i.checked);
  const checked = s.items.filter((i) => i.checked);
  const setItems = (items: ChecklistItem[]) => h.set({ ...s, items: [...items.filter((i) => !i.checked), ...items.filter((i) => i.checked)] });
  const dragIdx = useRef<number | null>(null);

  return (
    <Modal onClose={close}>
      <div className="editor" style={{ background: `var(--n-${meta.color})` }}>
        <div className="row">
          <input className="title grow" type="text" placeholder="Title" value={s.title} onChange={(e) => h.set({ ...s, title: e.target.value })} />
          <IconButton icon="pin" title={meta.isPinned ? 'Unpin' : 'Pin'} on={meta.isPinned} onClick={() => setMeta({ ...meta, isPinned: !meta.isPinned })} />
        </div>

        <Attachments repo={repo} items={meta.attachments} onRemove={(id) => setMeta({ ...meta, attachments: meta.attachments.filter((a) => a.id !== id) })} />

        {s.kind === 'text' ? (
          <>
            <div className="fmt">
              <button className="btn small" title="Bold (Ctrl+B)" onClick={() => wrap('**')}><b>B</b></button>
              <button className="btn small" title="Italic (Ctrl+I)" onClick={() => wrap('*')}><i>I</i></button>
              <button className="btn small" title="Bullet" onClick={bullet}>• List</button>
              <button className="btn small" title="Insert link" onClick={link}>Link</button>
              <span className="grow" />
              <button className="btn small" onClick={() => setPreview(!preview)}>{preview ? 'Edit' : 'Preview'}</button>
            </div>
            {preview ? (
              <div style={{ minHeight: 160 }}>
                <Markdown text={s.body} />
              </div>
            ) : (
              <textarea ref={bodyRef} className="body" placeholder="Take a note…" value={s.body} autoFocus={isNew} onChange={(e) => h.set({ ...s, body: e.target.value })} />
            )}
          </>
        ) : (
          <div>
            {unchecked.map((it, idx) => (
              <div
                key={it.id}
                className="check-row"
                draggable
                onDragStart={() => (dragIdx.current = idx)}
                onDragOver={(e) => e.preventDefault()}
                onDrop={() => {
                  const from = dragIdx.current;
                  if (from == null || from === idx) return;
                  const arr = [...unchecked];
                  const [m] = arr.splice(from, 1);
                  arr.splice(idx, 0, m);
                  setItems([...arr, ...checked]);
                  dragIdx.current = null;
                }}
              >
                <span className="handle" title="Drag to reorder">⋮⋮</span>
                <input type="checkbox" checked={false} onChange={() => setItems(s.items.map((x) => (x.id === it.id ? { ...x, checked: true } : x)))} />
                <input type="text" value={it.text} onChange={(e) => h.set({ ...s, items: s.items.map((x) => (x.id === it.id ? { ...x, text: e.target.value } : x)) })} />
                <IconButton icon="close" title="Remove item" onClick={() => h.set({ ...s, items: s.items.filter((x) => x.id !== it.id) })} />
              </div>
            ))}
            <div className="check-row">
              <span className="handle">+</span>
              <input
                type="text"
                placeholder="List item"
                onKeyDown={(e) => {
                  const v = (e.target as HTMLInputElement).value.trim();
                  if (e.key === 'Enter' && v) {
                    setItems([...unchecked, { id: uuid(), text: v, checked: false, position: 0 }, ...checked]);
                    (e.target as HTMLInputElement).value = '';
                  }
                }}
              />
            </div>
            {checked.length > 0 && <div className="muted" style={{ fontSize: 12, margin: '8px 0 2px' }}>{checked.length} completed</div>}
            {checked.map((it) => (
              <div key={it.id} className="check-row done">
                <span className="handle" />
                <input type="checkbox" checked onChange={() => setItems(s.items.map((x) => (x.id === it.id ? { ...x, checked: false } : x)))} />
                <input type="text" value={it.text} onChange={(e) => h.set({ ...s, items: s.items.map((x) => (x.id === it.id ? { ...x, text: e.target.value } : x)) })} />
                <IconButton icon="close" title="Remove item" onClick={() => h.set({ ...s, items: s.items.filter((x) => x.id !== it.id) })} />
              </div>
            ))}
          </div>
        )}

        {panel === 'color' && (
          <div className="swatches">
            {NOTE_COLORS.map((c) => (
              <button key={c} title={c} className={'swatch' + (meta.color === c ? ' sel' : '')} style={{ background: `var(--n-${c})` }} onClick={() => setMeta({ ...meta, color: c })} />
            ))}
          </div>
        )}
        {panel === 'labels' && <LabelPicker repo={repo} selected={meta.labelIds} onChange={(labelIds) => setMeta({ ...meta, labelIds })} />}

        <div className="footer">
          <IconButton icon="palette" title="Color" on={panel === 'color'} onClick={() => setPanel(panel === 'color' ? 'none' : 'color')} />
          <IconButton icon="label" title="Labels" on={panel === 'labels'} onClick={() => setPanel(panel === 'labels' ? 'none' : 'labels')} />
          <IconButton icon="image" title="Add image" onClick={() => fileRef.current?.click()} />
          <IconButton icon="mic" title={recording ? 'Stop recording' : 'Record voice memo'} on={!!recording} onClick={toggleRecord} />
          <IconButton icon="check" title={s.kind === 'text' ? 'Convert to checklist' : 'Convert to text'} onClick={toggleKind} />
          <IconButton icon="undo" title="Undo (Ctrl+Z)" onClick={h.undo} />
          <IconButton icon="redo" title="Redo (Ctrl+Y)" onClick={h.redo} />
          <span className="grow" />
          {recording && <span className="badge">● REC</span>}
          <button className="btn primary" onClick={close}>Close</button>
          <input ref={fileRef} type="file" accept="image/*,audio/*" multiple hidden onChange={(e) => addFiles(e.target.files)} />
        </div>
      </div>
    </Modal>
  );
}

export function Attachments({ repo, items, onRemove }: { repo: Repo; items: Attachment[]; onRemove?: (id: string) => void }) {
  const [urls, setUrls] = useState<Record<string, string | null>>({});
  useEffect(() => {
    let live = true;
    Promise.all(items.map(async (a) => [a.id, await repo.mediaUrl(a.id)] as const)).then((e) => live && setUrls(Object.fromEntries(e)));
    return () => {
      live = false;
    };
  }, [items, repo, repo.version]);
  if (!items.length) return null;
  return (
    <div className="attach">
      {items.map((a) => (
        <figure key={a.id}>
          {urls[a.id] == null ? (
            <span className="chip">{a.name} (syncing…)</span>
          ) : a.kind === 'image' ? (
            <img src={urls[a.id]!} alt={a.name} />
          ) : (
            <audio controls src={urls[a.id]!} />
          )}
          {onRemove && <IconButton icon="close" title="Remove attachment" onClick={() => onRemove(a.id)} />}
        </figure>
      ))}
    </div>
  );
}

function LabelPicker({ repo, selected, onChange }: { repo: Repo; selected: string[]; onChange: (ids: string[]) => void }) {
  const [name, setName] = useState('');
  return (
    <div style={{ padding: '8px 0' }}>
      <div className="labels">
        {[...repo.labels.values()].map((l) => (
          <label key={l.id} className="chip" style={{ cursor: 'pointer' }}>
            <input type="checkbox" checked={selected.includes(l.id)} onChange={(e) => onChange(e.target.checked ? [...selected, l.id] : selected.filter((x) => x !== l.id))} /> {l.name}
          </label>
        ))}
      </div>
      <div className="row" style={{ marginTop: 6 }}>
        <input type="text" placeholder="New label" value={name} onChange={(e) => setName(e.target.value)} />
        <button
          className="btn small"
          onClick={async () => {
            if (!name.trim()) return;
            const l = await repo.saveLabel(name.trim());
            onChange([...selected, l.id]);
            setName('');
          }}
        >
          Add
        </button>
      </div>
    </div>
  );
}
