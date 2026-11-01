package org.madmail.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import org.json.JSONObject
import org.madmail.mobile.core.GuestAddresses
import org.madmail.mobile.core.GuestNetwork
import org.madmail.mobile.core.InviteLinks
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.engine.*
import java.io.File
import java.net.URI
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private data class Invitation(val token: String, val name: String, val used: Int, val pending: Int, val limit: Int, val status: String, val expires: String?) {
    val expiry: Instant? get() = expires?.let { runCatching { Instant.parse(it.replace(' ', 'T').let { t -> if (t.endsWith('Z') || t.contains('+')) t else "${t}Z" }) }.getOrNull() }
    val statusLabel: String get() = when { expiry?.isBefore(Instant.now()) == true || status == "expired" -> "Expired"; used + pending >= limit || status == "exhausted" -> "Used up"; status == "active" -> "Active"; else -> "Unavailable" }
    val active: Boolean get() = status == "active" && used + pending < limit && expiry?.isBefore(Instant.now()) != true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RegistrationScreen(serverId: String, serverName: String, remote: RemoteServer?, initialEndpoint: String, onEndpointChange: (String) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val phone = remote == null
    val local = remember { LocalEngine.get(context) }
    val localState by local.state.collectAsState()
    val ssh = remember(remote?.id) { remote?.let { RemoteEngine.get(context, it) } }
    val remoteState = ssh?.state?.collectAsState()?.value
    val ready = if (phone) localState.phase == EnginePhase.Running else remoteState?.connected == true && remoteState.status.startsWith("Running")
    val prefs = remember(serverId) { context.getSharedPreferences("invite-$serverId", Context.MODE_PRIVATE) }
    var origin by remember(serverId) { mutableStateOf(prefs.getString("origin", null) ?: if (phone) localInviteOrigin(context) else GuestAddresses.origin(remote!!.host).orEmpty()) }
    var code by remember(serverId) { mutableStateOf(initialEndpoint) }
    var invitations by remember(serverId) { mutableStateOf<List<Invitation>>(emptyList()) }
    var loaded by remember(serverId) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val creation = remember(serverId) { InvitationCreation.get(context, serverId, remote) }
    val creationState by creation.state.collectAsState()
    var revoking by remember { mutableStateOf(false) }
    val busy = revoking || creationState.busy
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var editAddress by remember { mutableStateOf(false) }
    var large by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<Invitation?>(null) }
    var copied by remember(code) { mutableStateOf(false) }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    suspend fun request(method: String, body: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        if (phone) local.invitationRequest(method, body) else ssh!!.invitationRequest(method, body)
    }
    fun select(value: String) { code = value; onEndpointChange(value) }
    LaunchedEffect(creationState.code) {
        creationState.code?.let { select(it); creation.consume(); create = false; refresh++; notice = "Invitation created." }
    }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    LaunchedEffect(ready, refresh) {
        loaded = false
        if (!ready) return@LaunchedEffect
        do {
            try {
                val array = request("GET").getJSONArray("tokens")
                invitations = (0 until array.length()).map { array.getJSONObject(it) }.map {
                    Invitation(it.getString("token"), it.optString("comment").ifBlank { "Invitation" }, it.optInt("used_count"), it.optInt("pending_reservations"), it.getInt("max_uses"), it.getString("status"), it.optString("expires_at").takeUnless { s -> s.isBlank() || s == "null" })
                }
                loaded = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn’t load invitations. Check the server connection and refresh."; break }
            delay(30000)
        } while (isActive)
    }
    val personal = code.startsWith("dclogin:", ignoreCase = true)
    val selected = invitations.find { InviteLinks.origin(origin)?.let { base -> InviteLinks.invite(base, it.token) == code } == true }
    val showCode = ready && code.isNotBlank() && (personal || loaded && selected?.active == true)
    val bitmap = remember(code, showCode) { if (showCode) invitationBitmap(code) else null }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (bitmap != null) {
            Text(if (personal) "One person. One account." else selected!!.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (personal) "Scan in Delta Chat to set up this account." else "Scan with a camera to open the Madmail invitation.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f).clickable(onClickLabel = "Enlarge QR code") { large = true }) {
                    Image(bitmap.asImageBitmap(), "${if (personal) "Account" else "Invitation"} QR code for $serverName. Tap to enlarge.", Modifier.padding(12.dp))
                }
            }
            if (!personal) InvitationSummary(selected!!, numbers)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val clip = ClipData.newPlainText("Madmail invitation", code)
                    if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    copied = true
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (copied) "Copied" else "Copy") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, code) }, "Share invitation"))
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (personal) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(code))) }
                        .onFailure { error = "Install Delta Chat to open this account here, or scan the QR on another device." }
                }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open in Delta Chat") }
                TextButton(onClick = { large = true }) { Icon(Icons.Outlined.Fullscreen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Enlarge") }
                TextButton(onClick = { runCatching { shareInvitationImage(context, bitmap) }.onFailure { error = "Couldn’t share the QR image." } }) { Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share QR") }
                TextButton(onClick = { select("") }) { Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Hide") }
            }
            Text(if (personal) "This QR contains an account password. Give it to one person only. Hiding it does not delete the account." else "Each guest creates their own account. Revoking this invitation stops new sign-ups; existing accounts stay active.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else {
            Icon(Icons.Outlined.QrCode2, null, Modifier.size(44.dp), tint = colors.primary)
            Text(if (!ready) "Bring your server online" else "Invite someone in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(if (!ready) if (phone) "Start hosting from Overview to create invitations." else "Connect to this server from Overview to create invitations." else if (code.isNotBlank()) "This invitation is unavailable or has expired. Choose another invitation or create a new one." else "Create a link for guests, or an account QR for one person.", color = colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { editAddress = true }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Link, null, tint = colors.primary)
            Column(Modifier.weight(1f)) {
                Text("Guest-facing address", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(origin.ifBlank { "Choose an address" }, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Outlined.Edit, "Edit guest-facing address", Modifier.size(20.dp))
        }
        if (phone) Text("Use Wi-Fi or hotspot for nearby guests, or a VPN / Tailscale address for guests on that network. Update this address when the network changes.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Button(onClick = { create = true }, enabled = ready && !busy && !creationState.uncertain && InviteLinks.origin(origin) != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Create invitation") }
        TextButton(onClick = { manage = true }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.ConfirmationNumber, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (loaded) "Manage invitations · ${numbers.format(invitations.size)}" else "Manage invitations") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        notice?.let { Text(it, color = colors.primary, style = MaterialTheme.typography.bodyMedium) }
        error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
        creationState.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
    }
    if (editAddress) GuestAddressSheet(origin, remote, onDismiss = { editAddress = false }) { updated ->
        val updatedCode = if (personal) InviteLinks.login(code, updated) else selected?.let { InviteLinks.invite(updated, it.token) }.orEmpty()
        origin = updated; prefs.edit().putString("origin", origin).apply()
        select(updatedCode); editAddress = false; notice = "Guest address updated. Share the updated QR code."
    }
    if (create) {
        var account by remember { mutableStateOf(true) }
        var title by remember { mutableStateOf("") }
        var uses by remember { mutableStateOf("1") }
        var expiry by remember { mutableStateOf("24h") }
        ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { if (!busy) create = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("New invitation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(account, { account = true }, label = { Text("Account QR") }, leadingIcon = { Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp)) }, enabled = !busy)
                    FilterChip(!account, { account = false }, label = { Text("Browser link") }, leadingIcon = { Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)) }, enabled = !busy)
                }
                Text(if (account) "Create one account now. Its QR includes the IMAP and SMTP settings for Delta Chat." else "Guests open this in a browser. The server must advertise a reachable mail hostname.", color = colors.onSurfaceVariant)
                OutlinedTextField(title, { title = it.take(120) }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                if (!account) OutlinedTextField(uses, { uses = it.filter(Char::isDigit).take(6) }, label = { Text("Maximum people") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Text(if (account) "Complete first sign-in within" else "Invitation expires after", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1h" to "1 hour", "24h" to "1 day", "168h" to "7 days", "" to "Never").forEach { (value, label) -> FilterChip(expiry == value, { expiry = value }, label = { Text(label) }, enabled = !busy) }
                }
                Text(if (account) "The managed server uses a self-signed certificate. This QR allows it in Delta Chat; share only with people who trust this server." else "With a self-signed certificate, browsers may ask guests to trust the server first.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = colors.error) }
                creationState.error?.let { Text(it, color = colors.error) }
                if (creationState.uncertain) TextButton(onClick = { create = false; manage = true; refresh++ }) { Text("Review invitations") }
                Button(enabled = !busy && !creationState.uncertain && (account || (uses.toIntOrNull() ?: 0) in 1..999999), onClick = {
                    error = null; notice = null
                    prefs.edit().putString("origin", origin).apply()
                    creation.create(origin, account, uses.toIntOrNull() ?: 1, title, expiry)
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(if (account) Icons.Outlined.PersonAdd else Icons.Outlined.QrCode2, null); Spacer(Modifier.width(8.dp)); Text(if (account) "Create account QR" else "Create invite link") }
            }
        }
    }
    if (manage) ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { manage = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Invitations", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { error = null; refresh++ }, enabled = !busy) { Icon(Icons.Outlined.Refresh, "Refresh invitations") }
            }
            if (!loaded) Text("Invitations haven’t loaded yet.")
            else if (invitations.isEmpty()) Text("No invitations yet.", color = colors.onSurfaceVariant)
            error?.let { Text(it, color = colors.error) }
            if (creationState.uncertain && loaded) TextButton(onClick = { creation.reviewed() }) { Text("I’ve checked invitations and accounts") }
            invitations.forEach { invitation ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).clickable(enabled = invitation.active && InviteLinks.origin(origin) != null && !busy, onClickLabel = "Show invitation QR") { select(InviteLinks.invite(origin, invitation.token)); manage = false }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(invitation.name, style = MaterialTheme.typography.titleMedium)
                        InvitationSummary(invitation, numbers)
                    }
                    IconButton(onClick = { revoke = invitation }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "Revoke ${invitation.name}", tint = colors.error) }
                }
                HorizontalDivider(color = colors.outlineVariant)
            }
        }
    }
    revoke?.let { invitation -> AlertDialog(onDismissRequest = { if (!busy) revoke = null }, title = { Text("Revoke ${invitation.name}?") }, text = { Text("Guests won’t be able to use this invitation. Accounts already created remain active.") }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            revoking = true
            scope.launch {
                try {
                    request("DELETE", JSONObject().put("token", invitation.token))
                    if (selected?.token == invitation.token) select("")
                    notice = "Invitation revoked."; revoke = null; refresh++
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Revocation wasn’t confirmed. Refresh to check its status."; revoke = null; refresh++ }
                finally { revoking = false }
            }
        }) { Text("Revoke", color = colors.error) }
    }, dismissButton = { TextButton(onClick = { revoke = null }, enabled = !busy) { Text("Cancel") } }) }
    if (large && bitmap != null) Dialog(onDismissRequest = { large = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) { val previous = view.keepScreenOn; view.keepScreenOn = true; onDispose { view.keepScreenOn = previous } }
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(24.dp), color = Color.White, contentColor = Color.Black) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(serverName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Image(bitmap.asImageBitmap(), "QR code for $serverName", Modifier.fillMaxWidth().aspectRatio(1f))
                Text(if (personal) "Scan in Delta Chat · One person only" else "Scan with your camera", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { large = false }) { Text("Close") }
            }
        }
    }
}

@Composable
private fun InvitationSummary(invitation: Invitation, numbers: NumberFormat) {
    val expires = invitation.expiry?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) ?: "No expiry"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${numbers.format(invitation.used)} / ${numbers.format(invitation.limit)} used${if (invitation.pending > 0) " · ${numbers.format(invitation.pending)} pending" else ""}", style = MaterialTheme.typography.bodyMedium)
        Text("${invitation.statusLabel} · $expires", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun invitationBitmap(payload: String): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 1024, 1024)
    Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(1024 * 1024) { i -> if (matrix[i % 1024, i / 1024]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 1024, 0, 0, 1024, 1024)
    }
}.getOrNull()

private fun localInviteOrigin(context: Context): String = GuestNetworks.phone(context)
    .firstOrNull { it.network != GuestNetwork.Vpn }?.origin.orEmpty()

private fun shareInvitationImage(context: Context, bitmap: Bitmap) {
    val dir = File(context.cacheDir, "invite-share").apply { mkdirs() }
    // Only explicit sharing writes a temporary QR image; no account data goes to preferences.
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000 }?.forEach { it.delete() }
    val file = File.createTempFile("invitation-", ".png", dir)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "Invitation QR", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Share QR code"))
}
