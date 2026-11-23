// Author: by Ivan Tokarev
// Default diagnostic suites modeled after hyperion-cs/dpi-checkers (ru/dpi-ch & ru/tcp-16-20).

export const CIDR_WHITELIST_SUITE = {
  timeoutMs: 8000,
  whitelisted: [
    'https://max.ru/',
    'https://ya.ru/',
    'https://vk.ru/',
  ],
  regular: [
    'https://github.com/',
    'https://ru.wikipedia.org/',
    'https://www.google.com/',
  ],
};

export const WEBHOST_SUITE = [
  {
    id: 'popular',
    name: 'Популярные сервисы',
    targets: [
      { name: 'Google Web', host: 'www.google.com' },
      { name: 'YouTube Web', host: 'www.youtube.com' },
      { name: 'YouTube Image', host: 'i.ytimg.com' },
      { name: 'X (Twitter)', host: 'x.com' },
      { name: 'Discord', host: 'discord.com' },
      { name: 'GitHub', host: 'github.com' },
      { name: 'GitHub Raw', host: 'raw.githubusercontent.com' },
      { name: 'Telegram Web', host: 'web.telegram.org' },
      { name: 'Telegram API', host: 'api.telegram.org' },
      { name: 'Instagram', host: 'www.instagram.com' },
      { name: 'WhatsApp Web', host: 'web.whatsapp.com' },
      { name: 'Yandex', host: 'ya.ru' },
      { name: 'VK', host: 'vk.ru' },
      { name: 'Gosuslugi', host: 'www.gosuslugi.ru' },
    ],
  },
  {
    id: 'infra',
    name: 'Инфраструктура и CDN (TCP 16-20)',
    targets: [
      { name: 'Cloudflare', host: '1.1.1.1' },
      { name: 'Cloudflare DNS', host: 'cloudflare-dns.com' },
      { name: 'Fastly', host: 'www.fastly.com' },
      { name: 'Hetzner', host: 'www.hetzner.com' },
      { name: 'DigitalOcean', host: 'www.digitalocean.com' },
      { name: 'OVH', host: 'www.ovhcloud.com' },
      { name: 'Amazon AWS', host: 'aws.amazon.com' },
      { name: 'Selectel (RU)', host: 'selectel.ru' },
      { name: 'Yandex Cloud (RU)', host: 'yandex.cloud' },
    ],
  },
];

export const DOH_PROVIDERS = [
  { name: 'Cloudflare DNS', url: 'https://cloudflare-dns.com/dns-query' },
  { name: 'Google DNS', url: 'https://dns.google/dns-query' },
  { name: 'Quad9', url: 'https://dns.quad9.net/dns-query' },
  { name: 'AdGuard DNS', url: 'https://dns.adguard-dns.com/dns-query' },
  { name: 'ControlD', url: 'https://freedns.controld.com/dns-query' },
  { name: 'Mullvad DNS', url: 'https://dns.mullvad.net/dns-query' },
  { name: 'Wikimedia DNS', url: 'https://wikimedia-dns.org/dns-query' },
  { name: 'AliDNS', url: 'https://dns.alidns.com/dns-query' },
];

export const DOH_TEST_DOMAINS = [
  'www.youtube.com',
  'discord.com',
  'x.com',
  'github.com',
];

export const DPI_NETWORK_ERRORS = new Set([
  'net::ERR_CONNECTION_RESET',
  'net::ERR_CONNECTION_TIMED_OUT',
  'net::ERR_CONNECTION_CLOSED',
  'net::ERR_SSL_PROTOCOL_ERROR',
  'net::ERR_SSL_VERSION_OR_CIPHER_MISMATCH',
  'net::ERR_TIMED_OUT',
  'net::ERR_NAME_NOT_RESOLVED',
  'net::ERR_CERT_AUTHORITY_INVALID',
]);
