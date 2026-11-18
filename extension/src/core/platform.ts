/** Thin wrapper over chrome.storage so core modules also run under Node tests. */
const hasChrome = typeof chrome !== 'undefined' && !!chrome.storage;
const memory = new Map<string, unknown>();

type Area = 'local' | 'session';
export async function storageGet<T>(area: Area, key: string): Promise<T | undefined> {
  if (!hasChrome) return memory.get(area + key) as T | undefined;
  const r = await chrome.storage[area].get(key);
  return r[key] as T | undefined;
}
export async function storageSet(area: Area, key: string, value: unknown): Promise<void> {
  if (!hasChrome) {
    memory.set(area + key, value);
    return;
  }
  await chrome.storage[area].set({ [key]: value });
}
export async function storageRemove(area: Area, key: string): Promise<void> {
  if (!hasChrome) {
    memory.delete(area + key);
    return;
  }
  await chrome.storage[area].remove(key);
}

export interface Settings {
  lockTimeoutMs: number; // 0 = immediately
  blurOnFocusLoss: boolean;
  signalingUrl: string;
  useStun: boolean;
  deviceName: string;
  defaultCurrency: string;
  view: 'grid' | 'list';
}
export const DEFAULT_SETTINGS: Settings = {
  lockTimeoutMs: 60_000,
  blurOnFocusLoss: true,
  signalingUrl: '',
  useStun: true,
  deviceName: 'Chrome',
  defaultCurrency: '₹',
  view: 'grid',
};
export async function getSettings(): Promise<Settings> {
  return { ...DEFAULT_SETTINGS, ...((await storageGet<Partial<Settings>>('local', 'settings')) ?? {}) };
}
export async function saveSettings(patch: Partial<Settings>): Promise<Settings> {
  const s = { ...(await getSettings()), ...patch };
  await storageSet('local', 'settings', s);
  return s;
}

export function broadcast(msg: { type: string }) {
  if (hasChrome && chrome.runtime?.sendMessage) chrome.runtime.sendMessage(msg).catch(() => {});
}
