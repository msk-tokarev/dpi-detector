// Author: by Ivan Tokarev
package com.dpidetector.app.util

import com.dpidetector.app.data.BatchItemState
import com.dpidetector.app.data.BatchQueueItem
import com.dpidetector.app.model.Results
import com.dpidetector.app.model.WebhostItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    JSON("json", "application/json", "JSON"),
    CSV("csv", "text/csv", "CSV"),
    TXT("txt", "text/plain", "TXT"),
}

object ReportExporter {

    fun csvCell(value: String?): String {
        val s = value ?: ""
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return "\"" + s.replace("\"", "\"\"") + "\""
        }
        return s
    }

    private fun fmtKbps(v: Double?): String =
        if (v == null || v.isNaN()) "" else String.format(Locale.US, "%.1f", v)

    fun resultsToCsv(results: Results): String {
        val sb = StringBuilder()
        sb.append("Category,Section_Or_Provider,Target,IP,Prefix,ASN,Org,Location,Alive_Or_Status,SNI_Blocked,TLS,TCP_16_20,Siberian,Tx_Kbps,Rx_Kbps\n")

        results.whoami?.let { w ->
            sb.append(
                listOf(
                    "WHOAMI", "External IP", w.ip, w.ip, w.subnet, w.asn, w.org, w.location,
                    w.status.code, "", "", "", "", "", "",
                ).joinToString(",") { csvCell(it) },
            ).append('\n')
        }

        results.cidr?.let { c ->
            sb.append(
                listOf(
                    "CIDR_WHITELIST", "Whitelist", "", "", "", "", "", "",
                    c.code, "", "", "", "", "", "",
                ).joinToString(",") { csvCell(it) },
            ).append('\n')
        }

        results.dnsLeak?.items?.forEach { leak ->
            sb.append(
                listOf(
                    "DNS_LEAK", "Resolver", leak.ip, leak.ip, leak.subnet, leak.asn, leak.org, leak.location,
                    results.dnsLeak.status.code, "", "", "", "", "", "",
                ).joinToString(",") { csvCell(it) },
            ).append('\n')
        }

        results.dnsPlain?.providers?.forEach { (prov, sts) ->
            sb.append(
                listOf(
                    "DNS_PLAIN", prov, prov, "", "", "", "", "",
                    sts.joinToString(";") { it.code }, "", "", "", "", "", "",
                ).joinToString(",") { csvCell(it) },
            ).append('\n')
        }

        results.dnsDoh?.providers?.forEach { (prov, sts) ->
            sb.append(
                listOf(
                    "DNS_DOH", prov, prov, "", "", "", "", "",
                    sts.joinToString(";") { it.code }, "", "", "", "", "", "",
                ).joinToString(",") { csvCell(it) },
            ).append('\n')
        }

        results.sections.forEach { sec ->
            sec.items.forEach { item ->
                sb.append(webhostRowCsv("WEBHOST", sec.name, item)).append('\n')
            }
        }
        return sb.toString()
    }

    private fun webhostRowCsv(category: String, section: String, item: WebhostItem): String =
        listOf(
            category,
            section,
            item.group,
            item.ip,
            item.prefix,
            item.asn,
            item.org,
            item.location,
            item.alive.code.ifEmpty { item.alive.msg },
            if (item.sniBlocked) "YES" else "NO",
            item.tls?.version ?: "",
            item.tcp1620.code,
            item.siberian.code,
            fmtKbps(item.txKbps),
            fmtKbps(item.rxKbps),
        ).joinToString(",") { csvCell(it) }

    fun resultsToTxt(results: Results, title: String = "DPI Detector Report"): String {
        val sb = StringBuilder()
        sb.appendLine("=== $title (by Ivan Tokarev) ===")
        results.whoami?.let { w ->
            sb.appendLine("Внешний IP: ${w.ip} (${w.asn} ${w.org}, ${w.subnet}, ${w.location}) [${w.status.code}]")
        }
        results.cidr?.let { c ->
            sb.appendLine("CIDR Белые списки: ${c.code} (${c.msg})")
        }
        results.dnsLeak?.let { dl ->
            val ips = dl.items.joinToString(", ") { "${it.ip} (${it.org})" }.ifEmpty { "нет данных" }
            sb.appendLine("DNS Leak: $ips")
        }
        results.dnsPlain?.let { dp ->
            sb.appendLine("--- Plain DNS ---")
            dp.providers.forEach { (p, sts) ->
                sb.appendLine("  $p: ${sts.joinToString(", ") { it.code }}")
            }
        }
        results.dnsDoh?.let { dd ->
            sb.appendLine("--- DNS over HTTPS (DoH) ---")
            dd.providers.forEach { (p, sts) ->
                sb.appendLine("  $p: ${sts.joinToString(", ") { it.code }}")
            }
        }
        results.sections.forEach { sec ->
            sb.appendLine("--- [${sec.name}] (${sec.items.size}) ---")
            sec.items.forEach { item ->
                val flags = buildList {
                    add("Alive=${item.alive.code}")
                    if (item.sniBlocked) add("SNI=BLOCKED")
                    add("TCP16-20=${item.tcp1620.code}")
                    add("Siberian=${item.siberian.code}")
                    item.txKbps?.let { add("Tx=${fmtKbps(it)}kbps") }
                }.joinToString(" | ")
                sb.appendLine("  ${item.group} (${item.ip}) -> $flags")
            }
        }
        return sb.toString()
    }

    fun batchToCsv(items: List<BatchQueueItem>): String {
        val sb = StringBuilder()
        sb.append("Index,Target,Port,SNI,Queue_State,IP,ASN,Org,Location,Alive,SNI_Blocked,TLS,TCP_16_20,Siberian,Tx_Kbps,Rx_Kbps,Error\n")
        items.forEach { q ->
            val i = q.item
            val row = listOf(
                (q.idx + 1).toString(),
                q.target,
                q.port.toString(),
                q.sni,
                q.state.name,
                i?.ip ?: "",
                i?.asn ?: "",
                i?.org ?: "",
                i?.location ?: "",
                i?.alive?.code ?: "",
                if (i?.sniBlocked == true) "YES" else if (i != null) "NO" else "",
                i?.tls?.version ?: "",
                i?.tcp1620?.code ?: "",
                i?.siberian?.code ?: "",
                fmtKbps(i?.txKbps),
                fmtKbps(i?.rxKbps),
                q.error ?: "",
            )
            sb.append(row.joinToString(",") { csvCell(it) }).append('\n')
        }
        return sb.toString()
    }

    fun batchToTxt(items: List<BatchQueueItem>): String {
        val sb = StringBuilder()
        val done = items.count { it.state == BatchItemState.DONE }
        val failed = items.count { it.state == BatchItemState.FAILED }
        sb.appendLine("=== Пакетное сканирование DPI Detector (by Ivan Tokarev) ===")
        sb.appendLine("Всего: ${items.size} | Проверено: $done | Ошибок: $failed")
        sb.appendLine("------------------------------------------------------------")
        items.forEach { q ->
            val i = q.item
            if (i != null) {
                val flags = buildList {
                    add("Alive=${i.alive.code}")
                    if (i.sniBlocked) add("SNI=BLOCKED")
                    add("TCP16-20=${i.tcp1620.code}")
                    add("Siberian=${i.siberian.code}")
                    i.txKbps?.let { add("Tx=${fmtKbps(it)}kbps") }
                }.joinToString(" | ")
                sb.appendLine("[${q.state}] ${q.target}:${q.port} (${i.ip}) -> $flags")
            } else {
                sb.appendLine("[${q.state}] ${q.target}:${q.port} -> ${q.error ?: "в очереди"}")
            }
        }
        return sb.toString()
    }

    fun batchToJson(items: List<BatchQueueItem>): String {
        val root = JSONObject()
        root.put("Author", "by Ivan Tokarev")
        root.put("Total", items.size)
        val arr = JSONArray()
        items.forEach { q ->
            val o = JSONObject()
            o.put("index", q.idx)
            o.put("target", q.target)
            o.put("port", q.port)
            o.put("sni", q.sni)
            o.put("state", q.state.name)
            if (q.error != null) o.put("error", q.error)
            q.item?.let { i ->
                o.put("ip", i.ip)
                o.put("asn", i.asn)
                o.put("org", i.org)
                o.put("location", i.location)
                o.put("alive", i.alive.code)
                o.put("sniBlocked", i.sniBlocked)
                o.put("tcp1620", i.tcp1620.code)
                o.put("siberian", i.siberian.code)
                if (i.txKbps != null) o.put("txKbps", i.txKbps)
                if (i.rxKbps != null) o.put("rxKbps", i.rxKbps)
            }
            arr.put(o)
        }
        root.put("Items", arr)
        return root.toString(2)
    }
}
