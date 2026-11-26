# DPI Детектор v1.1.0 (Android APK + Chrome Extension MV3 + Desktop Native Host)

[![DPI Detector CI & Release](https://github.com/msk-tokarev/dpi-detector/actions/workflows/ci.yml/badge.svg)](https://github.com/msk-tokarev/dpi-detector/actions/workflows/ci.yml)

**Автор:** `by Ivan Tokarev`  
**Базовое ядро:** [hyperion-cs/dpi-checkers](https://github.com/hyperion-cs/dpi-checkers) (`ru/dpi-ch`, Apache-2.0)  
**Chrome Web Store:** [DPI Детектор — ISP & Network Diagnostics](https://chromewebstore.google.com/detail/oajboookjgpekligaigmnfcieopimbic)

Кроссплатформенный автономный комплект для диагностики DPI-фильтрации и сетевых ограничений интернет-провайдера (SNI-блоки, TCP 16-20 / L4-25, «сибирские» ограничения, CIDR WhiteList, DNS Leak / Plain DNS / DoH).

---

## Что нового в `v1.1.0`

- **Полная поддержка IPv6 + IPv4** в ручной и пакетной проверке хостов (`[::1]`, `2606:4700:4700::1111`, `AAAA`-записи, `host:port`, GeoIP `::/0`).
- **Пакетный сканер доменов, диапазонов и подсетей CIDR** (со встроенным калькулятором подсетей, SQLite-очередью на Android и `chrome.storage` в браузере, мягкой **Паузой / Продолжением**, расчётом **ETA**, повтором ошибок и автовосстановлением после перезапуска процесса).
- **Фоновый `ForegroundService` на Android** с живым прогрессом и кнопками **«Пауза / Продолжить / Стоп»** прямо в шторке уведомлений.
- **Мультиформатный экспорт (CSV, TXT, JSON)** всех сохранённых отчётов и таблиц пакетного сканирования как в Android-приложении, так и в расширении Chrome.
- **Встроенное обновление APK в 1 тап** из GitHub Releases с автовыбором архитектуры процессора и криптографической проверкой `SHA-256`.

---

## Скачать актуальную версию (`v1.1.0`)

Все готовые подписанные сборки опубликованы в **[GitHub Releases (`v1.1.0`)](https://github.com/msk-tokarev/dpi-detector/releases/tag/v1.1.0)** (ветка [`release/v1.1.0`](https://github.com/msk-tokarev/dpi-detector/tree/release/v1.1.0)):

| Платформа | Файл | Описание |
|---|---|---|
| **Android (современные смартфоны)** | `dpi-detector-arm64-v8a.apk` | Рекомендуемая сборка (ARM64, вшита оффлайн-база GeoLite2) |
| **Android (32-bit ARM)** | `dpi-detector-armeabi-v7a.apk` | Для 32-битных Android-смартфонов |
| **Android (x86_64 / эмуляторы)** | `dpi-detector-x86_64.apk` | Для x86-планшетов и Android Emulator |
| **Android (универсальный)** | `dpi-detector-universal.apk` | Все архитектуры в одном APK |
| **Google Chrome / Edge / Brave** | `dpi-detector-chrome-mv3.zip` | Плагин Manifest V3 v1.1.0 ([Chrome Web Store](https://chromewebstore.google.com/detail/oajboookjgpekligaigmnfcieopimbic)) |
| **Windows (Pro Native Host)** | `dpich-native-host-windows-amd64.exe` | Нативный Go-компаньон для Pro-режима Chrome-плагина |
| **Linux (Pro Native Host)** | `dpich-native-host-linux-amd64` | Нативный Go-компаньон для Linux x86_64 |
| **macOS Apple Silicon** | `dpich-native-host-macos-arm64` | Нативный Go-компаньон для Mac M1/M2/M3/M4 |
| **macOS Intel** | `dpich-native-host-macos-amd64` | Нативный Go-компаньон для Intel Mac |
| **Контрольные суммы** | `SHA256SUMS.txt` | SHA-256 хэши всех файлов релиза |

---

## Документация и инструкции

- **[GUIDE.md](GUIDE.md)** — простая иллюстрированная инструкция «для деревенских» по сценариям со скриншотами (также есть оффлайн-версия [GUIDE.html](GUIDE.html))
- **[chrome-extension/PRIVACY.md](chrome-extension/PRIVACY.md)** — политика конфиденциальности (Privacy Policy)
- **[docs/TESTING_PLAN.md](docs/TESTING_PLAN.md)** — архитектура системы тестирования (Go + Android Kotlin/Compose JaCoCo + Chrome Extension)
- **[docs/CHROME_EXTENSION_PLAN.md](docs/CHROME_EXTENSION_PLAN.md)** — архитектура Chrome MV3 и публикация в Chrome Web Store

---

## Сборка и запуск тестов из исходников

```powershell
# Android APK (Go core + signed release APKs)
.\build-apk.ps1

# Go тесты с покрытием
cd core\bridge && go test -v "-coverprofile=coverage.out" . && go tool cover "-func=coverage.out"

# Android тесты (Unit + Robolectric + Compose UI) + JaCoCo
cd android && .\gradlew testDebugUnitTest jacocoTestReport --no-daemon

# Тесты Chrome-расширения (100% coverage)
cd chrome-extension && node --test --experimental-test-coverage tests/extension.test.mjs
```

---

## Отказ от ответственности

Инструмент предназначен исключительно для исследовательских, диагностических и образовательных целей. Основан на коде [hyperion-cs/dpi-checkers](https://github.com/hyperion-cs/dpi-checkers) (Apache-2.0).
