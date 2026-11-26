// Author: by Ivan Tokarev

import {
  batchToCsv,
  batchToTxt,
  describeCidr,
  reportToCsv,
  reportToTxt,
} from '../shared/batch-export.js';

const STORAGE_KEY_SETTINGS = 'dpi_settings_v1';

const selectedModules = new Set(['whoami', 'cidr', 'webhost', 'doh']);
let probeHistory = [];
let currentBatch = { status: 'IDLE', items: [], etaSeconds: null, recovered: false };

function downloadTextFile(filename, content, mimeType) {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// Tabs switching
document.querySelectorAll('.tab').forEach((btn) => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((b) => b.classList.remove('active'));
    btn.classList.add('active');
    const target = btn.dataset.tab;
    ['scanner', 'host', 'reports', 'settings'].forEach((id) => {
      document.getElementById(`tab-${id}`).classList.toggle('hidden', id !== target);
    });
    if (target === 'reports') loadReports();
  });
});

// Module chips
document.querySelectorAll('#module-chips .chip').forEach((chip) => {
  chip.addEventListener('click', () => {
    const mod = chip.dataset.mod;
    if (selectedModules.has(mod) && selectedModules.size > 1) {
      selectedModules.delete(mod);
      chip.classList.remove('selected');
    } else {
      selectedModules.add(mod);
      chip.classList.add('selected');
    }
  });
});

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function fmtSpeed(kbps) {
  if (!kbps || Number.isNaN(kbps)) return '—';
  return kbps >= 1000 ? `${(kbps / 1000).toFixed(1)} Мбит/с` : `${Math.round(kbps)} Кбит/с`;
}

