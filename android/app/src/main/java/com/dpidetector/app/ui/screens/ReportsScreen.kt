package com.dpidetector.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpidetector.app.data.ReportSummary
import com.dpidetector.app.model.Results
import com.dpidetector.app.model.Status
import com.dpidetector.app.ui.components.PanelCard
import com.dpidetector.app.ui.components.SectionHeader
import com.dpidetector.app.ui.components.StatusBadge
import com.dpidetector.app.ui.theme.MonoFamily
import com.dpidetector.app.ui.theme.Palette
import com.dpidetector.app.util.ExportFormat
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReportsScreen(
    reports: List<ReportSummary>,
    onRefresh: () -> Unit,
    onLoadReport: suspend (File) -> Result<Results>,
    onDeleteReport: (File) -> Unit,
    onShareReport: (File) -> Unit,
    onExportReport: (File, ExportFormat) -> Unit = { f, _ -> onShareReport(f) },
) {
    val scope = rememberCoroutineScope()
    var openedFile by remember { mutableStateOf<File?>(null) }
    var openedResults by remember { mutableStateOf<Results?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }

    if (openedFile != null && openedResults != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { openedFile = null; openedResults = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Palette.TextPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = openedFile!!.name,
                        fontFamily = MonoFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = dateFmt.format(Date(openedFile!!.lastModified())),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { onShareReport(openedFile!!) }) {
                    Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = Palette.Good)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExportFormat.entries.forEach { fmt ->
                    OutlinedButton(
                        onClick = { onExportReport(openedFile!!, fmt) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(fmt.label, fontFamily = MonoFamily, fontSize = 12.sp)
                    }
                }
            }

            ResultsDashboard(results = openedResults!!, running = false)
            Spacer(Modifier.height(16.dp))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader(
            title = "Сохранённые отчёты (${reports.size})",
            subtitle = "Экспорт в JSON (dpich --import-all), CSV и TXT",
            trailing = {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить", tint = Palette.TextSecondary)
                }
            },
        )

        if (loadError != null) {
            PanelCard(borderColor = Palette.Bad) {
                Text(loadError!!, color = Palette.Bad, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (reports.isEmpty()) {
            PanelCard {
                Text(
                    text = "Пока нет сохранённых отчётов. Запустите полную или выборочную проверку на вкладке «Сканер» — отчёт сохранится автоматически.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                )
            }
        } else {
            reports.forEach { rep ->
                PanelCard(
                    modifier = Modifier.clickable {
                        scope.launch {
                            loadError = null
                            onLoadReport(rep.file)
                                .onSuccess { r ->
                                    openedFile = rep.file
                                    openedResults = r
                                }
                                .onFailure { e ->
                                    loadError = "Не удалось открыть отчёт: ${e.message}"
                                }
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dateFmt.format(Date(rep.modifiedAt)),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (rep.providerHint.isNotBlank()) {
                                Text(
                                    text = rep.providerHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.Accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatusBadge(
                                    text = if (rep.issuesCount > 0) "Проблем: ${rep.issuesCount}" else "Чисто",
                                    level = if (rep.issuesCount > 0) Status.Level.BAD else Status.Level.GOOD,
                                )
                                Text(
                                    text = "${(rep.sizeBytes / 1024).coerceAtLeast(1)} КБ",
                                    fontFamily = MonoFamily,
                                    fontSize = 11.sp,
                                    color = Palette.TextMuted,
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { onShareReport(rep.file) }) {
                            Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = Palette.Good, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { onDeleteReport(rep.file) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Palette.TextMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onExportReport(rep.file, ExportFormat.CSV) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("CSV", fontFamily = MonoFamily, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { onExportReport(rep.file, ExportFormat.TXT) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("TXT", fontFamily = MonoFamily, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
