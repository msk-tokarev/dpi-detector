# Система тестирования и достижения 100% покрытия кода

**Авторы:** `by Ivan Tokarev`  
**Проект:** DPI Детектор (`core/dpi-ch` + `core/bridge` + `android/app`)

---

## 1. Общая архитектура пирамиды тестов

Проект состоит из двух равнозначных контуров — **Go-ядра** (`core/bridge` + завендоренный `core/dpi-ch`) и **Android-клиента** (`android/app` на Kotlin + Jetpack Compose). Чтобы достичь **100% line & branch coverage** без «хрупких» тестов, зависящих от живого интернета или реального провайдера, система строится из **4 детерминированных уровней**:

```
               ┌─────────────────────────────┐
               │   L4: APK & Emulator E2E    │  apksigner, R8 DEX check, ADB smoke, logcat
               ├─────────────────────────────┤
               │   L3: Compose UI + Visual   │  Robolectric + compose-ui-test-junit4
               ├─────────────────────────────┤
               │  L2: Kotlin Engine & Models │  JUnit4 + Coroutines Test + FakeGateway + JaCoCo
               ├─────────────────────────────┤
               │ L1: Go Bridge + DPI MockLab │  go test -race -coverprofile + local TLS/UDP/HTTP mocks
               └─────────────────────────────┘
```

Ключевой принцип: **ни один юнит- или интеграционный тест не ходит в реальный интернет**. Все DPI-сценарии воспроизводятся локально на `127.0.0.1` через управляемые mock-серверы («DPI Mock Lab»).

---

## 2. Инвентаризация модулей и матрица 100% покрытия

### 2.1. Уровень L1 — Go-мост (`core/bridge`) и аддитивные расширения (`core/dpi-ch`)

| Файл / пакет | Что должно быть покрыто для 100% | Механизм тестирования |
|---|---|---|
| `core/bridge/dpich.go` | `Setup` (обычный старт, `debug: true` с созданием `debug.log`, повторный вызов при `running=true`, битый YAML, недоступный путь) | `t.TempDir()`, RO-директория для ветки `os.MkdirAll` err |
| `core/bridge/dpich.go` | `DefaultConfigYaml`, `ValidateConfig` (пустой, валидный, невалидный YAML, вызов во время `running=true`) | Прямые вызовы + sync barrier |
| `core/bridge/dpich.go` | `Sections`, `DnsProviders`, `ReportsDir`, `GeolitePaths`, `GeoStatus`, `MarkBundledGeoTimestamp` (до и после записи, `unixSec <= 0`, `!inited`) | Табличные тесты |
| `core/bridge/dpich.go` | `PrepareGeo` (`!inited`, уже запущено, `allowOnlineUpdate=false`, `force=true` с mock GitHub API/raw сервером, сетевой фейл с fallback на локальную базу, `ReloadDefault` ветка) | `httptest.NewTLSServer` + переопределение `updater.geolite` |
| `core/bridge/dpich.go` | `CheckCustomHost` (пустая строка, IPv6 отказ, `https://host/path`, порт <=0 / >65535 → 443, домен с DNS fail, домен с IPv4, чистый HTTPS mock, TCP 16-20 mock, SNI-блок mock) | Локальный TLS-сервер (`startMockHttpsHost`) |
| `core/bridge/dpich.go` | `Start`, `Cancel`, `IsRunning`, `run` — все ветки (`whoami`, `cidrwhitelist`, `dns`, `webhost` с выбором секций и `FarmTimeout`, отмена через `Cancel()`, ошибка сохранения отчёта, panic recovery в горутине) | Локальные mock-серверы для всех 4 чекеров |
| `core/dpi-ch/inetlookup/reload_mobile.go` | `ReloadDefault` — успешная перезагрузка новых CSV и ошибка при битом/удалённом CSV | `t.TempDir()` |
| `core/dpi-ch/checkers/fullcheck/export_mobile.go` | Все адаптеры `NewReport`, `ToWhoamiDto`, `ToCidrWhitelistDto`, `ToDnsLeakDto`, `ToDnsReportDto`, `ToWebhostItemDto`, `DnsStateOf`, `ErrorStateOf` | Табличные тесты для каждого sentinel-error |
| Upstream `core/dpi-ch` пакеты без тестов | Сейчас без тестовых файлов: `checkers/whoami`, `checkers/cidrwhitelist`, `gochan`, `inetutil`. Плюс кроссплатформенный фикс текста TCP RST на Windows (`wsarecv: forcibly closed` vs `connection reset`) | Добавление `*_test.go` в каждый пакет + локальные TCP/TLS/UDP моки |

#### DPI Mock Lab (симуляция всех типов цензуры локально)

