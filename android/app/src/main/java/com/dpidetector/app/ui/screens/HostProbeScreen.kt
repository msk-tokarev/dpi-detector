package com.dpidetector.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpidetector.app.data.BatchItemState
import com.dpidetector.app.data.BatchJobStatus
import com.dpidetector.app.data.EngineUiState
import com.dpidetector.app.model.Status
import com.dpidetector.app.ui.components.PanelCard
import com.dpidetector.app.ui.components.SectionHeader
import com.dpidetector.app.ui.components.StatusBadge
import com.dpidetector.app.ui.components.WebhostItemCard
import com.dpidetector.app.ui.theme.MonoFamily
import com.dpidetector.app.ui.theme.Palette
import com.dpidetector.app.util.CidrExpandMode
import com.dpidetector.app.util.ExportFormat
import com.dpidetector.app.util.SubnetPlanner

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostProbeScreen(
    state: EngineUiState,
    onProbe: (target: String, sni: String, port: Int) -> Unit,
    onClearHistory: () -> Unit,
    onStartBatch: (rawTargets: String, port: Int, sni: String, mode: CidrExpandMode) -> Unit = { _, _, _, _ -> },
    onPauseBatch: () -> Unit = {},
    onResumeBatch: () -> Unit = {},
    onRetryFailedBatch: () -> Unit = {},
    onStopBatch: () -> Unit = {},
    onExportBatch: (ExportFormat) -> Unit = {},
) {
    var modeTab by rememberSaveable { mutableIntStateOf(0) } // 0 = single, 1 = batch/CIDR

    var target by rememberSaveable { mutableStateOf("") }
    var sni by rememberSaveable { mutableStateOf("") }
    var portStr by rememberSaveable { mutableStateOf("443") }

    var batchInput by rememberSaveable { mutableStateOf("") }
    var batchPortStr by rememberSaveable { mutableStateOf("443") }
    var batchSni by rememberSaveable { mutableStateOf("") }
    var cidrModeName by rememberSaveable { mutableStateOf(CidrExpandMode.USABLE.name) }
    var batchFilter by rememberSaveable { mutableIntStateOf(0) } // 0=all, 1=issues, 2=failed

    val cidrMode = runCatching { CidrExpandMode.valueOf(cidrModeName) }.getOrDefault(CidrExpandMode.USABLE)

    val presets = listOf(
        "www.youtube.com",
        "discord.com",
        "x.com",
        "www.instagram.com",
        "api.telegram.org",
        "github.com",
        "1.1.1.1",
        "2606:4700:4700::1111",
    )

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Good,
        unfocusedBorderColor = Palette.Border,
        focusedContainerColor = Palette.SurfaceAlt,
        unfocusedContainerColor = Palette.SurfaceAlt,
        focusedTextColor = Palette.TextPrimary,
        unfocusedTextColor = Palette.TextPrimary,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Mode switcher: Single Host vs Batch / CIDR Queue
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = modeTab == 0,
                onClick = { modeTab = 0 },
                modifier = Modifier.weight(1f),
                label = { Text("Одиночный (IPv4/IPv6)", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.GoodBg,
                    selectedLabelColor = Palette.Good,
                ),
            )
            FilterChip(
                selected = modeTab == 1,
                onClick = { modeTab = 1 },
                modifier = Modifier.weight(1f),
                label = {
                    val b = state.batch
                    val suffix = if (b.total > 0) " (${b.completed}/${b.total})" else ""
                    Text("Пакетный / CIDR$suffix", fontSize = 12.sp)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.AccentBg,
                    selectedLabelColor = Palette.Accent,
                ),
            )
        }

        if (modeTab == 0) {
            PanelCard {
                Text("Проверка своего хоста", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Проверяет доступность, SNI-блокировку, TCP 16-20 (L4-25), «сибирские» ограничения, TLS-сертификат и burst-скорость для любого домена, IPv4 или IPv6.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Домен, IPv4 или IPv6") },
                    placeholder = { Text("youtube.com, 1.1.1.1 или 2606:4700:4700::1111", color = Palette.TextMuted) },
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = sni,
                        onValueChange = { sni = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("SNI (опционально)") },
                        placeholder = { Text("по умолчанию = домен", color = Palette.TextMuted) },
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                    )
                    OutlinedTextField(
                        value = portStr,
                        onValueChange = { portStr = it.filter { c -> c.isDigit() }.take(5) },
                        modifier = Modifier.width(96.dp),
                        singleLine = true,
                        label = { Text("Порт") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                    )
                }

                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    presets.forEach { preset ->
                        AssistChip(
                            onClick = { target = preset },
                            label = { Text(preset, fontFamily = MonoFamily, fontSize = 11.sp) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
                        onProbe(target, sni, port)
                    },
                    enabled = state.ready && state.geo.loaded && !state.hostProbeBusy && target.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.Accent,
                        contentColor = Palette.Bg,
                    ),
                ) {
                    if (state.hostProbeBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Palette.Bg,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Проверяем хост…", fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Проверить хост", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (state.hostProbes.isNotEmpty()) {
                SectionHeader(
                    title = "Результаты проверок (${state.hostProbes.size})",
                    trailing = {
                        IconButton(onClick = onClearHistory) {
                            Icon(Icons.Default.Delete, contentDescription = "Очистить", tint = Palette.TextMuted)
                        }
                    },
                )
                state.hostProbes.forEachIndexed { idx, entry ->
                    if (entry.item != null) {
                        WebhostItemCard(item = entry.item, initiallyExpanded = idx == 0)
                    } else {
                        PanelCard(borderColor = Palette.Bad.copy(alpha = 0.5f)) {
                            Text(
                                text = "${entry.query}:${entry.port}",
                                fontFamily = MonoFamily,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = entry.error ?: "Ошибка проверки",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Bad,
                            )
                        }
                    }
                }
            }
        } else {
            // BATCH / CIDR SCANNER MODE
            val b = state.batch
            if (b.recoveredAfterRestart) {
                PanelCard(borderColor = Palette.Warn, containerColor = Palette.WarnBg) {
                    Text(
                        text = "Очередь восстановлена после перезапуска",
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Warn,
                    )
                    Text(
                        text = "Проверенные адреса сохранены в SQLite. Нажмите «Продолжить», чтобы досчитать оставшиеся.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Live CIDR calculator preview if input has a CIDR line
            val cidrPreview = remember(batchInput) {
                batchInput.lineSequence()
                    .flatMap { it.split(',', ';', ' ').asSequence() }
                    .map { it.trim() }
                    .firstOrNull { '/' in it && !it.startsWith("http") }
                    ?.let { SubnetPlanner.describeCidr(it) }
            }

            PanelCard {
                Text("Пакетный сканер доменов и подсетей CIDR", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Укажите список доменов, IPv4/IPv6 адресов, диапазонов (1.1.1.1-10) или подсетей CIDR (например, 104.16.0.0/29). Очередь сохраняется в SQLite и работает в фоне.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = batchInput,
                    onValueChange = { batchInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 180.dp),
                    label = { Text("Цели (домены, IP, CIDR, диапазоны)") },
                    placeholder = {
                        Text(
                            text = "www.youtube.com\ndiscord.com\n1.1.1.0/30\n2606:4700:4700::1111",
                            fontFamily = MonoFamily,
                            fontSize = 12.sp,
                            color = Palette.TextMuted,
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                )

                if (cidrPreview != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Калькулятор CIDR ${cidrPreview.input}: ${cidrPreview.network} – ${cidrPreview.lastAddress} · всего ${cidrPreview.totalCount} (рабочих: ${cidrPreview.usableCount})",
                        fontFamily = MonoFamily,
                        fontSize = 11.sp,
                        color = Palette.Accent,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CidrExpandMode.entries.forEach { m ->
                        FilterChip(
                            selected = cidrMode == m,
                            onClick = { cidrModeName = m.name },
                            label = { Text(m.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Palette.GoodBg,
                                selectedLabelColor = Palette.Good,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = batchSni,
                        onValueChange = { batchSni = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Общий SNI (опц.)") },
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                    )
                    OutlinedTextField(
                        value = batchPortStr,
                        onValueChange = { batchPortStr = it.filter { c -> c.isDigit() }.take(5) },
                        modifier = Modifier.width(96.dp),
                        singleLine = true,
                        label = { Text("Порт") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val port = batchPortStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
                        onStartBatch(batchInput, port, batchSni, cidrMode)
                    },
                    enabled = state.ready && state.geo.loaded && b.status != BatchJobStatus.RUNNING && batchInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.Good,
                        contentColor = Palette.Bg,
                    ),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Запустить очередь", fontWeight = FontWeight.SemiBold)
                }
            }

            // Active / Paused / Completed Batch Queue Status & Results
            if (b.total > 0) {
                PanelCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Очередь: ${b.completed} / ${b.total}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val stLevel = when (b.status) {
                            BatchJobStatus.RUNNING -> Status.Level.WARN
                            BatchJobStatus.PAUSED -> Status.Level.NEUTRAL
                            BatchJobStatus.COMPLETED -> Status.Level.GOOD
                            BatchJobStatus.IDLE -> Status.Level.NEUTRAL
                        }
                        val stText = when (b.status) {
                            BatchJobStatus.RUNNING -> b.etaSeconds?.let { "Идёт (~${it}с)" } ?: "Выполняется"
                            BatchJobStatus.PAUSED -> "На паузе"
                            BatchJobStatus.COMPLETED -> "Завершено"
                            BatchJobStatus.IDLE -> "Ожидание"
                        }
                        StatusBadge(text = stText, level = stLevel)
                    }

                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (b.total > 0) b.completed.toFloat() / b.total.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Palette.Good,
                        trackColor = Palette.SurfaceAlt,
                    )

                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StatusBadge("Проблем: ${b.issues}", if (b.issues > 0) Status.Level.BAD else Status.Level.GOOD)
                        StatusBadge("Ошибок: ${b.failed}", if (b.failed > 0) Status.Level.WARN else Status.Level.NEUTRAL)
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (b.status == BatchJobStatus.RUNNING) {
                            OutlinedButton(
                                onClick = onPauseBatch,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text("Пауза")
                            }
                        } else if (b.status == BatchJobStatus.PAUSED) {
                            Button(
                                onClick = onResumeBatch,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.Good, contentColor = Palette.Bg),
                            ) {
                                Text("Продолжить", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (b.status != BatchJobStatus.RUNNING && b.failed > 0) {
                            OutlinedButton(
                                onClick = onRetryFailedBatch,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text("Повторить (${b.failed})")
                            }
                        }
                        OutlinedButton(
                            onClick = onStopBatch,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Сбросить", color = Palette.Bad)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ExportFormat.entries.forEach { fmt ->
                            OutlinedButton(
                                onClick = { onExportBatch(fmt) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(fmt.label, fontFamily = MonoFamily, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Filter chips for batch items
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Все (${b.items.size})", 1 to "Проблемы (${b.issues})", 2 to "Ошибки (${b.failed})").forEach { (idx, label) ->
                        FilterChip(
                            selected = batchFilter == idx,
                            onClick = { batchFilter = idx },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }

                val filteredItems = b.items.filter { q ->
                    when (batchFilter) {
                        1 -> q.hasDpiIssue || q.state == BatchItemState.FAILED
                        2 -> q.state == BatchItemState.FAILED
                        else -> true
                    }
                }

                filteredItems.take(150).forEach { q ->
                    if (q.item != null) {
                        WebhostItemCard(item = q.item, initiallyExpanded = false)
                    } else {
                        PanelCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${q.target}:${q.port}", fontFamily = MonoFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                StatusBadge(
                                    text = when (q.state) {
                                        BatchItemState.QUEUED -> "В очереди"
                                        BatchItemState.RUNNING -> "Проверка…"
                                        BatchItemState.FAILED -> "Ошибка"
                                        BatchItemState.DONE -> "Готово"
                                    },
                                    level = if (q.state == BatchItemState.FAILED) Status.Level.BAD else Status.Level.NEUTRAL,
                                )
                            }
                            if (q.error != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(q.error, style = MaterialTheme.typography.bodySmall, color = Palette.Bad)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
