package org.madmail.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.madmail.mobile.core.RemoteServer

private data class SettingsSection(val name: String, val summary: String, val features: List<Pair<String, String>>)

// Feature inventory adapted from madmail-admin-web, not copied UI or server defaults.
private val adminSections = listOf(
    SettingsSection("Registration & accounts", "Sign-ups, invitation tokens and account limits", listOf(
        "Registration" to "Open or close account creation; control just-in-time registration.",
        "Invitation tokens" to "Require tokens, set use limits and expiration, or revoke a token.",
        "Accounts & quotas" to "Create and remove accounts; set default and per-account storage limits.",
        "Blocked accounts" to "View the blocklist and block or unblock accounts."
    )),
    SettingsSection("Mail & storage", "Message limits, retention and queue maintenance", listOf(
        "Message size" to "Configure IMAP append and SMTP message size limits.",
        "Retention" to "Set message retention and automatic cleanup of seen messages.",
        "Storage & queue" to "Inspect storage and purge queued mail or blobs with confirmation."
    )),
    SettingsSection("Network & ports", "Listener ports and access restrictions", listOf(
        "Mail listeners" to "SMTP, submission and IMAP ports, including TLS listeners.",
        "Web listeners" to "HTTP, HTTPS and shared HTTPS-port IMAP/SMTP support.",
        "Access restrictions" to "Public or local access for supported listeners.",
        "Server identity" to "SMTP hostname and client login security settings."
    )),
    SettingsSection("Services", "Calls, push delivery and browser access", listOf(
        "Calls & relays" to "TURN and Iroh switches, relay addresses and TURN relay port range.",
        "Push delivery" to "Push enablement and delivery mode.",
        "Browser access" to "Web IMAP/SMTP, admin web interface and permitted CORS origins."
    )),
    SettingsSection("Federation", "Delivery policy, DNS and remote relays", listOf(
        "Federation policy" to "Enable federation, choose a policy and manage domain rules.",
        "DNS & DKIM" to "DNS overrides, DKIM status and signing checks.",
        "Exchangers & traffic" to "Manage exchangers and inspect federation traffic and size limits."
    )),
    SettingsSection("Proxies", "Shadowsocks and HTTP proxy configuration", listOf(
        "Shadowsocks" to "Native, WebSocket and gRPC listeners, cipher and credentials.",
        "HTTP proxy" to "Enablement, listener port, path and authentication."
    )),
    SettingsSection("Administration", "Notices, logging and server lifecycle", listOf(
        "Notices" to "Send a notice to an account or all accounts.",
        "Logging & interface" to "Logging, interface language and admin paths.",
        "Reload & restart" to "Reload HTTP routes or all listeners, or restart the service."
    ))
)

@Composable
internal fun ServerSettingsScreen(serverId: String, serverName: String, remote: RemoteServer?, initiallyShowHosting: Boolean = false, onRemove: () -> Unit) {
    Text("Settings for $serverName", style = MaterialTheme.typography.titleLarge)
    Text("Hosting and version controls are on Overview. The settings below describe the available admin features; editing them is not connected yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (remote == null) {
        var hosting by remember(serverId) { mutableStateOf(initiallyShowHosting) }
        OutlinedButton(onClick = { hosting = !hosting }) { Text(if (hosting) "Hide phone hosting preferences" else "Phone hosting preferences") }
        if (hosting) PhoneHostingSettings(serverId)
    } else {
        Text("SSH connection", style = MaterialTheme.typography.titleMedium)
        Text(remote.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    adminSections.forEach { section ->
        var expanded by remember(serverId, section.name) { mutableStateOf(false) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(section.name, style = MaterialTheme.typography.titleMedium)
                    Text(section.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse ${section.name}" else "Expand ${section.name}")
            }
        }
        if (expanded) section.features.forEach { (title, detail) ->
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Not loaded · server connection required", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (remote != null) {
        var confirm by remember(serverId) { mutableStateOf(false) }
        OutlinedButton(onClick = { confirm = true }) { Text("Remove saved server") }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Remove $serverName?") }, text = { Text("Only this saved connection is removed. Nothing on the remote server is changed.") }, confirmButton = { TextButton(onClick = onRemove) { Text("Remove") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    }
}
