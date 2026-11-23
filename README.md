# DPI Детектор (Android APK + Chrome Extension MV3 + Desktop Native Host)

**Автор:** `by Ivan Tokarev`  
**Базовое ядро:** [hyperion-cs/dpi-checkers](https://github.com/hyperion-cs/dpi-checkers) (`ru/dpi-ch`, Apache-2.0)

Кроссплатформенный комплект для диагностики DPI-фильтрации и сетевых ограничений интернет-провайдера (SNI-блоки, TCP 16-20 / L4-25, «сибирские» ограничения, CIDR WhiteList, DNS Leak / Plain DNS / DoH).

---

## Скачать актуальную версию (`v1.0.0`)

Все готовые подписанные сборки опубликованы в **[GitHub Releases (`v1.0.0`)](https://github.com/msk-tokarev/dpi-detector/releases/tag/v1.0.0)** (ветка [`release/v1.0.0`](https://github.com/msk-tokarev/dpi-detector/tree/release/v1.0.0)):

| Платформа | Файл | Описание |
|---|---|---|
| **Android (современные смартфоны)** | `dpi-detector-arm64-v8a.apk` | Рекомендуемая сборка (ARM64, 24.3 МБ, вшита оффлайн-база GeoLite2) |
| **Android (32-bit ARM)** | `dpi-detector-armeabi-v7a.apk` | Для старых 32-битных Android-смартфонов (24.1 МБ) |
| **Android (x86_64 / эмуляторы)** | `dpi-detector-x86_64.apk` | Для x86-планшетов и Android Emulator (25.5 МБ) |
| **Android (универсальный)** | `dpi-detector-universal.apk` | Все архитектуры в одном APK (55.0 МБ) |
| **Google Chrome / Edge / Brave** | `dpi-detector-chrome-mv3.zip` | Плагин Manifest V3 (Popup + Side Panel + TCP 16-20 + CIDR + DoH) |
| **Windows (Pro Native Host)** | `dpich-native-host-windows-amd64.exe` | Нативный Go-компаньон для Pro-режима Chrome-плагина |
| **Linux (Pro Native Host)** | `dpich-native-host-linux-amd64` | Нативный Go-компаньон для Linux x86_64 |
| **macOS Apple Silicon** | `dpich-native-host-macos-arm64` | Нативный Go-компаньон для Mac M1/M2/M3/M4 |
| **macOS Intel** | `dpich-native-host-macos-amd64` | Нативный Go-компаньон для Intel Mac |
| **Контрольные суммы** | `SHA256SUMS.txt` | SHA-256 хэши всех файлов релиза |

---

## Документация и инструкции

- **[GUIDE.md](GUIDE.md)** — простая иллюстрированная инструкция «для деревенских» по всем 7 сценариям со скриншотами (также есть оффлайн-версия [GUIDE.html](GUIDE.html))
- **[chrome-extension/PRIVACY.md](chrome-extension/PRIVACY.md)** — политика конфиденциальности (Privacy Policy)
- **[docs/TESTING_PLAN.md](docs/TESTING_PLAN.md)** — архитектура системы тестирования (Go `91.3%`, Android Kotlin/Compose JaCoCo `94.8%`, Chrome Extension `100.0%`)
- **[docs/CHROME_EXTENSION_PLAN.md](docs/CHROME_EXTENSION_PLAN.md)** — архитектура Chrome MV3 и публикация в Chrome Web Store

---

## Сборка и запуск тестов из исходников

```powershell
# Android APK (Go core + signed release APKs)
.\build-apk.ps1

# Go тесты с покрытием
cd core\bridge && go test -v -coverprofile=coverage.out . && go tool cover -func=coverage.out

# Android тесты (Unit + Robolectric + Compose UI) + JaCoCo
cd android && gradle testDebugUnitTest jacocoTestReport --no-daemon

# Тесты Chrome-расширения (100% coverage)
cd chrome-extension && node --test --experimental-test-coverage tests/extension.test.mjs
```

---

## Отказ от ответственности

Инструмент предназначен исключительно для исследовательских, диагностических и образовательных целей. Основан на коде [hyperion-cs/dpi-checkers](https://github.com/hyperion-cs/dpi-checkers) (Apache-2.0).
