// Author: by Ivan Tokarev
// Subnet planner (IPv4/IPv6 CIDR & ranges) and CSV/TXT report & batch exporters.

export function parseIpv4Int(raw) {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(String(raw || '').trim());
  if (!m) return null;
  const octs = m.slice(1).map(Number);
  if (octs.some((n) => n < 0 || n > 255)) return null;
  return (((octs[0] << 24) >>> 0) + (octs[1] << 16) + (octs[2] << 8) + octs[3]) >>> 0;
}

export function ipv4FromInt(num) {
  const u = num >>> 0;
  return `${(u >>> 24) & 255}.${(u >>> 16) & 255}.${(u >>> 8) & 255}.${u & 255}`;
}

export function parseIpv6BigInt(raw) {
  const s = String(raw || '').trim().replace(/^\[|\]$/g, '');
  if (!s.includes(':') || !/^[0-9a-fA-F:]+$/.test(s)) return null;
  const dbl = s.split('::');
  if (dbl.length > 2) return null;
  const left = dbl[0] ? dbl[0].split(':') : [];
  const right = dbl.length === 2 && dbl[1] ? dbl[1].split(':') : [];
  if (dbl.length === 1 && left.length !== 8) return null;
  const missing = 8 - (left.length + right.length);
  if (dbl.length === 2 && missing < 1) return null;
  const groups = dbl.length === 2 ? [...left, ...Array(missing).fill('0'), ...right] : left;
  if (groups.some((g) => g.length === 0 || g.length > 4)) return null;
  let val = 0n;
  for (const g of groups) {
    val = (val << 16n) | BigInt(parseInt(g, 16));
  }
  return val;
}

export function ipv6FromBigInt(val) {
  const groups = [];
  let cur = val;
  for (let i = 0; i < 8; i++) {
    groups.unshift( Number(cur & 0xffffn).toString(16) );
    cur >>= 16n;
  }
  return groups.join(':').replace(/(^|:)0(:0)+(:|$)/, '::');
}

export function describeCidr(raw) {
  const s = String(raw || '').trim();
  const slash = s.indexOf('/');
  if (slash <= 0 || slash === s.length - 1) return null;
  const ipPart = s.slice(0, slash).trim();
  const prefix = Number(s.slice(slash + 1).trim());
  if (!Number.isInteger(prefix)) return null;

  const v4 = parseIpv4Int(ipPart);
  if (v4 !== null) {
    if (prefix < 0 || prefix > 32) return null;
    const hostBits = 32 - prefix;
    const total = 2 ** hostBits;
    const mask = prefix === 0 ? 0 : (0xffffffff << hostBits) >>> 0;
    const net = (v4 & mask) >>> 0;
    const last = (net + total - 1) >>> 0;
    const usable = prefix <= 30 && total > 2 ? total - 2 : total;
    return {
      input: `${ipv4FromInt(net)}/${prefix}`,
      network: ipv4FromInt(net),
      lastAddress: ipv4FromInt(last),
      prefixLength: prefix,
      totalCount: total,
      usableCount: usable,
      isIpv6: false,
    };
  }

  const v6 = parseIpv6BigInt(ipPart);
  if (v6 !== null) {
    if (prefix < 0 || prefix > 128) return null;
    const hostBits = BigInt(128 - prefix);
    const total = 1n << hostBits;
    const allOnes = (1n << 128n) - 1n;
    const hostMask = hostBits === 0n ? 0n : (1n << hostBits) - 1n;
    const net = v6 & (allOnes ^ hostMask);
    const last = net | hostMask;
    const numTotal = total > BigInt(Number.MAX_SAFE_INTEGER) ? Number.MAX_SAFE_INTEGER : Number(total);
    return {
      input: `${ipv6FromBigInt(net)}/${prefix}`,
      network: ipv6FromBigInt(net),
      lastAddress: ipv6FromBigInt(last),
      prefixLength: prefix,
      totalCount: numTotal,
      usableCount: numTotal,
      isIpv6: true,
    };
  }

  return null;
}

