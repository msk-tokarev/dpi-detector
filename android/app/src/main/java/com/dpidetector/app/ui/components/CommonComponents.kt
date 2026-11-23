package com.dpidetector.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpidetector.app.model.Status
import com.dpidetector.app.model.TaskState
import com.dpidetector.app.model.WebhostItem
import com.dpidetector.app.ui.theme.MonoFamily
import com.dpidetector.app.ui.theme.Palette
import java.util.Locale

@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Palette.Border,
    containerColor: Color = Palette.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content,
        )
    }
}

fun levelColors(level: Status.Level): Pair<Color, Color> = when (level) {
    Status.Level.GOOD -> Palette.Good to Palette.GoodBg
    Status.Level.BAD -> Palette.Bad to Palette.BadBg
    Status.Level.WARN -> Palette.Warn to Palette.WarnBg
    Status.Level.NEUTRAL -> Palette.Neutral to Palette.NeutralBg
}

@Composable
fun StatusBadge(
    text: String,
    level: Status.Level,
    modifier: Modifier = Modifier,
) {
    val (fg, bg) = levelColors(level)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(fg),
        )
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TaskPill(
    label: String,
    state: TaskState,
    modifier: Modifier = Modifier,
) {
    val (fg, bg) = when (state) {
        TaskState.OK -> Palette.Good to Palette.GoodBg
        TaskState.ERROR -> Palette.Bad to Palette.BadBg
        TaskState.TIMEOUT -> Palette.Warn to Palette.WarnBg
        TaskState.RUNNING -> Palette.Accent to Palette.AccentBg
        TaskState.PENDING -> Palette.Neutral to Palette.NeutralBg
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state == TaskState.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.6.dp,
                color = fg,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(fg),
            )
        }
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextPrimary,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

fun localizeSectionName(name: String): String = when (name) {
    "Popular Web Services" -> "Популярные сервисы"
    "Foreign Infrastructure Providers" -> "Зарубежные провайдеры"
    "Russian Infrastructure Providers" -> "Российские провайдеры"
    "Mobile Push Notification Providers" -> "Мобильные Push-сервисы"
    else -> name
}

fun localizeDnsStatus(s: Status): String = when (s.code) {
    "OK" -> "OK"
    "SKIP" -> "Пропущен"
    "PROVIDER_HIJACKING" -> "Перехват провайдера"
    "RESPONSE_SPOOFING" -> "Подмена ответа"
    "NXDOMAIN_SPOOFING" -> "NXDOMAIN-спуфинг"
    "BOOTSTRAP_SPOOFING" -> "Подмена bootstrap"
    "EMPTY_BOOTSTRAP" -> "Пустой bootstrap"
    "INVALID_HTTPS_CERT" -> "Неверный TLS-серт"
    "NON_2XX_RESP" -> "Ошибка HTTP"
    "PROVIDER_TIMEOUT" -> "Таймаут провайдера"
    "LOOKUP_ERR" -> "Ошибка DNS"
    "INETUTIL_ERR" -> localizeNetError(s.msg)
    else -> s.msg.ifBlank { s.code }
}

fun localizeNetError(msg: String): String = when {
    msg.contains("blocked by sni", true) -> "Блок по SNI"
    msg.contains("connection reset", true) -> "Сброс TCP (RST)"
    msg.contains("connection timeout", true) -> "Таймаут TCP"
    msg.contains("handshake timeout", true) -> "Таймаут TLS"
    msg.contains("handshake failure", true) -> "Сбой TLS"
    msg.contains("read timeout", true) -> "Таймаут чтения"
    msg.contains("write timeout", true) -> "Таймаут записи"
    msg.contains("certificate invalid", true) -> "Неверный серт."
    msg.contains("broken write pipe", true) -> "Разрыв записи"
    msg.contains("custom HTTP", true) -> "OK (нестанд. HTTP)"
    msg.isBlank() -> "—"
    else -> msg
}

