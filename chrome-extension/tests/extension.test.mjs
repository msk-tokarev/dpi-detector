// Author: by Ivan Tokarev
import test from 'node:test';
import assert from 'node:assert/strict';

import { encodeDnsQueryA, decodeDnsResponseA } from '../src/shared/dns-wire.js';
import {
  buildDpichReport,
  checkCidrWhitelist,
  checkDohProvider,
  checkWebhostTarget,
  checkWhoami,
  createRandomPayload,
  isAbortOrTimeout,
  isPublicIp,
  normalizeTargetHost,
  parseMailExternalIp,
} from '../src/background/checkers.js';
import { CIDR_WHITELIST_SUITE, DOH_PROVIDERS, WEBHOST_SUITE } from '../src/shared/suites.js';

function makeDnsResponseBytes({ rcode = 0, ips = ['1.1.1.1'] } = {}) {
  const q = encodeDnsQueryA('example.com', 0x4242);
  const ansChunks = ips.map((ip) => {
    const octets = ip.split('.').map(Number);
    // Compressed pointer 0xC0 0x0C + TYPE A (1) + CLASS IN (1) + TTL (60) + RDLEN (4) + IPv4
    return new Uint8Array([0xc0, 0x0c, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3c, 0x00, 0x04, ...octets]);
  });
  const totalAns = ansChunks.reduce((acc, a) => acc + a.length, 0);
  const out = new Uint8Array(q.length + totalAns);
  out.set(q, 0);
  const view = new DataView(out.buffer);
  view.setUint16(2, 0x8180 | (rcode & 0x0f), false); // QR=1, RD=1, RA=1, rcode
  view.setUint16(6, ips.length, false); // ANCOUNT
  let off = q.length;
  for (const a of ansChunks) {
    out.set(a, off);
    off += a.length;
  }
  return out;
}

test('suites are populated and well-formed', () => {
  assert.ok(CIDR_WHITELIST_SUITE.whitelisted.length >= 3);
  assert.ok(CIDR_WHITELIST_SUITE.regular.length >= 3);
  assert.ok(WEBHOST_SUITE.length >= 2);
  assert.ok(DOH_PROVIDERS.length >= 6);
});

test('DNS wire encoder & decoder handle A records, NXDOMAIN, and malformed inputs', () => {
  const query = encodeDnsQueryA('www.youtube.com.', 0x1234);
  assert.equal(query[0], 0x12);
  assert.equal(query[1], 0x34);
  assert.throws(() => encodeDnsQueryA(''), /empty/i);
  assert.throws(() => encodeDnsQueryA('a..b'), /Invalid DNS label/);
  assert.throws(() => encodeDnsQueryA('a'.repeat(64) + '.com'), /Invalid DNS label/);

  const okResp = makeDnsResponseBytes({ rcode: 0, ips: ['142.250.74.110', '1.1.1.1'] });
  const decoded = decodeDnsResponseA(okResp);
  assert.equal(decoded.rcode, 0);
  assert.deepEqual(decoded.ips, ['142.250.74.110', '1.1.1.1']);

  const nxdResp = makeDnsResponseBytes({ rcode: 3, ips: [] });
  assert.equal(decodeDnsResponseA(nxdResp).rcode, 3);

  assert.throws(() => decodeDnsResponseA(new Uint8Array(5)), /shorter than 12/);
  assert.throws(() => decodeDnsResponseA(new Uint8Array([0, 0, 0x80, 0, 0, 1, 0, 0, 0, 0, 0, 0, 5, 97])), /Malformed|Truncated/);
});

test('helpers: createRandomPayload, isAbortOrTimeout, parseMailExternalIp, normalizeTargetHost', () => {
  const big = createRandomPayload(70000);
  assert.equal(big.length, 70000);
  assert.ok(big.some((b) => b !== 0));

  assert.equal(isAbortOrTimeout(null), false);
  assert.equal(isAbortOrTimeout(Object.assign(new Error('x'), { name: 'AbortError' })), true);
  assert.equal(isAbortOrTimeout(new Error('request timeout')), true);
  assert.equal(isAbortOrTimeout(new Error('connection reset')), false);

  assert.equal(parseMailExternalIp('(none)({"IpAddress":"203.0.113.5"})'), '203.0.113.5');
  assert.equal(parseMailExternalIp('(none)({"ipAddress": "104.168.114.135", "xForwardedFor": "(none)"})'), '104.168.114.135');
  assert.throws(() => parseMailExternalIp('bad'), /invalid mail/);
  assert.throws(() => parseMailExternalIp('(none)({})'), /missing ipAddress/);

  // Public IP filtering (rejects loopback, private, CGNAT, malformed)
  assert.equal(isPublicIp('104.168.114.135'), true);
  assert.equal(isPublicIp('127.0.0.1'), false);
  assert.equal(isPublicIp('10.0.0.1'), false);
  assert.equal(isPublicIp('192.168.1.1'), false);
  assert.equal(isPublicIp('172.16.0.5'), false);
  assert.equal(isPublicIp('100.64.1.2'), false);
  assert.equal(isPublicIp('169.254.1.1'), false);
  assert.equal(isPublicIp('224.0.0.1'), false);
  assert.equal(isPublicIp('999.1.1.1'), false);
  assert.equal(isPublicIp('not-an-ip'), false);

  assert.equal(normalizeTargetHost('https://example.com/foo/bar'), 'example.com');
  assert.equal(normalizeTargetHost('http://1.1.1.1/'), '1.1.1.1');
});