function renderState(st) {
  if (!st) return;

  const btnScan = document.getElementById('btn-scan');
  const btnCancel = document.getElementById('btn-cancel');
  btnScan.classList.toggle('hidden', st.running);
  btnCancel.classList.toggle('hidden', !st.running);

  const nativeBadge = document.getElementById('native-badge');
  nativeBadge.textContent = st.nativeConnected ? 'Pro Native dpich' : 'Browser MV3';
  nativeBadge.className = `badge ${st.nativeConnected ? 'good' : 'neutral'}`;

  // Logs
  const logCard = document.getElementById('log-card');
  if (st.logs?.length > 0) {
    logCard.classList.remove('hidden');
    document.getElementById('log-count').textContent = `${st.logs.length}`;
    const con = document.getElementById('log-console');
    con.textContent = [...st.logs].reverse().join('\n');
  } else {
    logCard.classList.add('hidden');
  }

  // Verdict
  const allItems = Object.values(st.webhost || {}).flatMap((s) => s.Items || []);
  const tcp1620 = allItems.filter((i) => i.Tcp1620?.Code === 'DETECTED').length;
  const down = allItems.filter((i) => i.Alive?.Code !== 'OK').length;
  const cidrDetected = st.cidr?.Status?.Code === 'DETECTED';
  const dohBad = Object.values(st.doh?.Providers || {}).filter((list) =>
    list.some((s) => s.Code !== 'OK'),
  ).length;

  const verdictEl = document.getElementById('verdict-card');
  if (st.whoami || st.cidr || allItems.length > 0 || st.doh) {
    verdictEl.classList.remove('hidden');
    let title = 'Явных ограничений не обнаружено';
    let cls = 'good';
    if (cidrDetected) { title = 'Обнаружен режим «белых списков» (CIDR)!'; cls = 'bad'; }
    else if (tcp1620 > 0 || dohBad > 0) { title = 'Обнаружены признаки DPI-фильтрации'; cls = 'bad'; }
    else if (down > 0) { title = `Есть недоступные сервисы (${down})`; cls = 'warn'; }
    else if (st.running) { title = 'Идёт сканирование…'; cls = 'info'; }

    verdictEl.innerHTML = `
      <div class="card-title" style="color:var(--${cls === 'info' ? 'accent' : cls})">${title}</div>
      <div class="wrap" style="margin-top:8px;">
        <span class="badge info">Хостов: ${allItems.length}</span>
        <span class="badge ${tcp1620 > 0 ? 'bad' : 'good'}">TCP 16-20: ${tcp1620}</span>
        <span class="badge ${down > 0 ? 'warn' : 'good'}">Недоступно: ${down}</span>
        <span class="badge ${dohBad > 0 ? 'bad' : 'good'}">DoH пробл.: ${dohBad}</span>
      </div>
    `;
  } else {
    verdictEl.classList.add('hidden');
  }

  // Whoami
  const whoamiEl = document.getElementById('whoami-card');
  if (st.whoami) {
    whoamiEl.classList.remove('hidden');
    const w = st.whoami;
    const ok = w.Status?.Code === 'OK';
    whoamiEl.innerHTML = `
      <div class="row">
        <div class="card-title" style="margin:0;">Подключение (Whoami)</div>
        <span class="badge ${ok ? 'good' : 'warn'}">${ok ? `${w.TtlbMs} мс` : esc(w.Status?.Msg)}</span>
      </div>
      <div class="mono muted" style="margin-top:6px;">
        IP: <span style="color:var(--text)">${esc(w.Ip || '—')}</span><br/>
        ASN / Org: <span style="color:var(--text)">${esc([w.Asn, w.Org].filter(Boolean).join(' · ') || '—')}</span><br/>
        Подсеть: <span style="color:var(--text)">${esc(w.Subnet || '—')}</span>
      </div>
    `;
  } else {
    whoamiEl.classList.add('hidden');
  }

  // CIDR
  const cidrEl = document.getElementById('cidr-card');
  if (st.cidr?.Status) {
    cidrEl.classList.remove('hidden');
    const code = st.cidr.Status.Code;
    const label = code === 'DETECTED' ? 'Активен CIDR WhiteList!' : code === 'NOT_DETECTED' ? 'Белые списки не активны' : 'Нет интернета';
    const cls = code === 'DETECTED' ? 'bad' : code === 'NOT_DETECTED' ? 'good' : 'warn';
    cidrEl.innerHTML = `
      <div class="row">
        <div class="card-title" style="margin:0;">CIDR WhiteList</div>
        <span class="badge ${cls}">${label}</span>
      </div>
    `;
  } else {
    cidrEl.classList.add('hidden');
  }

  // Webhost
  const whEl = document.getElementById('webhost-card');
  const secEntries = Object.entries(st.webhost || {});
  if (secEntries.length > 0) {
    whEl.classList.remove('hidden');
    whEl.innerHTML = secEntries
      .map(([secName, sec]) => {
        const rows = (sec.Items || [])
          .map((item) => {
            const aliveOk = item.Alive?.Code === 'OK';
            const tcpBad = item.Tcp1620?.Code === 'DETECTED';
            return `
              <div class="item-box ${!aliveOk || tcpBad ? 'bad' : ''}">
                <div class="row">
                  <strong>${esc(item.Group)}</strong>
                  <span class="mono muted">${esc(item.IP)}</span>
                </div>
                <div class="wrap">
                  <span class="badge ${aliveOk ? 'good' : 'bad'}">${aliveOk ? 'Доступен' : esc(item.Alive?.Msg)}</span>
                  <span class="badge ${tcpBad ? 'bad' : item.Tcp1620?.Code === 'OK' ? 'good' : 'neutral'}">
                    TCP 16-20: ${tcpBad ? 'БЛОК' : item.Tcp1620?.Code === 'OK' ? 'нет' : 'пропущен'}
                  </span>
                  ${item.BurstTxKbps ? `<span class="badge neutral">↑ ${fmtSpeed(item.BurstTxKbps)}</span>` : ''}
                </div>
              </div>
            `;
          })
          .join('');
        return `<div class="card-title" style="margin-top:4px;">${esc(secName)} (${sec.Items?.length || 0})</div><div class="container" style="padding:6px 0 0;">${rows}</div>`;
      })
      .join('');
  } else {
    whEl.classList.add('hidden');
  }

  // DoH
  const dohEl = document.getElementById('doh-card');
  const dohEntries = Object.entries(st.doh?.Providers || {});
  if (dohEntries.length > 0) {
    dohEl.classList.remove('hidden');
    const rows = dohEntries
      .map(([prov, sts]) => {
        const badges = sts
          .map((s) => `<span class="badge ${s.Code === 'OK' ? 'good' : s.Code === 'NXDOMAIN_SPOOFING' ? 'bad' : 'warn'}">${esc(s.Msg || s.Code)}</span>`)
          .join('');
        return `<div class="item-box"><div class="row"><strong>${esc(prov)}</strong><div class="wrap">${badges}</div></div></div>`;
      })
      .join('');
    dohEl.innerHTML = `<div class="card-title">DNS over HTTPS (DoH)</div><div class="container" style="padding:6px 0 0;">${rows}</div>`;
  } else {
    dohEl.classList.add('hidden');
  }
}

