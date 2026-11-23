// Author: by Ivan Tokarev
package com.dpidetector.app.ui.components

import com.dpidetector.app.model.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingAndLocalizationTest {

    @Test
    fun localizeSectionNames() {
        assertEquals("Популярные сервисы", localizeSectionName("Popular Web Services"))
        assertEquals("Зарубежные провайдеры", localizeSectionName("Foreign Infrastructure Providers"))
        assertEquals("Российские провайдеры", localizeSectionName("Russian Infrastructure Providers"))
        assertEquals("Мобильные Push-сервисы", localizeSectionName("Mobile Push Notification Providers"))
        assertEquals("My Custom Section", localizeSectionName("My Custom Section"))
    }

    @Test
    fun localizeDnsStatusesCoversEveryCode() {
        val cases = mapOf(
            "OK" to "OK",
            "SKIP" to "Пропущен",
            "PROVIDER_HIJACKING" to "Перехват провайдера",
            "RESPONSE_SPOOFING" to "Подмена ответа",
            "NXDOMAIN_SPOOFING" to "NXDOMAIN-спуфинг",
            "BOOTSTRAP_SPOOFING" to "Подмена bootstrap",
            "EMPTY_BOOTSTRAP" to "Пустой bootstrap",
            "INVALID_HTTPS_CERT" to "Неверный TLS-серт",
            "NON_2XX_RESP" to "Ошибка HTTP",
            "PROVIDER_TIMEOUT" to "Таймаут провайдера",
            "LOOKUP_ERR" to "Ошибка DNS",
        )
        cases.forEach { (code, expected) ->
            assertEquals(expected, localizeDnsStatus(Status("raw", code, "NONE")))
        }
        assertEquals("Сброс TCP (RST)", localizeDnsStatus(Status("tcp: connection reset", "INETUTIL_ERR", "NONE")))
        assertEquals("Custom msg", localizeDnsStatus(Status("Custom msg", "OTHER", "NONE")))
    }

    @Test
    fun localizeNetErrorsCoversAllPatterns() {
        assertEquals("Блок по SNI", localizeNetError("TLS blocked by SNI"))
        assertEquals("Сброс TCP (RST)", localizeNetError("tcp: connection reset"))
        assertEquals("Таймаут TCP", localizeNetError("tcp: connection timeout"))
        assertEquals("Таймаут TLS", localizeNetError("tls: handshake timeout"))
        assertEquals("Сбой TLS", localizeNetError("tls: handshake failure"))
        assertEquals("Таймаут чтения", localizeNetError("tcp: read timeout"))
        assertEquals("Таймаут записи", localizeNetError("tcp: write timeout"))
        assertEquals("Неверный серт.", localizeNetError("tls: certificate invalid"))
        assertEquals("Разрыв записи", localizeNetError("tls: broken write pipe"))
        assertEquals("OK (нестанд. HTTP)", localizeNetError("Ok, custom HTTP"))
        assertEquals("—", localizeNetError(""))
        assertEquals("custom err", localizeNetError("custom err"))
    }

    @Test
    fun formatKbpsFormatsMbpsKbpsAndInvalid() {
        assertEquals("—", formatKbps(null))
        assertEquals("—", formatKbps(Double.NaN))
        assertEquals("—", formatKbps(0.0))
        assertEquals("—", formatKbps(-10.0))
        assertEquals("640 Кбит/с", formatKbps(640.0))
        assertEquals("1.0 Мбит/с", formatKbps(1000.0))
        assertEquals("12.5 Мбит/с", formatKbps(12500.0))
    }
}
