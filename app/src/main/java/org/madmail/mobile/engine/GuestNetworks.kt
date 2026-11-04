package org.madmail.mobile.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.madmail.mobile.core.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

internal object GuestNetworks {
    @Suppress("DEPRECATION")
    fun phone(context: Context): List<GuestAddress> {
        val result = mutableListOf<GuestAddress>()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // Inspect underlying networks as well as the default VPN. Never label a VPN as Wi-Fi.
        runCatching { manager.allNetworks.forEach { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@forEach
            val links = manager.getLinkProperties(network) ?: return@forEach
            val device = links.interfaceName ?: return@forEach
            val kind = GuestAddresses.classify(device, caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN), caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) ?: return@forEach
            links.linkAddresses.forEach { link ->
                val host = link.address.hostAddress ?: return@forEach
                if (link.address is Inet4Address && GuestAddresses.ipv4(host)) result += GuestAddress(host, kind, device)
            }
        } }
        // Hotspot and 464XLAT interfaces may not be registered with ConnectivityManager.
        runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.forEach { device ->
            val kind = GuestAddresses.classify(device.name) ?: return@forEach
            device.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { address ->
                val host = address.hostAddress ?: return@forEach
                if (GuestAddresses.ipv4(host)) result += GuestAddress(host, kind, device.name)
            }
        } }
        return result.distinctBy { it.host }.sortedBy { it.network.ordinal }
    }
    fun checkPorts(origin: String): List<Pair<Int, Boolean>> {
        val uri = URI(requireNotNull(InviteLinks.origin(origin)))
        return listOf(uri.port.takeIf { it > 0 } ?: 443, 11993, 11465).distinct().map { port ->
            port to runCatching { Socket().use { it.connect(InetSocketAddress(uri.host.removeSurrounding("[", "]"), port), 2000) } }.isSuccess
        }
    }
}
