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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpidetector.app.data.EngineUiState
import com.dpidetector.app.ui.components.PanelCard
import com.dpidetector.app.ui.components.SectionHeader
import com.dpidetector.app.ui.components.WebhostItemCard
import com.dpidetector.app.ui.theme.MonoFamily
import com.dpidetector.app.ui.theme.Palette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostProbeScreen(
    state: EngineUiState,
    onProbe: (target: String, sni: String, port: Int) -> Unit,
    onClearHistory: () -> Unit,
) {
    var target by rememberSaveable { mutableStateOf("") }
    var sni by rememberSaveable { mutableStateOf("") }
    var portStr by rememberSaveable { mutableStateOf("443") }

    val presets = listOf(
        "www.youtube.com",
        "discord.com",
        "x.com",
        "www.instagram.com",
        "api.telegram.org",
        "github.com",
        "1.1.1.1",
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
        PanelCard {
            Text("Проверка своего хоста", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Проверяет доступность, SNI-блокировку, TCP 16-20 (L4-25), «сибирские» ограничения, TLS-сертификат и burst-скорость для любого домена или IPv4 (в т.ч. вашего VPS).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Домен или IPv4") },
                placeholder = { Text("например, youtube.com или 1.1.1.1", color = Palette.TextMuted) },
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

        Spacer(Modifier.height(16.dp))
    }
}
