// Author: by Ivan Tokarev
package com.dpidetector.app.util

import java.math.BigInteger
import java.net.InetAddress

enum class CidrExpandMode(val label: String) {
    USABLE("Без сети и broadcast"),
    ALL("Все адреса"),
}

data class CidrInfo(
    val input: String,
    val network: String,
    val lastAddress: String,
    val prefixLength: Int,
    val totalCount: BigInteger,
    val usableCount: BigInteger,
    val isIpv6: Boolean,
)

object SubnetPlanner {
    const val DEFAULT_MAX_TARGETS = 4096

    /** Pure literal check so InetAddress.getByName never triggers DNS resolution. */
    fun isIpLiteral(raw: String): Boolean {
        val s = raw.trim().removeSurrounding("[", "]")
        if (s.isEmpty()) return false
        val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(s)
        if (ipv4 != null) {
            return ipv4.groupValues.drop(1).all { it.toIntOrNull() in 0..255 }
        }
        if (':' !in s) return false
        return s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    private fun parseLiteralBytes(ip: String): ByteArray? {
        val clean = ip.trim().removeSurrounding("[", "]")
        if (!isIpLiteral(clean)) return null
        return runCatching { InetAddress.getByName(clean).address }.getOrNull()
    }

    private fun bytesToAddressString(bytes: ByteArray): String =
        InetAddress.getByAddress(bytes).hostAddress ?: ""

    private fun bigIntToFixedBytes(value: BigInteger, byteLen: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(byteLen)
        val copyLen = minOf(raw.size, byteLen)
        System.arraycopy(raw, raw.size - copyLen, out, byteLen - copyLen, copyLen)
        return out
    }

    fun describeCidr(raw: String): CidrInfo? {
        val trimmed = raw.trim()
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.lastIndex) return null
        val ipPart = trimmed.substring(0, slash).trim()
        val prefix = trimmed.substring(slash + 1).trim().toIntOrNull() ?: return null
        val bytes = parseLiteralBytes(ipPart) ?: return null
        val bits = bytes.size * 8
        if (prefix !in 0..bits) return null

        val addrInt = BigInteger(1, bytes)
        val hostBits = bits - prefix
        val allOnes = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        val hostMask = if (hostBits == 0) BigInteger.ZERO else BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE)
        val netMask = allOnes.xor(hostMask)

        val netInt = addrInt.and(netMask)
        val lastInt = netInt.or(hostMask)
        val total = BigInteger.ONE.shiftLeft(hostBits)
        val isIpv6 = bytes.size == 16
        val usable = if (!isIpv6 && prefix <= 30 && total > BigInteger.valueOf(2)) {
            total.subtract(BigInteger.valueOf(2))
        } else {
            total
        }

        return CidrInfo(
            input = "${bytesToAddressString(bigIntToFixedBytes(netInt, bytes.size))}/$prefix",
            network = bytesToAddressString(bigIntToFixedBytes(netInt, bytes.size)),
            lastAddress = bytesToAddressString(bigIntToFixedBytes(lastInt, bytes.size)),
            prefixLength = prefix,
            totalCount = total,
            usableCount = usable,
            isIpv6 = isIpv6,
        )
    }

    fun normalizeSingleTarget(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("https://").removePrefix("http://")
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        return s.trim()
    }

    /**
     * Expands multi-line / comma / space separated list of domains, IPv4, IPv6, CIDR subnets,
     * and IPv4 ranges (e.g. `1.1.1.1-1.1.1.5` or `1.1.1.1-5`) into a deduplicated target list.
     */
    fun expandTargets(
        rawText: String,
        mode: CidrExpandMode = CidrExpandMode.USABLE,
        maxTotal: Int = DEFAULT_MAX_TARGETS,
    ): Result<List<String>> = runCatching {
        val tokens = rawText
            .lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .flatMap { line -> line.split(',', ';', ' ', '\t').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        require(tokens.isNotEmpty()) { "Введите хотя бы один домен, IP или подсеть CIDR" }

        val out = LinkedHashSet<String>()
        for (token in tokens) {
            if ('/' in token && !token.startsWith("http://", ignoreCase = true) && !token.startsWith("https://", ignoreCase = true)) {
                val info = describeCidr(token)
                    ?: throw IllegalArgumentException("Некорректная подсеть CIDR: $token")
                val countToAdd = if (mode == CidrExpandMode.USABLE) info.usableCount else info.totalCount
                if (countToAdd > BigInteger.valueOf(maxTotal.toLong()) ||
                    out.size.toLong() + countToAdd.toLong() > maxTotal.toLong()
                ) {
                    throw IllegalArgumentException(
                        "Подсеть ${info.input} содержит $countToAdd адресов — превышен лимит очереди ($maxTotal)",
                    )
                }
                val bytes = parseLiteralBytes(info.network)!!
                val byteLen = bytes.size
                val netInt = BigInteger(1, bytes)
                val skipEdges = mode == CidrExpandMode.USABLE && !info.isIpv6 && info.prefixLength <= 30 && info.totalCount > BigInteger.valueOf(2)
                val startOffset = if (skipEdges) 1L else 0L
                val totalLong = info.totalCount.toLong()
                val endExclusive = if (skipEdges) totalLong - 1L else totalLong
                for (offset in startOffset until endExclusive) {
                    val cur = netInt.add(BigInteger.valueOf(offset))
                    out.add(bytesToAddressString(bigIntToFixedBytes(cur, byteLen)))
                }
                continue
            }

            // IPv4 range: 1.1.1.1-1.1.1.10 or 1.1.1.1-10
            if ('-' in token && isIpLiteral(token.substringBefore('-'))) {
                val left = token.substringBefore('-').trim()
                val right = token.substringAfter('-').trim()
                val startBytes = parseLiteralBytes(left)
                if (startBytes != null && startBytes.size == 4) {
                    val endBytes = if (right.all { it.isDigit() }) {
                        val lastOctet = right.toIntOrNull()
                        if (lastOctet == null || lastOctet !in 0..255) {
                            throw IllegalArgumentException("Некорректный диапазон IP: $token")
                        }
                        byteArrayOf(startBytes[0], startBytes[1], startBytes[2], lastOctet.toByte())
                    } else {
                        parseLiteralBytes(right)?.takeIf { it.size == 4 }
                            ?: throw IllegalArgumentException("Некорректный диапазон IP: $token")
                    }
                    val startInt = BigInteger(1, startBytes).toLong()
                    val endInt = BigInteger(1, endBytes).toLong()
                    if (endInt < startInt) {
                        throw IllegalArgumentException("Конец диапазона меньше начала: $token")
                    }
                    val span = endInt - startInt + 1L
                    if (span > maxTotal || out.size + span > maxTotal) {
                        throw IllegalArgumentException("Диапазон $token превышает лимит очереди ($maxTotal)")
                    }
                    for (cur in startInt..endInt) {
                        out.add(bytesToAddressString(bigIntToFixedBytes(BigInteger.valueOf(cur), 4)))
                    }
                    continue
                }
            }

            val normalized = normalizeSingleTarget(token)
            if (normalized.isNotEmpty()) {
                if (out.size >= maxTotal) {
                    throw IllegalArgumentException("Превышен лимит очереди ($maxTotal целей)")
                }
                out.add(normalized)
            }
        }
        require(out.isNotEmpty()) { "Список целей пуст" }
        out.toList()
    }
}