test('checkWhoami succeeds via Mail API + RIPE enrichment and handles fallback/failure', async () => {
  const mockFetch = async (url) => {
    if (url.includes('ip.mail.ru')) {
      return { ok: true, text: async () => '(none)({"IpAddress":"1.1.1.1"})' };
    }
    if (url.includes('network-info')) {
      return { ok: true, json: async () => ({ data: { prefix: '1.1.1.0/24', asns: [13335] } }) };
    }
    if (url.includes('as-overview')) {
      return { ok: true, json: async () => ({ data: { holder: 'CLOUDFLARENET' } }) };
    }
    return { ok: false, status: 500 };
  };

  const res = await checkWhoami(mockFetch, 2000);
  assert.equal(res.Status.Code, 'OK');
  assert.equal(res.Ip, '1.1.1.1');
  assert.equal(res.Asn, 'AS13335');
  assert.equal(res.Org, 'CLOUDFLARENET');
  assert.equal(res.Subnet, '1.1.1.0/24');

  // Fallback to Yandex (source #2)
  const yandexFetch = async (url) => {
    if (url.includes('yandex.ru')) {
      return { ok: true, json: async () => '9.9.9.9' };
    }
    throw new Error('blocked');
  };
  const ya = await checkWhoami(yandexFetch, 2000);
  assert.equal(ya.Status.Code, 'OK');
  assert.equal(ya.Ip, '9.9.9.9');

  // Fallback when mail fails & yandex returns 127.0.0.1 (must reject 127.0.0.1 and fall through to ipify!)
  const fallbackFetch = async (url) => {
    if (url.includes('yandex.ru')) {
      return { ok: true, json: async () => '127.0.0.1' };
    }
    if (url.includes('api.ipify.org')) {
      return { ok: true, json: async () => ({ ip: '8.8.8.8' }) };
    }
    throw new Error('blocked');
  };
  const fb = await checkWhoami(fallbackFetch, 2000);
  assert.equal(fb.Status.Code, 'OK');
  assert.equal(fb.Ip, '8.8.8.8');

  // Fallback to RIPE Stat
  const ripeFetch = async (url) => {
    if (url.includes('whats-my-ip')) {
      return { ok: true, json: async () => ({ data: { ip: '9.9.9.9' } }) };
    }
    throw new Error('blocked');
  };
  const ripeRes = await checkWhoami(ripeFetch, 2000);
  assert.equal(ripeRes.Status.Code, 'OK');
  assert.equal(ripeRes.Ip, '9.9.9.9');

  // All sources fail
  const dead = await checkWhoami(async () => { throw new Error('offline'); }, 2000);
  assert.equal(dead.Status.Code, 'ERR');
});

test('checkCidrWhitelist covers NOT_DETECTED, DETECTED, and NO_INTERNET_ACCESS', async () => {
  const suite = { timeoutMs: 500, whitelisted: ['https://ya.ru/'], regular: ['https://github.com/'] };

  const clean = await checkCidrWhitelist(async () => ({ ok: true }), suite);
  assert.equal(clean.Status.Code, 'NOT_DETECTED');

  const detected = await checkCidrWhitelist(async (url) => {
    if (url.includes('ya.ru')) return { ok: true };
    throw new Error('blocked');
  }, suite);
  assert.equal(detected.Status.Code, 'DETECTED');

  const offline = await checkCidrWhitelist(async () => { throw new Error('down'); }, suite);
  assert.equal(offline.Status.Code, 'NO_INTERNET_ACCESS');
});

