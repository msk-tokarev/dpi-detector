// Author: by Ivan Tokarev
// Browser-native DPI diagnostic checkers modeled after hyperion-cs/dpi-checkers.

import { CIDR_WHITELIST_SUITE, DOH_TEST_DOMAINS } from '../shared/suites.js';
import { decodeDnsResponseA, encodeDnsQueryA } from '../shared/dns-wire.js';

export const TCP1620_PAYLOAD_BYTES = 65536;

export function createRandomPayload(size = TCP1620_PAYLOAD_BYTES) {
  const data = new Uint8Array(size);
  // crypto.getRandomValues has a 65536-byte per-call quota in browsers.
  const step = 65536;
  for (let offset = 0; offset < size; offset += step) {
    const slice = data.subarray(offset, Math.min(offset + step, size));
    globalThis.crypto.getRandomValues(slice);
  }
  return data;
}

async function fetchWithTimeout(fetchFn, url, options, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error('timeout')), timeoutMs);
  try {
    return await fetchFn(url, { ...options, signal: controller.signal, cache: 'no-store' });
  } finally {
    clearTimeout(timer);
  }
}

export function isAbortOrTimeout(err) {
  if (!err) return false;
  const name = String(err.name || '');
  const msg = String(err.message || err);
  return name === 'AbortError' || name === 'TimeoutError' || /timeout|abort/i.test(msg);
}

export function isPublicIp(raw) {
  const ip = String(raw || '').trim().replace(/^\[|\]$/g, '');
  if (ip.includes(':')) {
    if (!/^[0-9a-fA-F:.]+$/.test(ip)) return false;
    const low = ip.toLowerCase();
    if (low === '::' || low === '::1' || low.startsWith('fe80:') || low.startsWith('fc') || low.startsWith('fd')) {
      return false;
    }
    return true;
  }
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(ip);
  if (!m) return false;
  const [a, b, c, d] = m.slice(1).map(Number);
  if ([a, b, c, d].some((n) => n < 0 || n > 255)) return false;
  if (a === 0 || a === 10 || a === 127) return false;
  if (a === 169 && b === 254) return false;
  if (a === 172 && b >= 16 && b <= 31) return false;
  if (a === 192 && b === 168) return false;
  if (a === 100 && b >= 64 && b <= 127) return false; // CGNAT 100.64.0.0/10
  if (a >= 224) return false; // multicast / reserved
  return true;
}

export function parseMailExternalIp(body) {
  const trimmed = String(body || '').trim();
  const prefix = '(none)(';
  if (!trimmed.startsWith(prefix) || !trimmed.endsWith(')')) {
    throw new Error('invalid mail IP response format');
  }
  const inner = JSON.parse(trimmed.slice(prefix.length, -1));
  const ip = inner.ipAddress || inner.IpAddress;
  if (!ip) throw new Error('missing ipAddress');
  return String(ip).trim();
}

/**
 * Whoami checker: resolves external IP via mail.ru -> yandex.ru -> RIPE Stat -> ipify,
 * rejects loopback/private/CGNAT IPs (matching dpi-ch), and enriches ASN/Org via RIPE Stat.
 */
