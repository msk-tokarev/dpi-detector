package com.dpidetector.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpidetector.app.data.EngineUiState
import com.dpidetector.app.data.NetState
import com.dpidetector.app.ui.components.PanelCard
import com.dpidetector.app.ui.components.TaskPill
import com.dpidetector.app.ui.components.localizeSectionName
import com.dpidetector.app.ui.theme.MonoFamily
import com.dpidetector.app.ui.theme.Palette
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScannerScreen(
    state: EngineUiState,
    net: NetState,
    onToggleChecker: (String) -> Unit,
    onToggleSection: (Int) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit,
    onShareReport: (File) -> Unit,
    onRetryGeo: () -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    var showModules by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Network bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (net.online) Palette.Good else Palette.Bad),
                )
                Text(
                    text = net.transport,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = when {
                    state.geo.loading -> "GeoLite: загрузка…"
                    state.geo.loaded -> "GeoLite: готова"
                    else -> "GeoLite: нет базы"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.geo.loaded) Palette.Good else Palette.Warn,
            )
        }

        // VPN warning
        if (net.vpnActive) {
            PanelCard(
                borderColor = Palette.Warn.copy(alpha = 0.6f),
                containerColor = Palette.WarnBg,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Palette.Warn,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            text = "Обнаружен активный VPN",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Palette.Warn,
                        )
                        Text(
                            text = "Для точной проверки DPI вашего провайдера отключите VPN перед сканированием.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextPrimary,
                        )
                    }
                }
            }
        }

        // Init or Geo error banner
        if (state.initError != null) {
            PanelCard(borderColor = Palette.Bad, containerColor = Palette.BadBg) {
                Text(state.initError, color = Palette.Bad, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (state.geo.loading) {
            PanelCard {
                Text("Подготовка базы GeoLite…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Palette.Good,
                    trackColor = Palette.SurfaceAlt,
                )
            }
        } else if (!state.geo.loaded && state.geo.error != null) {
            PanelCard(borderColor = Palette.Warn) {
                Text("База GeoLite не загружена: ${state.geo.error}", color = Palette.Warn, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRetryGeo) {
                    Text("Скачать базу GeoLite")
                }
            }
        }

        if (state.errorBanner != null) {
            PanelCard(borderColor = Palette.Bad, containerColor = Palette.BadBg) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.errorBanner,
                        color = Palette.Bad,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClearError, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Palette.Bad, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Control & module selector card
        PanelCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showModules = !showModules },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Модули диагностики", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "На базе ядра hyperion-cs/dpi-checkers",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    imageVector = if (showModules) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Palette.TextSecondary,
                )
            }

            AnimatedVisibility(visible = showModules) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    val coreCheckers = listOf(
                        "whoami" to "Whoami (IP/ASN)",
                        "cidrwhitelist" to "CIDR Белые списки",
                        "dns" to "DNS (Leak/Plain/DoH)",
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        coreCheckers.forEach { (id, title) ->
                            val sel = id in state.selectedCheckers
                            FilterChip(
                                selected = sel,
                                enabled = !state.running,
                                onClick = { onToggleChecker(id) },
                                label = { Text(title, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Palette.GoodBg,
                                    selectedLabelColor = Palette.Good,
                                ),
                            )
                        }
                    }

                    if (state.sections.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Секции Webhost (SNI · TCP 16-20 · Сибирские · Скорость)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.TextMuted,
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            state.sections.forEach { sec ->
                                val sel = sec.index in state.selectedSections && "webhost" in state.selectedCheckers
                                FilterChip(
                                    selected = sel,
                                    enabled = !state.running,
                                    onClick = { onToggleSection(sec.index) },
                                    label = { Text(localizeSectionName(sec.name), fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Palette.AccentBg,
                                        selectedLabelColor = Palette.Accent,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            val canStart = state.ready && !state.geo.loading && (state.geo.loaded || state.selectedCheckers == setOf("cidrwhitelist"))
            if (!state.running) {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.Good,
                        contentColor = Palette.Bg,
                    ),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Запустить проверку DPI", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            } else {
                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.Bad,
                        contentColor = Palette.TextPrimary,
                    ),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Palette.TextPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Остановить проверку", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }

            // Share button after finish
            val savedPath = state.lastReportPath
            if (!state.running && savedPath != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onShareReport(File(savedPath)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Поделиться отчётом JSON (dpi-ch формат)")
                }
            }
        }

        // Live tasks status
        if (state.results.tasks.isNotEmpty() || state.results.sections.isNotEmpty()) {
            PanelCard {
                Text(
                    text = if (state.running) "Прогресс модулей" else "Статус модулей",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val taskNames = mapOf(
                        "whoami" to "Whoami",
                        "cidrwhitelist" to "CIDR WL",
                        "dnsLeak" to "DNS Leak",
                        "dnsPlain" to "DNS Plain",
                        "dnsDoh" to "DNS DoH",
                    )
                    state.results.tasks.forEach { (k, v) ->
                        TaskPill(label = taskNames[k] ?: k, state = v)
                    }
                    state.results.sections.forEach { sec ->
                        TaskPill(label = localizeSectionName(sec.name), state = sec.state)
                    }
                }
            }
        }

        // Collapsible live log console
        if (state.logs.isNotEmpty()) {
            PanelCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLog = !showLog },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Журнал событий (${state.logs.size})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Icon(
                        imageVector = if (showLog) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Palette.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (!showLog) {
                    Text(
                        text = state.logs.last(),
                        fontFamily = MonoFamily,
                        fontSize = 11.sp,
                        color = Palette.TextSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                AnimatedVisibility(visible = showLog) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Palette.Bg)
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.logs.asReversed().forEach { line ->
                            Text(
                                text = line,
                                fontFamily = MonoFamily,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = Palette.TextSecondary,
                            )
                        }
                    }
                }
            }
        }

        // Full results dashboard
        ResultsDashboard(results = state.results, running = state.running)

        Spacer(Modifier.height(16.dp))
    }
}
