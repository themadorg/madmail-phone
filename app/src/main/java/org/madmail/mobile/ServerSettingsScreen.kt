package org.madmail.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject import kotlin.math.ceil
import org.madmail.mobile.core.*
import org.madmail.mobile.engine.*
import java.text.NumberFormat

private data class SettingsPage(val id: String, @StringRes val title: Int, @StringRes val subtitle: Int, val tint: Tint, val icon: ImageVector)
private val settingsPages = listOf(
    SettingsPage("registration", R.string.screen_page_registration, R.string.screen_page_registration_sub, Tint.Purple, Icons.Rounded.PersonAdd),
    SettingsPage("accounts", R.string.screen_accounts, R.string.screen_page_accounts_sub, Tint.Orange, Icons.Rounded.Group),
    SettingsPage("storage", R.string.screen_page_storage, R.string.screen_page_storage_sub, Tint.Yellow, Icons.Rounded.Inventory2),
    SettingsPage("network", R.string.screen_page_network, R.string.screen_page_network_sub, Tint.Teal, Icons.Rounded.SettingsEthernet),
    SettingsPage("services", R.string.screen_page_services, R.string.screen_page_services_sub, Tint.Green, Icons.Rounded.Call),
    SettingsPage("federation", R.string.screen_federation, R.string.screen_page_federation_sub, Tint.Indigo, Icons.Rounded.Public),
    SettingsPage("proxies", R.string.screen_page_proxies, R.string.screen_page_proxies_sub, Tint.Graphite, Icons.Rounded.VpnLock),
    SettingsPage("administration", R.string.screen_page_administration, R.string.screen_page_administration_sub, Tint.Gray, Icons.Rounded.AdminPanelSettings),
    SettingsPage("connection", R.string.screen_connection, R.string.screen_page_connection_sub, Tint.Teal, Icons.Rounded.Link),
)
/** Root list sections: caption to page ids. */
private val settingsSections = listOf(
    R.string.screen_section_server to listOf("registration", "accounts", "storage"),
    R.string.screen_section_delivery to listOf("network", "services", "federation"),
    R.string.screen_section_advanced to listOf("proxies", "administration", "connection"),
)

