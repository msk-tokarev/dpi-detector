// Author: by Ivan Tokarev
package com.dpidetector.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.dpidetector.app.DpiDetectorApp
import com.dpidetector.app.data.DpiEngine
import com.dpidetector.app.data.DpichGateway
import com.dpidetector.app.data.EngineUiState
import com.dpidetector.core.dpich.EventListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import com.dpidetector.app.data.GeoStatus
import com.dpidetector.app.data.HostProbeEntry
import com.dpidetector.app.data.NetState
import com.dpidetector.app.data.ReportSummary
import com.dpidetector.app.data.SectionMeta
import com.dpidetector.app.model.DnsLeak
import com.dpidetector.app.model.DnsReport
import com.dpidetector.app.model.IpInfo
import com.dpidetector.app.model.Results
import com.dpidetector.app.model.SectionResult
import com.dpidetector.app.model.Status
import com.dpidetector.app.model.TaskState
import com.dpidetector.app.model.TlsInfo
import com.dpidetector.app.model.WebhostItem
import com.dpidetector.app.model.Whoami
import com.dpidetector.app.ui.screens.HostProbeScreen
import com.dpidetector.app.ui.screens.ReportsScreen
import com.dpidetector.app.ui.screens.ResultsDashboard
import com.dpidetector.app.ui.screens.ScannerScreen
import com.dpidetector.app.ui.screens.SettingsScreen
import com.dpidetector.app.ui.theme.DpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class UiScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleResults(): Results = Results(
        whoami = Whoami(Status("Ok", "OK", "NONE"), "203.0.113.7", "203.0.113.0/24", "AS64512", "Karandash ISP", "RU", 28),
        cidr = Status("Not detected", "NOT_DETECTED", "NONE"),
        dnsLeak = DnsLeak(
            Status("Ok", "OK", "NONE"),
            listOf(IpInfo("8.8.8.8", "8.8.8.0/24", "AS15169", "Google LLC", "US")),
        ),
        dnsPlain = DnsReport(
            Status("Ok", "OK", "NONE"),
            mapOf(
                "Cloudflare DNS" to listOf(Status("Provider hijacking", "PROVIDER_HIJACKING", "CHECKERS_DNS_ERR_PROVIDER_HIJACKING")),
                "Skipped DNS" to listOf(Status("Skip", "SKIP", "CHECKERS_DNS_ERR_SKIP")),
            ),
        ),
        dnsDoh = DnsReport(
            Status("Ok", "OK", "NONE"),
            mapOf("Google DNS" to listOf(Status("Ok", "OK", "NONE"))),
        ),
        sections = listOf(
            SectionResult(
                index = 0,
                name = "Popular Web Services",
                state = TaskState.OK,
                items = listOf(
                    WebhostItem(
                        group = "YouTube Web",
                        org = "Google LLC",
                        asn = "AS15169",
                        location = "US",
                        ip = "142.250.74.110",
                        prefix = "142.250.0.0/15",
                        alive = Status("TLS blocked by SNI", "ERR", "CHECKERS_WEBHOST_ERR_BLOCKED_BY_SNI"),
                        tls = null,
                        tcp1620 = Status("Skipped", "SKIP", "CHECKERS_WEBHOST_ERR_SKIP"),
                        siberian = Status("Skipped", "SKIP", "CHECKERS_WEBHOST_ERR_SKIP"),
                        txKbps = null,
                        rxKbps = null,
                    ),
                    WebhostItem(
                        group = "Cloudflare",
                        org = "Cloudflare Inc.",
                        asn = "AS13335",
                        location = "US",
                        ip = "1.1.1.1",
                        prefix = "1.1.1.0/24",
                        alive = Status("Ok", "OK", "NONE"),
                        tls = TlsInfo("1.3", listOf("*.one.one", "1.1.1.1", "a", "b", "c"), "one.one"),
                        tcp1620 = Status("Detected", "DETECTED", "INETUTIL_ERR_TCP_READ_TIMEOUT"),
                        siberian = Status("Detected", "DETECTED", "INETUTIL_ERR_TLS_HANDSHAKE_TIMEOUT"),
                        txKbps = 9500.0,
                        rxKbps = 2400.0,
                    ),
                ),
            ),
        ),
        tasks = mapOf("whoami" to TaskState.OK, "cidrwhitelist" to TaskState.ERROR, "dnsLeak" to TaskState.TIMEOUT),
    )

    @Test
    fun scannerScreenRendersVpnWarningDashboardTabsAndControls() {
        var started = false
        var errorCleared = false
        var shared = false
        val state = EngineUiState(
            ready = true,
            geo = GeoStatus(loaded = true, due = false, updatedAt = 1700000000L),
            running = false,
            sections = listOf(SectionMeta(0, "Popular Web Services", "Popular", listOf("YouTube"))),
            selectedSections = setOf(0),
            results = sampleResults(),
            lastReportPath = "/tmp/report.json",
            logs = listOf("[00:00:01] Проверка завершена"),
            errorBanner = "Тестовое предупреждение",
        )

        composeRule.setContent {
            DpiTheme {
                ScannerScreen(
                    state = state,
                    net = NetState(online = true, transport = "Wi-Fi", vpnActive = true),
                    onToggleChecker = {},
                    onToggleSection = {},
                    onStart = { started = true },
                    onCancel = {},
                    onClearError = { errorCleared = true },
                    onShareReport = { shared = true },
                    onRetryGeo = {},
                )
            }
        }

        composeRule.onNodeWithText("Обнаружен активный VPN").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Закрыть").performScrollTo().performClick()
        assertTrue(errorCleared)

        composeRule.onNodeWithText("Запустить проверку DPI").performScrollTo().performClick()
        assertTrue(started)

        composeRule.onNodeWithText("Поделиться отчётом JSON (dpi-ch формат)").performScrollTo().performClick()
        assertTrue(shared)

        composeRule.onNodeWithText("Журнал событий (1)").performScrollTo().performClick()
        composeRule.onNodeWithText("Обнаружена DPI-фильтрация трафика").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Karandash ISP").performScrollTo().assertIsDisplayed()

        // Switch results tabs: Хосты -> filter & expand -> DNS
        composeRule.onNodeWithText("Хосты").performScrollTo().performClick()
        composeRule.onNodeWithText("YouTube Web").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Только проблемы").performScrollTo().performClick()
        composeRule.onNodeWithText("Cloudflare").performScrollTo().performClick()
        composeRule.onNodeWithText("TLS 1.3").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("DNS").performScrollTo().performClick()
        composeRule.onNodeWithText("DNS Leak (фактические резолверы)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Перехват провайдера").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun scannerScreenRunningGeoLoadingAndErrors() {
        var cancelled = false
        var retriedGeo = false
        val state = EngineUiState(
            ready = true,
            initError = "Init boom",
            geo = GeoStatus(loaded = false, loading = false, error = "no file"),
            running = true,
            results = Results(
                cidr = Status("Detected", "DETECTED", "CHECKERS_CIDRWHITELIST_ERR_DETECTED"),
                tasks = mapOf("whoami" to TaskState.RUNNING),
            ),
        )

        composeRule.setContent {
            DpiTheme {
                ScannerScreen(
                    state = state,
                    net = NetState(online = false, transport = "Нет сети", vpnActive = false),
                    onToggleChecker = {},
                    onToggleSection = {},
                    onStart = {},
                    onCancel = { cancelled = true },
                    onClearError = {},
                    onShareReport = {},
                    onRetryGeo = { retriedGeo = true },
                )
            }
        }

        composeRule.onNodeWithText("Init boom").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Скачать базу GeoLite").performScrollTo().performClick()
        assertTrue(retriedGeo)
        composeRule.onNodeWithText("Остановить проверку").performScrollTo().performClick()
        assertTrue(cancelled)
        composeRule.onNodeWithText("Обнаружен режим «белых списков» (CIDR)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun resultsDashboardCoversCleanAndEmptyTabs() {
        val clean = Results(
            cidr = Status("No inet", "NO_INTERNET_ACCESS", "CHECKERS_CIDRWHITELIST_ERR_NO_INET_ACCESS"),
        )
        composeRule.setContent {
            DpiTheme {
                ResultsDashboard(results = clean, running = false)
            }
        }
        composeRule.onNodeWithText("Явных DPI-ограничений не обнаружено").assertIsDisplayed()
        composeRule.onNodeWithText("Хосты").performClick()
        composeRule.onNodeWithText("Нет данных по секциям Webhost").assertIsDisplayed()
        composeRule.onNodeWithText("DNS").performClick()
        composeRule.onNodeWithText("Нет данных DNS-проверок").assertIsDisplayed()
    }

    @Test
    fun hostProbeScreenPresetInputAndHistory() {
        var probedTarget = ""
        var probedPort = 0
        var cleared = false

        val state = EngineUiState(
            ready = true,
            geo = GeoStatus(loaded = true),
            hostProbes = listOf(
                HostProbeEntry(
                    id = 1L,
                    query = "1.1.1.1",
                    port = 443,
                    sni = "",
                    timestamp = 1L,
                    error = "Таймаут TCP",
                ),
            ),
        )

        composeRule.setContent {
            DpiTheme {
                HostProbeScreen(
                    state = state,
                    onProbe = { t, _, p -> probedTarget = t; probedPort = p },
                    onClearHistory = { cleared = true },
                )
            }
        }

        composeRule.onNodeWithText("discord.com").performScrollTo().performClick()
        composeRule.onNodeWithText("Проверить хост").performScrollTo().performClick()
        assertEquals("discord.com", probedTarget)
        assertEquals(443, probedPort)

        composeRule.onNodeWithText("Таймаут TCP").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Очистить").performScrollTo().performClick()
        assertTrue(cleared)
    }

    @Test
    fun reportsScreenEmptyOpenAndShare() {
        var sharedName = ""
        var deleted = false
        val dummyFile = File("results_2026-11-24.json")
        val reports = listOf(
            ReportSummary(
                file = dummyFile,
                name = dummyFile.name,
                modifiedAt = 1700000000000L,
                sizeBytes = 4096,
                providerHint = "Karandash ISP · 203.0.113.7",
                issuesCount = 2,
            ),
        )

        composeRule.setContent {
            DpiTheme {
                ReportsScreen(
                    reports = reports,
                    onRefresh = {},
                    onLoadReport = { Result.success(sampleResults()) },
                    onDeleteReport = { deleted = true },
                    onShareReport = { sharedName = it.name },
                )
            }
        }

        composeRule.onNodeWithText("Проблем: 2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Поделиться").performScrollTo().performClick()
        assertEquals("results_2026-11-24.json", sharedName)
        composeRule.onNodeWithContentDescription("Удалить").performScrollTo().performClick()
        assertTrue(deleted)

        composeRule.onNodeWithText("Karandash ISP · 203.0.113.7").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Обнаружена DPI-фильтрация трафика").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Назад").performScrollTo().performClick()
        composeRule.onNodeWithText("Сохранённые отчёты (1)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settingsScreenShowsAuthorAttributionConfigSaveAndDefaultDialog() {
        var savedYaml: String? = null
        var geoUpdated = false

        val state = EngineUiState(
            ready = true,
            geo = GeoStatus(loaded = true, updatedAt = 1700000000L, error = "minor warn"),
            userYaml = "",
        )

        composeRule.setContent {
            DpiTheme {
                SettingsScreen(
                    state = state,
                    onUpdateGeo = { geoUpdated = true },
                    onSaveConfig = { yaml -> savedYaml = yaml; null },
                    onLoadDefaultYaml = { "checkers:\n  webhost:\n    workers: 8" },
                )
            }
        }

        composeRule.onNodeWithText("Обновить базу GeoLite с GitHub").performScrollTo().performClick()
        assertTrue(geoUpdated)

        composeRule.onNodeWithText("Применить").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("", savedYaml)

        composeRule.onNodeWithText("Сбросить").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("by Ivan Tokarev").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Посмотреть встроенный default.yaml").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("default.yaml (только чтение)").assertIsDisplayed()
        composeRule.onNodeWithText("Закрыть").performClick()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun mainAppNavigationSwitchesAcrossAllFourTabs() {
        val dummyGateway = object : DpichGateway {
            override fun setup(filesDir: String, cacheDir: String, userYaml: String) {}
            override fun sections(): String = "[]"
            override fun geolitePaths(): String = ""
            override fun markBundledGeoTimestamp(unixSec: Long) {}
            override fun geoStatus(): String = """{"loaded":true,"due":false,"updatedAt":0}"""
            override fun prepareGeo(force: Boolean, allowOnline: Boolean, listener: EventListener) {}
            override fun start(optsJson: String, listener: EventListener) {}
            override fun cancel() {}
            override fun checkCustomHost(target: String, sni: String, port: Int): String = "{}"
            override fun validateConfig(userYaml: String): String = ""
            override fun defaultConfigYaml(): String = ""
            override fun reportsDir(): String = ""
        }
        val engine = DpiEngine(
            appContext = ApplicationProvider.getApplicationContext(),
            gateway = dummyGateway,
            ioDispatcher = UnconfinedTestDispatcher(),
            autoStart = false,
        )
        engine.bootstrap()

        composeRule.setContent {
            DpiTheme {
                DpiDetectorApp(engine = engine, onShareReport = {})
            }
        }

        composeRule.onNodeWithText("DPI Детектор").assertIsDisplayed()
        composeRule.onNodeWithText("Хост").performClick()
        composeRule.onNodeWithText("Проверка своего хоста").assertIsDisplayed()
        composeRule.onNodeWithText("Отчёты").performClick()
        composeRule.onNodeWithText("Сохранённые отчёты (0)").assertIsDisplayed()
        composeRule.onNodeWithText("Настройки").performClick()
        composeRule.onNodeWithText("База GeoLite2 (ASN + Страны)").assertIsDisplayed()
        composeRule.onNodeWithText("Сканер").performClick()
        composeRule.onNodeWithText("Модули диагностики").assertIsDisplayed()
    }
}
