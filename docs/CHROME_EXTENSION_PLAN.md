# Плагин «DPI Детектор» для Google Chrome: архитектура и публикация в Chrome Web Store

**Авторы:** `by Ivan Tokarev`  
**Базовое ядро:** [hyperion-cs/dpi-checkers](https://github.com/hyperion-cs/dpi-checkers) (`ru/tcp-16-20`, `ru/ipv4-whitelisted-subnets`, `ru/dpi-ch`)

---

## 1. Честный разбор ограничений Chrome (почему нужна гибридная архитектура)

Расширения Google Chrome работают в жёсткой песочнице **Manifest V3 (MV3)**. Из JS/Service Worker браузера **нельзя**:
- открывать сырые TCP/UDP-сокеты (например, на UDP-порт 53 для Plain DNS);
- подменять TLS SNI или задавать кастомный uTLS-отпечаток браузера (как Chrome v133 для детекта «сибирских» ограничений).

**НО** браузер **может** делать очень многое прямо «из коробки» — ровно на этом построены веб-чекеры самого `hyperion-cs/dpi-checkers`!  
Поэтому идеальный дизайн — **двухуровневая (гибридная) архитектура**:

### Режим 1: Автономный (Pure Chrome Extension MV3 — 0 дополнительных установок)
Работает сразу после нажатия «Установить» в Chrome Web Store на Windows, macOS, Linux и ChromeOS:

1. **TCP 16-20 (L4-25) Детектор** (по алгоритму `ru/tcp-16-20`):
   - Из Service Worker отправляется `fetch(url, { method: 'POST', body: random64KB, signal: AbortSignal.timeout(15000) })` на набор эталонных и/или пользовательских хостов.
   - Если `HEAD` прошёл, а `POST` на 64 КБ завис до таймаута (`AbortError`) — фиксируется **TCP 16-20 (L4-25) блок**!
   - *Преимущество расширения над обычной веб-страницей*: в расширении с `host_permissions` нет ограничений CORS, можно читать HTTP-статусы, заголовки и точные коды ошибок!
2. **CIDR WhiteList Детектор** (по алгоритму `ru/cidrwhitelist` + `ru/ipv4-whitelisted-subnets`):
   - Быстрая проверка: параллельные `HEAD` к «белому списку» (`ya.ru`, `vk.ru`, `max.ru`) vs обычным (`github.com`, `ru.wikipedia.org`, `www.google.com`).
   - Глубокий сканер `/24` подсетей с кэшированием списка AS в `chrome.storage.local` (работает в фоне в Offscreen/Side Panel — пользователь может спокойно переключать вкладки!).
3. **DNS over HTTPS (DoH) Чекер**:
   - Формируем бинарный DNS-запрос (RFC 8484 `application/dns-message` — ~40 байт чистым JS `Uint8Array`) и отправляем `POST` на `dns.google/dns-query`, `cloudflare-dns.com/dns-query`, `dns.quad9.net/dns-query`, `dns.adguard-dns.com/dns-query` и др.
   - Сверяем возвращённые A-записи с ожидаемыми ASN/подсетями (вшитый компактный бинарный префикс-индекс).
4. **Whoami (IP / Провайдер / ASN)**:
   - Определение внешнего IP через `ip.mail.ru` / `yandex.ru/internet/api/v0/ip` / `stat.ripe.net` + сопоставление со встроенной базой или RIPE API.
5. **Пассивный радар текущей вкладки (killer-фича именно для Chrome!)**:
   - Подписка на `chrome.webRequest.onErrorOccurred`: расширение в реальном времени видит ошибки загрузки сайтов (`net::ERR_CONNECTION_RESET`, `net::ERR_CONNECTION_TIMED_OUT`, `net::ERR_SSL_PROTOCOL_ERROR`, `net::ERR_NAME_NOT_RESOLVED`).
   - Если сайт «завис» или сбросил TCP — на иконке расширения загорается значок-предупреждение, а в Popup появляется кнопка **«Проверить этот сайт на DPI»** в 1 клик!

### Режим 2: Pro-режим (Native Messaging Host + наш Go-бинарник `dpi-ch`)
Для продвинутых пользователей, которым нужны **SNI-подмена**, **«сибирские» ограничения (uTLS)**, **Plain UDP :53 DNS-перехват** и **полный `subnetfilter`**:
- Chrome предоставляет официальный стандартный API **`chrome.runtime.connectNative("com.dpidetector.dpich")`**.
- Пользователь по желанию скачивает с GitHub Releases лёгкий нативный компаньон (наш Go `dpi-ch` с JSON stdin/stdout адаптером — 100% переиспользование `core/bridge/dpich.go`!).
- Расширение автоматически видит компаньон и разблокирует полный набор проверок прямо в Chrome Side Panel, сохраняя те же самые JSON-отчёты `dpi-ch --import-all`!

---

## 2. Структура проекта Chrome-расширения (`chrome-extension/`)

```text
chrome-extension/
├── manifest.json                 # Manifest V3 (описание, права, авторство)
├── icons/
│   ├── icon16.png
│   ├── icon48.png
│   └── icon128.png               # Обязательна для Chrome Web Store
├── src/
│   ├── background/
│   │   ├── service-worker.ts     # Оркестратор проверок, badge, webRequest-радар, Native Messaging
│   │   ├── checkers/
│   │   │   ├── whoami.ts         # Определение IP / ASN / провайдера
│   │   │   ├── cidr-whitelist.ts # Проверка белых списков + сканер подсетей
│   │   │   ├── tcp1620.ts        # Проверка TCP 16-20 (L4-25) через 64KB POST + AbortController
│   │   │   └── doh.ts            # RFC 8484 DoH-кодировщик/декодировщик и проверка провайдеров
│   │   └── native-bridge.ts      # Опциональный мост к локальному Go dpich (Native Messaging)
│   ├── ui/
│   │   ├── popup.html / .tsx     # Быстрый вердикт + проверка текущей вкладки в 1 клик
│   │   ├── sidepanel.html / .tsx # Полноценный дашборд (как в Android-приложении: Обзор/Хосты/DNS/Отчёты)
│   │   └── options.html / .tsx   # Настройки, кастомные цели, экспорт/импорт JSON dpi-ch
│   └── shared/
│       ├── types.ts              # Общие DTO (совместимы с fullcheck.ReportDto)
│       └── suites.json           # Дефолтные списки сервисов и DoH-резолверов
├── tests/                        # Vitest + Playwright E2E (100% покрытие TS-кода)
└── package.json                  # Vite + TypeScript + Preact/React
```

### Пример `manifest.json` (Manifest V3) — готовый к ревью в Chrome Web Store

```json
{
  "manifest_version": 3,
  "name": "DPI Детектор — Network & ISP Diagnostics",
  "short_name": "DPI Детектор",
  "version": "1.0.0",
  "author": "by Ivan Tokarev",
  "description": "Диагностика доступности сайтов, TCP 16-20 (L4-25), CIDR WhiteList и DoH на базе hyperion-cs/dpi-checkers.",
  "homepage_url": "https://github.com/hyperion-cs/dpi-checkers",
  "icons": {
    "16": "icons/icon16.png",
    "48": "icons/icon48.png",
    "128": "icons/icon128.png"
  },
  "action": {
    "default_popup": "popup.html",
    "default_title": "DPI Детектор"
  },
  "side_panel": {
    "default_path": "sidepanel.html"
  },
  "background": {
    "service_worker": "service-worker.js",
    "type": "module"
  },
  "options_page": "options.html",
  "permissions": [
    "storage",
    "activeTab",
    "sidePanel"
  ],
  "optional_permissions": [
    "webRequest",
    "nativeMessaging"
  ],
  "optional_host_permissions": [
    "https://*/*",
    "http://*/*"
  ]
}
```

> **Почему `optional_permissions` и `optional_host_permissions` — критически важно!**  
> Если указать `"host_permissions": ["<all_urls>"]` сразу, Google отправляет расширение на **ручную углублённую проверку** (до 2–4 недель) и часто отклоняет с формулировкой *"Broad Host Permissions"*.  
> С `optional_host_permissions` расширение проходит быструю проверку, а при нажатии пользователем кнопки «Запустить полную проверку» запрашивает разрешение наглядно через `chrome.permissions.request(...)`!

---

## 3. Пошаговое размещение в Chrome Web Store

### Шаг 1. Подготовка аккаунта разработчика
1. Перейдите в **Chrome Web Store Developer Dashboard**: `https://chrome.google.com/webstore/devconsole`
2. Войдите через Google-аккаунт (рекомендуется отдельный проектный аккаунт с включённой 2FA).
3. Оплатите разовый регистрационный взнос **\$5 USD** (пожизненный, даёт право публиковать до 20 расширений).
   - *Нюанс оплаты*: карты банков РФ не принимаются Google Payments — потребуется иностранная банковская карта (Казахстан, Армения, Турция, ЕС, виртуальная карта и т.п.).
4. Заполните профиль разработчика:
   - **Publisher Name:** `by Ivan Tokarev`
   - **Contact Email:** подтверждённый email поддержки (публикуется на странице расширения).
   - **Trader / Non-trader declaration** (обязательно для публикации в ЕС): выберите *Non-trader*, если расширение бесплатное и без монетизации.

### Шаг 2. Сборка чистого пакета `.zip`
- Соберите production-бандл (`npm run build`), упакуйте содержимое `dist/` в `.zip` (чтобы `manifest.json` лежал в корне архива, без вложенной папки!).
- **Запреты Google MV3** (за них автоматический отказ):
  - никакого удалённого исполняемого JS (все скрипты и шрифты внутри `.zip`);
  - никакого `eval()`, `new Function()`, инлайн-скриптов в HTML;
  - код не должен быть обфусцирован (обычная минификация Vite/esbuild разрешена, но для ускорения модерации лучше отключить агрессивный mangling или приложить ссылку на открытый исходный код GitHub).

### Шаг 3. Карточка расширения и требования политики Google
В Developer Dashboard заполните 4 обязательные вкладки:

1. **Store Listing**:
   - Название и описание: **акцентируйте внимание на диагностике сети и доступности** (*"ISP network connectivity and DPI diagnostics tool for educational and research purposes"*). **Не используйте** слова «обход блокировок / VPN / anti-censorship bypass» — расширение ничего не обходит, оно только **диагностирует**, и правильное позиционирование избавит от лишних вопросов модераторов.
   - Укажите авторство: `by Ivan Tokarev` и ссылку на исходный проект `hyperion-cs/dpi-checkers` (лицензия Apache-2.0).
   - **Графика**:
     - Иконка магазина: `128×128 px` PNG;
     - Скриншоты: минимум 1 (лучше 4–5): `1280×800 px` (без альфа-канала, PNG/JPEG);
     - Малая промо-плитка: `440×280 px` (рекомендуется для красивой выдачи в поиске).
2. **Privacy Practices** (самая частая причина отклонения — заполнять максимально аккуратно!):
   - **Single Purpose Description**: *"Diagnose internet service provider network restrictions, packet throttling (TCP 16-20), CIDR whitelists, and DNS-over-HTTPS availability directly from the user's browser."*
   - **Permission Justifications**: для каждого разрешения (`storage`, `activeTab`, `sidePanel`, `optional_host_permissions`, `optional_permissions`) написать 1–2 чётких предложения, зачем оно нужно именно для сетевой диагностики.
   - **Remote Code**: выберите *"No, I am not using Remote code"*.
   - **Data Usage Certification**: отметьте, что расширение **НЕ собирает и НЕ передаёт на сторонние серверы** персональные данные, историю браузера или трафик — все сетевые пробы выполняются локально и результаты хранятся только в `chrome.storage.local` устройства.
   - **Privacy Policy URL**: обязательна публичная ссылка (проще всего разместить `PRIVACY.md` на GitHub Pages репозитория).
3. **Distribution**:
   - Видимость: **Public** (или сначала **Unlisted** для закрытого бета-теста по прямой ссылке, пока не пройдёт тест на реальных пользователях!).
   - Регионы: Все регионы.

### Шаг 4. Модерация и выпуск обновлений
- Нажмите **Submit for review**.
- Срок первичной проверки: от **24 часов до 5 рабочих дней** (если в `manifest.json` нет broad `<all_urls>` — часто проходит за 1–2 дня).
- Обновления выходят через повышение `version` в `manifest.json` и загрузку нового `.zip` (можно автоматизировать через GitHub Actions + `chrome-webstore-upload-cli` по OAuth2 API).

---

## 4. Альтернативные каналы распространения (если нет карты для \$5 или нужна полная свобода)

| Канал | Стоимость | Плюсы |
|---|---|---|
| **Microsoft Edge Add-ons Store** | **Бесплатно** | Принимает тот же самый MV3 `.zip` 1-в-1! Работает в Edge, а также поддерживает импорт |
| **Firefox Add-ons (AMO)** | **Бесплатно** | Огромная аудитория в РФ, простая модерация, поддерживает MV3 + даже работает на **Firefox для Android** |
| **GitHub Releases (`.zip` / Unpacked)** | **Бесплатно** | Пользователь распаковывает архив → `chrome://extensions` → «Режим разработчика» → «Загрузить распакованное» |
| **Self-hosted `.crx` + Update XML** | **Бесплатно** | Для Linux/корпоративных политик (`ExtensionInstallForcelist`) с автообновлением с собственного сервера или GitHub |
