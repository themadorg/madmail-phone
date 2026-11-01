package org.madmail.mobile.core

internal enum class SettingKind { Toggle, Text, Port, PositiveNumber, Duration, Size, Choice, Secret, Managed }
internal data class ServerSetting(
    val key: String, val title: String, val section: String,
    val kind: SettingKind = SettingKind.Text,
    val hint: String = "", val route: String = "/admin/settings/$key",
    val choices: List<String> = emptyList(), val restart: Boolean = false,
) {
    fun display(value: String, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val match = Regex("([0-9]+)([BKMGbdhms])").matchEntire(value)
        if (kind == SettingKind.Size && match != null) {
            val unit = mapOf("B" to "B", "b" to "B", "K" to "KiB", "M" to "MiB", "G" to "GiB")[match.groupValues[2]]
            if (unit != null) return "${java.text.NumberFormat.getIntegerInstance(locale).format(match.groupValues[1].toBigInteger())} $unit"
        }
        if (kind == SettingKind.Duration && match != null) {
            val unit = mapOf("d" to "day", "h" to "hour", "m" to "minute", "s" to "second")[match.groupValues[2]]
            if (unit != null) return "${java.text.NumberFormat.getIntegerInstance(locale).format(match.groupValues[1].toBigInteger())} $unit${if (match.groupValues[1] == "1") "" else "s"}"
        }
        return value
    }
    fun valid(value: String, phone: Boolean): Boolean {
        if (value.isBlank() || value.length > 4096 || value.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        return when (kind) {
            SettingKind.Port -> (value.toIntOrNull() ?: 0) in (if (phone) 1024 else 1)..65535
            SettingKind.PositiveNumber -> (value.toLongOrNull() ?: 0) > 0
            SettingKind.Duration -> Regex("(?:[0-9]+(?:\\.[0-9]+)?(?:d|h|ms|m|s))+").matches(value)
            SettingKind.Size -> value == "0" || Regex("[0-9]+[BKMGb](?: +[0-9]+[BKMGb])*").matches(value)
            SettingKind.Choice -> value in choices
            else -> true
        }
    }
}

internal val serverSettings = buildList {
    fun toggle(key: String, title: String, section: String, route: String, hint: String = "", restart: Boolean = false) { add(ServerSetting(key, title, section, SettingKind.Toggle, hint, route, restart = restart)) }
    fun value(key: String, title: String, section: String, kind: SettingKind = SettingKind.Text, hint: String = "", choices: List<String> = emptyList(), restart: Boolean = false) { add(ServerSetting(key, title, section, kind, hint, choices = choices, restart = restart)) }
    toggle("registration", "Open registration", "registration", "/admin/registration", "Allow guests to create accounts without an invitation token.")
    toggle("jit_registration", "Create accounts on first login", "registration", "/admin/registration/jit", "Automatically create an account when new credentials first sign in.")
    toggle("registration_token_required", "Require an invitation", "registration", "/admin/settings/registration_token_required", "Require a valid token for account creation.")
    toggle("auto_purge_seen_enabled", "Clean up read messages", "storage", "/admin/services/auto_purge_seen", "Automatically remove messages after they have been read.")
    toggle("message_retention_enabled", "Limit message retention", "storage", "/admin/services/message_retention")
    value("message_retention", "Keep messages for", "storage", SettingKind.Duration, "For example: 30d or 24h.")
    value("appendlimit", "IMAP upload limit", "storage", SettingKind.Size, "For example: 25M.")
    value("max_message_size", "SMTP message limit", "storage", SettingKind.Size, "For example: 25M.")
    listOf("smtp_port" to "SMTP", "submission_port" to "Submission", "submission_tls_port" to "SMTP TLS", "imap_port" to "IMAP", "imap_tls_port" to "IMAP TLS", "http_port" to "Admin HTTP", "https_port" to "HTTPS").forEach { (key, title) -> value(key, title, "network", SettingKind.Managed, "Managed by the app’s hosting configuration.") }
    value("smtp_hostname", "SMTP hostname", "network", hint = "The server name announced to mail clients.", restart = true)
    toggle("shared_port_imap", "IMAP on the HTTPS port", "network", "/admin/settings/shared_port_imap", restart = true)
    toggle("shared_port_smtp", "SMTP on the HTTPS port", "network", "/admin/settings/shared_port_smtp", restart = true)
    toggle("turn_enabled", "TURN relay", "services", "/admin/services/turn", "Relay calls when a direct connection is unavailable.", true)
    toggle("iroh_enabled", "Iroh relay", "services", "/admin/services/iroh", restart = true)
    toggle("push_enabled", "Push notifications", "services", "/admin/services/push", restart = true)
    toggle("webimap_enabled", "Browser IMAP", "services", "/admin/services/webimap")
    toggle("websmtp_enabled", "Browser SMTP", "services", "/admin/services/websmtp")
    value("webmail_cors_origins", "Allowed browser origins", "services", hint = "HTTPS origins separated by commas; * allows all origins.")
    value("turn_port", "TURN port", "services", SettingKind.Port, restart = true)
    value("turn_realm", "TURN realm", "services", restart = true)
    value("turn_secret", "TURN secret", "services", SettingKind.Secret, restart = true)
    value("turn_relay_ip", "TURN relay IP", "services", restart = true)
    value("turn_relay_port_min", "First relay port", "services", SettingKind.Port, restart = true)
    value("turn_relay_port_max", "Last relay port", "services", SettingKind.Port, restart = true)
    value("turn_ttl", "TURN credential lifetime", "services", SettingKind.PositiveNumber, "Seconds", restart = true)
    value("iroh_relay_url", "Iroh relay URL", "services", restart = true)
    toggle("federation_enabled", "Federation", "federation", "/admin/settings/federation", "Exchange messages with other mail servers.")
    add(ServerSetting("federation_policy", "Delivery policy", "federation", SettingKind.Choice, route = "/admin/settings/federation", choices = listOf("ACCEPT", "REJECT")))
    value("max_federation_size", "Federation message limit", "federation", SettingKind.Size, "For example: 25M.")
    toggle("ss_enabled", "Shadowsocks", "proxies", "/admin/services/shadowsocks", restart = true)
    value("ss_port", "Shadowsocks port", "proxies", SettingKind.Port, restart = true)
    value("ss_cipher", "Encryption cipher", "proxies", restart = true)
    value("ss_password", "Shadowsocks password", "proxies", SettingKind.Secret, restart = true)
    toggle("http_proxy_enabled", "HTTP proxy", "proxies", "/admin/services/http_proxy", restart = true)
    value("http_proxy_port", "HTTP proxy port", "proxies", SettingKind.Port, restart = true)
    value("http_proxy_path", "Proxy path", "proxies", restart = true)
    value("http_proxy_username", "Proxy username", "proxies", restart = true)
    value("http_proxy_password", "Proxy password", "proxies", SettingKind.Secret, restart = true)
    toggle("admin_web_enabled", "Web admin dashboard", "administration", "/admin/services/admin_web")
    value("language", "Server web language", "administration", hint = "Language code supported by this server, for example en or fa.")
    value("admin_path", "Admin API path", "administration", SettingKind.Managed, "Kept stable for the app’s authenticated connection.")
    value("admin_web_path", "Web dashboard path", "administration", hint = "For example: /admin", restart = true)
}
