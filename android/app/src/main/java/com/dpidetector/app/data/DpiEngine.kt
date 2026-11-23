package com.dpidetector.app.data

import android.content.Context
import com.dpidetector.app.model.DnsLeak
import com.dpidetector.app.model.DnsReport
import com.dpidetector.app.model.Results
import com.dpidetector.app.model.SectionResult
import com.dpidetector.app.model.Status
import com.dpidetector.app.model.TaskState
import com.dpidetector.app.model.WebhostItem
import com.dpidetector.app.model.Whoami
import com.dpidetector.app.model.objects
import com.dpidetector.core.dpich.EventListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SectionMeta(
    val index: Int,
    val name: String,
    val desc: String,
    val targets: List<String>,
)

data class GeoStatus(
    val loaded: Boolean = false,
    val due: Boolean = true,
    val updatedAt: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ReportSummary(
    val file: File,
    val name: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val providerHint: String = "",
    val issuesCount: Int = 0,
)

data class HostProbeEntry(
    val id: Long,
    val query: String,
    val port: Int,
    val sni: String,
    val timestamp: Long,
    val item: WebhostItem? = null,
    val error: String? = null,
)

data class EngineUiState(
    val ready: Boolean = false,
    val initError: String? = null,
    val geo: GeoStatus = GeoStatus(),
    val running: Boolean = false,
    val sections: List<SectionMeta> = emptyList(),
    val selectedCheckers: Set<String> = setOf("whoami", "cidrwhitelist", "dns", "webhost"),
    val selectedSections: Set<Int> = emptySet(), // empty => all
    val results: Results = Results(),
    val lastReportPath: String? = null,
    val logs: List<String> = emptyList(),
    val errorBanner: String? = null,
    val reports: List<ReportSummary> = emptyList(),
    val hostProbeBusy: Boolean = false,
    val hostProbes: List<HostProbeEntry> = emptyList(),
    val userYaml: String = "",
)

class DpiEngine(
    private val appContext: Context,
    private val gateway: DpichGateway = RealDpichGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext),
    autoStart: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val prefs = appContext.getSharedPreferences("dpidetector_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(EngineUiState())
    val state: StateFlow<EngineUiState> = _state.asStateFlow()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val listener = EventListener { raw ->
        handleEvent(raw)
    }

    init {
        if (autoStart) {
            scope.launch {
                bootstrap()
            }
        }
    }

    internal fun bootstrap() {
        val savedYaml = prefs.getString("user_yaml", "") ?: ""
        _state.update { it.copy(userYaml = savedYaml) }

        try {
            gateway.setup(
                appContext.filesDir.absolutePath,
                appContext.cacheDir.absolutePath,
                savedYaml,
            )
        } catch (t: Throwable) {
            _state.update { it.copy(initError = "Ошибка инициализации ядра: ${t.message}") }
            return
        }

        val sections = parseSections(gateway.sections())
        _state.update {
            it.copy(
                ready = true,
                initError = null,
                sections = sections,
                selectedSections = sections.map { s -> s.index }.toSet(),
            )
        }

        refreshReports()
        extractBundledGeoIfMissing()
        loadGeo(force = false, allowOnline = false)
    }

    private fun extractBundledGeoIfMissing() {
        try {
            val paths = gateway.geolitePaths().lines().filter { it.isNotBlank() }
            if (paths.size != 3) return
            val assetNames = listOf(
                "geolite/cidr-as.csv",
                "geolite/cidr-country.csv",
                "geolite/geonameid-country.csv",
            )
            var unpackedAny = false
            for (i in 0..2) {
                val dest = File(paths[i])
                if (dest.exists() && dest.length() > 0L) continue
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".tmp")
                appContext.assets.open(assetNames[i]).use { raw ->
                    tmp.outputStream().use { out -> raw.copyTo(out, bufferSize = 64 * 1024) }
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                unpackedAny = true
            }
            if (unpackedAny) {
                val ts = runCatching {
                    appContext.assets.open("geolite/bundled-ts").bufferedReader().use { it.readText().trim().toLong() }
                }.getOrDefault(0L)
                if (ts > 0L) gateway.markBundledGeoTimestamp(ts)
                appendLog("geoip: распакована встроенная оффлайн-база GeoLite")
            }
        } catch (t: Throwable) {
            appendLog("geoip: встроенная база не распакована (${t.message})")
        }
    }

    fun loadGeo(force: Boolean, allowOnline: Boolean) {
        scope.launch {
            _state.update { it.copy(geo = it.geo.copy(loading = true, error = null)) }
            try {
                gateway.prepareGeo(force, allowOnline, listener)
                val status = parseGeoStatus(gateway.geoStatus())
                _state.update { it.copy(geo = status.copy(loading = false, error = null)) }
            } catch (t: Throwable) {
                val status = runCatching { parseGeoStatus(gateway.geoStatus()) }.getOrDefault(GeoStatus())
                _state.update { it.copy(geo = status.copy(loading = false, error = t.message)) }
                appendLog("geoip ошибка: ${t.message}")
            }
        }
    }

    fun toggleChecker(id: String) {
        _state.update { s ->
            val next = s.selectedCheckers.toMutableSet()
            if (!next.add(id) && next.size > 1) next.remove(id)
            s.copy(selectedCheckers = next)
        }
    }

    fun toggleSection(index: Int) {
        _state.update { s ->
            val next = s.selectedSections.toMutableSet()
            if (!next.add(index)) next.remove(index)
            val checkers = s.selectedCheckers.toMutableSet()
            if (next.isEmpty()) checkers.remove("webhost") else checkers.add("webhost")
            s.copy(selectedSections = next, selectedCheckers = checkers)
        }
    }

    fun startCheck() {
        networkMonitor.refresh()
        val cur = _state.value
        if (!cur.ready || cur.running) return

        val checkers = cur.selectedCheckers.toList()
        if (checkers.isEmpty()) {
            _state.update { it.copy(errorBanner = "Выберите хотя бы один модуль проверки") }
            return
        }

        val opts = JSONObject().apply {
            put("checkers", JSONArray(checkers))
            if (cur.selectedSections.isNotEmpty() && cur.selectedSections.size < cur.sections.size) {
                put("sections", JSONArray(cur.selectedSections.sorted()))
            }
        }

        _state.update {
            it.copy(
                running = true,
                errorBanner = null,
                lastReportPath = null,
                results = Results(),
                logs = listOf(stamp("Запуск проверки…")),
            )
        }

        scope.launch {
            try {
                gateway.start(opts.toString(), listener)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        errorBanner = t.message ?: "Не удалось запустить проверку",
                    )
                }
            }
        }
    }

    fun cancelCheck() {
        scope.launch {
            runCatching { gateway.cancel() }
            appendLog("Запрошена остановка проверки…")
        }
    }

    fun clearError() {
        _state.update { it.copy(errorBanner = null) }
    }

    fun probeCustomHost(target: String, sni: String, port: Int) {
        val clean = target.trim()
        if (clean.isEmpty()) return
        if (_state.value.hostProbeBusy) return

        _state.update { it.copy(hostProbeBusy = true) }
        scope.launch {
            val now = System.currentTimeMillis()
            val entry = try {
                val json = gateway.checkCustomHost(clean, sni.trim(), port)
                val item = WebhostItem.from(JSONObject(json))
                HostProbeEntry(
                    id = now,
                    query = clean,
                    port = if (port in 1..65535) port else 443,
                    sni = sni.trim(),
                    timestamp = now,
                    item = item,
                )
            } catch (t: Throwable) {
                HostProbeEntry(
                    id = now,
                    query = clean,
                    port = if (port in 1..65535) port else 443,
                    sni = sni.trim(),
                    timestamp = now,
                    error = t.message ?: "Ошибка проверки",
                )
            }
            _state.update { s ->
                s.copy(
                    hostProbeBusy = false,
                    hostProbes = (listOf(entry) + s.hostProbes).take(30),
                )
            }
        }
    }

    fun clearHostProbes() {
        _state.update { it.copy(hostProbes = emptyList()) }
    }

    fun refreshReports() {
        scope.launch {
            val dir = runCatching { File(gateway.reportsDir()) }.getOrNull() ?: return@launch
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            val list = files.map { f -> summarizeReportFile(f) }
            _state.update { it.copy(reports = list) }
        }
    }

    suspend fun loadReport(file: File): Result<Results> = withContext(ioDispatcher) {
        runCatching {
            val raw = file.readText()
            Results.fromReport(JSONObject(raw))
        }
    }

    fun deleteReport(file: File) {
        scope.launch {
            runCatching { file.delete() }
            refreshReports()
        }
    }

    suspend fun saveUserConfig(yaml: String): String? = withContext(ioDispatcher) {
        if (_state.value.running) return@withContext "Остановите текущую проверку перед изменением конфига"
        val err = gateway.validateConfig(yaml)
        if (err.isNotEmpty()) return@withContext err
        try {
            gateway.setup(appContext.filesDir.absolutePath, appContext.cacheDir.absolutePath, yaml)
            prefs.edit().putString("user_yaml", yaml).apply()
            val sections = parseSections(gateway.sections())
            _state.update {
                it.copy(
                    userYaml = yaml,
                    sections = sections,
                    selectedSections = sections.map { s -> s.index }.toSet(),
                )
            }
            null
        } catch (t: Throwable) {
            t.message ?: "Не удалось применить конфиг"
        }
    }

    suspend fun loadDefaultConfigYaml(): String = withContext(ioDispatcher) {
        runCatching { gateway.defaultConfigYaml() }.getOrDefault("")
    }

    internal fun handleEvent(raw: String) {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "log" -> {
                val msg = obj.optString("msg")
                if (msg.isNotBlank()) appendLog(msg)
            }

            "started" -> {
                val checkers = obj.optJSONArray("checkers")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList()
                val secList = obj.optJSONArray("sections").objects().map { s ->
                    SectionResult(
                        index = s.optInt("index"),
                        name = s.optString("name"),
                        state = TaskState.RUNNING,
                    )
                }
                val tasks = linkedMapOf<String, TaskState>()
                if ("whoami" in checkers) tasks["whoami"] = TaskState.RUNNING
                if ("cidrwhitelist" in checkers) tasks["cidrwhitelist"] = TaskState.RUNNING
                if ("dns" in checkers) {
                    tasks["dnsLeak"] = TaskState.RUNNING
                    tasks["dnsPlain"] = TaskState.RUNNING
                    tasks["dnsDoh"] = TaskState.RUNNING
                }
                _state.update { it.copy(results = Results(sections = secList, tasks = tasks)) }
            }

            "task" -> {
                val task = obj.optString("task")
                val index = obj.optInt("index")
                val st = TaskState.parse(obj.optString("state"))
                _state.update { cur ->
                    if (task == "webhost") {
                        val updated = cur.results.sections.map { sec ->
                            if (sec.index == index) sec.copy(state = st) else sec
                        }
                        cur.copy(results = cur.results.copy(sections = updated))
                    } else {
                        val tasks = cur.results.tasks.toMutableMap()
                        tasks[task] = st
                        cur.copy(results = cur.results.copy(tasks = tasks))
                    }
                }
            }

            "whoami" -> {
                val dto = obj.optJSONObject("data")?.let(Whoami::from) ?: return
                _state.update { it.copy(results = it.results.copy(whoami = dto)) }
            }

            "cidr" -> {
                val st = obj.optJSONObject("data")?.optJSONObject("Status")?.let(Status::from) ?: return
                _state.update { it.copy(results = it.results.copy(cidr = st)) }
            }

            "dnsLeak" -> {
                val dto = obj.optJSONObject("data")?.let(DnsLeak::from) ?: return
                _state.update { it.copy(results = it.results.copy(dnsLeak = dto)) }
            }

            "dnsPlain" -> {
                val dto = obj.optJSONObject("data")?.let(DnsReport::from) ?: return
                _state.update { it.copy(results = it.results.copy(dnsPlain = dto)) }
            }

            "dnsDoh" -> {
                val dto = obj.optJSONObject("data")?.let(DnsReport::from) ?: return
                _state.update { it.copy(results = it.results.copy(dnsDoh = dto)) }
            }

            "webhostItem" -> {
                val idx = obj.optInt("index")
                val secName = obj.optString("section")
                val item = obj.optJSONObject("data")?.let(WebhostItem::from) ?: return
                _state.update { cur ->
                    val list = cur.results.sections.toMutableList()
                    val pos = list.indexOfFirst { it.index == idx }
                    if (pos >= 0) {
                        val existing = list[pos]
                        list[pos] = existing.copy(items = existing.items + item)
                    } else {
                        list += SectionResult(idx, secName, listOf(item), TaskState.RUNNING)
                    }
                    cur.copy(results = cur.results.copy(sections = list))
                }
            }

            "webhostDone" -> {
                val idx = obj.optInt("index")
                val ft = obj.optBoolean("farmTimeout")
                val err = obj.optString("error")
                _state.update { cur ->
                    val list = cur.results.sections.map { sec ->
                        if (sec.index == idx) sec.copy(farmTimeout = ft, error = err) else sec
                    }
                    cur.copy(results = cur.results.copy(sections = list))
                }
            }

            "error" -> {
                val msg = obj.optString("msg")
                if (msg.isNotBlank()) {
                    appendLog("ОШИБКА: $msg")
                    _state.update { it.copy(errorBanner = msg) }
                }
            }

            "done" -> {
                val path = obj.optString("path").ifBlank { null }
                val cancelled = obj.optBoolean("cancelled")
                appendLog(if (cancelled) "Проверка остановлена пользователем" else "Проверка завершена" + (path?.let { " · отчёт сохранён" } ?: ""))
                _state.update { cur ->
                    val fixedTasks = cur.results.tasks.mapValues { (_, v) ->
                        if (v == TaskState.RUNNING) TaskState.ERROR else v
                    }
                    val fixedSections = cur.results.sections.map { sec ->
                        if (sec.state == TaskState.RUNNING) sec.copy(state = TaskState.ERROR) else sec
                    }
                    cur.copy(
                        running = false,
                        lastReportPath = path,
                        results = cur.results.copy(tasks = fixedTasks, sections = fixedSections),
                    )
                }
                if (path != null) refreshReports()
            }
        }
    }

    private fun appendLog(msg: String) {
        val line = stamp(msg)
        _state.update { cur ->
            val next = if (cur.logs.size >= 400) cur.logs.drop(cur.logs.size - 399) + line else cur.logs + line
            cur.copy(logs = next)
        }
    }

    private fun stamp(msg: String): String = "[${timeFmt.format(Date())}] $msg"

    private fun parseSections(raw: String): List<SectionMeta> = runCatching {
        JSONArray(raw).objects().map { o ->
            val targets = o.optJSONArray("targets")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            SectionMeta(o.optInt("index"), o.optString("name"), o.optString("desc"), targets)
        }
    }.getOrDefault(emptyList())

    private fun parseGeoStatus(raw: String): GeoStatus = runCatching {
        val o = JSONObject(raw)
        GeoStatus(
            loaded = o.optBoolean("loaded"),
            due = o.optBoolean("due"),
            updatedAt = o.optLong("updatedAt"),
        )
    }.getOrDefault(GeoStatus())

    private fun summarizeReportFile(f: File): ReportSummary {
        var hint = ""
        var issues = 0
        runCatching {
            val r = Results.fromReport(JSONObject(f.readText()))
            hint = listOfNotNull(
                r.whoami?.org?.takeIf { it.isNotBlank() },
                r.whoami?.ip?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (r.cidr?.code == "DETECTED") issues++
            r.sections.forEach { s ->
                s.items.forEach { item ->
                    if (item.sniBlocked || item.tcp1620.code == "DETECTED" || item.siberian.code == "DETECTED" || !item.reachable) {
                        issues++
                    }
                }
            }
            listOfNotNull(r.dnsPlain, r.dnsDoh).forEach { dr ->
                dr.providers.values.forEach { sts ->
                    if (sts.any { it.level == Status.Level.BAD }) issues++
                }
            }
        }
        return ReportSummary(
            file = f,
            name = f.name,
            modifiedAt = f.lastModified(),
            sizeBytes = f.length(),
            providerHint = hint,
            issuesCount = issues,
        )
    }
}
