package org.madmail.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.madmail.mobile.core.*
import org.madmail.mobile.engine.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuestAddressSheet(origin: String, remote: RemoteServer?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(origin) }
    var addresses by remember { mutableStateOf<List<GuestAddress>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<List<Pair<Int, Boolean>>?>(null) }
    LaunchedEffect(refresh) {
        loading = true
        try { addresses = withContext(Dispatchers.IO) { if (remote == null) GuestNetworks.phone(context) else RemoteEngine.get(context, remote).guestAddresses() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { feedback = "Couldn’t read server addresses over SSH. Reconnect, or enter its guest-facing address below." }
        finally { loading = false }
    }
    fun choose(value: String, label: String) { draft = value; checks = null; feedback = "$label selected. Save to update the QR code." }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Guest address", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { feedback = null; refresh++ }, enabled = !loading) { Icon(Icons.Outlined.Refresh, "Refresh network addresses") }
            }
            Text(if (remote == null) "Choose the network your guests will use." else "These addresses belong to the remote server. Choose one your guests can reach.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && addresses.isEmpty()) Text(if (remote == null) "No Wi-Fi, hotspot or VPN address detected. Connect a network and refresh, or enter an address." else "No interface addresses available. Enter the server’s public name or Tailscale address.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (remote != null) GuestAddresses.origin(remote.host)?.let { value ->
                ListItem(headlineContent = { Text("SSH host address") }, supportingContent = { Text(value) }, leadingContent = { Icon(Icons.Outlined.Dns, null) }, trailingContent = { if (draft == value) Icon(Icons.Outlined.Check, "Selected") }, modifier = Modifier.clickable { choose(value, "SSH host address") })
            }
            addresses.forEach { address ->
                ListItem(headlineContent = { Text(address.network.label) }, supportingContent = { Text("${address.host} · ${address.device}") }, leadingContent = {
                    Icon(when (address.network) { GuestNetwork.Wifi -> Icons.Outlined.Wifi; GuestNetwork.Hotspot -> Icons.Outlined.WifiTethering; GuestNetwork.Vpn -> Icons.Outlined.VpnKey; else -> Icons.Outlined.SettingsEthernet }, null, tint = MaterialTheme.colorScheme.primary)
                }, trailingContent = { if (draft == address.origin) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { choose(address.origin, address.network.label) })
            }
            OutlinedTextField(draft, { draft = it; checks = null; feedback = null }, label = { Text("HTTPS address") }, placeholder = { Text("https://mail.example.org:18443") }, supportingText = { Text("A public hostname or Tailscale MagicDNS name also works. Keep the HTTPS port your server exposes.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Wi-Fi / hotspot: guests join the same network. VPN / Tailscale: guests need an allowed route to this server. A public server also needs its HTTPS, IMAP and SMTP ports open.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("An exit-node VPN can block LAN access. Enable LAN access in the VPN when sharing over Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            OutlinedButton(enabled = !checking && InviteLinks.origin(draft) != null, onClick = {
                val target = draft
                checking = true; checks = null
                scope.launch {
                    try { val result = withContext(Dispatchers.IO) { GuestNetworks.checkPorts(target) }; if (draft == target) checks = result }
                    finally { checking = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.NetworkCheck, null); Spacer(Modifier.width(8.dp)); Text(if (checking) "Checking ports…" else "Check from this phone") }
            checks?.let { results ->
                results.forEach { (port, reachable) -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (reachable) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = if (reachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("$port · ${if (reachable) "Reachable" else "Not reachable"}")
                } }
                Text("Checks TCP access to HTTPS and the managed mail ports (11993 / 11465). This does not verify TLS, other guests’ access, or port mappings on a proxy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(enabled = InviteLinks.origin(draft) != null, onClick = { onSave(checkNotNull(InviteLinks.origin(draft))) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.Check, null); Spacer(Modifier.width(8.dp)); Text("Save address") }
        }
    }
}
