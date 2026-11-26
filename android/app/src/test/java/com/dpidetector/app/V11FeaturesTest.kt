// Author: by Ivan Tokarev
package com.dpidetector.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.dpidetector.app.data.AppUpdater
import com.dpidetector.app.data.BatchItemState
import com.dpidetector.app.data.BatchJobStatus
import com.dpidetector.app.data.BatchQueueItem
import com.dpidetector.app.data.BatchScanStore
import com.dpidetector.app.data.BatchScanUiState
import com.dpidetector.app.data.DpiEngine
import com.dpidetector.app.data.DpichGateway
import com.dpidetector.app.data.EngineUiState
import com.dpidetector.app.data.GeoStatus
import com.dpidetector.app.data.ReleaseUpdateInfo
import com.dpidetector.app.data.ReportSummary
import com.dpidetector.app.data.UpdateHttpTransport
import com.dpidetector.app.data.UpdaterUiState
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
import com.dpidetector.app.service.DpiForegroundService
import com.dpidetector.app.ui.screens.HostProbeScreen
import com.dpidetector.app.ui.screens.ReportsScreen
import com.dpidetector.app.ui.screens.SettingsScreen
import com.dpidetector.app.ui.theme.DpiTheme
import com.dpidetector.app.util.CidrExpandMode
import com.dpidetector.app.util.ExportFormat
import com.dpidetector.app.util.ReportExporter
import com.dpidetector.app.util.SubnetPlanner
import com.dpidetector.core.dpich.EventListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class V11FeaturesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun subnetPlannerHandlesIpv4Ipv6CidrRangesAndLimits() {
        assertTrue(SubnetPlanner.isIpLiteral("1.1.1.1"))
        assertTrue(SubnetPlanner.isIpLiteral("2606:4700:4700::1111"))
        assertTrue(SubnetPlanner.isIpLiteral("[::1]"))
        assertFalse(SubnetPlanner.isIpLiteral("youtube.com"))

        val v4Info = SubnetPlanner.describeCidr("104.16.0.5/30")!!
        assertEquals("104.16.0.4/30", v4Info.input)
        assertEquals("104.16.0.4", v4Info.network)
        assertEquals("104.16.0.7", v4Info.lastAddress)
        assertEquals(4L, v4Info.totalCount.toLong())
        assertEquals(2L, v4Info.usableCount.toLong())

        val v6Info = SubnetPlanner.describeCidr("2606:4700:4700::/126")!!
        assertTrue(v6Info.isIpv6)
        assertEquals(4L, v6Info.totalCount.toLong())

        assertNull(SubnetPlanner.describeCidr("youtube.com/24"))
        assertNull(SubnetPlanner.describeCidr("1.1.1.1/99"))

        val expandedUsable = SubnetPlanner.expandTargets(
            """
            # comment line
            https://youtube.com/watch?v=1, 104.16.0.0/30
            1.1.1.1-3; 1.1.1.10-1.1.1.11
            2606:4700:4700::/127
            """.trimIndent(),
            mode = CidrExpandMode.USABLE,
        ).getOrThrow()

        assertTrue("youtube.com" in expandedUsable)
        assertTrue("104.16.0.1" in expandedUsable)
        assertTrue("104.16.0.2" in expandedUsable)
        assertFalse("104.16.0.0" in expandedUsable)
        assertTrue("1.1.1.2" in expandedUsable)
        assertTrue("1.1.1.11" in expandedUsable)
        assertEquals(10, expandedUsable.size)

        val expandedAll = SubnetPlanner.expandTargets("104.16.0.0/30", mode = CidrExpandMode.ALL).getOrThrow()
        assertEquals(4, expandedAll.size)

        assertTrue(SubnetPlanner.expandTargets("10.0.0.0/16", maxTotal = 100).isFailure)
        assertTrue(SubnetPlanner.expandTargets("1.1.1.5-1.1.1.2").isFailure)
        assertTrue(SubnetPlanner.expandTargets("   ").isFailure)
    }

    @Test
    fun reportExporterFormatsResultsAndBatchToCsvTxtAndJson() {
        val item = WebhostItem(
            group = "YouTube, \"Web\"",
            org = "Google, Inc",
            asn = "AS15169",
            location = "US",
            ip = "2606:4700:4700::1111",
            prefix = "2606:4700::/32",
            alive = Status("TLS blocked by SNI", "ERR", "CHECKERS_WEBHOST_ERR_BLOCKED_BY_SNI"),
            tls = TlsInfo("1.3", listOf("youtube.com"), "youtube.com"),
            tcp1620 = Status("Detected", "DETECTED", "INETUTIL_ERR_TCP_READ_TIMEOUT"),
            siberian = Status("No", "OK", "NONE"),
            txKbps = 4200.5,
            rxKbps = 1200.0,
        )
        val res = Results(
            whoami = Whoami(Status("Ok", "OK", "NONE"), "203.0.113.9", "203.0.113.0/24", "AS64512", "ISP", "RU", 20),
            cidr = Status("Under", "DETECTED", "CHECKERS_CIDRWHITELIST_ERR_DETECTED"),
            dnsLeak = DnsLeak(Status("Ok", "OK", "NONE"), listOf(IpInfo("8.8.8.8", "8.8.8.0/24", "AS15169", "Google", "US"))),
            dnsPlain = DnsReport(Status("Ok", "OK", "NONE"), mapOf("CF" to listOf(Status("Ok", "OK", "NONE")))),
            dnsDoh = DnsReport(Status("Ok", "OK", "NONE"), mapOf("Google" to listOf(Status("Ok", "OK", "NONE")))),
            sections = listOf(SectionResult(0, "Popular", listOf(item), TaskState.OK)),
        )

        val csv = ReportExporter.resultsToCsv(res)
        assertTrue(csv.contains("\"YouTube, \"\"Web\"\"\""))
        assertTrue(csv.contains("WHOAMI,External IP,203.0.113.9"))
        assertTrue(csv.contains("CIDR_WHITELIST"))

        val txt = ReportExporter.resultsToTxt(res, "test_run")
        assertTrue(txt.contains("by Ivan Tokarev"))
        assertTrue(txt.contains("SNI=BLOCKED"))

        val queue = listOf(
            BatchQueueItem(0, "youtube.com", 443, "", BatchItemState.DONE, item = item),
            BatchQueueItem(1, "dead.host", 8443, "sni", BatchItemState.FAILED, error = "timeout"),
        )
        val bCsv = ReportExporter.batchToCsv(queue)
        assertTrue(bCsv.contains("youtube.com"))
        assertTrue(bCsv.contains("dead.host"))
        val bTxt = ReportExporter.batchToTxt(queue)
        assertTrue(bTxt.contains("Ошибок: 1"))
        val bJson = JSONObject(ReportExporter.batchToJson(queue))
        assertEquals(2, bJson.getInt("Total"))
    }

    @Test
    fun batchScanStorePersistsRecoversAndRetries() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = BatchScanStore(ctx)
        store.clearAll()

        store.replaceQueue(listOf("a.com", "b.com"), 443, "")
        val c1 = store.claimNextQueued()!!
        assertEquals("a.com", c1.target)

        // Simulate crash while item 0 is RUNNING and job is RUNNING
        assertTrue(store.recoverInterrupted())
        val recoveredSnap = store.snapshot(recoveredAfterRestart = true)
        assertEquals(BatchJobStatus.PAUSED, recoveredSnap.status)
        assertEquals(BatchItemState.QUEUED, recoveredSnap.items.first().state)

        // Complete 1st as DONE, 2nd as FAILED, then retryFailed
        store.setJobStatus(BatchJobStatus.RUNNING)
        val first = store.claimNextQueued()!!
        store.markItemResult(
            first.idx,
            BatchItemState.DONE,
            """{"Group":"a.com","IP":"1.1.1.1","Alive":{"Msg":"Ok","Code":"OK","Reason":"NONE"},"Tcp1620":{"Msg":"No","Code":"OK","Reason":"NONE"},"Siberian":{"Msg":"No","Code":"OK","Reason":"NONE"}}""",
            null,
        )
        val second = store.claimNextQueued()!!
        store.markItemResult(second.idx, BatchItemState.FAILED, null, "DNS fail")
        assertNull(store.claimNextQueued())

        val retried = store.retryFailed()
        assertEquals(1, retried)
        assertEquals("b.com", store.claimNextQueued()?.target)

        store.clearAll()
        assertEquals(0, store.snapshot().total)
        store.close()
    }

    @Test
    fun appUpdaterChecksDownloadsAndVerifiesSha256() {
        assertTrue(AppUpdater.isNewerVersion("1.1.0", "v1.2.0"))
        assertFalse(AppUpdater.isNewerVersion("1.1.0", "v1.1.0"))
        assertFalse(AppUpdater.isNewerVersion("1.2.0", "v1.1.9"))

        val payloadBytes = "fake-apk-binary-content".toByteArray()
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(payloadBytes)
            .joinToString("") { "%02x".format(it) }

        val sumsMap = AppUpdater.parseSha256Sums("$expectedHash  dpi-detector-arm64-v8a.apk\n")
        assertEquals(expectedHash, sumsMap["dpi-detector-arm64-v8a.apk"])

        val fakeTransport = object : UpdateHttpTransport {
            override fun getText(url: String): String {
                if (url.endsWith("SHA256SUMS.txt")) {
                    return "$expectedHash  dpi-detector-arm64-v8a.apk\n"
                }
                return """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://github.com/msk-tokarev/dpi-detector/releases/tag/v1.2.0",
                  "body": "New IPv6 & batch scanner",
                  "assets": [
                    {"name": "dpi-detector-arm64-v8a.apk", "browser_download_url": "https://example.test/app.apk", "size": ${payloadBytes.size}},
                    {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.test/SHA256SUMS.txt", "size": 90}
                  ]
                }
                """.trimIndent()
            }

            override fun downloadToFile(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
                dest.parentFile?.mkdirs()
                dest.writeBytes(payloadBytes)
                onProgress(payloadBytes.size.toLong(), payloadBytes.size.toLong())
            }
        }

        val info = AppUpdater.checkLatest("1.1.0", listOf("arm64-v8a"), fakeTransport)!!
        assertEquals("v1.2.0", info.tagName)
        assertEquals(expectedHash, info.expectedSha256)

        val (downloaded, actualSha) = AppUpdater.downloadAndVerify(info, tmp.newFolder("upd"), fakeTransport)
        assertTrue(downloaded.exists())
        assertEquals(expectedHash, actualSha)

        // Mismatched SHA-256 must throw and delete temp file
        val badInfo = info.copy(expectedSha256 = "0".repeat(64))
        val badDir = tmp.newFolder("bad_upd")
        var threw = false
        try {
            AppUpdater.downloadAndVerify(badInfo, badDir, fakeTransport)
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(badDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun dpiEngineBatchExportUpdaterAndForegroundService() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = BatchScanStore(ctx).apply { clearAll() }
        val apkBytes = "apk-v1.2".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

        val fakeGateway = object : DpichGateway {
            var failTarget = "fail.test"
            override fun setup(filesDir: String, cacheDir: String, userYaml: String) {}
            override fun sections(): String = "[]"
            override fun geolitePaths(): String = ""
            override fun markBundledGeoTimestamp(unixSec: Long) {}
            override fun geoStatus(): String = """{"loaded":true,"due":false,"updatedAt":1700000000}"""
            override fun prepareGeo(force: Boolean, allowOnline: Boolean, listener: EventListener) {}
            override fun start(optsJson: String, listener: EventListener) {}
            override fun cancel() {}
            override fun checkCustomHost(target: String, sni: String, port: Int): String {
                if (target == failTarget) throw RuntimeException("reset")
                return """{"Group":"$target","IP":"1.1.1.1","Alive":{"Msg":"Ok","Code":"OK","Reason":"NONE"},"Tcp1620":{"Msg":"Detected","Code":"DETECTED","Reason":"INETUTIL_ERR_TCP_READ_TIMEOUT"},"Siberian":{"Msg":"No","Code":"OK","Reason":"NONE"}}"""
            }
            override fun validateConfig(userYaml: String): String = ""
            override fun defaultConfigYaml(): String = ""
            override fun reportsDir(): String = tmp.root.absolutePath
        }

        val fakeTransport = object : UpdateHttpTransport {
            override fun getText(url: String): String = """
                {
                  "tag_name": "v1.3.0",
                  "html_url": "https://github.com/msk-tokarev/dpi-detector/releases/tag/v1.3.0",
                  "body": "notes",
                  "assets": [
                    {"name": "dpi-detector-universal.apk", "browser_download_url": "https://example.test/u.apk", "size": ${apkBytes.size}, "digest": "sha256:$sha"}
                  ]
                }
            """.trimIndent()

            override fun downloadToFile(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
                dest.parentFile?.mkdirs()
                dest.writeBytes(apkBytes)
                onProgress(apkBytes.size.toLong(), apkBytes.size.toLong())
            }
        }

        val engine = DpiEngine(
            appContext = ctx,
            gateway = fakeGateway,
            ioDispatcher = UnconfinedTestDispatcher(),
            autoStart = false,
            batchStore = store,
            updateTransport = fakeTransport,
            enableForegroundSync = true,
        )
        engine.bootstrap()

        // Batch scan + retry failed + export CSV/TXT/JSON + stop
        engine.startBatchScan("ok.test, fail.test", port = 443, sni = "", concurrency = 1)
        assertEquals(BatchJobStatus.COMPLETED, engine.state.value.batch.status)
        assertEquals(1, engine.state.value.batch.failed)
        assertEquals(2, engine.state.value.batch.issues)

        val batchCsv = engine.exportBatchResults(ExportFormat.CSV).getOrThrow()
        assertTrue(batchCsv.readText().contains("ok.test"))
        val batchTxt = engine.exportBatchResults(ExportFormat.TXT).getOrThrow()
        assertTrue(batchTxt.exists())
        val batchJson = engine.exportBatchResults(ExportFormat.JSON).getOrThrow()
        assertTrue(batchJson.exists())

        fakeGateway.failTarget = "none"
        engine.retryFailedBatchItems(concurrency = 1)
        assertEquals(0, engine.state.value.batch.failed)

        // Export saved report to CSV and TXT
        val repFile = File(tmp.root, "results_1.json").apply {
            writeText("""{"Version":1,"CidrWhitelist":{"Status":{"Msg":"No","Code":"NOT_DETECTED","Reason":"NONE"}}}""")
        }
        assertTrue(engine.exportReportFile(repFile, ExportFormat.CSV).getOrThrow().exists())
        assertTrue(engine.exportReportFile(repFile, ExportFormat.TXT).getOrThrow().exists())

        // Updater check + download
        engine.checkForUpdates(currentVersion = "1.1.0")
        assertNotNull(engine.state.value.updater.availableUpdate)
        engine.downloadUpdate()
        assertEquals(sha, engine.state.value.updater.verifiedSha256)

        // ForegroundService notification builder coverage
        val svc = Robolectric.setupService(DpiForegroundService::class.java)
        val nBatch = svc.buildNotification(
            engine.state.value.copy(batch = engine.state.value.batch.copy(status = BatchJobStatus.RUNNING, etaSeconds = 12L)),
        )
        assertNotNull(nBatch)
        val nScan = svc.buildNotification(engine.state.value.copy(running = true))
        assertNotNull(nScan)

        engine.stopBatchScan()
        assertEquals(BatchJobStatus.IDLE, engine.state.value.batch.status)
        store.close()
    }

    @Test
    fun composeScreensRenderBatchModeCsvExportAndUpdater() {
        var batchStarted = false
        var exportedFmt: ExportFormat? = null
        var checkUpdatesClicked = false

        val batchState = BatchScanUiState(
            status = BatchJobStatus.PAUSED,
            items = listOf(BatchQueueItem(0, "1.1.1.1", 443, "", BatchItemState.FAILED, error = "timeout")),
            total = 1,
            completed = 1,
            failed = 1,
            issues = 1,
            recoveredAfterRestart = true,
        )
        val st = EngineUiState(
            ready = true,
            geo = GeoStatus(loaded = true),
            batch = batchState,
            updater = UpdaterUiState(
                currentVersion = "1.1.0",
                availableUpdate = ReleaseUpdateInfo("v1.2.0", "1.2.0", "", "notes", "dpi-detector-arm64-v8a.apk", "https://x", 100L, "abcdef1234567890"),
                downloadedApkPath = "/tmp/u.apk",
                verifiedSha256 = "abcdef1234567890",
            ),
        )

        composeRule.setContent {
            DpiTheme {
                HostProbeScreen(
                    state = st,
                    onProbe = { _, _, _ -> },
                    onClearHistory = {},
                    onStartBatch = { _, _, _, _ -> batchStarted = true },
                    onExportBatch = { exportedFmt = it },
                )
            }
        }

        composeRule.onNodeWithText("Пакетный / CIDR (1/1)").performClick()
        composeRule.onNodeWithText("Очередь восстановлена после перезапуска").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CSV").performScrollTo().performClick()
        assertEquals(ExportFormat.CSV, exportedFmt)
    }

    @Test
    fun settingsScreenRendersUpdaterDownloadedApkAndInstallButton() {
        var installPath: String? = null
        val st = EngineUiState(
            ready = true,
            geo = GeoStatus(loaded = true),
            updater = UpdaterUiState(
                currentVersion = "1.1.0",
                availableUpdate = ReleaseUpdateInfo(
                    tagName = "v1.2.0",
                    versionName = "1.2.0",
                    htmlUrl = "",
                    notes = "notes",
                    apkName = "dpi-detector-arm64-v8a.apk",
                    apkUrl = "https://x",
                    apkSizeBytes = 100L,
                    expectedSha256 = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                ),
                downloadedApkPath = "/tmp/u.apk",
                verifiedSha256 = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            ),
        )
        composeRule.setContent {
            DpiTheme {
                SettingsScreen(
                    state = st,
                    onUpdateGeo = {},
                    onSaveConfig = { null },
                    onLoadDefaultYaml = { "" },
                    onInstallUpdate = { installPath = it },
                )
            }
        }
        composeRule.onNodeWithText("Установить обновление APK").performScrollTo().performClick()
        assertEquals("/tmp/u.apk", installPath)
    }
}
