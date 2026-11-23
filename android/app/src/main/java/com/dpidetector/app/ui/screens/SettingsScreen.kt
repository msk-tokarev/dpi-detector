package com.dpidetector.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpidetector.app.BuildConfig
import com.dpidetector.app.data.EngineUiState
import com.dpidetector.app.model.Status
import com.dpidetector.app.ui.components.DetailRow
import com.dpidetector.app.ui.components.PanelCard
import com.dpidetector.app.ui.components.StatusBadge
import com.dpidetector.app.ui.theme.MonoFamily
import com.dpidetector.app.ui.theme.Palette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    state: EngineUiState,
    onUpdateGeo: () -> Unit,
    onSaveConfig: suspend (String) -> String?,
    onLoadDefaultYaml: suspend () -> String,
) {
    val scope = rememberCoroutineScope()
    var yamlDraft by remember { mutableStateOf(state.userYaml) }
    var saveMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var defaultYamlDialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.userYaml) {
        yamlDraft = state.userYaml
    }

    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // GeoLite DB card
        PanelCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("База GeoLite2 (ASN + Страны)", style = MaterialTheme.typography.titleMedium)
                StatusBadge(
                    text = if (state.geo.loaded) "Загружена" else "Не загружена",
                    level = if (state.geo.loaded) Status.Level.GOOD else Status.Level.WARN,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Оффлайн-база вшита в APK и работает даже при блокировке GitHub. При желании можно обновить её до самой свежей версии Loyalsoldier/geoip.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.geo.updatedAt > 0L) {
                Spacer(Modifier.height(6.dp))
                DetailRow("Дата базы", dateFmt.format(Date(state.geo.updatedAt * 1000L)), mono = true)
            }
            if (state.geo.error != null) {
                Spacer(Modifier.height(4.dp))
                Text("Последняя ошибка: ${state.geo.error}", color = Palette.Warn, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onUpdateGeo,
                enabled = state.ready && !state.running && !state.geo.loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.SurfaceAlt,
                    contentColor = Palette.Accent,
                ),
            ) {
                if (state.geo.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Accent)
                    Spacer(Modifier.width(8.dp))
                    Text("Обновляем базу…")
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Обновить базу GeoLite с GitHub")
                }
            }
        }

        // User config.yaml card
        PanelCard {
            Text("Конфигурация config.yaml", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "YAML-переопределения в формате dpi-ch (можно добавить свои секции webhost, фильтры subnetfilter, таймауты, fingerprint и т.д.).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = yamlDraft,
                onValueChange = { yamlDraft = it; saveMessage = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 130.dp, max = 260.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFamily,
                    color = Palette.TextPrimary,
                ),
                placeholder = {
                    Text(
                        text = "# Пример:\ncheckers:\n  webhost:\n    workers: 6",
                        fontFamily = MonoFamily,
                        fontSize = 12.sp,
                        color = Palette.TextMuted,
                    )
                },
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Good,
                    unfocusedBorderColor = Palette.Border,
                    focusedContainerColor = Palette.Bg,
                    unfocusedContainerColor = Palette.Bg,
                ),
            )

            saveMessage?.let { (ok, msg) ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) Palette.Good else Palette.Bad,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            val err = onSaveConfig(yamlDraft)
                            saving = false
                            saveMessage = if (err == null) {
                                true to "Конфигурация проверена и применена"
                            } else {
                                false to "Ошибка YAML: $err"
                            }
                        }
                    },
                    enabled = !saving && !state.running,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.Good,
                        contentColor = Palette.Bg,
                    ),
                ) {
                    Text(if (saving) "Проверка…" else "Применить", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = {
                        yamlDraft = ""
                        scope.launch {
                            saving = true
                            val err = onSaveConfig("")
                            saving = false
                            saveMessage = if (err == null) true to "Сброшено к настройкам по умолчанию"
                            else false to err
                        }
                    },
                    enabled = !saving && !state.running,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Сбросить")
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        defaultYamlDialog = onLoadDefaultYaml()
                    }
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Посмотреть встроенный default.yaml", color = Palette.Accent, fontSize = 12.sp)
            }
        }

        // Glossary of DPI methods
        PanelCard {
            Text("Справка по методам DPI", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GlossaryItem(
                "SNI-блок",
                "Провайдер/ТСПУ режет TLS-рукопожатие по имени домена в ClientHello (SNI), при этом сам IP отвечает на другой SNI.",
            )
            GlossaryItem(
                "TCP 16-20 (L4-25)",
                "Соединение рвётся или зависает после передачи ~16–20 КБ (или ~25 пакетов) данных в одну сессию.",
            )
            GlossaryItem(
                "Сибирские ограничения",
                "Блокировка серии параллельных TLS-соединений с отпечатком браузера (Chrome v133), характерная для ряда региональных ТСПУ.",
            )
            GlossaryItem(
                "CIDR WhiteList",
                "Жёсткий режим «белых списков» подсетей, когда доступны только разрешённые ресурсы (ya.ru, vk.ru, max.ru), а остальной интернет отрезан.",
            )
            GlossaryItem(
                "DNS Spoofing / Hijacking",
                "Подмена A-записей, ложный NXDOMAIN, перехват UDP-запросов на сторонний резолвер или подмена bootstrap-адреса DoH.",
            )
        }

        // About card
        PanelCard {
            Text("О приложении", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            DetailRow("Авторы", "by Ivan Tokarev")
            DetailRow("Версия", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", mono = true)
            DetailRow("Ядро dpi-ch", BuildConfig.CORE_COMMIT, mono = true)
            DetailRow("Upstream", "github.com/hyperion-cs/dpi-checkers", mono = true)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Инструмент предназначен для исследовательских и диагностических целей.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextMuted,
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    if (defaultYamlDialog != null) {
        AlertDialog(
            onDismissRequest = { defaultYamlDialog = null },
            containerColor = Palette.Surface,
            title = { Text("default.yaml (только чтение)") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.Bg)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        text = defaultYamlDialog!!,
                        fontFamily = MonoFamily,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Palette.TextSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { defaultYamlDialog = null }) {
                    Text("Закрыть", color = Palette.Good)
                }
            },
        )
    }
}

@Composable
private fun GlossaryItem(term: String, desc: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(term, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    }
}
