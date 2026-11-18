import { getDb } from './core/db';
import { pushClip } from './core/inbox';
import { purgeExpired } from './core/purge';

const PURGE_ALARM = 'trash-purge';

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({ id: 'save-selection', title: 'Save selection to Notes', contexts: ['selection'] });
  chrome.contextMenus.create({ id: 'open-panel', title: 'Open FingerNotes side panel', contexts: ['action'] });
  chrome.alarms.create(PURGE_ALARM, { periodInMinutes: 24 * 60, delayInMinutes: 1 });
});

chrome.runtime.onStartup.addListener(() => {
  chrome.alarms.get(PURGE_ALARM, (a) => {
    if (!a) chrome.alarms.create(PURGE_ALARM, { periodInMinutes: 24 * 60, delayInMinutes: 1 });
  });
});

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== PURGE_ALARM) return;
  const n = await purgeExpired(getDb());
  if (n) chrome.runtime.sendMessage({ type: 'db-changed' }).catch(() => {});
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId === 'open-panel' && tab?.windowId != null) {
    await chrome.sidePanel.open({ windowId: tab.windowId });
    return;
  }
  if (info.menuItemId !== 'save-selection' || !info.selectionText) return;
  const ok = await pushClip(getDb(), {
    text: info.selectionText,
    url: info.pageUrl ?? tab?.url ?? '',
    title: tab?.title ?? 'Clipped text',
    createdAt: Date.now(),
  });
  chrome.action.setBadgeBackgroundColor({ color: ok ? '#2E7D32' : '#C62828' });
  chrome.action.setBadgeText({ text: ok ? '+1' : '!' });
  setTimeout(() => chrome.action.setBadgeText({ text: '' }), 2500);
  chrome.runtime.sendMessage({ type: 'inbox' }).catch(() => {});
});

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (msg?.type === 'open-side-panel' && sender.tab?.windowId == null) {
    chrome.windows.getCurrent().then(async (w) => { if (w.id != null) await chrome.sidePanel.open({ windowId: w.id }); reply(true); });
    return true;
  }
  return false;
});