export async function checkWhoami(fetchFn = globalThis.fetch, timeoutMs = 10000) {
  const sources = [
    async () => {
      const r = await fetchWithTimeout(fetchFn, 'https://ip.mail.ru/ip.html', { method: 'GET', credentials: 'omit' }, timeoutMs);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return parseMailExternalIp(await r.text());
    },
    async () => {
      const r = await fetchWithTimeout(fetchFn, 'https://yandex.ru/internet/api/v0/ip', { method: 'GET', credentials: 'omit' }, timeoutMs);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return String(await r.json()).trim();
    },
    async () => {
      const r = await fetchWithTimeout(fetchFn, 'https://stat.ripe.net/data/whats-my-ip/data.json', { method: 'GET', credentials: 'omit' }, timeoutMs);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const json = await r.json();
      const ip = json?.data?.ip;
      if (!ip) throw new Error('empty RIPE IP');
      return String(ip).trim();
    },
    async () => {
      const r = await fetchWithTimeout(fetchFn, 'https://api.ipify.org?format=json', { method: 'GET', credentials: 'omit' }, timeoutMs);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const json = await r.json();
      if (!json?.ip) throw new Error('empty ipify IP');
      return String(json.ip).trim();
    },
  ];

  let ip = '';
  let ttlbMs = 0;
  let lastErr = null;
  for (const fn of sources) {
    const t0 = Date.now();
    try {
      const candidate = await fn();
      if (!isPublicIp(candidate)) {
        throw new Error(`non-public IP: ${candidate}`);
      }
      ip = candidate;
      ttlbMs = Math.max(1, Date.now() - t0);
      break;
    } catch (e) {
      lastErr = e;
    }
  }

  if (!ip) {
    return {
      Status: { Msg: lastErr?.message || 'Failed to detect external IP', Code: 'ERR', Reason: 'UNKNOWN' },
      Ip: '',
      Subnet: '',
      Asn: '',
      Org: '',
      Location: '',
      TtlbMs: 0,
    };
  }

  // Optional RIPE Stat enrichment for ASN / Org / Prefix
  let asn = '';
  let subnet = '';
  let org = '';
  let location = '';
  try {
    const netResp = await fetchWithTimeout(
      fetchFn,
      `https://stat.ripe.net/data/network-info/data.json?resource=${encodeURIComponent(ip)}`,
      { method: 'GET' },
      Math.min(timeoutMs, 6000),
    );
    if (netResp.ok) {
      const netJson = await netResp.json();
      subnet = netJson?.data?.prefix || '';
      const asns = netJson?.data?.asns || [];
      if (asns.length > 0) {
        asn = `AS${asns[0]}`;
        const ovResp = await fetchWithTimeout(
          fetchFn,
          `https://stat.ripe.net/data/as-overview/data.json?resource=${encodeURIComponent(asn)}`,
          { method: 'GET' },
          Math.min(timeoutMs, 5000),
        );
        if (ovResp.ok) {
          const ovJson = await ovResp.json();
          org = ovJson?.data?.holder || '';
        }
      }
    }
  } catch {
    // Enrichment is best-effort (RIPE might itself be throttled or under whitelist)
  }

  return {
    Status: { Msg: 'Ok', Code: 'OK', Reason: 'NONE' },
    Ip: ip,
    Subnet: subnet,
    Asn: asn,
    Org: org,
    Location: location,
    TtlbMs: ttlbMs,
  };
}

/**
 * CIDR WhiteList checker: compares reachability of whitelisted vs regular endpoints.
 */
export async function checkCidrWhitelist(
  fetchFn = globalThis.fetch,
  suite = CIDR_WHITELIST_SUITE,
) {
  const probeGroup = async (urls) => {
    const results = await Promise.all(
      urls.map(async (url) => {
        try {
          // Do NOT use mode:'no-cors' — sites sending Cross-Origin-Resource-Policy: same-origin
          // (like x.com / github.com) fail in no-cors mode even when extension has host_permissions.
          await fetchWithTimeout(
            fetchFn,
            url,
            { method: 'HEAD', redirect: 'manual', credentials: 'omit' },
            suite.timeoutMs,
          );
          return true;
        } catch {
          return false;
        }
      }),
    );
    return results.some(Boolean);
  };

  const [regularOk, whitelistedOk] = await Promise.all([
    probeGroup(suite.regular),
    probeGroup(suite.whitelisted),
  ]);

  if (regularOk) {
    return { Status: { Msg: "You're NOT under one", Code: 'NOT_DETECTED', Reason: 'NONE' } };
  }
  if (whitelistedOk) {
    return {
      Status: {
        Msg: "You're UNDER one",
        Code: 'DETECTED',
        Reason: 'CHECKERS_CIDRWHITELIST_ERR_DETECTED',
      },
    };
  }
  return {
    Status: {
      Msg: 'It seems that there is no Internet access',
      Code: 'NO_INTERNET_ACCESS',
      Reason: 'CHECKERS_CIDRWHITELIST_ERR_NO_INET_ACCESS',
    },
  };
}

