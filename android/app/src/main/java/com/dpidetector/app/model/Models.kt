package com.dpidetector.app.model

import org.json.JSONArray
import org.json.JSONObject

/** Mirrors fullcheck.StatusDto from dpi-ch. */
data class Status(val msg: String, val code: String, val reason: String) {
    val level: Level get() = levelOf(code)

    enum class Level { GOOD, BAD, WARN, NEUTRAL }

    companion object {
        val EMPTY = Status("", "", "")

        fun from(o: JSONObject?): Status =
            if (o == null) EMPTY else Status(o.optString("Msg"), o.optString("Code"), o.optString("Reason"))

        fun levelOf(code: String): Level = when (code) {
            "OK", "OK_CUSTOM_HTTP", "NOT_DETECTED" -> Level.GOOD
            "DETECTED", "PROVIDER_HIJACKING", "RESPONSE_SPOOFING", "NXDOMAIN_SPOOFING",
            "BOOTSTRAP_SPOOFING", "INVALID_HTTPS_CERT" -> Level.BAD
            "SKIP", "NOT_SUPPORTED_BY_HOST", "" -> Level.NEUTRAL
            else -> Level.WARN
        }
    }
}

data class Whoami(
    val status: Status,
    val ip: String,
    val subnet: String,
    val asn: String,
    val org: String,
    val location: String,
    val ttlbMs: Long,
) {
    companion object {
        fun from(o: JSONObject) = Whoami(
            Status.from(o.optJSONObject("Status")),
            o.optString("Ip"), o.optString("Subnet"), o.optString("Asn"),
            o.optString("Org"), o.optString("Location"), o.optLong("TtlbMs"),
        )
    }
}

data class IpInfo(val ip: String, val subnet: String, val asn: String, val org: String, val location: String) {
    companion object {
        fun from(o: JSONObject) = IpInfo(
            o.optString("Ip"), o.optString("Subnet"), o.optString("Asn"),
            o.optString("Org"), o.optString("Location"),
        )
    }
}

data class DnsLeak(val status: Status, val items: List<IpInfo>) {
    companion object {
        fun from(o: JSONObject) = DnsLeak(
            Status.from(o.optJSONObject("Status")),
            o.optJSONArray("Items").objects().map(IpInfo::from),
        )
    }
}

data class DnsReport(val status: Status, val providers: Map<String, List<Status>>) {
    companion object {
        fun from(o: JSONObject): DnsReport {
            val p = o.optJSONObject("Providers")
            val map = sortedMapOf<String, List<Status>>(String.CASE_INSENSITIVE_ORDER)
            p?.keys()?.forEach { k -> map[k] = p.optJSONArray(k).objects().map { Status.from(it) } }
            return DnsReport(Status.from(o.optJSONObject("Status")), map)
        }
    }
}

data class TlsInfo(val version: String, val san: List<String>, val cn: String)

data class WebhostItem(
    val group: String,
    val org: String,
    val asn: String,
    val location: String,
    val ip: String,
    val prefix: String,
    val alive: Status,
    val tls: TlsInfo?,
    val tcp1620: Status,
    val siberian: Status,
    val txKbps: Double?,
    val rxKbps: Double?,
) {
    val sniBlocked get() = alive.msg.contains("blocked by SNI", ignoreCase = true)
    val reachable get() = alive.level == Status.Level.GOOD

    companion object {
        fun from(o: JSONObject): WebhostItem {
            val t = o.optJSONObject("Tls")
            return WebhostItem(
                group = o.optString("Group"),
                org = o.optString("Org"),
                asn = o.optString("AS"),
                location = o.optString("Location"),
                ip = o.optString("IP"),
                prefix = o.optString("Prefix"),
                alive = Status.from(o.optJSONObject("Alive")),
                tls = t?.let {
                    TlsInfo(
                        it.optString("V").trim(),
                        it.optJSONArray("San")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList(),
                        it.optString("Cn"),
                    )
                },
                tcp1620 = Status.from(o.optJSONObject("Tcp1620")),
                siberian = Status.from(o.optJSONObject("Siberian")),
                txKbps = if (o.isNull("BurstTxKbps")) null else o.optDouble("BurstTxKbps"),
                rxKbps = if (o.isNull("BurstRxKbps")) null else o.optDouble("BurstRxKbps"),
            )
        }
    }
}

enum class TaskState { PENDING, RUNNING, OK, ERROR, TIMEOUT;
    companion object {
        fun parse(s: String) = when (s) {
            "ok" -> OK; "error" -> ERROR; "timeout" -> TIMEOUT; else -> RUNNING
        }
    }
}

data class SectionResult(
    val index: Int,
    val name: String,
    val items: List<WebhostItem> = emptyList(),
    val state: TaskState = TaskState.PENDING,
    val farmTimeout: Boolean = false,
    val error: String = "",
)

/** Results of one run (live or loaded from an ALL report). */
data class Results(
    val whoami: Whoami? = null,
    val cidr: Status? = null,
    val dnsLeak: DnsLeak? = null,
    val dnsPlain: DnsReport? = null,
    val dnsDoh: DnsReport? = null,
    val sections: List<SectionResult> = emptyList(),
    val tasks: Map<String, TaskState> = emptyMap(),
) {
    val isEmpty get() = whoami == null && cidr == null && dnsLeak == null && dnsPlain == null &&
        dnsDoh == null && sections.isEmpty()

    companion object {
        /** Parses an upstream-compatible ALL report (dpich --all json). */
        fun fromReport(o: JSONObject): Results {
            val dns = o.optJSONObject("Dns")
            val wh = o.optJSONObject("Webhost")
            val sections = mutableListOf<SectionResult>()
            var i = 0
            wh?.keys()?.forEach { name ->
                val s = wh.optJSONObject(name) ?: return@forEach
                sections += SectionResult(
                    index = i++,
                    name = name,
                    items = s.optJSONArray("Items").objects().map(WebhostItem::from),
                    state = if (s.optBoolean("FarmTimeout")) TaskState.TIMEOUT else TaskState.OK,
                    farmTimeout = s.optBoolean("FarmTimeout"),
                )
            }
            return Results(
                whoami = o.optJSONObject("Whoami")?.let(Whoami::from),
                cidr = o.optJSONObject("CidrWhitelist")?.let { Status.from(it.optJSONObject("Status")) },
                dnsLeak = dns?.optJSONObject("Leak")?.let(DnsLeak::from),
                dnsPlain = dns?.optJSONObject("Plain")?.let(DnsReport::from),
                dnsDoh = dns?.optJSONObject("Doh")?.let(DnsReport::from),
                sections = sections,
            )
        }
    }
}

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