@Composable
internal fun ServerSettingsScreen(serverId: String, serverName: String, remote: RemoteServer?, initiallyShowHosting: Boolean = false, onNavigate: () -> Unit = {}, onRemove: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val colors = MaterialTheme.colorScheme
    val controller = remember(serverId) { SettingsController.get(context, serverId, remote) }
    val state by controller.state.collectAsState()
    val toaster = LocalToaster.current
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
    // A refresh leaves rows as they are; the controller queues edits behind it, so nothing dims or jumps while it runs.
    val enabled = ready && !state.stale && state.values != null
    val canRestart = ready && !state.busy && (remote == null || remoteState?.managed == true)
    fun navigate(next: String) { page = next; onNavigate() }
    fun back() { navigate(if (page == "account") "accounts" else "root") }
    BackHandler(page != "root") { back() }
    LaunchedEffect(ready) { if (ready) controller.refresh() }
    // Confirmations float as toasts. The controller keeps its last message, so one already set when the page opens isn't repeated;
    // collecting the flow (not recomposition) catches the brief null between two identical "Saved." messages.
    LaunchedEffect(controller) {
        var seen = controller.state.value.message
        controller.state.collect { current -> if (current.message != seen) { seen = current.message; current.message?.let { toaster.show(it) } } }
    }
    LaunchedEffect(page, ready) { if (ready && page in listOf("accounts", "federation")) controller.loadDetails(page) }
    val title = settingsPages.find { it.id == page }?.let { stringResource(it.title) } ?: if (page == "account") stringResource(R.string.screen_account) else stringResource(R.string.screen_settings)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column {
            AnimatedVisibility(page != "root", enter = fadeIn(tween(220, easing = Emphasized)) + expandVertically(tween(300, easing = Emphasized)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(260, easing = Emphasized))) {
                IconButton(onClick = { back() }, Modifier.offset(x = (-8).dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_back_to_settings)) }
            }
            PageTitle(title, serverName) {
                IconButton(onClick = { if (!state.busy) { controller.refresh(); if (page in listOf("accounts", "federation")) controller.loadDetails(page) } }, enabled = ready,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = groupColor(), contentColor = colors.onSurfaceVariant,
                        disabledContainerColor = groupColor(), disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.38f))) {
                    RefreshGlyph(state.busy)
                }
            }
        }
        val notices = !ready || state.error != null || state.restartRequired
        if (notices) Column(Modifier.animateContentSize(tween(300, easing = Emphasized)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!ready) Group {
                InfoRow(Tint.Gray, Icons.Rounded.PowerSettingsNew, if (remote == null) stringResource(R.string.screen_hosting_off) else stringResource(R.string.screen_not_connected),
                    if (remote == null) stringResource(R.string.screen_start_hosting_on_home) else stringResource(R.string.screen_connect_on_home))
            }
            state.error?.let { Group { InfoRow(Tint.Red, Icons.Rounded.ErrorOutline, stringResource(R.string.screen_something_wrong), it) } }
            if (state.restartRequired) Group {
                InfoRow(Tint.Orange, Icons.Rounded.RestartAlt, stringResource(R.string.screen_restart_needed)) {
                    PillButton(stringResource(R.string.screen_restart), onClick = { restart = true }, enabled = canRestart)
                }
            }
        }
        AnimatedContent(page, transitionSpec = {
            (fadeIn(tween(240, 60, easing = Emphasized)) + slideInVertically(tween(320, easing = Emphasized)) { 20 }) togetherWith fadeOut(tween(90))
        }, label = "Settings navigation") { displayedPage ->
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                when (displayedPage) {
                    "root" -> {
                        settingsSections.forEach { (caption, ids) ->
                            Section(stringResource(caption)) {
                                settingsPages.filter { it.id in ids }.forEachIndexed { index, item ->
                                    if (index > 0) RowDivider(64.dp)
                                    NavRow({ TintIcon(item.tint, item.icon) }, stringResource(item.title), stringResource(item.subtitle)) { navigate(item.id) }
                                }
                            }
                        }
                        Column {
                            Caption(stringResource(R.string.screen_on_this_phone))
                            AppLockSettings()
                        }
                    }
                    "connection" -> {
                        if (remote != null) {
                            if (remoteState?.transport == "API") Section("Admin API") {
                                val endpoint = remoteState.apiEndpoint
                                ValueRow(stringResource(R.string.screen_address), endpoint ?: stringResource(R.string.screen_not_configured), copyable = endpoint != null, mark = { TintIcon(Tint.Teal, Icons.Rounded.Api) })
                            } else Section("SSH") {
                                ValueRow(stringResource(R.string.screen_host), remote.host, copyable = true, mark = { TintIcon(Tint.Blue, Icons.Rounded.Dns) })
                                RowDivider(64.dp)
                                ValueRow(stringResource(R.string.screen_username), remote.user, copyable = true, mark = { TintIcon(Tint.Orange, Icons.Rounded.Person) })
                                RowDivider(64.dp)
                                ValueRow(stringResource(R.string.screen_port), remote.port.toString(), copyable = true, mark = { TintIcon(Tint.Teal, Icons.Rounded.SettingsEthernet) })
                                RemoteEngine.get(context, remote).fingerprint?.let {
                                    RowDivider(64.dp)
                                    ValueRow(stringResource(R.string.screen_fingerprint), it, copyable = true, mark = { TintIcon(Tint.Graphite, Icons.Rounded.Fingerprint) })
                                }
                            }
                            RemoteControls(remote)
                            Section {
                                ActionRow(stringResource(R.string.screen_remove_saved_server), { remove = true }, destructive = true, icon = Icons.Rounded.DeleteOutline)
                            }
                        } else {
                            Section(stringResource(R.string.screen_hosting_ports)) {
                                ValueRow("SMTP TLS", "11465", copyable = true, mark = { TintIcon(Tint.Teal, Icons.Rounded.Upload) })
                                RowDivider(64.dp)
                                ValueRow("IMAP TLS", "11993", copyable = true, mark = { TintIcon(Tint.Teal, Icons.Rounded.Download) })
                                RowDivider(64.dp)
                                ValueRow("HTTPS", "18443", copyable = true, mark = { TintIcon(Tint.Teal, Icons.Rounded.Lock) })
                            }
                            val prefs = remember { context.getSharedPreferences("server-settings-phone", Context.MODE_PRIVATE) }
                            var root by remember { mutableStateOf(prefs.getBoolean("root_ports", false)) }
                            if (root) Group {
                                InfoRow(Tint.Red, Icons.Rounded.ErrorOutline, stringResource(R.string.screen_std_ports_unavailable),
                                    stringResource(R.string.screen_std_ports_need_root))
                                RowDivider(16.dp)
                                ActionRow(stringResource(R.string.screen_use_default_ports), { prefs.edit().putBoolean("root_ports", false).apply(); root = false })
                            }
                        }
                    }
                    "accounts" -> {
                        val quota = state.quota
                        val rows = state.accounts?.optJSONArray("accounts")
                        if (ready && rows != null) {
                            Section(stringResource(R.string.screen_defaults)) {
                                NavRow({ TintIcon(Tint.Yellow, Icons.Rounded.DataUsage) }, stringResource(R.string.screen_default_storage_quota),
                                    value = quota?.optLong("default_quota_bytes")?.let { ByteSize.format(it) } ?: stringResource(R.string.screen_not_loaded), enabled = enabled) {
                                    quotaTarget = ""; quotaInitial = quota?.optLong("default_quota_bytes") ?: 0L
                                }
                            }
                            if (rows.length() == 0) Group { InfoRow(Tint.Orange, Icons.Rounded.Group, stringResource(R.string.screen_no_accounts_yet), stringResource(R.string.screen_create_one_invite)) }
                            else Section(resources.getQuantityString(R.plurals.screen_accounts_count, rows.length(), NumberFormat.getIntegerInstance().format(rows.length())),
                                footer = stringResource(R.string.screen_legacy_accounts)
                                    .takeIf { (0 until rows.length()).any { rows.getJSONObject(it).optString("username").endsWith("@madmail.local", ignoreCase = true) } }) {
                                for (i in 0 until rows.length()) {
                                    val row = rows.getJSONObject(i)
                                    if (i > 0) RowDivider(64.dp)
                                    NavRow({ TintIcon(Tint.Orange, Icons.Rounded.Person) }, row.getString("username"),
                                        stringResource(R.string.screen_used_quota, ByteSize.format(row.optLong("used_bytes")), ByteSize.format(row.optLong("max_bytes")))) {
                                        account = row; navigate("account")
                                    }
                                }
                            }
                        }
                    }
                    "account" -> account?.let { row ->
                        val username = row.getString("username")
                        val updated = state.accounts?.optJSONArray("accounts")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) }.find { it.optString("username") == username } } ?: row
                        Section(stringResource(R.string.screen_account)) {
                            ValueRow(stringResource(R.string.screen_address), username, copyable = true, mark = { TintIcon(Tint.Blue, Icons.Rounded.AlternateEmail) })
                            RowDivider(64.dp)
                            ValueRow(stringResource(R.string.screen_storage_used), ByteSize.format(updated.optLong("used_bytes")), mark = { TintIcon(Tint.Yellow, Icons.Rounded.Storage) })
                            RowDivider(64.dp)
                            NavRow({ TintIcon(Tint.Yellow, Icons.Rounded.DataUsage) }, stringResource(R.string.screen_storage_quota), value = ByteSize.format(updated.optLong("max_bytes")), enabled = enabled) {
                                quotaTarget = username; quotaInitial = updated.optLong("max_bytes")
                            }
                        }
                        Section {
                            ActionRow(stringResource(R.string.screen_delete_account), { deleteAccount = true }, destructive = true, enabled = enabled, icon = Icons.Rounded.DeleteOutline)
                        }
                    }
                    else -> {
                        val footer = when (displayedPage) {
                            "registration" -> stringResource(R.string.screen_footer_manage_invites)
                            "network" -> stringResource(R.string.screen_footer_ports_readonly)
                            else -> null
                        }
                        Section(footer = footer) {
                            serverSettings.filter { it.section == displayedPage }.forEachIndexed { index, setting ->
                                val raw = if (ready) state.values?.opt(setting.key)?.takeUnless { it == JSONObject.NULL } else null
                                val current = if (raw is JSONObject) raw.optString("value") else raw?.toString()
                                val checked = when (current) { "enabled", "open", "true" -> true; "disabled", "closed", "false" -> false; else -> null }
                                val display = when {
                                    current == null -> if (ready && state.values != null) stringResource(R.string.screen_not_reported) else stringResource(R.string.screen_not_loaded)
                                    current.isEmpty() -> stringResource(R.string.screen_hosting_default)
                                    setting.kind == SettingKind.Secret -> "••••••••"
                                    setting.kind == SettingKind.PositiveNumber -> current.toLongOrNull()?.let { NumberFormat.getIntegerInstance().format(it) } ?: current
                                    else -> setting.display(current)
                                }
                                val (tint, icon) = settingMark(setting)
                                val copiedMessage = stringResource(R.string.screen_setting_copied, setting.title)
                                if (index > 0) RowDivider(64.dp)
                                if (setting.kind == SettingKind.Toggle) {
                                    if (checked != null) SwitchRow(tint, icon, setting.title, setting.hint.ifBlank { null }, checked, enabled) { controller.save(setting, it.toString()) }
                                    else InfoRow(tint, icon, setting.title, listOf(setting.hint, display).filter { it.isNotBlank() }.joinToString("\n"))
                                } else SettingRow(tint, icon, setting.title, display, clickable = enabled && current != null, editable = setting.kind != SettingKind.Managed,
                                    copy = if (!current.isNullOrBlank() && setting.kind != SettingKind.Secret) ({ copySetting(context, toaster, setting.title, copiedMessage, current) }) else null) { editing = setting }
                            }
                        }
                        if (displayedPage == "federation" && ready) state.dkim?.let { dkim ->
                            val rows = listOf(Triple("domain", stringResource(R.string.screen_domain), Tint.Indigo to Icons.Rounded.Public), Triple("selector", stringResource(R.string.screen_selector), Tint.Graphite to Icons.Rounded.Key),
                                Triple("dns_name", stringResource(R.string.screen_dns_record), Tint.Blue to Icons.Rounded.Dns), Triple("txt", stringResource(R.string.screen_txt_value), Tint.Graphite to Icons.Rounded.VerifiedUser))
                                .mapNotNull { (key, label, mark) -> dkim.optString(key).takeUnless { it.isBlank() || it == "null" }?.let { Triple(label, it, mark) } }
                            if (rows.isNotEmpty()) Section(stringResource(R.string.screen_domain_signing)) {
                                rows.forEachIndexed { index, (label, value, mark) ->
                                    if (index > 0) RowDivider(64.dp)
                                    ValueRow(label, value, copyable = true, mark = { TintIcon(mark.first, mark.second) })
                                }
                            }
                        }
                        if (displayedPage == "administration") Section(stringResource(R.string.screen_maintenance)) {
                            NavRow({ TintIcon(Tint.Gray, Icons.Rounded.RestartAlt) }, stringResource(R.string.screen_restart_server),
                                if (remote == null || remoteState?.managed == true) null else stringResource(R.string.screen_needs_managed_service),
                                enabled = canRestart) { restart = true }
                        }
                    }
                }
            }
        }
    }
    editing?.let { setting ->
        val raw = state.values?.opt(setting.key)
        val value = if (raw is JSONObject) raw.optString("value") else raw?.toString().orEmpty()
        SettingsEditor(setting, value, remote == null, onDismiss = { editing = null }, onSave = { next, reset -> controller.save(setting, next, reset); editing = null })
    }
    if (restart) KitDialog(stringResource(R.string.screen_restart_server_title, serverName), stringResource(R.string.screen_connections_drop), stringResource(R.string.screen_restart),
        onConfirm = { controller.restart(); restart = false }, onDismiss = { restart = false })
    if (remove) KitDialog(stringResource(R.string.screen_remove_server_title, serverName), stringResource(R.string.screen_remove_server_body), stringResource(R.string.screen_remove),
        onConfirm = { SettingsController.forget(serverId); onRemove() }, onDismiss = { remove = false })
    if (deleteAccount && account != null) KitDialog(stringResource(R.string.screen_delete_account_title), stringResource(R.string.screen_delete_account_body, account!!.getString("username")), stringResource(R.string.screen_delete),
        onConfirm = {
            controller.mutate("/admin/accounts", "DELETE", JSONObject().put("username", account!!.getString("username")), success = resources.getString(R.string.screen_account_deleted), refreshAccounts = true)
            deleteAccount = false; navigate("accounts")
        }, onDismiss = { deleteAccount = false })
    quotaTarget?.let { username ->
        var draft by remember { mutableStateOf((quotaInitial / (1024 * 1024)).toString()) }
        val amount = draft.toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE / (1024 * 1024)) }
        val focus = remember { FocusRequester() }
        fun save() {
            controller.mutate("/admin/quota", "PUT", JSONObject().put("max_bytes", amount!! * 1024 * 1024).apply { if (username.isNotEmpty()) put("username", username) }, success = resources.getString(R.string.screen_quota_saved), refreshAccounts = true)
            quotaTarget = null
        }
        KitDialog(if (username.isEmpty()) stringResource(R.string.screen_default_storage_quota) else stringResource(R.string.screen_account_quota), null, stringResource(R.string.screen_save), confirmEnabled = amount != null, onConfirm = ::save, onDismiss = { quotaTarget = null }) {
            Group {
                FieldRow(stringResource(R.string.screen_storage), draft, { draft = it.filter(Char::isDigit) }, "512", focus, fieldKeyboard(KeyboardType.Number, ImeAction.Done), { if (amount != null) save() },
                    trailing = { Text("MB", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant) })
            }
        }
    }
}