export function normalizeTargetHost(raw) {
  let host = String(raw || '').trim();
  host = host.replace(/^https?:\/\//i, '');
  const slashIdx = host.indexOf('/');
  if (slashIdx >= 0) host = host.slice(0, slashIdx);
  // Wrap bare IPv6 literals in brackets so URL https://[ipv6]/ is valid
  if (!host.startsWith('[') && (host.match(/:/g) || []).length >= 2) {
    host = `[${host}]`;
  }
  return host;
}

/**
 * Browser Webhost + TCP 16-20 (L4-25) checker:
 * 1) Sends lightweight HEAD to check basic reachability / TLS / SNI availability.
 * 2) Sends 64 KB random POST payload — if connection stalls/times out after HEAD succeeded,
 *    flags TCP 16-20 (L4-25) DPI restriction and estimates burst throughput when it passes.
 */
export async function checkWebhostTarget(
  target,
  fetchFn = globalThis.fetch,
  opts = {},
) {
  const headTimeoutMs = opts.headTimeoutMs ?? 6000;
  const tcp1620TimeoutMs = opts.tcp1620TimeoutMs ?? 12000;
  const payloadBytes = opts.payloadBytes ?? TCP1620_PAYLOAD_BYTES;

  const host = normalizeTargetHost(typeof target === 'string' ? target : target?.host);
  const group = (typeof target === 'object' ? target?.name : '') || host;
  if (!host) {
    throw new Error('Target host must not be empty');
  }

  const url = `https://${host}/`;
  const baseDto = {
    Group: group,
    Org: '',
    AS: '',
    Location: '',
    IP: host,
    Prefix: '',
    Alive: { Msg: 'Ok', Code: 'OK', Reason: 'NONE' },
    Tls: null,
    Tcp1620: { Msg: 'Skipped', Code: 'SKIP', Reason: 'CHECKERS_WEBHOST_ERR_SKIP' },
    Siberian: { Msg: 'Skipped (browser mode)', Code: 'SKIP', Reason: 'CHECKERS_WEBHOST_ERR_SKIP' },
    BurstTxKbps: null,
    BurstRxKbps: null,
  };

  // Phase 1: Alive (HEAD, with GET Range fallback if server rejects HEAD).
  // Important: do NOT set mode:'no-cors' — with extension host_permissions, default mode:'cors'
  // bypasses both CORS and Cross-Origin-Resource-Policy: same-origin (e.g. x.com, instagram.com).
  try {
    await fetchWithTimeout(
      fetchFn,
      url,
      { method: 'HEAD', redirect: 'manual', credentials: 'omit' },
      headTimeoutMs,
    );
  } catch (headErr) {
    if (isAbortOrTimeout(headErr)) {
      return {
        ...baseDto,
        Alive: {
          Msg: 'tcp/tls: timeout',
          Code: 'ERR',
          Reason: 'INETUTIL_ERR_TCP_CONN_TIMEOUT',
        },
      };
    }
    try {
      await fetchWithTimeout(
        fetchFn,
        url,
        { method: 'GET', redirect: 'manual', credentials: 'omit', headers: { Range: 'bytes=0-0' } },
        headTimeoutMs,
      );
    } catch (getErr) {
      const timedOut = isAbortOrTimeout(getErr);
      return {
        ...baseDto,
        Alive: {
          Msg: timedOut ? 'tcp/tls: timeout' : (getErr?.message || 'connection failed'),
          Code: 'ERR',
          Reason: timedOut ? 'INETUTIL_ERR_TCP_CONN_TIMEOUT' : 'INETUTIL_ERR_TCP_CONN_RESET',
        },
      };
    }
  }

  // Phase 2: TCP 16-20 / L4-25 (64 KB outgoing POST stream)
  const payload = createRandomPayload(payloadBytes);
  const txStart = Date.now();
  try {
    const resp = await fetchWithTimeout(
      fetchFn,
      url,
      {
        method: 'POST',
        redirect: 'manual',
        credentials: 'omit',
        body: payload,
        headers: { 'Content-Type': 'application/octet-stream' },
      },
      tcp1620TimeoutMs,
    );
    const elapsedSec = Math.max(0.005, (Date.now() - txStart) / 1000);
    // Drain body if readable (non-opaque)
    let rxBytes = 0;
    try {
      const ab = await resp.arrayBuffer();
      rxBytes = ab.byteLength;
    } catch {
      rxBytes = 0;
    }
    const txKbps = (payloadBytes * 8) / 1000 / elapsedSec;
    const rxKbps = rxBytes > 0 ? (rxBytes * 8) / 1000 / elapsedSec : null;

    return {
      ...baseDto,
      Tcp1620: { Msg: 'No', Code: 'OK', Reason: 'NONE' },
      BurstTxKbps: Number(txKbps.toFixed(1)),
      BurstRxKbps: rxKbps !== null ? Number(rxKbps.toFixed(1)) : null,
    };
  } catch (err) {
    const timedOut = isAbortOrTimeout(err);
    if (timedOut) {
      return {
        ...baseDto,
        Tcp1620: {
          Msg: 'Detected',
          Code: 'DETECTED',
          Reason: 'INETUTIL_ERR_TCP_READ_TIMEOUT',
        },
      };
    }
    return {
      ...baseDto,
      Tcp1620: {
        Msg: err?.message || 'write error',
        Code: 'ERR',
        Reason: 'INETUTIL_ERR_INTERNAL',
      },
    };
  }
}

/**
 * DNS over HTTPS (RFC 8484) checker for a single DoH provider.
 */
export async function checkDohProvider(
  provider,
  fetchFn = globalThis.fetch,
  domains = DOH_TEST_DOMAINS,
  timeoutMs = 7000,
) {
  const statuses = [];
  let sawNxdomain = false;
  let sawNon2xx = false;
  let sawTimeout = false;
  let sawError = null;

  for (const domain of domains) {
    const wire = encodeDnsQueryA(domain);
    try {
      const resp = await fetchWithTimeout(
        fetchFn,
        provider.url,
        {
          method: 'POST',
          credentials: 'omit',
          headers: {
            'Content-Type': 'application/dns-message',
            Accept: 'application/dns-message',
          },
          body: wire,
        },
        timeoutMs,
      );
      if (!resp.ok) {
        sawNon2xx = true;
        continue;
      }
      const bytes = new Uint8Array(await resp.arrayBuffer());
      const decoded = decodeDnsResponseA(bytes);
      if (decoded.rcode === 3) {
        sawNxdomain = true;
      }
    } catch (err) {
      if (isAbortOrTimeout(err)) {
        sawTimeout = true;
      } else {
        sawError = err;
      }
    }
  }

  if (sawNxdomain) {
    statuses.push({ Msg: 'NXDOMAIN spoofing', Code: 'NXDOMAIN_SPOOFING', Reason: 'CHECKERS_DNS_ERR_NXDOMAIN_SPOOFING' });
  }
  if (sawNon2xx) {
    statuses.push({ Msg: 'Non-2xx response', Code: 'NON_2XX_RESP', Reason: 'CHECKERS_DNS_ERR_DOH_NON_2XX_RESP' });
  }
  if (sawTimeout) {
    statuses.push({ Msg: 'Provider timeout', Code: 'PROVIDER_TIMEOUT', Reason: 'CHECKERS_DNS_ERR_PROVIDER_TIMEOUT' });
  }
  if (statuses.length === 0 && sawError) {
    statuses.push({ Msg: sawError.message || 'DoH request failed', Code: 'INETUTIL_ERR', Reason: 'INETUTIL_ERR_INTERNAL' });
  }
  if (statuses.length === 0) {
    statuses.push({ Msg: 'Ok', Code: 'OK', Reason: 'NONE' });
  }

  return statuses;
}

/**
 * Builds an upstream-compatible Version 1 ALL report (importable in `dpich --import-all`).
 */
export function buildDpichReport({ whoami, cidr, dohProviders, webhostSections }) {
  const report = { Version: 1 };
  if (whoami) report.Whoami = whoami;
  if (cidr) report.CidrWhitelist = cidr;
  if (dohProviders && Object.keys(dohProviders).length > 0) {
    report.Dns = {
      Doh: {
        Status: { Msg: 'Ok', Code: 'OK', Reason: 'NONE' },
        Providers: dohProviders,
      },
    };
  }
  if (webhostSections && Object.keys(webhostSections).length > 0) {
    report.Webhost = webhostSections;
  }
  return report;
}
