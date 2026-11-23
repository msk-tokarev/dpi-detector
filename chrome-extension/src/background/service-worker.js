// Author: by Ivan Tokarev
// Chrome Manifest V3 Service Worker: orchestrates DPI checks, passive tab error radar,
// report persistence, and optional Native Messaging bridge to Go `dpich`.

import {
  buildDpichReport,
  checkCidrWhitelist,
  checkDohProvider,
  checkWebhostTarget,
  checkWhoami,
} from './checkers.js';
import {
  CIDR_WHITELIST_SUITE,
  DOH_PROVIDERS,
  DOH_TEST_DOMAINS,
  DPI_NETWORK_ERRORS,
  WEBHOST_SUITE,
} from '../shared/suites.js';

const STORAGE_KEY_STATE = 'dpi_state_v1';
const STORAGE_KEY_REPORTS = 'dpi_reports_v1';
const STORAGE_KEY_SETTINGS = 'dpi_settings_v1';
const NATIVE_HOST_NAME = 'com.dpidetector.dpich';

const tabErrors = new Map(); // tabId -> [{ url, error, ts }]
let activeRunAbort = null;
let nativePort = null;

let runtimeState = {
  running: false,
  progress: '',
  logs: [],
  whoami: null,
  cidr: null,
  doh: null,
  webhost: {},
  lastReportId: null,
  nativeConnected: false,
};

function logLine(msg) {
  const ts = new Date().toTimeString().slice(0, 8);
  const entry = `[${ts}] ${msg}`;
  runtimeState.logs = [...runtimeState.logs.slice(-299), entry];
  broadcastState();
}

async function persistState() {
  try {
    await chrome.storage.local.set({ [STORAGE_KEY_STATE]: runtimeState });
  } catch {
    // storage may be unavailable in tests
  }
}

function broadcastState() {
  persistState();
  try {
    chrome.runtime.sendMessage({ type: 'STATE_UPDATE', state: runtimeState }).catch(() => {});
  } catch {
    // no active UI listener
  }
}

async function getSettings() {
  const res = await chrome.storage.local.get(STORAGE_KEY_SETTINGS);
  return {
    headTimeoutMs: 6000,
    tcp1620TimeoutMs: 12000,
    dohTimeoutMs: 7000,
    customHosts: [],
    ...(res[STORAGE_KEY_SETTINGS] || {}),
  };
}

// ---------------------------------------------------------------------------
// Passive DPI radar (active when optional `webRequest` permission is granted)
// ---------------------------------------------------------------------------

function onWebRequestError(details) {
  if (details.tabId < 0 || !DPI_NETWORK_ERRORS.has(details.error)) return;
  const list = tabErrors.get(details.tabId) || [];
  list.push({ url: details.url, error: details.error, ts: Date.now() });
  tabErrors.set(details.tabId, list.slice(-20));

  try {
    chrome.action.setBadgeText({ tabId: details.tabId, text: 'DPI' });
    chrome.action.setBadgeBackgroundColor({ tabId: details.tabId, color: '#FF5C6C' });
  } catch {
    // ignore if tab closed
  }
}

function attachPassiveRadarIfAllowed() {
  if (!chrome.webRequest?.onErrorOccurred) return;
  try {
    chrome.webRequest.onErrorOccurred.removeListener(onWebRequestError);
    chrome.webRequest.onErrorOccurred.addListener(onWebRequestError, { urls: ['<all_urls>'] });
  } catch {
    // permission not yet granted
  }
}

if (typeof chrome !== 'undefined' && chrome.tabs) {
  chrome.tabs.onRemoved.addListener((tabId) => tabErrors.delete(tabId));
  chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
    if (changeInfo.status === 'loading') {
      tabErrors.delete(tabId);
      chrome.action.setBadgeText({ tabId, text: '' }).catch(() => {});
    }
  });
  chrome.permissions?.onAdded?.addListener(() => attachPassiveRadarIfAllowed());
  attachPassiveRadarIfAllowed();
}

// ---------------------------------------------------------------------------
// Full & selective scan runner
// ---------------------------------------------------------------------------

