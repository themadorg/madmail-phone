package org.madmail.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.madmail.mobile.core.*
import org.madmail.mobile.engine.*
import java.text.NumberFormat

private data class SettingsPage(val id: String, val title: String, val subtitle: String, val icon: ImageVector)
private val settingsPages = listOf(
    SettingsPage("registration", "Registration", "Sign-ups and invitation requirements", Icons.Outlined.PersonAdd),
    SettingsPage("accounts", "Accounts", "Members and storage quotas", Icons.Outlined.PeopleOutline),
    SettingsPage("storage", "Mail & storage", "Message size, retention and cleanup", Icons.Outlined.Storage),
    SettingsPage("network", "Network & ports", "Listeners and server hostname", Icons.Outlined.SettingsEthernet),
    SettingsPage("services", "Calls & services", "Relays, push and browser access", Icons.Outlined.Widgets),
    SettingsPage("federation", "Federation", "Delivery policy and domain signing", Icons.Outlined.Hub),
    SettingsPage("proxies", "Proxies", "Shadowsocks and HTTP proxy", Icons.Outlined.VpnLock),
    SettingsPage("administration", "Administration", "Web dashboard and server restart", Icons.Outlined.AdminPanelSettings),
    SettingsPage("connection", "Connection", "Hosting and connection details", Icons.Outlined.Link),
)