fun formatKbps(kbps: Double?): String {
    if (kbps == null || kbps.isNaN() || kbps <= 0.0) return "—"
    return if (kbps >= 1000.0) {
        String.format(Locale.US, "%.1f Мбит/с", kbps / 1000.0)
    } else {
        String.format(Locale.US, "%.0f Кбит/с", kbps)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebhostItemCard(
    item: WebhostItem,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(item.ip, item.group) { mutableStateOf(initiallyExpanded) }
    val hasIssue = !item.reachable ||
        item.sniBlocked ||
        item.tcp1620.code == "DETECTED" ||
        item.siberian.code == "DETECTED"

    val border = when {
        item.sniBlocked || item.tcp1620.code == "DETECTED" || item.siberian.code == "DETECTED" ->
            Palette.Bad.copy(alpha = 0.45f)
        !item.reachable -> Palette.Warn.copy(alpha = 0.45f)
        else -> Palette.Border
    }

    PanelCard(
        borderColor = border,
        modifier = Modifier.clickable { expanded = !expanded },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.group.ifBlank { item.ip },
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(
                    item.ip.takeIf { it.isNotBlank() },
                    item.asn.takeIf { it.isNotBlank() && it != "AS0" },
                    item.location.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
                        fontFamily = MonoFamily,
                        fontSize = 11.sp,
                        color = Palette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Palette.TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val aliveLabel = when {
                item.sniBlocked -> "SNI-блок"
                item.alive.code == "OK" -> "Доступен"
                item.alive.code == "OK_CUSTOM_HTTP" -> "Доступен (HTTP)"
                else -> localizeNetError(item.alive.msg)
            }
            val aliveLevel = when {
                item.sniBlocked -> Status.Level.BAD
                else -> item.alive.level
            }
            StatusBadge(text = aliveLabel, level = aliveLevel)

            if (item.tcp1620.code != "SKIP" && item.tcp1620.code.isNotBlank()) {
                val tLabel = when (item.tcp1620.code) {
                    "DETECTED" -> "TCP 16-20: БЛОК"
                    "OK" -> "TCP 16-20: нет"
                    "NOT_SUPPORTED_BY_HOST" -> "TCP 16-20: н/п хостом"
                    else -> "TCP 16-20: ${localizeNetError(item.tcp1620.msg)}"
                }
                StatusBadge(text = tLabel, level = item.tcp1620.level)
            }

            if (item.siberian.code != "SKIP" && item.siberian.code.isNotBlank()) {
                val sLabel = when (item.siberian.code) {
                    "DETECTED" -> "Сибирский: БЛОК"
                    "OK" -> "Сибирский: нет"
                    else -> "Сибирский: ${localizeNetError(item.siberian.msg)}"
                }
                StatusBadge(text = sLabel, level = item.siberian.level)
            }

            if (item.rxKbps != null || item.txKbps != null) {
                StatusBadge(
                    text = "↑ ${formatKbps(item.txKbps)}  ↓ ${formatKbps(item.rxKbps)}",
                    level = Status.Level.NEUTRAL,
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.SurfaceAlt)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (item.org.isNotBlank()) DetailRow("Провайдер / Org", item.org)
                if (item.prefix.isNotBlank() && item.prefix != "0.0.0.0/0") DetailRow("Подсеть (CIDR)", item.prefix, mono = true)
                if (item.tls != null) {
                    val ver = item.tls.version.ifBlank { "—" }
                    DetailRow("TLS", "TLS $ver", mono = true)
                    if (item.tls.cn.isNotBlank()) DetailRow("Cert CN", item.tls.cn, mono = true)
                    if (item.tls.san.isNotEmpty()) {
                        val preview = item.tls.san.take(4).joinToString(", ") +
                            if (item.tls.san.size > 4) " (+${item.tls.san.size - 4})" else ""
                        DetailRow("Cert SAN", preview, mono = true)
                    }
                }
                if (item.txKbps != null || item.rxKbps != null) {
                    DetailRow("Burst TX / RX", "${formatKbps(item.txKbps)} / ${formatKbps(item.rxKbps)}", mono = true)
                }
                if (hasIssue && item.alive.msg.isNotBlank() && !item.reachable) {
                    DetailRow("Причина Alive", "${item.alive.msg} (${item.alive.reason})", mono = true)
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (mono) MonoFamily else MaterialTheme.typography.bodySmall.fontFamily,
                fontWeight = FontWeight.Medium,
            ),
            color = Palette.TextPrimary,
        )
    }
}
