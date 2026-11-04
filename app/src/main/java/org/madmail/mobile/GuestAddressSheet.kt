package org.madmail.mobile

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.madmail.mobile.core.*
import org.madmail.mobile.engine.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuestAddressSheet(origin: String, remote: RemoteServer?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val field = remember { FocusRequester() }
    val apiOnly = remote?.let { RemoteEngine.get(context, it).state.value.transport == "API" } == true
    var draft by remember { mutableStateOf(origin) }
    var addresses by remember { mutableStateOf<List<GuestAddress>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<List<Pair<Int, Boolean>>?>(null) }
    LaunchedEffect(refresh) {
        loading = true; readError = null
        try { addresses = withContext(Dispatchers.IO) { if (remote == null) GuestNetworks.phone(context) else if (apiOnly) emptyList() else RemoteEngine.get(context, remote).guestAddresses() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { readError = context.getString(R.string.remote_error_read_addresses) }
        finally { loading = false }
    }
    fun choose(value: String) { draft = value; checks = null; feedback = context.getString(R.string.remote_save_to_update_qr) }
    val server = remote?.let { RemoteEngine.get(context, it).guestOrigin ?: GuestAddresses.origin(it.host) }
    KitSheet(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { SheetHeader(stringResource(R.string.remote_guest_address), stringResource(R.string.remote_guest_pick),
                mark = { TintIcon(Tint.Teal, Icons.Rounded.Link, size = 40.dp) }) }
            IconButton(onClick = { feedback = null; refresh++ }, enabled = !loading, modifier = Modifier.padding(top = 4.dp, end = 12.dp)) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.remote_refresh_addresses), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = GroupGutter).padding(bottom = 32.dp)
            .animateContentSize(tween(300, easing = Emphasized))) {
            Caption(if (remote == null) stringResource(R.string.remote_networks_phone) else stringResource(R.string.remote_server_addresses))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).clip(RoundedCornerShape(2.dp)), strokeCap = StrokeCap.Round)
            if (server != null || addresses.isNotEmpty()) Group {
                server?.let { value ->
                    AddressRow(Tint.Blue, Icons.Rounded.Dns, stringResource(R.string.remote_server_address), value, draft == value) { choose(value) }
                }
                addresses.forEachIndexed { index, address ->
                    if (index > 0 || server != null) RowDivider(64.dp)
                    val (tint, icon) = when (address.network) {
                        GuestNetwork.Wifi -> Tint.Teal to Icons.Rounded.Wifi
                        GuestNetwork.Hotspot -> Tint.Teal to Icons.Rounded.WifiTethering
                        GuestNetwork.Vpn -> Tint.Teal to Icons.Rounded.VpnKey
                        GuestNetwork.Mobile -> Tint.Teal to Icons.Rounded.SignalCellularAlt
                        else -> Tint.Teal to Icons.Rounded.SettingsEthernet
                    }
                    AddressRow(tint, icon, address.network.label, "${address.host} · ${address.device}", draft == address.origin) { choose(address.origin) }
                }
            }
            if (!loading && addresses.isEmpty()) Footer(if (remote == null) stringResource(R.string.remote_no_network) else stringResource(R.string.remote_none_found))
            Spacer(Modifier.height(24.dp))
            Caption(stringResource(R.string.remote_https_address))
            Group {
                FieldRow(stringResource(R.string.remote_address), draft, { draft = it; checks = null; feedback = null }, "https://mail.example.org:18443", field,
                    fieldKeyboard(KeyboardType.Uri, ImeAction.Done), { focus.clearFocus() })
            }
            Footer(if (remote == null) stringResource(R.string.remote_phone_ip_footer) else stringResource(R.string.remote_server_port_footer))
            feedback?.let { Footer(it) }
            readError?.let { Footer(it, MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
            PillButton(if (checking) stringResource(R.string.remote_checking_ports) else stringResource(R.string.remote_check_ports), onClick = {
                val target = draft
                checking = true; checks = null
                scope.launch {
                    try { val result = withContext(Dispatchers.IO) { GuestNetworks.checkPorts(target) }; if (draft == target) checks = result }
                    finally { checking = false }
                }
            }, icon = Icons.Rounded.NetworkCheck, enabled = !checking && InviteLinks.origin(draft) != null, wide = true)
            checks?.let { results ->
                Spacer(Modifier.height(16.dp))
                Caption(stringResource(R.string.remote_reachability))
                Group {
                    results.forEachIndexed { index, (port, reachable) ->
                        if (index > 0) RowDivider(64.dp)
                        InfoRow(if (reachable) Tint.Green else Tint.Red, if (reachable) Icons.Rounded.Check else Icons.Rounded.Close, stringResource(R.string.remote_port, port),
                            if (reachable) stringResource(R.string.remote_reachable) else stringResource(R.string.remote_not_reachable))
                    }
                }
                Footer(stringResource(R.string.remote_ports_footer))
            }
            Spacer(Modifier.height(28.dp))
            BrandButton(stringResource(R.string.remote_save_address), enabled = usable(draft, remote == null)) { onSave(checkNotNull(InviteLinks.origin(draft))) }
        }
    }
}

/** Guests reach servers by IP. The phone accepts only its IPv4 addresses; no address may be a .local name. */
private fun usable(draft: String, phone: Boolean): Boolean {
    val host = InviteLinks.origin(draft)?.let { java.net.URI(it).host.removeSurrounding("[", "]") } ?: return false
    return if (phone) GuestAddresses.ipv4(host) else !host.lowercase().endsWith(".local")
}

/** A selectable address: mark, network name and host, check when chosen. */
@Composable
private fun AddressRow(tint: Tint, icon: ImageVector, title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 60.dp)
        .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TintIcon(tint, icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) SelectedCheck()
    }
}
