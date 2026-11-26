# Готовые тексты для заполнения Chrome Web Store Developer Dashboard (v1.1.0)

**Издатель / Автор:** `by Ivan Tokarev`  
**Пакет для загрузки:** `chrome-extension/store-assets/dpi-detector-chrome-mv3.zip` (`v1.1.0`)

---

## 1. Вкладка «Store listing» (Описание в магазине)

- **Title (Название):** `DPI Детектор — ISP & Network Diagnostics`
- **Summary (Краткое описание, до 132 символов):**
  `Диагностика сетевых ограничений провайдера: TCP 16-20 (L4-25), CIDR WhiteList, DNS over HTTPS (DoH) и доступность хостов.`
- **Category (Категория):** `Tools` (Инструменты) или `Developer Tools`
- **Language:** `Russian`
- **Detailed Description (Подробное описание, без перечисления сторонних брендов):**

```text
DPI Детектор — диагностический инструмент для проверки доступности сетевых ресурсов и анализа ограничений интернет-провайдера (ISP / DPI) прямо из браузера. Основан на открытом проекте hyperion-cs/dpi-checkers (Apache-2.0).

Что нового в версии 1.1.0:
• Полная поддержка IPv6 и IPv4 при проверке одиночных хостов и подсетей.
• Пакетный сканер списков доменов, IP-диапазонов и подсетей CIDR со встроенным калькулятором подсети, паузой, продолжением, повтором ошибок, расчётом оставшегося времени (ETA) и автосохранением очереди.
• Выгрузка всех сохранённых отчётов и таблиц пакетного сканирования в форматах CSV, TXT и JSON.

Основные возможности:
• Проверка открытого сайта в 1 клик (Popup) и подробная боковая панель диагностики (Side Panel).
• Тест TCP 16-20 (L4-25): определяет обрыв или замирание TCP-соединения после передачи ~16–20 КБ данных и оценивает пиковую скорость передачи.
• Проверка CIDR WhiteList: определяет жёсткий режим «белых списков» подсетей у оператора связи.
• Проверка DNS over HTTPS (RFC 8484): тестирует публичные DoH-резолверы на доступность и подмену ответов (NXDOMAIN-спуфинг).
• Whoami: определяет внешний IP-адрес, подсеть и автономную систему провайдера (ASN / организация).
• Быстрая и пакетная проверка собственных доменов, серверов (VPS), IPv4/IPv6-адресов и CIDR-диапазонов.
• Пассивный радар вкладки (опционально): фиксирует сетевые сбои ERR_CONNECTION_RESET / ERR_TIMED_OUT.
• Экспорт результатов диагностики в CSV, TXT и JSON (совместим с утилитой dpi-ch --import-all).
• Опциональный Pro-режим (Native Messaging) для подключения локального нативного ядра dpi-ch.

Приватность:
Расширение выполняет все диагностические запросы локально из браузера пользователя, не собирает персональные данные и не передаёт историю посещений на сторонние серверы. Предназначено исключительно для диагностических, исследовательских и образовательных целей.

Автор: by Ivan Tokarev
```

- **Графика (лежит в `chrome-extension/store-assets/`):**
  - Store icon (`128×128`): `icon_128x128.png`
  - Screenshots (`1280×800`): `screenshot_1280x800.png`, `screenshot_2_1280x800.png`
  - Small promo tile (`440×280`): `promo_tile_440x280.png`

---

## 2. Вкладка «Privacy» (Политика конфиденциальности и обоснования прав)

- **Single Purpose Description:**
  `Diagnose internet service provider (ISP) network restrictions, TCP 16-20 (L4-25) throttling, CIDR subnet whitelists, and DNS-over-HTTPS availability directly from the user's browser.`

- **Permission Justifications (Обоснования разрешений):**
  - **`storage`**: `Used solely to persist local user preferences (custom diagnostic hosts), batch scan queue progress, and locally saved diagnostic reports in chrome.storage.local.`
  - **`activeTab`**: `Used only when the user clicks the extension icon to read the hostname of the currently active tab for one-click connectivity diagnostics.`
  - **`sidePanel`**: `Used to render the diagnostic dashboard (Scanner, Host & CIDR Probe, Reports, Settings) in Chrome's native Side Panel.`
  - **`optional_permissions` (`webRequest`)**: `Optional and disabled by default; enabled only by explicit user action in Settings to detect passive network errors (ERR_CONNECTION_RESET / ERR_TIMED_OUT) on the active tab.`
  - **`optional_permissions` (`nativeMessaging`)**: `Optional and disabled by default; enabled only if the user installs the open-source local Go dpi-ch companion to run low-level TLS/UDP diagnostics.`
  - **`optional_host_permissions` (`http://*/*`, `https://*/*`)**: `Requested at runtime only when the user clicks "Start DPI Check", "Probe Host", or "Start Batch Queue", so the extension can send diagnostic HEAD/POST probes to target domains and user-specified hosts.`

- **Are you using remote code?** → **`No, I am not using Remote code`**
- **Data Usage (Сбор данных):** → **НЕ отмечать ни одну категорию сбора данных** + поставить все 3 галочки сертификации («не продаю данные», «не использую вне Single Purpose», «не использую для кредитного скоринга»).
- **Privacy Policy URL:** → `https://github.com/msk-tokarev/dpi-detector/blob/main/chrome-extension/PRIVACY.md`