/** Caption, white group, footer: the rhythm every settings section follows. */
@Composable
private fun Section(caption: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        caption?.let { Caption(it) }
        Group(content = content)
        footer?.let { Footer(it) }
    }
}

/** A server value: mark, title and current value; copy and edit when the setting allows. */
@Composable
private fun SettingRow(tint: Tint, icon: ImageVector, title: String, value: String, clickable: Boolean, editable: Boolean, copy: (() -> Unit)?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(enabled = clickable && editable, role = Role.Button, onClick = rememberTap(onClick)).heightIn(min = 60.dp)
        .padding(start = 14.dp, end = if (editable) 10.dp else if (copy != null) 4.dp else 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TintIcon(tint, icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant)
        }
        copy?.let { IconButton(onClick = it) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.screen_copy_named, title), Modifier.size(18.dp), tint = colors.onSurfaceVariant.copy(alpha = 0.6f)) } }
        if (editable) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

/** The refresh arrow turns while a load runs, in place, so the page below never moves. */
@Composable
private fun RefreshGlyph(busy: Boolean) {
    val turn = remember { Animatable(0f) }
    LaunchedEffect(busy) {
        if (busy) while (true) { turn.animateTo(turn.value + 360f, tween(800, easing = LinearEasing)) }
        // Finish the current turn instead of snapping back, then reset for the next one.
        else { turn.animateTo(ceil(turn.value / 360f) * 360f, tween(260, easing = Emphasized)); turn.snapTo(0f) }
    }
    Icon(Icons.Rounded.Refresh, if (busy) stringResource(R.string.screen_refreshing) else stringResource(R.string.screen_refresh_settings), Modifier.size(22.dp).graphicsLayer { rotationZ = turn.value })
}

