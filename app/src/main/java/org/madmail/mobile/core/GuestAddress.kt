package org.madmail.mobile.core

internal enum class GuestNetwork(val label: String) { Wifi("Wi-Fi"), Hotspot("Hotspot"), Vpn("VPN / Tailscale"), Ethernet("Ethernet"), Server("Server network") }
internal data class GuestAddress(val host: String, val network: GuestNetwork, val device: String) {
    val origin: String get() = "https://$host:18443"
}
internal object GuestAddresses {
    // Address ranges alone cannot identify Tailscale: real Wi-Fi and mobile networks also use CGNAT.
    fun classify(device: String, vpn: Boolean = false, wifi: Boolean = false, remote: Boolean = false): GuestNetwork? = when {
        vpn || device.startsWith("tun") || device.startsWith("wg") || device.startsWith("tailscale") -> GuestNetwork.Vpn
        device.startsWith("ap") || device.startsWith("swlan") -> GuestNetwork.Hotspot
        wifi || device.startsWith("wlan") || device.startsWith("wifi") -> GuestNetwork.Wifi
        device.startsWith("eth") || device.startsWith("en") -> GuestNetwork.Ethernet
        remote && device != "lo" -> GuestNetwork.Server
        else -> null
    }
    fun ipv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } && parts[0].toInt() !in listOf(0, 127) && parts[0].toInt() < 224 && !value.startsWith("169.254.")
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
