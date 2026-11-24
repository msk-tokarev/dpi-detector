// Author: by Ivan Tokarev

const currentHostEl = document.getElementById('current-host');
const hostBadgeEl = document.getElementById('host-badge');
const btnProbe = document.getElementById('btn-probe-tab');
const resultEl = document.getElementById('tab-probe-result');
const radarCard = document.getElementById('radar-card');
const radarList = document.getElementById('radar-list');

let activeHost = '';
let activeWindowId = null;

async function ensureHostPermission(originPattern) {
  const has = await chrome.permissions.contains({ origins: [originPattern] });
  if (has) return true;
  return chrome.permissions.request({ origins: [originPattern] });
}

function renderProbeItem(item) {
  const aliveGood = item.Alive?.Code === 'OK';
  const tcpDetected = item.Tcp1620?.Code === 'DETECTED';
  const speed = item.BurstTxKbps
    ? `${item.BurstTxKbps >= 1000 ? (item.BurstTxKbps / 1000).toFixed(1) + ' Мбит/с' : Math.round(item.BurstTxKbps) + ' Кбит/с'}`
    : '—';

  hostBadgeEl.className = `badge ${!aliveGood || tcpDetected ? 'bad' : 'good'}`;
  hostBadgeEl.textContent = tcpDetected ? 'TCP 16-20 БЛОК' : aliveGood ? 'Доступен' : 'Недоступен';

  resultEl.innerHTML = `
    <div class="item-box ${!aliveGood || tcpDetected ? 'bad' : ''}">
      <div class="wrap">
        <span class="badge ${aliveGood ? 'good' : 'bad'}">Доступ: ${aliveGood ? 'OK' : item.Alive?.Msg || 'Ошибка'}</span>
        <span class="badge ${tcpDetected ? 'bad' : item.Tcp1620?.Code === 'OK' ? 'good' : 'neutral'}">
          TCP 16-20: ${tcpDetected ? 'ОБНАРУЖЕН' : item.Tcp1620?.Code === 'OK' ? 'нет' : item.Tcp1620?.Msg || 'пропущен'}
        </span>
        <span class="badge neutral">↑ ${speed}</span>
      </div>
    </div>
  `;
}

const RESTRICTED_BROWSER_HOSTS = new Set([
  'chrome.google.com',
  'chromewebstore.google.com',
  'microsoftedge.microsoft.com',
  'browser.yandex.ru',
]);

async function init() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  activeWindowId = tab?.windowId ?? null;

  if (tab?.url && /^https?:\/\//i.test(tab.url)) {
    const u = new URL(tab.url);
    activeHost = u.hostname;
    currentHostEl.textContent = activeHost;
    if (RESTRICTED_BROWSER_HOSTS.has(activeHost.toLowerCase())) {
      hostBadgeEl.className = 'badge warn';
      hostBadgeEl.textContent = 'Защищён браузером';
      btnProbe.disabled = true;
      resultEl.innerHTML = `<div class="muted" style="color:var(--warn)">Браузер запрещает расширениям делать запросы к домену магазина <b>${activeHost}</b> (политика безопасности Chromium). Откройте любой обычный сайт или используйте «Полную панель».</div>`;
    } else {
      hostBadgeEl.textContent = 'Готов';
    }
  } else {
    currentHostEl.textContent = 'Откройте обычный сайт (http/https)';
    btnProbe.disabled = true;
  }

  if (tab?.id != null) {
    const resp = await chrome.runtime.sendMessage({ type: 'GET_TAB_ERRORS', tabId: tab.id });
    if (resp?.errors?.length > 0) {
      radarCard.classList.remove('hidden');
      radarList.textContent = resp.errors.map((e) => `${e.error} · ${e.url}`).join('\n');
    }
  }
}

btnProbe.addEventListener('click', async () => {
  if (!activeHost) return;
  btnProbe.disabled = true;
  btnProbe.textContent = 'Проверяем хост…';
  resultEl.innerHTML = '';

  const granted = await ensureHostPermission(`https://${activeHost}/*`);
  if (!granted) {
    resultEl.innerHTML = `<div class="muted" style="color:var(--warn)">Разрешение на доступ к ${activeHost} отклонено.</div>`;
    btnProbe.disabled = false;
    btnProbe.textContent = 'Проверить этот сайт на DPI (TCP 16-20)';
    return;
  }

  const resp = await chrome.runtime.sendMessage({ type: 'PROBE_HOST', host: activeHost });
  if (resp?.ok) {
    renderProbeItem(resp.item);
  } else {
    resultEl.innerHTML = `<div class="muted" style="color:var(--bad)">Ошибка: ${resp?.error || 'неизвестно'}</div>`;
  }
  btnProbe.disabled = false;
  btnProbe.textContent = 'Проверить этот сайт на DPI (TCP 16-20)';
});

async function openSidePanel() {
  if (chrome.sidePanel && activeWindowId != null) {
    await chrome.sidePanel.open({ windowId: activeWindowId });
    window.close();
  } else {
    chrome.tabs.create({ url: chrome.runtime.getURL('sidepanel.html') });
  }
}

document.getElementById('btn-sidepanel').addEventListener('click', openSidePanel);
document.getElementById('btn-open-full').addEventListener('click', openSidePanel);

init();