private fun copySetting(context: Context, toaster: Toaster, title: String, message: String, value: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, value))
    toaster.show(message)
}

/** Hue follows meaning across the app: ports teal, secrets graphite, sizes blue, time pink, otherwise the page's own hue. */
private fun settingMark(setting: ServerSetting): Pair<Tint, ImageVector> = when {
    setting.key == "registration" -> Tint.Purple to Icons.Rounded.PersonAdd
    setting.key == "jit_registration" -> Tint.Purple to Icons.AutoMirrored.Rounded.Login
    setting.key == "registration_token_required" -> Tint.Purple to Icons.Rounded.ConfirmationNumber
    setting.key == "auto_purge_seen_enabled" -> Tint.Yellow to Icons.Rounded.AutoDelete
    setting.key == "message_retention_enabled" -> Tint.Yellow to Icons.Rounded.History
    setting.key == "message_retention" -> Tint.Yellow to Icons.Rounded.Schedule
    setting.key == "turn_enabled" -> Tint.Green to Icons.Rounded.Call
    setting.key == "iroh_enabled" -> Tint.Green to Icons.Rounded.Hub
    setting.key == "push_enabled" -> Tint.Green to Icons.Rounded.NotificationsActive
    setting.key == "webimap_enabled" -> Tint.Blue to Icons.Rounded.CloudDownload
    setting.key == "websmtp_enabled" -> Tint.Blue to Icons.Rounded.CloudUpload
    setting.key == "smtp_hostname" -> Tint.Blue to Icons.Rounded.Dns
    setting.key == "shared_port_imap" || setting.key == "shared_port_smtp" -> Tint.Teal to Icons.Rounded.MergeType
    setting.key == "webmail_cors_origins" -> Tint.Teal to Icons.Rounded.Language
    setting.key == "turn_relay_ip" -> Tint.Teal to Icons.Rounded.Router
    setting.key == "iroh_relay_url" -> Tint.Teal to Icons.Rounded.Link
    setting.key == "federation_enabled" -> Tint.Indigo to Icons.Rounded.Public
    setting.key == "federation_policy" -> Tint.Indigo to Icons.Rounded.Policy
    setting.key == "admin_web_enabled" -> Tint.Gray to Icons.Rounded.Dashboard
    setting.key == "language" -> Tint.Gray to Icons.Rounded.Translate
    setting.kind == SettingKind.Secret -> Tint.Graphite to Icons.Rounded.Key
    setting.kind == SettingKind.Port || (setting.kind == SettingKind.Managed && setting.section == "network") -> Tint.Teal to Icons.Rounded.SettingsEthernet
    setting.kind == SettingKind.Duration || setting.kind == SettingKind.PositiveNumber -> Tint.Pink to Icons.Rounded.Schedule
    setting.kind == SettingKind.Size -> Tint.Blue to Icons.Rounded.DataUsage
    else -> settingsPages.find { it.id == setting.section }?.let { it.tint to it.icon } ?: (Tint.Gray to Icons.Rounded.Tune)
}