async function runScan(opts = {}) {
  if (runtimeState.running) return { ok: false, error: 'Проверка уже выполняется' };

  const modules = new Set(opts.modules || ['whoami', 'cidr', 'webhost', 'doh']);
  const settings = await getSettings();

  activeRunAbort = new AbortController();
  const signal = activeRunAbort.signal;

  runtimeState = {
    ...runtimeState,
    running: true,
    progress: 'Запуск диагностики…',
    logs: [],
    whoami: null,
    cidr: null,
    doh: null,
    webhost: {},
    lastReportId: null,
  };
  logLine('Запуск проверки DPI (by Ivan Tokarev)…');

  try {
    const tasks = [];

    if (modules.has('whoami')) {
      tasks.push(
        (async () => {
          logLine('whoami: определение внешнего IP и ASN…');
          const res = await checkWhoami(fetch, 9000);
          if (signal.aborted) return;
          runtimeState.whoami = res;
          logLine(res.Ip ? `whoami: ${res.Ip} (${res.Asn || 'ASN ?'} ${res.Org || ''})` : `whoami: ${res.Status.Msg}`);
          broadcastState();
        })(),
      );
    }

    if (modules.has('cidr')) {
      tasks.push(
        (async () => {
          logLine('cidrwhitelist: проверка режима «белых списков»…');
          const res = await checkCidrWhitelist(fetch, CIDR_WHITELIST_SUITE);
          if (signal.aborted) return;
          runtimeState.cidr = res;
          logLine(`cidrwhitelist: ${res.Status.Code}`);
          broadcastState();
        })(),
      );
    }

    if (modules.has('doh')) {
      tasks.push(
        (async () => {
          logLine(`dns/doh: проверка ${DOH_PROVIDERS.length} DoH-провайдеров…`);
          const providers = {};
          await Promise.all(
            DOH_PROVIDERS.map(async (p) => {
              if (signal.aborted) return;
              const statuses = await checkDohProvider(p, fetch, DOH_TEST_DOMAINS, settings.dohTimeoutMs);
              if (signal.aborted) return;
              providers[p.name] = statuses;
              runtimeState.doh = { Status: { Msg: 'Ok', Code: 'OK', Reason: 'NONE' }, Providers: { ...providers } };
              logLine(`dns/doh: ${p.name} -> ${statuses.map((s) => s.Code).join(', ')}`);
              broadcastState();
            }),
          );
        })(),
      );
    }

    if (modules.has('webhost')) {
      const sections = [...WEBHOST_SUITE];
      if (Array.isArray(settings.customHosts) && settings.customHosts.length > 0) {
        sections.push({
          id: 'custom',
          name: 'Пользовательские хосты',
          targets: settings.customHosts.map((h) => ({ name: h, host: h })),
        });
      }

      tasks.push(
        (async () => {
          for (const sec of sections) {
            if (signal.aborted) break;
            runtimeState.webhost[sec.name] = { Items: [], FarmTimeout: false };
            logLine(`webhost [${sec.name}]: проверка ${sec.targets.length} хостов…`);

            // Concurrency pool of 4 targets to keep browser responsive
            const queue = [...sec.targets];
            const workers = Array.from({ length: 4 }, async () => {
              while (queue.length > 0 && !signal.aborted) {
                const target = queue.shift();
                const item = await checkWebhostTarget(target, fetch, {
                  headTimeoutMs: settings.headTimeoutMs,
                  tcp1620TimeoutMs: settings.tcp1620TimeoutMs,
                });
                if (signal.aborted) return;
                runtimeState.webhost[sec.name].Items.push(item);
                logLine(`webhost: ${item.Group} -> Alive:${item.Alive.Code} TCP16-20:${item.Tcp1620.Code}`);
                broadcastState();
              }
            });
            await Promise.all(workers);
          }
        })(),
      );
    }

    await Promise.all(tasks);

    if (!signal.aborted) {
      const report = buildDpichReport({
        whoami: runtimeState.whoami,
        cidr: runtimeState.cidr,
        dohProviders: runtimeState.doh?.Providers,
        webhostSections: runtimeState.webhost,
      });
      const id = `results_${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}`;
      const stored = await chrome.storage.local.get(STORAGE_KEY_REPORTS);
      const reports = Array.isArray(stored[STORAGE_KEY_REPORTS]) ? stored[STORAGE_KEY_REPORTS] : [];
      reports.unshift({
        id,
        createdAt: Date.now(),
        provider: [runtimeState.whoami?.Org, runtimeState.whoami?.Ip].filter(Boolean).join(' · '),
        report,
      });
      await chrome.storage.local.set({ [STORAGE_KEY_REPORTS]: reports.slice(0, 25) });
      runtimeState.lastReportId = id;
      logLine(`Проверка завершена · отчёт сохранён (${id}.json)`);
    } else {
      logLine('Проверка остановлена пользователем');
    }
  } catch (err) {
    logLine(`Ошибка: ${err?.message || err}`);
  } finally {
    runtimeState.running = false;
    runtimeState.progress = '';
    activeRunAbort = null;
    broadcastState();
  }
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Optional Native Messaging Pro-mode (Go `dpich` companion)
// ---------------------------------------------------------------------------

function connectNativeHost() {
  if (!chrome.runtime?.connectNative) {
    return { ok: false, error: 'Требуется разрешение nativeMessaging' };
  }
  try {
    nativePort = chrome.runtime.connectNative(NATIVE_HOST_NAME);
    runtimeState.nativeConnected = true;
    nativePort.onMessage.addListener((msg) => {
      logLine(`[native dpich] ${msg.msg || msg.type || JSON.stringify(msg)}`);
      chrome.runtime.sendMessage({ type: 'NATIVE_EVENT', payload: msg }).catch(() => {});
    });
    nativePort.onDisconnect.addListener(() => {
      const err = chrome.runtime.lastError?.message || 'disconnected';
      runtimeState.nativeConnected = false;
      nativePort = null;
      logLine(`[native dpich] отключён: ${err}`);
      broadcastState();
    });
    nativePort.postMessage({ cmd: 'ping' });
    broadcastState();
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

// ---------------------------------------------------------------------------
// Message router (Popup / Side Panel / Options)
// ---------------------------------------------------------------------------

if (typeof chrome !== 'undefined' && chrome.runtime?.onMessage) {
  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    (async () => {
      switch (msg?.type) {
        case 'GET_STATE':
          sendResponse({ state: runtimeState });
          break;
        case 'GET_TAB_ERRORS':
          sendResponse({ errors: tabErrors.get(msg.tabId) || [] });
          break;
        case 'START_SCAN':
          sendResponse(await runScan(msg.opts));
          break;
        case 'CANCEL_SCAN':
          activeRunAbort?.abort();
          sendResponse({ ok: true });
          break;
        case 'PROBE_HOST': {
          const settings = await getSettings();
          try {
            const item = await checkWebhostTarget(
              { name: msg.host, host: msg.host },
              fetch,
              { headTimeoutMs: settings.headTimeoutMs, tcp1620TimeoutMs: settings.tcp1620TimeoutMs },
            );
            sendResponse({ ok: true, item });
          } catch (e) {
            sendResponse({ ok: false, error: e?.message || String(e) });
          }
          break;
        }
        case 'GET_REPORTS': {
          const s = await chrome.storage.local.get(STORAGE_KEY_REPORTS);
          sendResponse({ reports: s[STORAGE_KEY_REPORTS] || [] });
          break;
        }
        case 'DELETE_REPORT': {
          const s = await chrome.storage.local.get(STORAGE_KEY_REPORTS);
          const list = (s[STORAGE_KEY_REPORTS] || []).filter((r) => r.id !== msg.id);
          await chrome.storage.local.set({ [STORAGE_KEY_REPORTS]: list });
          sendResponse({ ok: true, reports: list });
          break;
        }
        case 'CONNECT_NATIVE':
          sendResponse(connectNativeHost());
          break;
        case 'NATIVE_CMD':
          if (!nativePort) {
            sendResponse({ ok: false, error: 'Native host не подключён' });
          } else {
            nativePort.postMessage(msg.payload);
            sendResponse({ ok: true });
          }
          break;
        default:
          sendResponse({ ok: false, error: 'Unknown message type' });
      }
    })();
    return true;
  });
}
