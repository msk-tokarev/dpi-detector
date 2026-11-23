package com.dpidetector.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpidetector.app.model.DnsReport
import com.dpidetector.app.model.Results
import com.dpidetector.app.model.SectionResult
import com.dpidetector.app.model.Status
import com.dpidetector.app.model.TaskState
import com.dpidetector.app.model.Whoami
import com.dpidetector.app.ui.components.DetailRow
import com.dpidetector.app.ui.components.PanelCard
import com.dpidetector.app.ui.components.SectionHeader
import com.dpidetector.app.ui.components.StatusBadge
import com.dpidetector.app.ui.components.TaskPill
import com.dpidetector.app.ui.components.WebhostItemCard
import com.dpidetector.app.ui.components.localizeDnsStatus
import com.dpidetector.app.ui.components.localizeSectionName
import com.dpidetector.app.ui.theme.MonoFamily
import com.dpidetector.app.ui.theme.Palette

@Composable
fun ResultsDashboard(
    results: Results,
    running: Boolean = false,
) {
    if (results.isEmpty) return

    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Обзор", "Хосты", "DNS")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Segmented tab bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { idx, label ->
                val selected = tab == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) Palette.SurfaceAlt else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (selected) Palette.Good.copy(alpha = 0.45f) else Color.Transparent,
                            shape = RoundedCornerShape(9.dp),
                        )
                        .clickable { tab = idx }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Palette.Good else Palette.TextSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }

        when (tab) {
            0 -> OverviewTab(results, running)
            1 -> WebhostTab(results.sections)
            2 -> DnsTab(results)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewTab(results: Results, running: Boolean) {
    val allItems = results.sections.flatMap { it.items }
    val sniCount = allItems.count { it.sniBlocked }
    val tcp1620Count = allItems.count { it.tcp1620.code == "DETECTED" }
    val siberianCount = allItems.count { it.siberian.code == "DETECTED" }
    val unreachableCount = allItems.count { !it.reachable && !it.sniBlocked }
    val dnsBadPlain = results.dnsPlain?.providers?.values?.count { list ->
        list.any { it.level == Status.Level.BAD }
    } ?: 0
    val dnsBadDoh = results.dnsDoh?.providers?.values?.count { list ->
        list.any { it.level == Status.Level.BAD }
    } ?: 0
    val cidrDetected = results.cidr?.code == "DETECTED"

    val totalDetected = (if (cidrDetected) 1 else 0) + sniCount + tcp1620Count + siberianCount + dnsBadPlain + dnsBadDoh

    // Verdict card
    val (verdictTitle, verdictSubtitle, verdictColor) = when {
        cidrDetected -> Triple(
            "Обнаружен режим «белых списков» (CIDR)",
            "Доступны только разрешённые подсети — обычные сайты блокируются на уровне IP/CIDR",
            Palette.Bad,
        )
        totalDetected > 0 -> Triple(
            "Обнаружена DPI-фильтрация трафика",
            "Найдены признаки активных ограничений провайдера или ТСПУ",
            Palette.Bad,
        )
        unreachableCount > 0 -> Triple(
            "Есть недоступные хосты ($unreachableCount)",
            "Явных DPI-сигнатур не поймано, но часть сервисов не отвечает",
            Palette.Warn,
        )
        running -> Triple(
            "Идёт анализ сети…",
            "Результаты обновляются в реальном времени по мере завершения тестов",
            Palette.Accent,
        )
        else -> Triple(
            "Явных DPI-ограничений не обнаружено",
            "Проверенные сервисы и DNS-резолверы работают штатно",
            Palette.Good,
        )
    }

    PanelCard(
        borderColor = verdictColor.copy(alpha = 0.55f),
        containerColor = Palette.Surface,
    ) {
        Text(
            text = verdictTitle,
            style = MaterialTheme.typography.titleMedium,
            color = verdictColor,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = verdictSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextSecondary,
        )

        if (allItems.isNotEmpty() || results.cidr != null || results.dnsPlain != null || results.dnsDoh != null) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricChip("Проверено хостов", "${allItems.size}", Palette.Accent)
                MetricChip(
                    "SNI-блоки",
                    "$sniCount",
                    if (sniCount > 0) Palette.Bad else Palette.Good,
                )
                MetricChip(
                    "TCP 16-20 (L4-25)",
                    "$tcp1620Count",
                    if (tcp1620Count > 0) Palette.Bad else Palette.Good,
                )
                MetricChip(
                    "Сибирские",
                    "$siberianCount",
                    if (siberianCount > 0) Palette.Bad else Palette.Good,
                )
                MetricChip(
                    "DNS подмены",
                    "${dnsBadPlain + dnsBadDoh}",
                    if (dnsBadPlain + dnsBadDoh > 0) Palette.Bad else Palette.Good,
                )
            }
        }
    }

    // Whoami card
    results.whoami?.let { w -> WhoamiCard(w) }

    // CIDR Whitelist card
    results.cidr?.let { cidr ->
        val (title, level) = when (cidr.code) {
            "NOT_DETECTED" -> "Белые списки не активны" to Status.Level.GOOD
            "DETECTED" -> "Активен CIDR WhiteList!" to Status.Level.BAD
            "NO_INTERNET_ACCESS" -> "Нет доступа к интернету" to Status.Level.WARN
            else -> cidr.msg.ifBlank { cidr.code } to cidr.level
        }
        PanelCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CIDR WhiteList", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Проверка жёсткой фильтрации по IP-подсетям",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StatusBadge(text = title, level = level)
            }
        }
    }

    // Quick breakdown of sections
    if (results.sections.isNotEmpty()) {
        SectionHeader("Секции Webhost")
        results.sections.forEach { sec ->
            val bad = sec.items.count {
                it.sniBlocked || it.tcp1620.code == "DETECTED" || it.siberian.code == "DETECTED" || !it.reachable
            }
            val ok = sec.items.size - bad
            PanelCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(localizeSectionName(sec.name), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Всего: ${sec.items.size} · OK: $ok · Проблемы: $bad" +
                                if (sec.farmTimeout) " · таймаут поиска" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TaskPill(
                        label = when (sec.state) {
                            TaskState.OK -> "Готово"
                            TaskState.RUNNING -> "Идёт…"
                            TaskState.TIMEOUT -> "Таймаут"
                            TaskState.ERROR -> "Ошибка"
                            TaskState.PENDING -> "Ожидание"
                        },
                        state = sec.state,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String, accent: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.SurfaceAlt)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = value,
            fontFamily = MonoFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextSecondary,
        )
    }
}

