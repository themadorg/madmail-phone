package org.madmail.mobile

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.madmail.mobile.core.GuestAddress
import org.madmail.mobile.engine.GuestNetworks

/**
 * The phone's guest-reachable addresses, kept current as Wi-Fi, mobile data, hotspots and VPNs come and go. Null until the first read.
 * A hotspot isn't always reported to ConnectivityManager, so the list is also re-read every few seconds.
 */
@Composable
internal fun rememberGuestNetworks(): State<List<GuestAddress>?> {
    val context = LocalContext.current.applicationContext
    return produceState<List<GuestAddress>?>(null, context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val changes = Channel<Unit>(Channel.CONFLATED)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { changes.trySend(Unit) }
            override fun onLost(network: Network) { changes.trySend(Unit) }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { changes.trySend(Unit) }
        }
        // The default request leaves VPNs out; guests can reach the phone over one, so ask for those too.
        val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        val registered = runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess
        try {
            while (true) {
                value = withContext(Dispatchers.IO) { runCatching { GuestNetworks.phone(context) }.getOrDefault(emptyList()) }
                withTimeoutOrNull(5_000) { changes.receive() }
            }
        } finally {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}

/**
 * Shown on the phone when it has no usable address at all: no Wi-Fi, mobile data, hotspot or VPN.
 * Says what to do and opens the right settings.
 */
@Composable
internal fun NoNetworkNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Group(modifier) {
        InfoRow(Tint.Orange, Icons.Rounded.WifiOff, "No network", "Guests can’t reach this phone. Join Wi-Fi, turn on mobile data or a hotspot, or connect a VPN.")
        RowDivider(64.dp)
        ActionRow("Wi-Fi settings", { context.openWifiSettings() }, icon = Icons.Rounded.Wifi)
        RowDivider(48.dp)
        // Tethering has no public settings action; fall back to the network page that links to it.
        ActionRow("Hotspot settings", { context.openSettings(TETHER_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS) }, icon = Icons.Rounded.WifiTethering)
    }
}

internal const val TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"

internal fun Context.openSettings(vararg actions: String) {
    actions.firstOrNull { action -> runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
}

/** The Wi-Fi panel slides up over the app on Android 10 and later; older phones open the full page. */
internal fun Context.openWifiSettings() = openSettings(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