document.getElementById('btn-scan').addEventListener('click', async () => {
  const granted = await chrome.permissions.request({ origins: ['https://*/*', 'http://*/*'] });
  if (!granted) {
    alert('Для выполнения сетевых тестов браузеру требуется разрешение на доступ к тестируемым сайтам.');
    return;
  }
  await chrome.runtime.sendMessage({ type: 'START_SCAN', opts: { modules: [...selectedModules] } });
});

document.getElementById('btn-cancel').addEventListener('click', async () => {
  await chrome.runtime.sendMessage({ type: 'CANCEL_SCAN' });
});

// Host Probe tab
const probeInput = document.getElementById('probe-input');
document.querySelectorAll('.preset-chip').forEach((c) => {
  c.addEventListener('click', () => { probeInput.value = c.dataset.host; });
});

document.getElementById('btn-probe-host').addEventListener('click', async () => {
  const host = probeInput.value.trim();
  if (!host) return;
  const btn = document.getElementById('btn-probe-host');
  btn.disabled = true;
  btn.textContent = 'Проверяем…';
  await chrome.permissions.request({ origins: ['https://*/*', 'http://*/*'] });
  const resp = await chrome.runtime.sendMessage({ type: 'PROBE_HOST', host });
  probeHistory.unshift({ host, resp, ts: new Date().toTimeString().slice(0, 8) });
  probeHistory = probeHistory.slice(0, 15);
  renderProbeHistory();
  btn.disabled = false;
  btn.textContent = 'Проверить хост';
});

function renderProbeHistory() {
  const el = document.getElementById('probe-history');
  el.innerHTML = probeHistory
    .map(({ host, resp, ts }) => {
      if (!resp?.ok) {
        return `<div class="card"><div class="row"><strong class="mono">${esc(host)}</strong><span class="muted">${ts}</span></div><div style="color:var(--bad);margin-top:4px;">${esc(resp?.error || 'Ошибка')}</div></div>`;
      }
      const i = resp.item;
      const aliveOk = i.Alive?.Code === 'OK';
      const tcpBad = i.Tcp1620?.Code === 'DETECTED';
      return `
        <div class="card">
          <div class="row"><strong class="mono">${esc(i.Group)}</strong><span class="muted">${ts}</span></div>
          <div class="wrap" style="margin-top:8px;">
            <span class="badge ${aliveOk ? 'good' : 'bad'}">${aliveOk ? 'Доступен' : esc(i.Alive?.Msg)}</span>
            <span class="badge ${tcpBad ? 'bad' : i.Tcp1620?.Code === 'OK' ? 'good' : 'neutral'}">TCP 16-20: ${tcpBad ? 'БЛОК' : i.Tcp1620?.Code === 'OK' ? 'нет' : 'пропущен'}</span>
            ${i.BurstTxKbps ? `<span class="badge neutral">↑ ${fmtSpeed(i.BurstTxKbps)}</span>` : ''}
          </div>
        </div>
      `;
    })
    .join('');
}