@Composable
internal fun ServerSettingsScreen(serverId: String, serverName: String, remote: RemoteServer?, initiallyShowHosting: Boolean = false, onNavigate: () -> Unit = {}, onRemove: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val controller = remember(serverId) { SettingsController.get(context, serverId, remote) }
    val state by controller.state.collectAsState()
    val local by LocalEngine.get(context).state.collectAsState()
    val remoteState = remote?.let { RemoteEngine.get(context, it).state.collectAsState().value }
    val ready = if (remote == null) local.phase == EnginePhase.Running else remoteState?.connected == true && remoteState.status.startsWith("Running")
    var page by rememberSaveable(serverId) { mutableStateOf(if (initiallyShowHosting) "connection" else "root") }
    var editing by remember { mutableStateOf<ServerSetting?>(null) }
    var restart by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<JSONObject?>(null) }
    var deleteAccount by remember { mutableStateOf(false) }
    var quotaTarget by remember { mutableStateOf<String?>(null) }
    var quotaInitial by remember { mutableLongStateOf(0L) }
    val enabled = ready && !state.busy && !state.stale && state.values != null
    fun navigate(next: String) { page = next; onNavigate() }
    fun back() { navigate(if (page == "account") "accounts" else "root") }
    BackHandler(page != "root") { back() }
    LaunchedEffect(ready) { if (ready) controller.refresh() }
    LaunchedEffect(page, ready) { if (ready && page in listOf("accounts", "federation")) controller.loadDetails(page) }
    val title = settingsPages.find { it.id == page }?.title ?: if (page == "account") "Account" else "Settings"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page != "root") IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to settings") }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(serverName, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = { controller.refresh(); if (page in listOf("accounts", "federation")) controller.loadDetails(page) }, enabled = ready && !state.busy) { Icon(Icons.Outlined.Refresh, "Refresh settings", tint = colors.primary) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!ready) Text(if (remote == null) "Start hosting in Overview to load and change settings." else "Connect and start this server in Overview to load settings.", color = colors.onSurfaceVariant)
        state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
        state.message?.let { Text(it, color = colors.primary, style = MaterialTheme.typography.bodyMedium) }
        if (state.restartRequired) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.RestartAlt, null, tint = colors.primary)
            Text("Saved changes need a restart.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { restart = true }, enabled = ready && !state.busy && (remote == null || remoteState?.managed == true)) { Text("Restart") }
        }
        when (page) {
            "root" -> {
                SettingsGroup {
                    settingsPages.take(3).forEach { item -> SettingsNavigation(item.title, item.subtitle, item.icon) { navigate(item.id) } }
                }
                SettingsGroup {
                    settingsPages.slice(3..6).forEach { item -> SettingsNavigation(item.title, item.subtitle, item.icon) { navigate(item.id) } }
                }
                SettingsGroup {
                    settingsPages.drop(7).forEach { item -> SettingsNavigation(item.title, item.subtitle, item.icon) { navigate(item.id) } }
                }
                HorizontalDivider()
                AppLockSettings()
            }
            "connection" -> {
                if (remote != null) {
                    SettingsGroup {
                        if (remoteState?.transport == "API") SettingsCopyRow("Admin API", remoteState.apiEndpoint ?: "Not configured", Icons.Outlined.Api)
                        else {
                        SettingsCopyRow("SSH host", remote.host, Icons.Outlined.Dns)
                        SettingsCopyRow("Username", remote.user, Icons.Outlined.PersonOutline)
                        SettingsCopyRow("SSH port", remote.port.toString(), Icons.Outlined.SettingsEthernet)
                        val fingerprint = RemoteEngine.get(context, remote).fingerprint
                        fingerprint?.let { SettingsCopyRow("Host fingerprint", it, Icons.Outlined.Fingerprint) }
                        }
                    }
                    RemoteControls(remote)
                    TextButton(onClick = { remove = true }) { Icon(Icons.Outlined.DeleteOutline, null, tint = colors.error); Spacer(Modifier.width(8.dp)); Text("Remove saved server", color = colors.error) }
                } else {
                    SettingsGroup {
                        SettingsCopyRow("SMTP TLS", "11465", Icons.Outlined.Upload)
                        SettingsCopyRow("IMAP TLS", "11993", Icons.Outlined.Download)
                        SettingsCopyRow("HTTPS", "18443", Icons.Outlined.Lock)
                    }
                    Text("These hosting ports work without root. Guests connect through the phone’s Wi-Fi or hotspot address.", color = colors.onSurfaceVariant)
                    val prefs = remember { context.getSharedPreferences("server-settings-phone", Context.MODE_PRIVATE) }
                    var root by remember { mutableStateOf(prefs.getBoolean("root_ports", false)) }
                    if (root) {
                        Text("The saved standard-port preference prevents startup because root forwarding is not available yet.", color = colors.error)
                        Button(onClick = { prefs.edit().putBoolean("root_ports", false).apply(); root = false }) { Text("Use default ports") }
                    }
                }
            }
            "accounts" -> {
                val quota = state.quota
                val rows = state.accounts?.optJSONArray("accounts")
                if (ready && rows != null) {
                    Text("${NumberFormat.getIntegerInstance().format(rows.length())} accounts", style = MaterialTheme.typography.titleMedium)
                    SettingsGroup {
                        SettingsNavigation("Default storage quota", quota?.optLong("default_quota_bytes")?.let { Formatter.formatFileSize(context, it) } ?: "Not loaded", Icons.Outlined.Storage, enabled) { quotaTarget = ""; quotaInitial = quota?.optLong("default_quota_bytes") ?: 0L }
                    }
                    if (rows.length() == 0) Text("No accounts yet. Create an account QR from Invite.", color = colors.onSurfaceVariant)
                    SettingsGroup {
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            SettingsNavigation(row.getString("username"), "${Formatter.formatFileSize(context, row.optLong("used_bytes"))} used · ${Formatter.formatFileSize(context, row.optLong("max_bytes"))} quota", Icons.Outlined.PersonOutline) { account = row; navigate("account") }
                        }
                    }
                }
            }
            "account" -> account?.let { row ->
                val username = row.getString("username")
                val updated = state.accounts?.optJSONArray("accounts")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) }.find { it.optString("username") == username } } ?: row
                SettingsGroup {
                    SettingsCopyRow("Email address", username, Icons.Outlined.AlternateEmail)
                    SettingsCopyRow("Storage used", Formatter.formatFileSize(context, updated.optLong("used_bytes")), Icons.Outlined.Storage)
                    SettingsNavigation("Storage quota", Formatter.formatFileSize(context, updated.optLong("max_bytes")), Icons.Outlined.DataUsage, enabled) { quotaTarget = username; quotaInitial = updated.optLong("max_bytes") }
                }
                TextButton(onClick = { deleteAccount = true }, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, null, tint = colors.error); Spacer(Modifier.width(8.dp)); Text("Delete account", color = colors.error) }
            }
            else -> {
                SettingsGroup {
                    serverSettings.filter { it.section == page }.forEach { setting ->
                        val raw = if (ready) state.values?.opt(setting.key)?.takeUnless { it == JSONObject.NULL } else null
                        val current = if (raw is JSONObject) raw.optString("value") else raw?.toString()
                        val checked = when (current) { "enabled", "open", "true" -> true; "disabled", "closed", "false" -> false; else -> null }
                        val display = when {
                            current == null -> if (ready && state.values != null) "Not reported by this server" else "Not loaded"
                            current.isEmpty() -> "Hosting default"
                            setting.kind == SettingKind.Secret -> "••••••••"
                            setting.kind == SettingKind.PositiveNumber -> current.toLongOrNull()?.let { NumberFormat.getIntegerInstance().format(it) } ?: current
                            else -> setting.display(current)
                        }
                        if (setting.kind == SettingKind.Toggle) {
                            Row(Modifier.fillMaxWidth().then(if (checked != null) Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { controller.save(setting, it.toString()) }) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(settingIcon(setting), null, Modifier.size(22.dp), tint = colors.primary)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(setting.title, style = MaterialTheme.typography.bodyLarge)
                                    if (setting.hint.isNotBlank()) Text(setting.hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    if (checked == null) Text(display, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                                if (checked != null) Switch(checked = checked, onCheckedChange = null, enabled = enabled)
                                else Icon(Icons.Outlined.HelpOutline, "Status unknown", tint = colors.onSurfaceVariant)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().clickable(enabled = enabled && current != null && setting.kind != SettingKind.Managed) { editing = setting }.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(settingIcon(setting), null, Modifier.size(22.dp), tint = colors.primary)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(setting.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(display, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant)
                                }
                                if (!current.isNullOrBlank() && setting.kind != SettingKind.Secret) IconButton(onClick = { copySetting(context, setting.title, current) }) { Icon(Icons.Outlined.ContentCopy, "Copy ${setting.title}", Modifier.size(18.dp), tint = colors.primary) }
                                if (setting.kind != SettingKind.Managed) Icon(Icons.Outlined.Edit, null, Modifier.padding(end = 12.dp).size(18.dp), tint = colors.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 50.dp), color = colors.outlineVariant.copy(alpha = .6f))
                    }
                }
                if (page == "registration") Text("Create, share and revoke invitation tokens from the Invite tab.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (page == "network") Text("App-managed mail and web ports are read-only here to keep hosting, health checks and SSH tunnels reachable.", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (page == "federation" && ready) state.dkim?.let { dkim ->
                    Text("Domain signing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    SettingsGroup {
                        listOf("domain" to "Domain", "selector" to "Selector", "dns_name" to "DNS record", "txt" to "TXT value").forEach { (key, label) ->
                            dkim.optString(key).takeUnless { it.isBlank() || it == "null" }?.let { SettingsCopyRow(label, it, Icons.Outlined.VerifiedUser) }
                        }
                    }
                }
                if (page == "administration") {
                    SettingsNavigation("Restart server", if (remote == null) "Restart the phone’s Madmail process" else if (remoteState?.managed == true) "Restart the managed service over SSH" else "Requires the app-managed SSH service", Icons.Outlined.RestartAlt, ready && !state.busy && (remote == null || remoteState?.managed == true)) { restart = true }
                }
            }
        }
    }
    editing?.let { setting ->
        val raw = state.values?.opt(setting.key)
        val value = if (raw is JSONObject) raw.optString("value") else raw?.toString().orEmpty()
        SettingsEditor(setting, value, remote == null, onDismiss = { editing = null }, onSave = { next, reset -> controller.save(setting, next, reset); editing = null })
    }
    if (restart) AlertDialog(onDismissRequest = { restart = false }, title = { Text("Restart $serverName?") }, text = { Text("Active connections will briefly disconnect while saved settings take effect.") }, confirmButton = { TextButton(onClick = { controller.restart(); restart = false }) { Text("Restart") } }, dismissButton = { TextButton(onClick = { restart = false }) { Text("Cancel") } })
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Remove $serverName?") }, text = { Text("Only the saved connection is removed. The remote server and its data stay intact.") }, confirmButton = { TextButton(onClick = { SettingsController.forget(serverId); onRemove() }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
    if (deleteAccount && account != null) AlertDialog(onDismissRequest = { deleteAccount = false }, title = { Text("Delete account?") }, text = { Text("Permanently remove ${account!!.getString("username")} and its stored mail. The address will also be blocked from re-registering.") }, confirmButton = { TextButton(onClick = {
        controller.mutate("/admin/accounts", "DELETE", JSONObject().put("username", account!!.getString("username")), success = "Account deleted.", refreshAccounts = true)
        deleteAccount = false; navigate("accounts")
    }) { Text("Delete", color = colors.error) } }, dismissButton = { TextButton(onClick = { deleteAccount = false }) { Text("Cancel") } })
    quotaTarget?.let { username ->
        var draft by remember { mutableStateOf((quotaInitial / (1024 * 1024)).toString()) }
        val amount = draft.toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE / (1024 * 1024)) }
        AlertDialog(onDismissRequest = { quotaTarget = null }, title = { Text(if (username.isEmpty()) "Default storage quota" else "Account quota") }, text = { OutlinedTextField(draft, { draft = it.filter(Char::isDigit) }, label = { Text("Storage in MiB") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("1 MiB = 1,048,576 bytes") }) }, confirmButton = { TextButton(enabled = amount != null, onClick = {
            controller.mutate("/admin/quota", "PUT", JSONObject().put("max_bytes", amount!! * 1024 * 1024).apply { if (username.isNotEmpty()) put("username", username) }, success = "Storage quota saved.", refreshAccounts = true)
            quotaTarget = null
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { quotaTarget = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) { Column(content = content) }
}

@Composable
private fun SettingsNavigation(title: String, subtitle: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsCopyRow(title: String, value: String, icon: ImageVector) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Copy $title") { copySetting(context, title, value) }.heightIn(min = 64.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

private fun copySetting(context: Context, title: String, value: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, value))
    Toast.makeText(context, "$title copied", Toast.LENGTH_SHORT).show()
}

private fun settingIcon(setting: ServerSetting): ImageVector = when {
    setting.key == "registration" -> Icons.Outlined.PersonAdd
    setting.key == "jit_registration" -> Icons.Outlined.Login
    setting.key == "registration_token_required" -> Icons.Outlined.ConfirmationNumber
    setting.key == "auto_purge_seen_enabled" -> Icons.Outlined.AutoDelete
    setting.key == "message_retention_enabled" -> Icons.Outlined.History
    setting.key == "turn_enabled" -> Icons.Outlined.Call
    setting.key == "iroh_enabled" -> Icons.Outlined.Hub
    setting.key == "push_enabled" -> Icons.Outlined.NotificationsActive
    setting.key == "webimap_enabled" -> Icons.Outlined.CloudDownload
    setting.key == "websmtp_enabled" -> Icons.Outlined.CloudUpload
    setting.key == "smtp_hostname" -> Icons.Outlined.Dns
    setting.key == "webmail_cors_origins" -> Icons.Outlined.Language
    setting.key == "federation_policy" -> Icons.Outlined.Rule
    setting.key == "admin_web_enabled" -> Icons.Outlined.Dashboard
    setting.key == "language" -> Icons.Outlined.Translate
    setting.kind == SettingKind.Secret -> Icons.Outlined.Key
    setting.kind == SettingKind.Port || setting.kind == SettingKind.Managed -> Icons.Outlined.SettingsEthernet
    setting.kind == SettingKind.Duration -> Icons.Outlined.Schedule
    setting.kind == SettingKind.Size -> Icons.Outlined.DataUsage
    else -> settingsPages.find { it.id == setting.section }?.icon ?: Icons.Outlined.Tune
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsEditor(setting: ServerSetting, current: String, phone: Boolean, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var draft by remember { mutableStateOf(if (setting.kind == SettingKind.Secret) "" else current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(setting.title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (setting.hint.isNotBlank()) Text(setting.hint)
            if (setting.kind == SettingKind.Choice) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                setting.choices.forEach { choice -> FilterChip(selected = draft == choice, onClick = { draft = choice }, label = { Text(choice) }) }
            } else OutlinedTextField(draft, { draft = it }, label = { Text(if (setting.kind == SettingKind.Secret) "New secret" else "Value") }, singleLine = true, isError = draft.isNotBlank() && !setting.valid(draft, phone), keyboardOptions = KeyboardOptions(keyboardType = if (setting.kind in listOf(SettingKind.Port, SettingKind.PositiveNumber)) KeyboardType.Number else if (setting.kind == SettingKind.Secret) KeyboardType.Password else KeyboardType.Text), visualTransformation = if (setting.kind == SettingKind.Secret) PasswordVisualTransformation() else VisualTransformation.None, supportingText = { if (setting.kind == SettingKind.Port) Text(if (phone) "Port 1024–65535; privileged ports need root." else "Port 1–65535") })
            if (setting.restart) Text("A server restart may be needed after saving.", style = MaterialTheme.typography.bodySmall)
            if (setting.kind != SettingKind.Choice) TextButton(onClick = { onSave("", true) }) { Text("Use server default") }
        }
    }, confirmButton = { TextButton(onClick = { onSave(draft, false) }, enabled = setting.valid(draft, phone) && (draft != current || setting.kind == SettingKind.Secret)) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