test('checkWebhostTarget covers OK, Alive failure, TCP 16-20 DETECTED, and POST error', async () => {
  await assert.rejects(() => checkWebhostTarget({ host: '' }, async () => ({})), /empty/i);

  // Clean host: must NOT use mode:'no-cors' (breaks CORP: same-origin sites like x.com)
  const seenModes = [];
  const okRes = await checkWebhostTarget(
    { name: 'Clean', host: 'x.com' },
    async (_url, opts) => {
      seenModes.push(opts.mode);
      assert.equal(opts.credentials, 'omit');
      assert.equal(opts.redirect, 'manual');
      return { ok: true, arrayBuffer: async () => new ArrayBuffer(2048) };
    },
    { payloadBytes: 1024 },
  );
  assert.ok(seenModes.every((m) => m !== 'no-cors'));
  assert.equal(okRes.Alive.Code, 'OK');
  assert.equal(okRes.Tcp1620.Code, 'OK');
  assert.ok(okRes.BurstTxKbps > 0);
  assert.ok(okRes.BurstRxKbps > 0);

  // HEAD reset falls back to GET Range and still passes
  const headFallback = await checkWebhostTarget(
    { name: 'GetOnly', host: 'getonly.test' },
    async (_url, opts) => {
      if (opts.method === 'HEAD') throw new Error('HEAD not allowed');
      return { ok: true, arrayBuffer: async () => new ArrayBuffer(512) };
    },
    { payloadBytes: 1024 },
  );
  assert.equal(headFallback.Alive.Code, 'OK');
  assert.equal(headFallback.Tcp1620.Code, 'OK');

  // Both HEAD and GET fail with non-timeout error
  const bothFail = await checkWebhostTarget(
    { name: 'ResetAll', host: 'reset.test' },
    async () => { throw new Error('connection reset'); },
  );
  assert.equal(bothFail.Alive.Code, 'ERR');
  assert.equal(bothFail.Alive.Reason, 'INETUTIL_ERR_TCP_CONN_RESET');

  // Clean host (opaque response where arrayBuffer() throws)
  const opaqueRes = await checkWebhostTarget(
    'opaque.test',
    async () => ({ ok: true, arrayBuffer: async () => { throw new Error('opaque'); } }),
    { payloadBytes: 1024 },
  );
  assert.equal(opaqueRes.Alive.Code, 'OK');
  assert.equal(opaqueRes.Tcp1620.Code, 'OK');
  assert.equal(opaqueRes.BurstRxKbps, null);

  // Unreachable on HEAD
  const deadRes = await checkWebhostTarget(
    { name: 'Dead', host: 'dead.test' },
    async () => { throw Object.assign(new Error('timeout'), { name: 'AbortError' }); },
  );
  assert.equal(deadRes.Alive.Code, 'ERR');
  assert.equal(deadRes.Tcp1620.Code, 'SKIP');

  // HEAD passes, POST stalls (TCP 16-20 / L4-25!)
  const dpiRes = await checkWebhostTarget(
    { name: 'Throttled', host: 'dpi.test' },
    async (_url, opts) => {
      if (opts.method === 'HEAD') return { ok: true };
      throw Object.assign(new Error('timeout'), { name: 'AbortError' });
    },
    { payloadBytes: 1024 },
  );
  assert.equal(dpiRes.Alive.Code, 'OK');
  assert.equal(dpiRes.Tcp1620.Code, 'DETECTED');

  // HEAD passes, POST throws generic network error
  const postErr = await checkWebhostTarget(
    { name: 'ErrPost', host: 'err.test' },
    async (_url, opts) => {
      if (opts.method === 'HEAD') return { ok: true };
      throw new Error('reset');
    },
    { payloadBytes: 1024 },
  );
  assert.equal(postErr.Tcp1620.Code, 'ERR');
});

test('checkDohProvider detects OK, NXDOMAIN spoofing, Non-2xx, timeout, and network errors', async () => {
  const prov = { name: 'MockDoH', url: 'https://doh.test/dns-query' };

  const ok = await checkDohProvider(
    prov,
    async () => ({ ok: true, arrayBuffer: async () => makeDnsResponseBytes({ rcode: 0, ips: ['1.1.1.1'] }).buffer }),
    ['a.test'],
  );
  assert.equal(ok[0].Code, 'OK');

  const nxd = await checkDohProvider(
    prov,
    async () => ({ ok: true, arrayBuffer: async () => makeDnsResponseBytes({ rcode: 3, ips: [] }).buffer }),
    ['blocked.test'],
  );
  assert.equal(nxd[0].Code, 'NXDOMAIN_SPOOFING');

  const non2xx = await checkDohProvider(prov, async () => ({ ok: false, status: 403 }), ['a.test']);
  assert.equal(non2xx[0].Code, 'NON_2XX_RESP');

  const to = await checkDohProvider(
    prov,
    async () => { throw Object.assign(new Error('timeout'), { name: 'AbortError' }); },
    ['a.test'],
  );
  assert.equal(to[0].Code, 'PROVIDER_TIMEOUT');

  const err = await checkDohProvider(prov, async () => { throw new Error('TLS fail'); }, ['a.test']);
  assert.equal(err[0].Code, 'INETUTIL_ERR');
});

test('buildDpichReport generates upstream-compatible Version 1 schema', () => {
  const rep = buildDpichReport({
    whoami: { Ip: '1.1.1.1' },
    cidr: { Status: { Code: 'NOT_DETECTED' } },
    dohProviders: { Cloudflare: [{ Code: 'OK' }] },
    webhostSections: { Popular: { Items: [], FarmTimeout: false } },
  });
  assert.equal(rep.Version, 1);
  assert.equal(rep.Whoami.Ip, '1.1.1.1');
  assert.equal(rep.CidrWhitelist.Status.Code, 'NOT_DETECTED');
  assert.ok(rep.Dns.Doh.Providers.Cloudflare);
  assert.ok(rep.Webhost.Popular);
});