@Composable
private fun SettingsEditor(setting: ServerSetting, current: String, phone: Boolean, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var draft by remember { mutableStateOf(if (setting.kind == SettingKind.Secret) "" else current) }
    val focus = remember { FocusRequester() }
    val valid = setting.valid(draft, phone)
    val canSave = valid && (draft != current || setting.kind == SettingKind.Secret)
    val colors = MaterialTheme.colorScheme
    KitDialog(setting.title, setting.hint.ifBlank { null }, stringResource(R.string.screen_save), confirmEnabled = canSave, onConfirm = { onSave(draft, false) }, onDismiss = onDismiss) {
        if (setting.kind == SettingKind.Choice) Group {
            setting.choices.forEachIndexed { index, choice ->
                if (index > 0) RowDivider(16.dp)
                Row(Modifier.fillMaxWidth().selectable(draft == choice, role = Role.RadioButton) { draft = choice }.heightIn(min = 52.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(choice, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (draft == choice) SelectedCheck()
                }
            }
        } else {
            val portHint = if (phone) stringResource(R.string.screen_port_range, "1024", "65535") else stringResource(R.string.screen_port_range, "1", "65535")
            val keyboard = when (setting.kind) {
                SettingKind.Port, SettingKind.PositiveNumber -> KeyboardType.Number
                SettingKind.Secret -> KeyboardType.Password
                else -> KeyboardType.Text
            }
            val error = if (draft.isNotBlank() && !valid) (if (setting.kind == SettingKind.Port) portHint else stringResource(R.string.screen_invalid_value)) else null
            Group {
                FieldRow(if (setting.kind == SettingKind.Secret) stringResource(R.string.screen_new_secret) else stringResource(R.string.screen_value), draft, { draft = it }, "", focus, fieldKeyboard(keyboard, ImeAction.Done),
                    { if (canSave) onSave(draft, false) }, error = error, secret = setting.kind == SettingKind.Secret)
                RowDivider(16.dp)
                ActionRow(stringResource(R.string.screen_use_server_default), { onSave("", true) })
            }
            if (setting.kind == SettingKind.Port && error == null) Footer(portHint)
        }
        if (setting.restart) Footer(stringResource(R.string.screen_needs_restart), colors.onSurfaceVariant)
    }
}
