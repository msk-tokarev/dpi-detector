// Author: by Ivan Tokarev
package com.dpidetector.app.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun statusLevelMappingCoversAllVerdicts() {
        val goodCodes = listOf("OK", "OK_CUSTOM_HTTP", "NOT_DETECTED")
        goodCodes.forEach { code ->
            assertEquals(code, Status.Level.GOOD, Status.levelOf(code))
        }

        val badCodes = listOf(
            "DETECTED", "PROVIDER_HIJACKING", "RESPONSE_SPOOFING",
            "NXDOMAIN_SPOOFING", "BOOTSTRAP_SPOOFING", "INVALID_HTTPS_CERT",
        )
        badCodes.forEach { code ->
            assertEquals(code, Status.Level.BAD, Status.levelOf(code))
        }

        val neutralCodes = listOf("SKIP", "NOT_SUPPORTED_BY_HOST", "")
        neutralCodes.forEach { code ->
            assertEquals(code, Status.Level.NEUTRAL, Status.levelOf(code))
        }

        val warnCodes = listOf("ERR", "PROVIDER_TIMEOUT", "NO_INTERNET_ACCESS", "INTERNAL_ERR", "UNKNOWN")
        warnCodes.forEach { code ->
            assertEquals(code, Status.Level.WARN, Status.levelOf(code))
        }

        assertEquals(Status.EMPTY, Status.from(null))
    }

    @Test
    fun taskStateParseHandlesAllValues() {
        assertEquals(TaskState.OK, TaskState.parse("ok"))
        assertEquals(TaskState.ERROR, TaskState.parse("error"))
        assertEquals(TaskState.TIMEOUT, TaskState.parse("timeout"))
        assertEquals(TaskState.RUNNING, TaskState.parse("running"))
        assertEquals(TaskState.RUNNING, TaskState.parse("anything_else"))
    }

    @Test
    fun webhostItemParsesTlsSpeedAndSniBlock() {
        val blockedJson = JSONObject(
            """
            {
              "Group": "YouTube Web",
              "Org": "Google LLC",
              "AS": "AS15169",
              "Location": "US",
              "IP": "142.250.74.110",
              "Prefix": "142.250.0.0/15",
              "Alive": {"Msg": "TLS blocked by SNI", "Code": "ERR", "Reason": "CHECKERS_WEBHOST_ERR_BLOCKED_BY_SNI"},
              "Tls": null,
              "Tcp1620": {"Msg": "Skipped", "Code": "SKIP", "Reason": "CHECKERS_WEBHOST_ERR_SKIP"},
              "Siberian": {"Msg": "Skipped", "Code": "SKIP", "Reason": "CHECKERS_WEBHOST_ERR_SKIP"},
              "BurstTxKbps": null,
              "BurstRxKbps": null
            }
            """.trimIndent(),
        )
        val blocked = WebhostItem.from(blockedJson)
        assertTrue(blocked.sniBlocked)
        assertFalse(blocked.reachable)
        assertNull(blocked.tls)
        assertNull(blocked.txKbps)
        assertNull(blocked.rxKbps)

        val okJson = JSONObject(
            """
            {
              "Group": "Cloudflare",
              "Org": "Cloudflare, Inc.",
              "AS": "AS13335",
              "Location": "US",
              "IP": "1.1.1.1",
              "Prefix": "1.1.1.0/24",
              "Alive": {"Msg": "Ok", "Code": "OK", "Reason": "NONE"},
              "Tls": {"V": " 1.3 ", "San": ["*.one.one", "1.1.1.1"], "Cn": "one.one"},
              "Tcp1620": {"Msg": "Detected", "Code": "DETECTED", "Reason": "INETUTIL_ERR_TCP_READ_TIMEOUT"},
              "Siberian": {"Msg": "No", "Code": "OK", "Reason": "NONE"},
              "BurstTxKbps": 12500.5,
              "BurstRxKbps": 4800.0
            }
            """.trimIndent(),
        )
        val ok = WebhostItem.from(okJson)
        assertFalse(ok.sniBlocked)
        assertTrue(ok.reachable)
        assertEquals("1.3", ok.tls?.version)
        assertEquals("one.one", ok.tls?.cn)
        assertEquals(listOf("*.one.one", "1.1.1.1"), ok.tls?.san)
        assertEquals(Status.Level.BAD, ok.tcp1620.level)
        assertEquals(12500.5, ok.txKbps!!, 0.01)
        assertEquals(4800.0, ok.rxKbps!!, 0.01)
    }

    @Test
    fun resultsFromReportParsesCompleteUpstreamAllReport() {
        assertTrue(Results().isEmpty)

        val rawReport = JSONObject(
            """
            {
              "Version": 1,
              "Whoami": {
                "Status": {"Msg": "Ok", "Code": "OK", "Reason": "NONE"},
                "Ip": "203.0.113.10",
                "Subnet": "203.0.113.0/24",
                "Asn": "AS64500",
                "Org": "Test Telecom",
                "Location": "RU",
                "TtlbMs": 42
              },
              "CidrWhitelist": {
                "Status": {"Msg": "You're UNDER one", "Code": "DETECTED", "Reason": "CHECKERS_CIDRWHITELIST_ERR_DETECTED"}
              },
              "Dns": {
                "Leak": {
                  "Status": {"Msg": "Ok", "Code": "OK", "Reason": "NONE"},
                  "Items": [{"Ip": "8.8.8.8", "Subnet": "8.8.8.0/24", "Asn": "AS15169", "Org": "Google", "Location": "US"}]
                },
                "Plain": {
                  "Status": {"Msg": "Ok", "Code": "OK", "Reason": "NONE"},
                  "Providers": {
                    "Cloudflare DNS": [{"Msg": "Provider hijacking", "Code": "PROVIDER_HIJACKING", "Reason": "CHECKERS_DNS_ERR_PROVIDER_HIJACKING"}],
                    "Google DNS": [{"Msg": "Ok", "Code": "OK", "Reason": "NONE"}]
                  }
                },
                "Doh": {
                  "Status": {"Msg": "Ok", "Code": "OK", "Reason": "NONE"},
                  "Providers": {
                    "Quad9": [{"Msg": "Ok", "Code": "OK", "Reason": "NONE"}]
                  }
                }
              },
              "Webhost": {
                "Popular Web Services": {
                  "FarmTimeout": true,
                  "Items": [
                    {
                      "Group": "Github",
                      "Org": "GitHub",
                      "AS": "AS36459",
                      "Location": "US",
                      "IP": "140.82.121.4",
                      "Prefix": "140.82.112.0/20",
                      "Alive": {"Msg": "Ok", "Code": "OK", "Reason": "NONE"},
                      "Tcp1620": {"Msg": "No", "Code": "OK", "Reason": "NONE"},
                      "Siberian": {"Msg": "No", "Code": "OK", "Reason": "NONE"}
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val parsed = Results.fromReport(rawReport)
        assertFalse(parsed.isEmpty)
        assertEquals("203.0.113.10", parsed.whoami?.ip)
        assertEquals(42L, parsed.whoami?.ttlbMs)
        assertEquals("DETECTED", parsed.cidr?.code)
        assertEquals(1, parsed.dnsLeak?.items?.size)
        assertEquals("8.8.8.8", parsed.dnsLeak?.items?.first()?.ip)
        assertEquals(Status.Level.BAD, parsed.dnsPlain?.providers?.get("Cloudflare DNS")?.first()?.level)
        assertEquals(Status.Level.GOOD, parsed.dnsDoh?.providers?.get("Quad9")?.first()?.level)
        assertEquals(1, parsed.sections.size)
        assertEquals(TaskState.TIMEOUT, parsed.sections.first().state)
        assertTrue(parsed.sections.first().farmTimeout)
        assertEquals("Github", parsed.sections.first().items.first().group)
    }

    @Test
    fun jsonArrayObjectsExtensionHandlesNullAndMixedTypes() {
        val nullArr: JSONArray? = null
        assertTrue(nullArr.objects().isEmpty())

        val mixed = JSONArray().apply {
            put(JSONObject().put("k", 1))
            put("not-an-object")
            put(JSONObject().put("k", 2))
        }
        assertEquals(2, mixed.objects().size)
    }
}