@Composable
private fun WhoamiCard(w: Whoami) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Подключение (Whoami)", style = MaterialTheme.typography.titleMedium)
            StatusBadge(
                text = if (w.status.code == "OK") "${w.ttlbMs} мс" else w.status.msg.ifBlank { "Ошибка" },
                level = w.status.level,
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (w.ip.isNotBlank()) DetailRow("Внешний IP", w.ip, mono = true)
            if (w.org.isNotBlank()) DetailRow("Провайдер", w.org)
            if (w.asn.isNotBlank() && w.asn != "AS0") DetailRow("ASN", w.asn, mono = true)
            if (w.subnet.isNotBlank() && w.subnet != "0.0.0.0/0") DetailRow("Подсеть", w.subnet, mono = true)
            if (w.location.isNotBlank()) DetailRow("Страна", w.location)
        }
    }
}

@Composable
private fun WebhostTab(sections: List<SectionResult>) {
    if (sections.isEmpty()) {
        PanelCard {
            Text("Нет данных по секциям Webhost", style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        }
        return
    }

    var onlyProblems by remember { mutableStateOf(false) }
    var selectedSection by remember { mutableIntStateOf(-1) } // -1 = all
    var query by remember { mutableStateOf("") }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Поиск по сервису, IP, ASN, org…", color = Palette.TextMuted) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Palette.TextMuted) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Очистить", tint = Palette.TextMuted)
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Good,
            unfocusedBorderColor = Palette.Border,
            focusedContainerColor = Palette.Surface,
            unfocusedContainerColor = Palette.Surface,
            focusedTextColor = Palette.TextPrimary,
            unfocusedTextColor = Palette.TextPrimary,
        ),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = onlyProblems,
            onClick = { onlyProblems = !onlyProblems },
            label = { Text("Только проблемы") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Palette.BadBg,
                selectedLabelColor = Palette.Bad,
            ),
        )
        FilterChip(
            selected = selectedSection == -1,
            onClick = { selectedSection = -1 },
            label = { Text("Все секции") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Palette.GoodBg,
                selectedLabelColor = Palette.Good,
            ),
        )
        sections.forEach { sec ->
            FilterChip(
                selected = selectedSection == sec.index,
                onClick = { selectedSection = if (selectedSection == sec.index) -1 else sec.index },
                label = { Text(localizeSectionName(sec.name)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.AccentBg,
                    selectedLabelColor = Palette.Accent,
                ),
            )
        }
    }

    val q = query.trim().lowercase()
    val visibleSections = sections.filter { selectedSection == -1 || it.index == selectedSection }
    var shownTotal = 0

    visibleSections.forEach { sec ->
        val filtered = sec.items.filter { item ->
            val issueOk = !onlyProblems ||
                !item.reachable ||
                item.sniBlocked ||
                item.tcp1620.code == "DETECTED" ||
                item.siberian.code == "DETECTED"
            val qOk = q.isEmpty() ||
                item.group.lowercase().contains(q) ||
                item.ip.contains(q) ||
                item.org.lowercase().contains(q) ||
                item.asn.lowercase().contains(q)
            issueOk && qOk
        }

        if (filtered.isNotEmpty() || (q.isEmpty() && !onlyProblems)) {
            SectionHeader(
                title = localizeSectionName(sec.name),
                subtitle = if (sec.error.isNotBlank()) "Ошибка: ${sec.error}"
                else "${filtered.size} из ${sec.items.size} хостов" + if (sec.farmTimeout) " · таймаут поиска" else "",
            )
            filtered.forEach { item ->
                shownTotal++
                WebhostItemCard(item = item)
            }
        }
    }

    if (shownTotal == 0) {
        PanelCard {
            Text(
                text = if (onlyProblems) "Проблемных хостов по выбранному фильтру не найдено" else "Пока нет результатов",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DnsTab(results: Results) {
    if (results.dnsLeak == null && results.dnsPlain == null && results.dnsDoh == null) {
        PanelCard {
            Text("Нет данных DNS-проверок", style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        }
        return
    }

    // DNS Leak
    results.dnsLeak?.let { leak ->
        SectionHeader(
            title = "DNS Leak (фактические резолверы)",
            subtitle = "Какие DNS-серверы реально обслуживают запросы устройства",
        )
        PanelCard {
            if (leak.status.code != "OK") {
                StatusBadge(text = leak.status.msg.ifBlank { leak.status.code }, level = leak.status.level)
            } else if (leak.items.isEmpty()) {
                Text("Серверы не определены", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    leak.items.forEach { info ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Palette.SurfaceAlt)
                                .padding(10.dp),
                        ) {
                            Text(
                                text = info.ip,
                                fontFamily = MonoFamily,
                                fontWeight = FontWeight.SemiBold,
                                color = Palette.Accent,
                                fontSize = 13.sp,
                            )
                            val meta = listOfNotNull(
                                info.org.takeIf { it.isNotBlank() },
                                info.asn.takeIf { it.isNotBlank() && it != "AS0" },
                                info.location.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    results.dnsPlain?.let { plain ->
        DnsProviderTable(
            title = "Plain DNS (UDP :53)",
            subtitle = "Проверка подмены A-записей, NXDOMAIN и перехвата провайдера",
            report = plain,
        )
    }

    results.dnsDoh?.let { doh ->
        DnsProviderTable(
            title = "DNS over HTTPS (DoH)",
            subtitle = "Проверка bootstrap-спуфинга, подмены TLS и доступности DoH",
            report = doh,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DnsProviderTable(
    title: String,
    subtitle: String,
    report: DnsReport,
) {
    SectionHeader(title = title, subtitle = subtitle)
    PanelCard {
        val entries = report.providers.entries.filter { (_, sts) ->
            !(sts.size == 1 && sts.first().code == "SKIP")
        }
        if (entries.isEmpty()) {
            Text("Нет результатов", style = MaterialTheme.typography.bodySmall)
            return@PanelCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.forEach { (provider, statuses) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.SurfaceAlt)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = provider,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.TextPrimary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        statuses.forEach { st ->
                            StatusBadge(text = localizeDnsStatus(st), level = st.level)
                        }
                    }
                }
            }
        }
    }
}