export function expandTargets(rawText, { usableOnly = true, maxTotal = 2048 } = {}) {
  const tokens = String(rawText || '')
    .split(/\r?\n/)
    .map((l) => l.split('#')[0].trim())
    .filter(Boolean)
    .flatMap((l) => l.split(/[,;\s]+/))
    .map((t) => t.trim())
    .filter(Boolean);

  if (tokens.length === 0) {
    throw new Error('Введите хотя бы один домен, IP или подсеть CIDR');
  }

  const out = new Set();
  for (const token of tokens) {
    if (token.includes('/') && !/^https?:\/\//i.test(token)) {
      const info = describeCidr(token);
      if (!info) throw new Error(`Некорректная подсеть CIDR: ${token}`);
      const count = usableOnly ? info.usableCount : info.totalCount;
      if (count > maxTotal || out.size + count > maxTotal) {
        throw new Error(`Подсеть ${info.input} превышает лимит очереди (${maxTotal})`);
      }
      if (!info.isIpv6) {
        const net = parseIpv4Int(info.network);
        const skipEdges = usableOnly && info.prefixLength <= 30 && info.totalCount > 2;
        const start = skipEdges ? 1 : 0;
        const end = skipEdges ? info.totalCount - 1 : info.totalCount;
        for (let i = start; i < end; i++) out.add(ipv4FromInt((net + i) >>> 0));
      } else {
        const net = parseIpv6BigInt(info.network);
        for (let i = 0n; i < BigInt(info.totalCount); i++) {
          out.add(`[${ipv6FromBigInt(net + i)}]`);
        }
      }
      continue;
    }

    if (token.includes('-') && parseIpv4Int(token.split('-')[0]) !== null) {
      const [left, right] = token.split('-', 2).map((s) => s.trim());
      const start = parseIpv4Int(left);
      let end = null;
      if (/^\d{1,3}$/.test(right) && Number(right) <= 255) {
        const prefixOctets = left.split('.').slice(0, 3).join('.');
        end = parseIpv4Int(`${prefixOctets}.${Number(right)}`);
      } else {
        end = parseIpv4Int(right);
      }
      if (end === null || end < start) throw new Error(`Некорректный диапазон IP: ${token}`);
      if (end - start + 1 > maxTotal || out.size + (end - start + 1) > maxTotal) {
        throw new Error(`Диапазон ${token} превышает лимит очереди (${maxTotal})`);
      }
      for (let cur = start; cur <= end; cur++) out.add(ipv4FromInt(cur));
      continue;
    }

    let cleaned = token.replace(/^https?:\/\//i, '').split('/')[0].trim();
    if (!cleaned.startsWith('[') && (cleaned.match(/:/g) || []).length >= 2) {
      cleaned = `[${cleaned}]`;
    }
    if (cleaned) {
      if (out.size >= maxTotal) throw new Error(`Превышен лимит очереди (${maxTotal})`);
      out.add(cleaned);
    }
  }

  return [...out];
}

export function csvCell(val) {
  const s = String(val ?? '');
  if (/[",\r\n]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

export function reportToCsv(report) {
  const lines = ['Category,Section_Or_Provider,Target,IP,ASN,Org,Alive_Or_Status,TCP_16_20,Tx_Kbps,Rx_Kbps'];
  if (report?.Whoami) {
    const w = report.Whoami;
    lines.push(['WHOAMI', 'External IP', w.Ip, w.Ip, w.Asn, w.Org, w.Status?.Code, '', '', ''].map(csvCell).join(','));
  }
  if (report?.CidrWhitelist?.Status) {
    lines.push(['CIDR_WHITELIST', 'Whitelist', '', '', '', '', report.CidrWhitelist.Status.Code, '', '', ''].map(csvCell).join(','));
  }
  const doh = report?.Dns?.Doh?.Providers || {};
  for (const [prov, sts] of Object.entries(doh)) {
    lines.push(['DNS_DOH', prov, prov, '', '', '', (sts || []).map((s) => s.Code).join(';'), '', '', ''].map(csvCell).join(','));
  }
  const wh = report?.Webhost || {};
  for (const [secName, sec] of Object.entries(wh)) {
    for (const item of sec?.Items || []) {
      lines.push([
        'WEBHOST',
        secName,
        item.Group,
        item.IP,
        item.AS,
        item.Org,
        item.Alive?.Code,
        item.Tcp1620?.Code,
        item.BurstTxKbps ?? '',
        item.BurstRxKbps ?? '',
      ].map(csvCell).join(','));
    }
  }
  return lines.join('\n') + '\n';
}

export function reportToTxt(report, id = 'report') {
  const lines = [`=== DPI Detector (${id}) · by Ivan Tokarev ===`];
  if (report?.Whoami) {
    const w = report.Whoami;
    lines.push(`Whoami: ${w.Ip || '—'} (${w.Asn || ''} ${w.Org || ''}) [${w.Status?.Code || ''}]`);
  }
  if (report?.CidrWhitelist?.Status) {
    lines.push(`CIDR WhiteList: ${report.CidrWhitelist.Status.Code}`);
  }
  const doh = report?.Dns?.Doh?.Providers || {};
  if (Object.keys(doh).length > 0) {
    lines.push('--- DoH ---');
    for (const [prov, sts] of Object.entries(doh)) {
      lines.push(`  ${prov}: ${(sts || []).map((s) => s.Code).join(', ')}`);
    }
  }
  const wh = report?.Webhost || {};
  for (const [secName, sec] of Object.entries(wh)) {
    lines.push(`--- [${secName}] ---`);
    for (const item of sec?.Items || []) {
      lines.push(`  ${item.Group} (${item.IP}) -> Alive=${item.Alive?.Code} | TCP16-20=${item.Tcp1620?.Code}`);
    }
  }
  return lines.join('\n') + '\n';
}

export function batchToCsv(items) {
  const lines = ['Index,Target,State,Alive,TCP_16_20,Tx_Kbps,Error'];
  for (const q of items || []) {
    lines.push([
      (q.idx ?? 0) + 1,
      q.target,
      q.state,
      q.item?.Alive?.Code || '',
      q.item?.Tcp1620?.Code || '',
      q.item?.BurstTxKbps ?? '',
      q.error || '',
    ].map(csvCell).join(','));
  }
  return lines.join('\n') + '\n';
}

export function batchToTxt(items) {
  const list = items || [];
  const done = list.filter((i) => i.state === 'DONE').length;
  const failed = list.filter((i) => i.state === 'FAILED').length;
  const lines = [
    '=== Пакетное сканирование DPI Detector (by Ivan Tokarev) ===',
    `Всего: ${list.length} | Готово: ${done} | Ошибок: ${failed}`,
    '------------------------------------------------------------',
  ];
  for (const q of list) {
    if (q.item) {
      lines.push(`[${q.state}] ${q.target} -> Alive=${q.item.Alive?.Code} | TCP16-20=${q.item.Tcp1620?.Code}`);
    } else {
      lines.push(`[${q.state}] ${q.target} -> ${q.error || 'в очереди'}`);
    }
  }
  return lines.join('\n') + '\n';
}