1. **Симулятор SNI-блока**: `net.Listen("tcp", "127.0.0.1:0")` читает ClientHello (через `tls.Server` с `GetConfigForClient`), если `hello.ServerName == "blocked.test"` — шлёт `TCP RST` (`SetLinger(0)` + `Close()`); если `cf.com` / `google.com` — завершает handshake. Проверяет, что ядро выдаёт `ErrBlockedBySni`.
2. **Симулятор TCP 16-20 (L4-25)**: принимает TLS handshake и `HEAD /` (200 OK), но на `POST /` после чтения первых 16 КБ «замораживает» сокет до `tcp-read-timeout`. Проверяет, что `webhost` выдаёт `Tcp1620.Code == "DETECTED"` *(уже реализовано в `core/bridge/dpich_test.go`!)*.
3. **Симулятор «Сибирских» ограничений**: принимает 1 соединение (beta), но при >= `siberian-conn-count` параллельных рукопожатий с одним SNI рвёт TLS handshake → `Siberian.Code == "DETECTED"`.
4. **Симулятор CIDR WhiteList**: 3 сценария — (а) регулярный + белый доступны → `NOT_DETECTED`, (б) только белый доступен → `DETECTED` *(уже реализовано!)*, (в) оба недоступны → `NO_INTERNET_ACCESS`.
5. **Симулятор DNS-атак**: локальный UDP-сервер (`golang.org/x/net/dns/dnsmessage`) имитирует чистый ответ, подмену A-записи (`RESPONSE_SPOOFING`), ложный NXDOMAIN (`NXDOMAIN_SPOOFING`) и подмену `whoami.akamai.net` (`PROVIDER_HIJACKING`); локальный HTTPS-сервер имитирует DoH 200 OK, невалидный сертификат (`INVALID_HTTPS_CERT`) и Non-2xx.

---

### 2.2. Уровень L2 — Kotlin-данные и бизнес-логика (`android/app/src/test`)

Для 100% покрытия `DpiEngine.kt` и `NetworkMonitor.kt` без JNI-вызовов на JVM вводится минимальная абстракция инъекции зависимостей (DI):

```kotlin
// Интерфейс-обёртка над статическим gomobile-классом Dpich
interface DpichGateway {
    fun setup(filesDir: String, cacheDir: String, userYaml: String)
    fun sections(): String
    fun geolitePaths(): String
    fun markBundledGeoTimestamp(unixSec: Long)
    fun geoStatus(): String
    fun prepareGeo(force: Boolean, allowOnline: Boolean, listener: EventListener)
    fun start(optsJson: String, listener: EventListener)
    fun cancel()
    fun checkCustomHost(target: String, sni: String, port: Int): String
    fun validateConfig(userYaml: String): String
    fun defaultConfigYaml(): String
    fun reportsDir(): String
}
```

В продакшне передаётся `RealDpichGateway` (делегирует в `Dpich.*`), в тестах — `FakeDpichGateway`!

| Файл | Тестовые сценарии для 100% покрытия | Инструмент |
|---|---|---|
| `model/Models.kt` | Все ветки `Status.levelOf`, `Status.from(null)`, `TaskState.parse`, `WebhostItem.from` (с TLS / без TLS, null скорости, SNI block, reachable), `Results.fromReport` (полный, пустой, с `FarmTimeout`), `JSONArray?.objects()` *(уже реализовано в `ModelsTest.kt`!)* | JUnit4 + `org.json` |
| `ui/components/CommonComponents.kt` (чистые функции) | `localizeSectionName`, `localizeDnsStatus`, `localizeNetError`, `formatKbps`, `levelColors` *(уже реализовано в `FormattingAndLocalizationTest.kt`!)* | JUnit4 |
| `data/DpiEngine.kt` | `bootstrap` (успех и исключение `setup`), `extractBundledGeoIfMissing` (распаковка, пропуск существующих, ошибка ассета), `loadGeo` (успех/ошибка), `toggleChecker` (нельзя снять последний), `toggleSection` (снятие всех отключает `webhost`, выбор включает), `startCheck` (не готов / уже запущен / пустой список / частичные секции / полный набор / ошибка `start`), `cancelCheck`, `probeCustomHost` (пустой, busy, успех, ошибка, история > 30), `refreshReports` / `loadReport` / `deleteReport`, `saveUserConfig` (во время `running`, невалидный YAML, валидный, ошибка `setup`), `handleEvent` для **всех 12 типов событий** (`log` + обрезка ring buffer на 401-й строке, `started`, `task` webhost и не-webhost, `whoami`, `cidr`, `dnsLeak`, `dnsPlain`, `dnsDoh`, `webhostItem` новый и существующий индекс, `webhostDone`, `error`, `done` обычный и cancelled, битый JSON) | JUnit4 + `kotlinx-coroutines-test` (`StandardTestDispatcher`) + Robolectric (`RuntimeEnvironment.getApplication()`) |
| `data/NetworkMonitor.kt` | Wi-Fi, Cellular, Ethernet, VPN-only, Wi-Fi + VPN (split tunnel), Offline, отсутствие capabilities, реакция `NetworkCallback` (`onAvailable`/`onLost`/`onCapabilitiesChanged`) | Robolectric `ShadowConnectivityManager` |
| `DpiApp.kt` & `MainActivity.kt` | Инициализация `DpiApp.onCreate()`, запуск `MainActivity`, переключение всех 4 вкладок BottomBar, `shareReportFile` (существующий и несуществующий файл, проверка Intent `ACTION_SEND` + `FileProvider` URI) | Robolectric `ActivityScenario` |

