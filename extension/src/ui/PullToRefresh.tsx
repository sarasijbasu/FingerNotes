import { createContext, useContext, useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import type { AutoSync } from '../sync/auto';
import { Icon, snack } from './common';

export const AutoSyncContext = createContext<AutoSync | null>(null);

export function useAutoSync() {
  const auto = useContext(AutoSyncContext);
  useSyncExternalStore(
    (cb) => auto?.subscribe(cb) ?? (() => {}),
    () => auto?.state,
  );
  return auto;
}

const THRESHOLD = 70;

/**
 * Pull down (touch, pen or mouse drag) at the top of the page to sync with every online device on the
 * chain. Also exposes a manual trigger through `useSyncNow()`.
 */
export function PullToRefresh({ children, onRefreshed }: { children: ReactNode; onRefreshed?: () => Promise<void> | void }) {
  const auto = useAutoSync();
  const [pull, setPull] = useState(0);
  const [busy, setBusy] = useState(false);
  const start = useRef<number | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  const refresh = useSyncNow(onRefreshed);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const atTop = () => (document.scrollingElement?.scrollTop ?? 0) <= 0;
    const down = (e: PointerEvent) => {
      if (busy || !atTop() || (e.target as HTMLElement).closest('input, textarea, button, a, select, [draggable="true"], .note')) return;
      start.current = e.clientY;
    };
    const move = (e: PointerEvent) => {
      if (start.current == null) return;
      const d = e.clientY - start.current;
      if (d <= 0) return setPull(0);
      setPull(Math.min(120, d * 0.5));
    };
    const up = async () => {
      if (start.current == null) return;
      start.current = null;
      const go = pull >= THRESHOLD * 0.5 + 10;
      setPull(0);
      if (go) {
        setBusy(true);
        await refresh();
        setBusy(false);
      }
    };
    el.addEventListener('pointerdown', down);
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    window.addEventListener('pointercancel', up);
    return () => {
      el.removeEventListener('pointerdown', down);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      window.removeEventListener('pointercancel', up);
    };
  });

  const show = busy || pull > 0;
  const ready = pull >= THRESHOLD * 0.5 + 10;
  return (
    <div ref={ref} style={{ touchAction: 'pan-y' }}>
      <div className="ptr" style={{ height: busy ? 44 : pull, opacity: show ? 1 : 0 }} aria-live="polite">
        <span className={'ptr-icon' + (busy ? ' spin' : '')} style={{ transform: busy ? undefined : `rotate(${pull * 3}deg)` }}>
          <Icon name="sync" />
        </span>
        <span>{busy ? 'Syncing with your devices…' : ready ? 'Release to sync' : 'Pull to sync'}</span>
      </div>
      {auto && auto.state.status !== 'off' && <SyncBadge />}
      {children}
    </div>
  );
}

/** Returns a function that syncs now and reports the result in a snackbar. */
export function useSyncNow(onRefreshed?: () => Promise<void> | void) {
  const auto = useAutoSync();
  return async () => {
    const msg = auto ? await auto.refresh() : 'Sync is not running';
    await onRefreshed?.();
    snack({ text: msg });
  };
}

function SyncBadge() {
  const auto = useAutoSync()!;
  const { status, online, lastSyncAt } = auto.state;
  const peers = Object.values(online).filter((p) => Date.now() - p.seen < 10 * 60_000).length;
  const label: Record<string, string> = {
    'no-chain': 'Sync: no chain',
    'no-relay': 'Sync: offline only (no relay URL)',
    connecting: 'Sync: connecting…',
    online: peers ? `Sync: ${peers} device${peers > 1 ? 's' : ''} online` : 'Sync: waiting for your devices',
    syncing: 'Syncing…',
    standby: 'Sync: running in another window',
  };
  return (
    <div className="sync-badge muted" title={lastSyncAt ? `Last sync ${new Date(lastSyncAt).toLocaleString()}` : undefined}>
      <span className={'dot ' + status} /> {label[status] ?? ''}
      {lastSyncAt ? ` · last ${new Date(lastSyncAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}` : ''}
    </div>
  );
}
