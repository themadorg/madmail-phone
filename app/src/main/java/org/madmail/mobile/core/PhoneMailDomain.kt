package org.madmail.mobile.core

/**
 * The phone server's mail domain is always one of its IPv4 addresses, written as `[ip]`.
 * Every domain the install has served stays in local_domains, so older accounts keep
 * signing in and receiving mail after the address changes. Nothing is ever removed.
 */
internal object PhoneMailDomain {
    private val token = Regex("""^\[?[A-Za-z0-9.\-]+]?$""")
    private fun canon(domain: String): String {
        val bare = domain.trim().removeSurrounding("[", "]")
        return if (GuestAddresses.ipv4(bare)) "[$bare]" else domain.trim().lowercase()
    }
    private fun field(toml: String, key: String) = Regex("""(?m)^\s*$key\s*=\s*"([^"]*)"\s*$""").find(toml)?.groupValues?.get(1)

    /** Domains a config file has served: its primary domain and its local_domains. */
    fun previous(toml: String?): Pair<String?, List<String>> {
        if (toml == null) return null to emptyList()
        val primary = field(toml, "primary_domain")?.takeIf { it.isNotBlank() && token.matches(it) }?.let(::canon)
        val locals = field(toml, "local_domains").orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() && token.matches(it) }.map(::canon)
        return primary to locals
    }

    /** Saved guest IP while it is still on this phone, then [preferred], then the last IP used. Never loopback, never a name. */
    fun resolve(savedOrigin: String?, detected: List<GuestAddress>, previousPrimary: String?): String? {
        val saved = savedOrigin?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        return saved?.takeIf { host -> GuestAddresses.ipv4(host) && detected.any { it.host == host } }
            ?: preferred(detected)?.host
            ?: previousPrimary?.removeSurrounding("[", "]")?.takeIf(GuestAddresses::ipv4)
    }

    /**
     * Nearby networks first, then a VPN, then mobile data. A VPN was set up on purpose; whether a carrier lets
     * guests reach a mobile address depends on the country, so it is the last resort.
     */
    fun preferred(detected: List<GuestAddress>): GuestAddress? = detected.firstOrNull { it.network != GuestNetwork.Vpn && it.network != GuestNetwork.Mobile }
        ?: detected.firstOrNull { it.network == GuestNetwork.Vpn } ?: detected.firstOrNull()

    fun render(ip: String, previousPrimary: String?, previousLocals: List<String>): String {
        require(GuestAddresses.ipv4(ip))
        val history = (previousLocals + listOfNotNull(previousPrimary)).map(::canon).distinct() - "[$ip]"
        return buildString {
            appendLine("hostname = \"$ip\"")
            appendLine("primary_domain = \"[$ip]\"")
            appendLine("public_ip = \"$ip\"")
            if (history.isNotEmpty()) appendLine("local_domains = \"${history.joinToString(" ")}\"")
            append("""
                tls_mode = "self_signed"
                auth_auto_create = true
                smtp_listen = "127.0.0.1:1125"
                submission_tls_listen = "0.0.0.0:11465"
                imap_tls_listen = "0.0.0.0:11993"
                http_listen = "127.0.0.1:18080"
                http_tls_listen = "0.0.0.0:18443"
                turn_enable = false
            """.trimIndent())
            appendLine()
        }
    }
}