---

### 2.3. Уровень L3 — UI-тесты Jetpack Compose (100% Composable coverage)

Через **Robolectric + `androidx.compose.ui:ui-test-junit4`** (запускаются на JVM за секунды без эмулятора!) покрываются все ветки отображения в каждом экране:

| Экран / компонент | Проверяемые ветки UI |
|---|---|
| `ScannerScreen` | Индикатор сети (online/offline), баннер VPN (`vpnActive=true/false`), `initError`, `geo.loading` / `geo.error` + кнопка «Скачать базу», `errorBanner` + крестик, сворачивание «Модули диагностики», клики по чипам, кнопка «Запустить» → «Остановить», кнопка «Поделиться отчётом», панель «Прогресс модулей», сворачивание/разворачивание «Журнал событий» |
| `ResultsDashboard` (`ResultsView.kt`) | Пустой `Results`, все 5 вариантов карточки вердикта (CIDR, DPI detected, Unreachable, Running, Clean), все 3 вкладки («Обзор», «Хосты», «DNS»), поиск в «Хосты», фильтр «Только проблемы», чипы секций, пустой результат фильтрации, раскрытие `WebhostItemCard`, отображение `DNS Leak` (OK / empty / error), `Plain DNS` и `DoH` (фильтрация `SKIP`) |
| `HostProbeScreen` | Клик по пресету заполняет поле, ввод порта (отсеивание не-цифр, лимит 5 символов), состояние `hostProbeBusy`, отображение карточки успеха и карточки ошибки, очистка истории |
| `ReportsScreen` | Пустой список, список с отчётами («Чисто» и «Проблем: N»), клик открывает детальный просмотр, ошибка чтения битого отчёта, кнопка «Назад», «Поделиться», «Удалить» |
| `SettingsScreen` | Статус GeoLite (загружена / не загружена / ошибка / loading / дата), ввод YAML + «Применить» (успех и ошибка валидации), «Сбросить», открытие и закрытие диалога `default.yaml`, проверка отображения авторства `by Ivan Tokarev` |

---

### 2.4. Уровень L4 — APK & E2E Smoke-проверки

Автоматизируемый скрипт после сборки релизных APK:
1. `apksigner verify --verbose` для всех 4 APK (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `universal`).
2. `aapt2 dump badging` — проверка `applicationId`, `minSdkVersion=26`, `targetSdkVersion=36`, permissions (`INTERNET`, `ACCESS_NETWORK_STATE`).
3. Проверка распакованных `assets/geolite/*.csv` (не сжаты повторно с обрезанием `.gz`) и `lib/*/libgojni.so`.
4. Проверка `classes.dex` — R8 не вырезал `Dpich`, `EventListener`, `go/Seq`.
5. Запуск на Android-эмуляторе (или реальном девайсе): `adb install -r` → `am start` → проверка отсутствия `FATAL EXCEPTION` / `UnsatisfiedLinkError` в `adb logcat`.

---

## 3. Инструменты отчётности и CI Quality Gate (100% threshold)

### Команды локального запуска

```powershell
# 1. Go-мост: тесты + race detector + детальный отчёт покрытия
cd core\bridge
go test -race -coverprofile=coverage.out .
go tool cover -func=coverage.out
go tool cover -html=coverage.out -o coverage.html

# 2. Android Kotlin: юнит-тесты + JaCoCo XML/HTML отчёт
cd ..\..\android
gradle testDebugUnitTest jacocoTestReport --no-daemon
# Отчёт: android/app/build/reports/jacoco/jacocoTestReport/html/index.html
```

### Порог в CI (GitHub Actions)

- **Go Gate**: парсинг `go tool cover -func=coverage.out` (строка `total:`) → провал билда при `< 100.0%` на `core/bridge` и аддитивных файлах.
- **Kotlin Gate**: задача Gradle `jacocoTestCoverageVerification` с `violationRules { rule { limit { minimum = "1.00".toBigDecimal() } } }` на `com.dpidetector.app.model.*`, `com.dpidetector.app.data.*`, `com.dpidetector.app.ui.*` (с исключением автогенерируемого `BuildConfig`, `R`, `ComposableSingletons`).
- **Мутационное тестирование (высший пилотаж)**: `go-mutesting` для Go и **PITest (`pl.droidsonroids.pitest`)** для Kotlin — гарантирует, что 100% покрытие не «пустое», а реально ловит любые мутации условий и логики.
