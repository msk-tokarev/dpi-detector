// Author: by Ivan Tokarev
package com.dpidetector.app.data

import android.os.Build
import com.dpidetector.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseUpdateInfo(
    val tagName: String,
    val versionName: String,
    val htmlUrl: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val expectedSha256: String?,
)

data class UpdaterUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progressPercent: Int = 0,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val availableUpdate: ReleaseUpdateInfo? = null,
    val upToDate: Boolean = false,
    val downloadedApkPath: String? = null,
    val verifiedSha256: String? = null,
    val error: String? = null,
)

interface UpdateHttpTransport {
    fun getText(url: String): String
    fun downloadToFile(url: String, dest: File, onProgress: (downloaded: Long, total: Long) -> Unit)
}

object DefaultUpdateHttpTransport : UpdateHttpTransport {
    override fun getText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DpiDetector-Android/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code при обращении к GitHub Releases")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    override fun downloadToFile(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DpiDetector-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code при скачивании APK")
            }
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}

object AppUpdater {
    const val LATEST_RELEASE_API = "https://api.github.com/repos/msk-tokarev/dpi-detector/releases/latest"

    fun parseVersionParts(raw: String): List<Int> {
        val clean = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        return clean.split('.').map { it.toIntOrNull() ?: 0 }
    }

    fun isNewerVersion(currentVersion: String, candidateTag: String): Boolean {
        val cur = parseVersionParts(currentVersion)
        val cand = parseVersionParts(candidateTag)
        val maxLen = maxOf(cur.size, cand.size)
        for (i in 0 until maxLen) {
            val a = cur.getOrElse(i) { 0 }
            val b = cand.getOrElse(i) { 0 }
            if (b != a) return b > a
        }
        return false
    }

    fun parseSha256Sums(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split(Regex("""\s+"""), limit = 2)
            if (parts.size == 2) {
                val hash = parts[0].lowercase()
                val file = parts[1].removePrefix("*").trim()
                if (hash.length == 64 && file.isNotEmpty()) {
                    map[file] = hash
                }
            }
        }
        return map
    }

    fun selectBestApkAsset(
        assets: JSONArray,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList() ?: listOf("arm64-v8a"),
    ): JSONObject? {
        val apkAssets = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null

        for (abi in supportedAbis) {
            val match = apkAssets.firstOrNull { it.optString("name").contains(abi, ignoreCase = true) }
            if (match != null) return match
        }
        return apkAssets.firstOrNull { it.optString("name").contains("universal", ignoreCase = true) }
            ?: apkAssets.first()
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun checkLatest(
        currentVersion: String = BuildConfig.VERSION_NAME,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList() ?: listOf("arm64-v8a"),
        transport: UpdateHttpTransport = DefaultUpdateHttpTransport,
        apiUrl: String = LATEST_RELEASE_API,
    ): ReleaseUpdateInfo? {
        val root = JSONObject(transport.getText(apiUrl))
        val tagName = root.optString("tag_name").trim()
        if (tagName.isEmpty() || !isNewerVersion(currentVersion, tagName)) {
            return null
        }
        val assets = root.optJSONArray("assets") ?: JSONArray()
        val chosen = selectBestApkAsset(assets, supportedAbis)
            ?: throw IllegalStateException("В релизе $tagName не найден APK-файл")

        val apkName = chosen.optString("name")
        val apkUrl = chosen.optString("browser_download_url")
        val apkSize = chosen.optLong("size", 0L)

        // 1. Check GitHub's native asset digest ("sha256:...")
        var expectedSha: String? = chosen.optString("digest")
            .takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.lowercase()
            ?.takeIf { it.length == 64 }

        // 2. Fallback to SHA256SUMS.txt asset if present
        if (expectedSha == null) {
            val sumsAsset = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").equals("SHA256SUMS.txt", ignoreCase = true) }
            if (sumsAsset != null) {
                val sumsUrl = sumsAsset.optString("browser_download_url")
                if (sumsUrl.isNotEmpty()) {
                    expectedSha = runCatching {
                        parseSha256Sums(transport.getText(sumsUrl))[apkName]
                    }.getOrNull()
                }
            }
        }

        return ReleaseUpdateInfo(
            tagName = tagName,
            versionName = tagName.removePrefix("v").removePrefix("V"),
            htmlUrl = root.optString("html_url"),
            notes = root.optString("body").trim(),
            apkName = apkName,
            apkUrl = apkUrl,
            apkSizeBytes = apkSize,
            expectedSha256 = expectedSha,
        )
    }

    fun downloadAndVerify(
        info: ReleaseUpdateInfo,
        updatesDir: File,
        transport: UpdateHttpTransport = DefaultUpdateHttpTransport,
        onProgress: (Int) -> Unit = {},
    ): Pair<File, String> {
        updatesDir.mkdirs()
        val tmpFile = File(updatesDir, "${info.apkName}.part")
        val finalFile = File(updatesDir, info.apkName)
        tmpFile.delete()

        val totalHint = info.apkSizeBytes
        transport.downloadToFile(info.apkUrl, tmpFile) { downloaded, contentLen ->
            val denom = if (contentLen > 0) contentLen else totalHint
            if (denom > 0) {
                val pct = ((downloaded * 100L) / denom).toInt().coerceIn(0, 100)
                onProgress(pct)
            }
        }

        val actualSha = sha256Hex(tmpFile)
        if (info.expectedSha256 != null && !actualSha.equals(info.expectedSha256, ignoreCase = true)) {
            tmpFile.delete()
            throw IllegalStateException(
                "Контрольная сумма SHA-256 не совпала (ожидалась ${info.expectedSha256.take(12)}…, получена ${actualSha.take(12)}…)",
            )
        }

        if (finalFile.exists()) finalFile.delete()
        if (!tmpFile.renameTo(finalFile)) {
            tmpFile.copyTo(finalFile, overwrite = true)
            tmpFile.delete()
        }
        onProgress(100)
        return finalFile to actualSha
    }
}
