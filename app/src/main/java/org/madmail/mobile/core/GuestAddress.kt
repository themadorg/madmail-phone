package org.madmail.mobile.core

internal enum class GuestNetwork(val label: String) { Wifi("Wi-Fi"), Hotspot("Hotspot"), Vpn("VPN / Tailscale"), Ethernet("Ethernet"), Mobile("Mobile data"), Server("Server network") }
internal data class GuestAddress(val host: String, val network: GuestNetwork, val device: String) {
    val origin: String get() = "https://$host:18443"
}
internal object GuestAddresses {
    // Address ranges alone cannot identify Tailscale: real Wi-Fi and mobile networks also use CGNAT.
    // Mobile data counts too: some carriers route their own subscribers to each other, so guests in the same country can reach it.
    fun classify(device: String, vpn: Boolean = false, wifi: Boolean = false, remote: Boolean = false, cellular: Boolean = false): GuestNetwork? = when {
        vpn || device.startsWith("tun") || device.startsWith("wg") || device.startsWith("tailscale") -> GuestNetwork.Vpn
        device.startsWith("ap") || device.startsWith("swlan") -> GuestNetwork.Hotspot
        wifi || device.startsWith("wlan") || device.startsWith("wifi") -> GuestNetwork.Wifi
        device.startsWith("eth") || device.startsWith("en") -> GuestNetwork.Ethernet
        cellular || cellularDevices.any(device::startsWith) -> GuestNetwork.Mobile
        remote && device != "lo" -> GuestNetwork.Server
        else -> null
    }
    // Qualcomm, MediaTek and other modem interfaces, plus the 464XLAT tunnel Android adds on IPv6-only carriers.
    private val cellularDevices = listOf("rmnet", "ccmni", "pdp", "wwan", "seth", "clat", "v4-")
    fun ipv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } && parts[0].toInt() !in listOf(0, 127) && parts[0].toInt() < 224 && !value.startsWith("169.254.") && !value.startsWith("192.0.0.")
    }
    fun remote(output: String): List<GuestAddress> = output.lineSequence().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        val at = fields.indexOf("inet")
        if (fields.size < 4 || at < 0 || at + 1 >= fields.size) return@mapNotNull null
        val host = fields[at + 1].substringBefore('/')
        val device = fields[1].substringBefore('@')
        val kind = classify(device, remote = true) ?: return@mapNotNull null
        if (ipv4(host)) GuestAddress(host, kind, device) else null
    }.distinctBy { it.host }.toList()
    fun origin(host: String): String? = InviteLinks.origin("https://${if (host.contains(':') && !host.startsWith('[')) "[$host]" else host}:18443")
}
