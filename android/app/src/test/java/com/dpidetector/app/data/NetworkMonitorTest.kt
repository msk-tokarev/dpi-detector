// Author: by Ivan Tokarev
package com.dpidetector.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkMonitorTest {

    @Test
    fun detectsWifiCellularEthernetVpnAndOffline() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = shadowOf(cm)
        val active = cm.activeNetwork!!

        fun setCaps(vararg transports: Int, internet: Boolean = true) {
            val nc = ShadowNetworkCapabilities.newInstance()
            val snc = shadowOf(nc)
            if (internet) snc.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            transports.forEach { snc.addTransportType(it) }
            shadowCm.setNetworkCapabilities(active, nc)
        }

        val monitor = NetworkMonitor(ctx)

        setCaps(NetworkCapabilities.TRANSPORT_WIFI)
        monitor.refresh()
        assertTrue(monitor.state.value.online)
        assertEquals("Wi-Fi", monitor.state.value.transport)
        assertFalse(monitor.state.value.vpnActive)

        setCaps(NetworkCapabilities.TRANSPORT_CELLULAR)
        monitor.refresh()
        assertEquals("Мобильная сеть", monitor.state.value.transport)

        setCaps(NetworkCapabilities.TRANSPORT_ETHERNET)
        monitor.refresh()
        assertEquals("Ethernet", monitor.state.value.transport)

        setCaps(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN)
        monitor.refresh()
        assertEquals("Wi-Fi", monitor.state.value.transport)
        assertTrue(monitor.state.value.vpnActive)

        setCaps(NetworkCapabilities.TRANSPORT_VPN)
        monitor.refresh()
        assertEquals("VPN", monitor.state.value.transport)
        assertTrue(monitor.state.value.vpnActive)

        setCaps(internet = false)
        monitor.refresh()
        assertFalse(monitor.state.value.online)
        assertEquals("Нет сети", monitor.state.value.transport)
    }
}