// Batch / CIDR Queue UI
const batchInputEl = document.getElementById('batch-input');
const cidrHintEl = document.getElementById('cidr-calc-hint');
const batchErrEl = document.getElementById('batch-error');

batchInputEl.addEventListener('input', () => {
  batchErrEl.textContent = '';
  const tok = batchInputEl.value
    .split(/[\r\n,;\s]+/)
    .map((s) => s.trim())
    .find((s) => s.includes('/') && !/^https?:\/\//i.test(s));
  const info = tok ? describeCidr(tok) : null;
  cidrHintEl.textContent = info
    ? `CIDR ${info.input}: ${info.network} – ${info.lastAddress} · всего ${info.totalCount} (рабочих: ${info.usableCount})`
    : '';
});

document.getElementById('btn-start-batch').addEventListener('click', async () => {
  batchErrEl.textContent = '';
  const rawText = batchInputEl.value.trim();
  if (!rawText) return;
  const granted = await chrome.permissions.request({ origins: ['https://*/*', 'http://*/*'] });
  if (!granted) {
    batchErrEl.textContent = 'Требуется разрешение на доступ к проверяемым хостам.';
    return;
  }
  const resp = await chrome.runtime.sendMessage({ type: 'START_BATCH', rawText, usableOnly: true });
  if (!resp?.ok) {
    batchErrEl.textContent = resp?.error || 'Не удалось запустить очередь';
  } else if (resp.batch) {
    renderBatch(resp.batch);
  }
});

function renderBatch(b) {
  currentBatch = b || { status: 'IDLE', items: [] };
  const card = document.getElementById('batch-status-card');
  if (!b || !Array.isArray(b.items) || b.items.length === 0) {
    card.classList.add('hidden');
    card.innerHTML = '';
    return;
  }
  card.classList.remove('hidden');
  const total = b.items.length;
  const done = b.items.filter((q) => q.state === 'DONE' || q.state === 'FAILED').length;
  const failed = b.items.filter((q) => q.state === 'FAILED').length;
  const issues = b.items.filter(
    (q) => q.state === 'FAILED' || (q.item && (q.item.Alive?.Code !== 'OK' || q.item.Tcp1620?.Code === 'DETECTED')),
  ).length;
  const stText =
    b.status === 'RUNNING'
      ? `Выполняется${b.etaSeconds ? ` (~${b.etaSeconds}с)` : ''}`
      : b.status === 'PAUSED'
        ? 'На паузе'
        : 'Завершено';

  const rows = b.items
    .slice(0, 100)
    .map((q) => {
      if (q.item) {
        const aliveOk = q.item.Alive?.Code === 'OK';
        const tcpBad = q.item.Tcp1620?.Code === 'DETECTED';
        return `
          <div class="item-box ${!aliveOk || tcpBad ? 'bad' : ''}">
            <div class="row"><strong class="mono">${esc(q.target)}</strong><span class="badge ${aliveOk ? 'good' : 'bad'}">${aliveOk ? 'OK' : esc(q.item.Alive?.Msg)}</span></div>
            <div class="wrap">
              <span class="badge ${tcpBad ? 'bad' : q.item.Tcp1620?.Code === 'OK' ? 'good' : 'neutral'}">TCP 16-20: ${tcpBad ? 'БЛОК' : q.item.Tcp1620?.Code === 'OK' ? 'нет' : 'пропущен'}</span>
              ${q.item.BurstTxKbps ? `<span class="badge neutral">↑ ${fmtSpeed(q.item.BurstTxKbps)}</span>` : ''}
            </div>
          </div>
        `;
      }
      return `<div class="item-box"><div class="row"><span class="mono">${esc(q.target)}</span><span class="badge ${q.state === 'FAILED' ? 'bad' : 'neutral'}">${esc(q.error || q.state)}</span></div></div>`;
    })
    .join('');

  card.innerHTML = `
    ${b.recovered ? `<div class="muted" style="color:var(--warn);margin-bottom:6px;">Очередь восстановлена после перезапуска — нажмите «Продолжить».</div>` : ''}
    <div class="row">
      <div class="card-title" style="margin:0;">Очередь: ${done} / ${total}</div>
      <span class="badge ${b.status === 'COMPLETED' ? 'good' : 'info'}">${stText}</span>
    </div>
    <div class="wrap" style="margin-top:6px;">
      <span class="badge ${issues > 0 ? 'bad' : 'good'}">Проблем: ${issues}</span>
      <span class="badge ${failed > 0 ? 'warn' : 'neutral'}">Ошибок: ${failed}</span>
    </div>
    <div class="wrap" style="margin-top:8px;">
      ${b.status === 'RUNNING' ? `<button id="btn-batch-pause" class="btn btn-outline btn-sm">Пауза</button>` : ''}
      ${b.status === 'PAUSED' ? `<button id="btn-batch-resume" class="btn btn-primary btn-sm">Продолжить</button>` : ''}
      ${b.status !== 'RUNNING' && failed > 0 ? `<button id="btn-batch-retry" class="btn btn-outline btn-sm">Повторить (${failed})</button>` : ''}
      <button id="btn-batch-stop" class="btn btn-outline btn-sm">Сбросить</button>
    </div>
    <div class="wrap" style="margin-top:8px;">
      <button id="btn-batch-csv" class="btn btn-outline btn-sm">CSV</button>
      <button id="btn-batch-txt" class="btn btn-outline btn-sm">TXT</button>
      <button id="btn-batch-json" class="btn btn-outline btn-sm">JSON</button>
    </div>
    <div class="container" style="padding:8px 0 0;">${rows}</div>
  `;

  document.getElementById('btn-batch-pause')?.addEventListener('click', () => chrome.runtime.sendMessage({ type: 'PAUSE_BATCH' }));
  document.getElementById('btn-batch-resume')?.addEventListener('click', () => chrome.runtime.sendMessage({ type: 'RESUME_BATCH' }));
  document.getElementById('btn-batch-retry')?.addEventListener('click', () => chrome.runtime.sendMessage({ type: 'RETRY_FAILED_BATCH' }));
  document.getElementById('btn-batch-stop')?.addEventListener('click', () => chrome.runtime.sendMessage({ type: 'STOP_BATCH' }));
  document.getElementById('btn-batch-csv')?.addEventListener('click', () =>
    downloadTextFile('dpi-batch.csv', batchToCsv(currentBatch.items), 'text/csv;charset=utf-8'),
  );
  document.getElementById('btn-batch-txt')?.addEventListener('click', () =>
    downloadTextFile('dpi-batch.txt', batchToTxt(currentBatch.items), 'text/plain;charset=utf-8'),
  );
  document.getElementById('btn-batch-json')?.addEventListener('click', () =>
    downloadTextFile('dpi-batch.json', JSON.stringify(currentBatch, null, 2), 'application/json'),
  );
}

// Reports tab
async function loadReports() {
  const resp = await chrome.runtime.sendMessage({ type: 'GET_REPORTS' });
  const list = resp?.reports || [];
  const el = document.getElementById('reports-list');
  if (list.length === 0) {
    el.innerHTML = `<div class="card muted">Отчётов пока нет. Запустите сканирование на вкладке «Сканер».</div>`;
    return;
  }
  el.innerHTML = list
    .map((r) => `
      <div class="card">
        <div class="row">
          <div>
            <div class="mono"><strong>${esc(r.id)}</strong></div>
            <div class="muted">${esc(r.provider || 'Локальная проверка')}</div>
          </div>
          <div class="wrap">
            <button class="btn btn-outline btn-sm btn-dl-json" data-id="${esc(r.id)}">JSON</button>
            <button class="btn btn-outline btn-sm btn-dl-csv" data-id="${esc(r.id)}">CSV</button>
            <button class="btn btn-outline btn-sm btn-dl-txt" data-id="${esc(r.id)}">TXT</button>
            <button class="btn btn-outline btn-sm btn-del" data-id="${esc(r.id)}">✕</button>
          </div>
        </div>
      </div>
    `)
    .join('');

  el.querySelectorAll('.btn-dl-json').forEach((btn) => {
    btn.addEventListener('click', () => {
      const rec = list.find((x) => x.id === btn.dataset.id);
      if (rec) downloadTextFile(`${rec.id}.json`, JSON.stringify(rec.report, null, 2), 'application/json');
    });
  });
  el.querySelectorAll('.btn-dl-csv').forEach((btn) => {
    btn.addEventListener('click', () => {
      const rec = list.find((x) => x.id === btn.dataset.id);
      if (rec) downloadTextFile(`${rec.id}.csv`, reportToCsv(rec.report), 'text/csv;charset=utf-8');
    });
  });
  el.querySelectorAll('.btn-dl-txt').forEach((btn) => {
    btn.addEventListener('click', () => {
      const rec = list.find((x) => x.id === btn.dataset.id);
      if (rec) downloadTextFile(`${rec.id}.txt`, reportToTxt(rec.report, rec.id), 'text/plain;charset=utf-8');
    });
  });
  el.querySelectorAll('.btn-del').forEach((btn) => {
    btn.addEventListener('click', async () => {
      await chrome.runtime.sendMessage({ type: 'DELETE_REPORT', id: btn.dataset.id });
      loadReports();
    });
  });
}

document.getElementById('btn-refresh-reports').addEventListener('click', loadReports);

// Settings tab
const settingsMsg = document.getElementById('settings-msg');
document.getElementById('btn-enable-radar').addEventListener('click', async () => {
  const ok = await chrome.permissions.request({ permissions: ['webRequest'], origins: ['<all_urls>'] });
  settingsMsg.textContent = ok ? 'Пассивный радар включён!' : 'Разрешение webRequest не выдано.';
});

document.getElementById('btn-connect-native').addEventListener('click', async () => {
  const granted = await chrome.permissions.request({ permissions: ['nativeMessaging'] });
  if (!granted) {
    settingsMsg.textContent = 'Разрешение nativeMessaging не выдано.';
    return;
  }
  const resp = await chrome.runtime.sendMessage({ type: 'CONNECT_NATIVE' });
  settingsMsg.textContent = resp?.ok ? 'Подключено к локальному dpich!' : `Ошибка: ${resp?.error || 'не установлен native-host'}`;
});

document.getElementById('btn-save-settings').addEventListener('click', async () => {
  const hosts = document.getElementById('custom-hosts').value
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean);
  await chrome.storage.local.set({ [STORAGE_KEY_SETTINGS]: { customHosts: hosts } });
  settingsMsg.textContent = `Сохранено пользовательских хостов: ${hosts.length}`;
});

// Live state listener
chrome.runtime.onMessage.addListener((msg) => {
  if (msg?.type === 'STATE_UPDATE') renderState(msg.state);
  if (msg?.type === 'BATCH_UPDATE') renderBatch(msg.batch);
});

(async () => {
  const resp = await chrome.runtime.sendMessage({ type: 'GET_STATE' });
  renderState(resp?.state);
  const bResp = await chrome.runtime.sendMessage({ type: 'GET_BATCH' });
  if (bResp?.batch) renderBatch(bResp.batch);
  const s = await chrome.storage.local.get(STORAGE_KEY_SETTINGS);
  const hosts = s[STORAGE_KEY_SETTINGS]?.customHosts || [];
  document.getElementById('custom-hosts').value = hosts.join('\n');
})();
