// Author: by Ivan Tokarev
// Pure JS RFC 1035 / RFC 8484 DNS wire-format encoder & A-record decoder (zero dependencies).

/**
 * Encodes a DNS A-record query into a wire-format Uint8Array suitable for
 * POST application/dns-message (DoH RFC 8484).
 */
export function encodeDnsQueryA(domain, id = 0x1a2b) {
  const clean = String(domain || '').trim().replace(/\.+$/, '');
  if (!clean) {
    throw new Error('Domain must not be empty');
  }
  const labels = clean.split('.');
  const encodedLabels = [];
  let qnameLen = 1; // trailing 0x00 root label

  for (const label of labels) {
    const bytes = new TextEncoder().encode(label);
    if (bytes.length === 0 || bytes.length > 63) {
      throw new Error(`Invalid DNS label: "${label}"`);
    }
    encodedLabels.push(bytes);
    qnameLen += 1 + bytes.length;
  }

  // 12-byte header + qname + 4-byte QTYPE(A=1)/QCLASS(IN=1)
  const buf = new Uint8Array(12 + qnameLen + 4);
  const view = new DataView(buf.buffer);
  view.setUint16(0, id & 0xffff, false);
  view.setUint16(2, 0x0100, false); // Standard query, RD=1
  view.setUint16(4, 1, false);      // QDCOUNT = 1
  view.setUint16(6, 0, false);      // ANCOUNT
  view.setUint16(8, 0, false);      // NSCOUNT
  view.setUint16(10, 0, false);     // ARCOUNT

  let offset = 12;
  for (const bytes of encodedLabels) {
    buf[offset++] = bytes.length;
    buf.set(bytes, offset);
    offset += bytes.length;
  }
  buf[offset++] = 0; // root terminator
  view.setUint16(offset, 1, false);     // QTYPE = A (1)
  view.setUint16(offset + 2, 1, false); // QCLASS = IN (1)
  return buf;
}

function skipDnsName(buf, start) {
  let offset = start;
  let guard = 0;
  while (offset < buf.length && guard++ < 128) {
    const len = buf[offset];
    if (len === 0) return offset + 1;
    if ((len & 0xc0) === 0xc0) {
      // Compression pointer (2 bytes)
      return offset + 2;
    }
    offset += 1 + len;
  }
  throw new Error('Malformed DNS name in response');
}

/**
 * Decodes a DNS wire-format response and extracts rcode and IPv4 A records.
 * @returns {{ rcode: number, ips: string[] }}
 */
export function decodeDnsResponseA(bytes) {
  const buf = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (buf.length < 12) {
    throw new Error('DNS response shorter than 12-byte header');
  }
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  const flags = view.getUint16(2, false);
  const rcode = flags & 0x0f;
  const qdCount = view.getUint16(4, false);
  const anCount = view.getUint16(6, false);

  let offset = 12;
  for (let i = 0; i < qdCount; i++) {
    offset = skipDnsName(buf, offset);
    offset += 4; // QTYPE + QCLASS
    if (offset > buf.length) throw new Error('Truncated DNS question section');
  }

  const ips = [];
  for (let i = 0; i < anCount; i++) {
    offset = skipDnsName(buf, offset);
    if (offset + 10 > buf.length) throw new Error('Truncated DNS answer header');
    const rtype = view.getUint16(offset, false);
    const rdLen = view.getUint16(offset + 8, false);
    offset += 10;
    if (offset + rdLen > buf.length) throw new Error('Truncated DNS answer rdata');
    if (rtype === 1 && rdLen === 4) {
      ips.push(`${buf[offset]}.${buf[offset + 1]}.${buf[offset + 2]}.${buf[offset + 3]}`);
    }
    offset += rdLen;
  }

  return { rcode, ips };
}
