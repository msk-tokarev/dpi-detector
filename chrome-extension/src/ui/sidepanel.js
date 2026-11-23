// Author: by Ivan Tokarev

const STORAGE_KEY_SETTINGS = 'dpi_settings_v1';

const selectedModules = new Set(['whoami', 'cidr', 'webhost', 'doh']);
let probeHistory = [];

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
            <div class="mono"><strong>${esc(r.id)}.json</strong></div>
            <div class="muted">${esc(r.provider || 'Локальная проверка')}</div>
          </div>
          <div class="wrap">
            <button class="btn btn-outline btn-sm btn-dl" data-id="${esc(r.id)}">JSON</button>
            <button class="btn btn-outline btn-sm btn-del" data-id="${esc(r.id)}">✕</button>
          </div>
        </div>
      </div>
    `)
    .join('');

  el.querySelectorAll('.btn-dl').forEach((btn) => {
    btn.addEventListener('click', () => {
      const rec = list.find((x) => x.id === btn.dataset.id);
      if (!rec) return;
      const blob = new Blob([JSON.stringify(rec.report, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${rec.id}.json`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
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
});

(async () => {
  const resp = await chrome.runtime.sendMessage({ type: 'GET_STATE' });
  renderState(resp?.state);
  const s = await chrome.storage.local.get(STORAGE_KEY_SETTINGS);
  const hosts = s[STORAGE_KEY_SETTINGS]?.customHosts || [];
  document.getElementById('custom-hosts').value = hosts.join('\n');
})();
