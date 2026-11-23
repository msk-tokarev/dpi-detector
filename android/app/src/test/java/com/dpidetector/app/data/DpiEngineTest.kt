// Author: by Ivan Tokarev
package com.dpidetector.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dpidetector.app.model.TaskState
import com.dpidetector.core.dpich.EventListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DpiEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: Context
    private lateinit var fake: FakeDpichGateway
    private lateinit var reportsDir: File

    private class FakeDpichGateway(private val baseDir: File) : DpichGateway {
        var setupError: Throwable? = null
        var prepareGeoError: Throwable? = null
        var startError: Throwable? = null
        var validateResult: String = ""
        var customHostResult: String = """{"Group":"test.com","Org":"CF","AS":"AS13335","Location":"US","IP":"1.1.1.1","Prefix":"1.1.1.0/24","Alive":{"Msg":"Ok","Code":"OK","Reason":"NONE"},"Tcp1620":{"Msg":"No","Code":"OK","Reason":"NONE"},"Siberian":{"Msg":"No","Code":"OK","Reason":"NONE"}}"""
        var customHostError: Throwable? = null
        var lastStartOpts: String? = null
        var cancelCalled = false
        var markedTs: Long = 0L
        var geoPathsOverride: String? = null
        var lastListener: EventListener? = null

        override fun setup(filesDir: String, cacheDir: String, userYaml: String) {
            setupError?.let { throw it }
        }

        override fun sections(): String = """[
            {"index":0,"name":"Popular Web Services","desc":"web","targets":["YouTube","Discord"]},
            {"index":1,"name":"Foreign Infrastructure Providers","desc":"infra","targets":["Cloudflare"]}
        ]"""

        override fun geolitePaths(): String = geoPathsOverride ?: listOf(
            File(baseDir, "geo/cidr-as.csv").absolutePath,
            File(baseDir, "geo/cidr-country.csv").absolutePath,
            File(baseDir, "geo/geonameid-country.csv").absolutePath,
        ).joinToString("\n")

        override fun markBundledGeoTimestamp(unixSec: Long) {
            markedTs = unixSec
        }

        override fun geoStatus(): String = """{"loaded":true,"due":false,"updatedAt":1700000000}"""

        override fun prepareGeo(force: Boolean, allowOnline: Boolean, listener: EventListener) {
            prepareGeoError?.let { throw it }
            listener.onEvent("""{"type":"log","msg":"geo ready"}""")
        }

        override fun start(optsJson: String, listener: EventListener) {
            lastStartOpts = optsJson
            lastListener = listener
            startError?.let { throw it }
        }

        override fun cancel() {
            cancelCalled = true
        }

        override fun checkCustomHost(target: String, sni: String, port: Int): String {
            customHostError?.let { throw it }
            return customHostResult
        }

        override fun validateConfig(userYaml: String): String = validateResult
        override fun defaultConfigYaml(): String = "checkers:\n  webhost:\n    workers: 8\n"
        override fun reportsDir(): String = File(baseDir, "reports").apply { mkdirs() }.absolutePath
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("dpidetector_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        fake = FakeDpichGateway(tmp.root)
        reportsDir = File(tmp.root, "reports").apply { mkdirs() }
    }

    private fun newEngine(): DpiEngine = DpiEngine(
        appContext = ctx,
        gateway = fake,
        ioDispatcher = UnconfinedTestDispatcher(),
        autoStart = false,
    )

    @Test
    fun bootstrapSuccessAndSetupError() {
        // Error branch
        fake.setupError = IllegalStateException("broken core")
        val failEngine = newEngine()
        failEngine.bootstrap()
        assertFalse(failEngine.state.value.ready)
        assertTrue(failEngine.state.value.initError!!.contains("broken core"))

        // Success branch (pre-create geo files so extractBundledGeoIfMissing hits skip path, then test unpack)
        fake.setupError = null
        val engine = newEngine()
        engine.bootstrap()
        assertTrue(engine.state.value.ready)
        assertEquals(2, engine.state.value.sections.size)
        assertTrue(engine.state.value.geo.loaded)
        assertEquals(1700000000L, engine.state.value.geo.updatedAt)

        // Geo error branch
        fake.prepareGeoError = RuntimeException("offline")
        engine.loadGeo(force = true, allowOnline = true)
        assertEquals("offline", engine.state.value.geo.error)
    }

    @Test
    fun moduleTogglesStartCancelAndAllStreamEvents() {
        val engine = newEngine()
        engine.bootstrap()

        // Toggle checkers (cannot uncheck the very last one)
        engine.toggleChecker("whoami")
        assertFalse("whoami" in engine.state.value.selectedCheckers)
        engine.toggleChecker("whoami")
        assertTrue("whoami" in engine.state.value.selectedCheckers)

        // Toggle sections
        engine.toggleSection(0)
        assertEquals(setOf(1), engine.state.value.selectedSections)
        engine.toggleSection(1)
        assertTrue(engine.state.value.selectedSections.isEmpty())
        assertFalse("webhost" in engine.state.value.selectedCheckers)
        engine.toggleSection(0)
        assertTrue("webhost" in engine.state.value.selectedCheckers)

        // Start error branch
        fake.startError = RuntimeException("already busy")
        engine.startCheck()
        assertFalse(engine.state.value.running)
        assertEquals("already busy", engine.state.value.errorBanner)
        engine.clearError()
        assertNull(engine.state.value.errorBanner)

        // Normal start + full event stream
        fake.startError = null
        engine.startCheck()
        assertTrue(engine.state.value.running)
        assertNotNull(fake.lastStartOpts)

        val l = fake.lastListener!!
        l.onEvent("not-valid-json")
        l.onEvent("""{"type":"started","checkers":["whoami","cidrwhitelist","dns","webhost"],"sections":[{"index":0,"name":"Popular Web Services"}]}""")
        l.onEvent("""{"type":"log","msg":"running test"}""")
        l.onEvent("""{"type":"whoami","data":{"Status":{"Msg":"Ok","Code":"OK","Reason":"NONE"},"Ip":"1.2.3.4","Subnet":"1.2.3.0/24","Asn":"AS123","Org":"ISP","Location":"RU","TtlbMs":15}}""")
        l.onEvent("""{"type":"task","task":"whoami","index":0,"state":"ok"}""")
        l.onEvent("""{"type":"cidr","data":{"Status":{"Msg":"You're UNDER one","Code":"DETECTED","Reason":"CHECKERS_CIDRWHITELIST_ERR_DETECTED"}}}""")
        l.onEvent("""{"type":"task","task":"cidrwhitelist","index":0,"state":"ok"}""")
        l.onEvent("""{"type":"dnsLeak","data":{"Status":{"Msg":"Ok","Code":"OK","Reason":"NONE"},"Items":[{"Ip":"8.8.8.8","Subnet":"8.8.8.0/24","Asn":"AS15169","Org":"Google","Location":"US"}]}}""")
        l.onEvent("""{"type":"dnsPlain","data":{"Status":{"Msg":"Ok","Code":"OK","Reason":"NONE"},"Providers":{"Google DNS":[{"Msg":"Ok","Code":"OK","Reason":"NONE"}]}}}""")
        l.onEvent("""{"type":"dnsDoh","data":{"Status":{"Msg":"Ok","Code":"OK","Reason":"NONE"},"Providers":{"Cloudflare DNS":[{"Msg":"Bootstrap spoofing","Code":"BOOTSTRAP_SPOOFING","Reason":"CHECKERS_DNS_ERR_DOH_BOOTSTRAP_SPOOFING"}]}}}""")
        l.onEvent("""{"type":"webhostItem","index":0,"section":"Popular Web Services","data":{"Group":"YouTube","Org":"Google","AS":"AS15169","Location":"US","IP":"142.250.1.1","Prefix":"142.250.0.0/16","Alive":{"Msg":"TLS blocked by SNI","Code":"ERR","Reason":"CHECKERS_WEBHOST_ERR_BLOCKED_BY_SNI"},"Tcp1620":{"Msg":"Skipped","Code":"SKIP","Reason":"CHECKERS_WEBHOST_ERR_SKIP"},"Siberian":{"Msg":"Skipped","Code":"SKIP","Reason":"CHECKERS_WEBHOST_ERR_SKIP"}}}""")
        // Also test dynamic section creation when index wasn't pre-registered
        l.onEvent("""{"type":"webhostItem","index":9,"section":"Dynamic","data":{"Group":"Extra","Org":"Org","AS":"AS1","Location":"DE","IP":"2.2.2.2","Prefix":"2.2.2.0/24","Alive":{"Msg":"Ok","Code":"OK","Reason":"NONE"},"Tcp1620":{"Msg":"No","Code":"OK","Reason":"NONE"},"Siberian":{"Msg":"No","Code":"OK","Reason":"NONE"}}}""")
        l.onEvent("""{"type":"webhostDone","index":0,"section":"Popular Web Services","farmTimeout":true,"error":"partial"}""")
        l.onEvent("""{"type":"task","task":"webhost","index":0,"state":"timeout"}""")
        l.onEvent("""{"type":"error","msg":"non-fatal warning"}""")

        // Ring buffer truncation (>400 log lines)
        repeat(410) { i -> l.onEvent("""{"type":"log","msg":"line-$i"}""") }
        assertEquals(400, engine.state.value.logs.size)

        // Cancel & done
        engine.cancelCheck()
        assertTrue(fake.cancelCalled)
        l.onEvent("""{"type":"done","path":"/tmp/report.json","cancelled":false}""")

        val st = engine.state.value
        assertFalse(st.running)
        assertEquals("/tmp/report.json", st.lastReportPath)
        assertEquals("1.2.3.4", st.results.whoami?.ip)
        assertEquals("DETECTED", st.results.cidr?.code)
        assertEquals(1, st.results.dnsLeak?.items?.size)
        assertEquals(TaskState.TIMEOUT, st.results.sections.first().state)
        assertTrue(st.results.sections.first().farmTimeout)
        assertEquals("partial", st.results.sections.first().error)
        // Unfinished dynamic section gets marked ERROR on done
        assertEquals(TaskState.ERROR, st.results.sections.last().state)
    }

    @Test
    fun customHostProbeReportsAndConfigSave() = runTest {
        val engine = newEngine()
        engine.bootstrap()

        // Blank query is ignored
        engine.probeCustomHost("   ", "", 443)
        assertTrue(engine.state.value.hostProbes.isEmpty())

        // Success probe + invalid port clamp
        engine.probeCustomHost("example.com", "custom.sni", 0)
        assertEquals(1, engine.state.value.hostProbes.size)
        assertEquals(443, engine.state.value.hostProbes.first().port)
        assertNotNull(engine.state.value.hostProbes.first().item)

        // Error probe
        fake.customHostError = RuntimeException("DNS failed")
        engine.probeCustomHost("bad.host", "", 8443)
        assertEquals(2, engine.state.value.hostProbes.size)
        assertEquals("DNS failed", engine.state.value.hostProbes.first().error)

        engine.clearHostProbes()
        assertTrue(engine.state.value.hostProbes.isEmpty())

        // Reports summary, load & delete
        val repFile = File(reportsDir, "results_2026-11-24_00-00-00.json")
        repFile.writeText(
            """
            {
              "Version": 1,
              "Whoami": {"Status":{"Msg":"Ok","Code":"OK","Reason":"NONE"},"Ip":"1.1.1.1","Subnet":"1.1.1.0/24","Asn":"AS13335","Org":"CF","Location":"US","TtlbMs":10},
              "CidrWhitelist": {"Status":{"Msg":"Under","Code":"DETECTED","Reason":"CHECKERS_CIDRWHITELIST_ERR_DETECTED"}},
              "Dns": {
                "Plain": {"Status":{"Msg":"Ok","Code":"OK","Reason":"NONE"},"Providers":{"G":[{"Msg":"Spoof","Code":"RESPONSE_SPOOFING","Reason":"CHECKERS_DNS_ERR_RESOLVE_SPOOFING"}]}}
              },
              "Webhost": {
                "Popular": {
                  "FarmTimeout": false,
                  "Items": [{"Group":"YT","Org":"G","AS":"AS1","Location":"US","IP":"1.2.3.4","Prefix":"1.2.3.0/24","Alive":{"Msg":"TLS blocked by SNI","Code":"ERR","Reason":"CHECKERS_WEBHOST_ERR_BLOCKED_BY_SNI"},"Tcp1620":{"Msg":"No","Code":"OK","Reason":"NONE"},"Siberian":{"Msg":"No","Code":"OK","Reason":"NONE"}}]
                }
              }
            }
            """.trimIndent(),
        )
        engine.refreshReports()
        assertEquals(1, engine.state.value.reports.size)
        assertEquals(3, engine.state.value.reports.first().issuesCount)
        assertTrue(engine.state.value.reports.first().providerHint.contains("CF"))

        val loaded = engine.loadReport(repFile)
        assertTrue(loaded.isSuccess)
        assertEquals("1.1.1.1", loaded.getOrThrow().whoami?.ip)

        engine.deleteReport(repFile)
        assertTrue(engine.state.value.reports.isEmpty())

        // Config save: validation error, setup error, success, default yaml
        fake.validateResult = "bad syntax"
        assertEquals("bad syntax", engine.saveUserConfig("bad"))

        fake.validateResult = ""
        fake.setupError = RuntimeException("apply err")
        assertEquals("apply err", engine.saveUserConfig("debug: true"))

        fake.setupError = null
        assertNull(engine.saveUserConfig("debug: true"))
        assertEquals("debug: true", engine.state.value.userYaml)
        assertTrue(engine.loadDefaultConfigYaml().contains("checkers:"))
    }
}
