package org.madmail.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import org.json.JSONObject
import org.madmail.mobile.core.GuestAddresses
import org.madmail.mobile.core.PhoneMailDomain
import org.madmail.mobile.core.InviteLinks
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.engine.*
import java.io.File
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var origin by remember(serverId) { mutableStateOf(prefs.getString("origin", null) ?: if (phone) localInviteOrigin(context) else ssh?.guestOrigin ?: GuestAddresses.origin(remote!!.host).orEmpty()) }
    var code by remember(serverId) { mutableStateOf(initialEndpoint) }
    var invitations by remember(serverId) { mutableStateOf<List<Invitation>>(emptyList()) }
    var loaded by remember(serverId) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val creation = remember(serverId) { InvitationCreation.get(context, serverId, remote) }
    val creationState by creation.state.collectAsState()
    val rotation = remember(serverId) { AccountQrRotation.get(context, serverId, remote) }
    val rotationState by rotation.state.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var revoking by remember { mutableStateOf(false) }
    val busy = revoking || creationState.busy
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var editAddress by remember { mutableStateOf(false) }
    var large by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<Invitation?>(null) }
    var copied by remember(code, rotationState.code) { mutableStateOf(false) }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    suspend fun request(method: String, body: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        if (phone) local.invitationRequest(method, body) else ssh!!.invitationRequest(method, body)
    }
    fun select(value: String) { code = value; onEndpointChange(value) }
    LaunchedEffect(creationState.code) {
        creationState.code?.let { rotation.stop(); select(it); creation.consume(); create = false; refresh++; notice = "Invitation created." }
    }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    // A new account QR every few seconds, only while the code is in view: page on screen, no sheet over it, app in front, server ready.
    val rotating = rotationState.active && ready && !create && !manage && !editAddress && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(rotating) { if (rotating) rotation.resume() else rotation.pause() }
    DisposableEffect(rotation) { onDispose { rotation.pause() } }
    LaunchedEffect(ready) { if (ready) rotation.tidy() }
    LaunchedEffect(ready, refresh) {
        loaded = false
        if (!ready) return@LaunchedEffect
        do {
            try {
                val array = request("GET").getJSONArray("tokens")
                val rotated = rotation.tokens()
                invitations = (0 until array.length()).map { array.getJSONObject(it) }.filter { it.getString("token") !in rotated }.map {
                    Invitation(it.getString("token"), it.optString("comment").ifBlank { "Invitation" }, it.optInt("used_count"), it.optInt("pending_reservations"), it.getInt("max_uses"), it.getString("status"), it.optString("expires_at").takeUnless { s -> s.isBlank() || s == "null" })
                }
                loaded = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn’t load invitations. Refresh to retry."; break }
            delay(30000)
        } while (isActive)
    }
    // Account QRs come only from the rotation; a stored dclogin link from before may already be deleted.
    val shown = if (rotationState.active) rotationState.code.orEmpty() else code.takeUnless { it.startsWith("dclogin:", ignoreCase = true) }.orEmpty()
    val personal = rotationState.active
    val selected = if (personal) null else invitations.find { InviteLinks.origin(origin)?.let { base -> InviteLinks.invite(base, it.token) == shown } == true }
    val showCode = ready && shown.isNotBlank() && (personal || loaded && selected?.active == true)
    val bitmap = remember(shown, showCode) { if (showCode) invitationBitmap(shown) else null }
    val canCreate = ready && !busy && !creationState.uncertain && InviteLinks.origin(origin) != null
    // Copying or sharing hands the code out, so the rotation keeps that account instead of deleting it.
    fun handOut() { if (personal) rotation.keep(rotationState.token) }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(300, easing = Emphasized)), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (bitmap != null) {
            Column {
                Caption(if (personal) "Account QR" else "Invitation")
                Group {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (personal) "One person. One account." else selected!!.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text(if (personal) "Scan in Delta Chat." else "Scan with a camera.",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color.White)
                            .clickable(onClickLabel = "Enlarge QR code", role = Role.Button) { large = true }) {
                            // A short crossfade so each new code reads as a refresh, not a flicker.
                            Crossfade(bitmap, animationSpec = tween(180), label = "qr") { image ->
                                Image(image.asImageBitmap(), "${if (personal) "Account" else "Invitation"} QR code for $serverName. Tap to enlarge.", Modifier.fillMaxSize().padding(12.dp))
                            }
                        }
                        if (personal) RotationTimer(rotationState.issuedAt, Modifier.widthIn(max = 280.dp).fillMaxWidth().padding(top = 10.dp))
                    }
                    if (!personal) {
                        RowDivider(16.dp)
                        InvitationRows(selected!!, numbers)
                    }
                }
                Footer(if (personal) "Contains a password. Give it to one person only." else "Revoking stops new sign-ups. Existing accounts stay.")
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(if (copied) "Copied" else "Copy", onClick = {
                        handOut()
                        val clip = ClipData.newPlainText("Madmail invitation", shown)
                        if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                        copied = true
                    }, Modifier.weight(1f), icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, filled = true, wide = true)
                    PillButton("Share", onClick = {
                        handOut()
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shown) }, "Share invitation"))
                    }, Modifier.weight(1f), icon = Icons.Rounded.Share, wide = true)
                }
                Group {
                    if (personal) {
                        ActionRow("Open in Delta Chat", onClick = {
                            handOut()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(shown))) }
                                .onFailure { error = "Install Delta Chat, or scan on another device." }
                        }, icon = Icons.AutoMirrored.Rounded.OpenInNew)
                        RowDivider(48.dp)
                    }
                    ActionRow("Enlarge", onClick = { large = true }, icon = Icons.Rounded.Fullscreen)
                    RowDivider(48.dp)
                    ActionRow("Share QR image", onClick = { handOut(); runCatching { shareInvitationImage(context, bitmap) }.onFailure { error = "Couldn’t share the QR image." } }, icon = Icons.Rounded.Image)
                    RowDivider(48.dp)
                    ActionRow(if (personal) "Stop" else "Hide", onClick = { if (personal) rotation.stop() else select("") }, icon = Icons.Rounded.VisibilityOff)
                }
            }
        } else if (personal && ready) Column {
            Caption("Account QR")
            Group {
                InfoRow(Tint.Purple, Icons.Rounded.QrCode2, "Preparing account QR…", rotationState.error) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        } else {
            val title = if (!ready) "Server offline" else "Invite someone"
            val body = if (!ready) if (phone) "Start hosting from Overview." else "Connect from Overview." else if (shown.isNotBlank()) "This invitation is unavailable. Choose or create another." else "Share a link or an account QR."
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                TintIcon(Tint.Purple, Icons.Rounded.QrCode2, size = 56.dp)
                Spacer(Modifier.height(18.dp))
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
        }
        Column {
            Caption("Guest address")
            Group {
                Box(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button, onClickLabel = "Edit guest address") { editAddress = true }) {
                    InfoRow(Tint.Teal, Icons.Rounded.Link, "Address", origin.ifBlank { "Choose an address" }) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Edit, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant) }
                    }
                }
            }
            if (phone) Footer("Update it when the network changes.")
        }
        Column {
            if (!personal) {
                PillButton("Show account QR", onClick = {
                    error = null; notice = null
                    prefs.edit().putString("origin", origin).apply()
                    select(""); rotation.start(origin)
                }, icon = Icons.Rounded.QrCode2, filled = canCreate, enabled = canCreate, wide = true)
                Footer("Each QR holds a password and trusts this server’s self-signed certificate.")
                Spacer(Modifier.height(16.dp))
            }
            PillButton("Create invitation", onClick = { create = true }, icon = Icons.Rounded.Add, enabled = canCreate, wide = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Group {
                NavRow({ TintIcon(Tint.Purple, Icons.Rounded.ConfirmationNumber) }, "Invitations", value = if (loaded) numbers.format(invitations.size) else null,
                    enabled = ready) { manage = true }
            }
            if (busy) InviteProgress()
            notice?.let { InviteStatus(Tint.Green, Icons.Rounded.Check, it) }
            error?.let { InviteStatus(Tint.Red, Icons.Rounded.PriorityHigh, it) }
            creationState.error?.let { InviteStatus(Tint.Red, Icons.Rounded.PriorityHigh, it) }
            if (bitmap != null) rotationState.error?.let { InviteStatus(Tint.Orange, Icons.Rounded.PriorityHigh, it) }
        }
    }
    if (editAddress) GuestAddressSheet(origin, remote, onDismiss = { editAddress = false }) { updated ->
        origin = updated; prefs.edit().putString("origin", origin).apply()
        if (personal) rotation.retarget(updated) else select(selected?.let { InviteLinks.invite(updated, it.token) }.orEmpty())
        editAddress = false; notice = "Address updated."
        // New accounts take this IP as their domain, so the phone server restarts onto it when it changed.
        val host = java.net.URI(updated).host
        if (phone && GuestAddresses.ipv4(host)) scope.launch(Dispatchers.IO) {
            val before = local.mailHost
            runCatching { local.useMailHost(host) }
                .onSuccess { switched -> if (!switched) error = "Couldn’t switch to $host. Check this phone’s network."
                    else if (before != host && local.mailHost == host) notice = "Hosting restarted on $host." }
                .onFailure { error = it.message ?: "Couldn’t restart hosting. Start it from Overview." }
        }
    }
    if (create) {
        var account by remember { mutableStateOf(true) }
        var title by remember { mutableStateOf("") }
        var uses by remember { mutableStateOf("1") }
        var expiry by remember { mutableStateOf("24h") }
        val focus = LocalFocusManager.current
        val fields = remember { List(2) { FocusRequester() } }
        KitSheet(onDismiss = { if (!busy) create = false }) {
            SheetHeader("New invitation", mark = { TintIcon(Tint.Purple, if (account) Icons.Rounded.PersonAdd else Icons.Rounded.Link, size = 40.dp) })
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = GroupGutter).padding(bottom = 32.dp)
                .animateContentSize(tween(300, easing = Emphasized))) {
                Caption("Type")
                Group {
                    InviteCheckRow("Account QR", null, account, !busy, { TintIcon(Tint.Purple, Icons.Rounded.PersonAdd, size = 40.dp) }) { account = true }
                    RowDivider(70.dp)
                    InviteCheckRow("Browser link", null, !account, !busy, { TintIcon(Tint.Teal, Icons.Rounded.Link, size = 40.dp) }) { account = false }
                }
                Footer(if (account) "A new QR every 5 seconds, for Delta Chat." else "Guests open it in a browser.")
                Spacer(Modifier.height(24.dp))
                Caption("Details")
                Group {
                    FieldRow("Label", title, { if (!busy) title = it.take(120) }, "Optional", fields[0],
                        fieldKeyboard(KeyboardType.Text, if (account) ImeAction.Done else ImeAction.Next), { if (account) focus.clearFocus() else fields[1].requestFocus() })
                    if (!account) {
                        RowDivider(16.dp)
                        FieldRow("Max people", uses, { if (!busy) uses = it.filter(Char::isDigit).take(6) }, "1", fields[1],
                            fieldKeyboard(KeyboardType.Number, ImeAction.Done), { focus.clearFocus() })
                    }
                }
                Spacer(Modifier.height(24.dp))
                Caption(if (account) "Sign in within" else "Expires after")
                Group {
                    listOf("1h" to "1 hour", "24h" to "1 day", "168h" to "7 days", "" to "Never").forEachIndexed { index, (value, label) ->
                        if (index > 0) RowDivider(16.dp)
                        InviteCheckRow(label, null, expiry == value, !busy) { expiry = value }
                    }
                }
                Footer(if (account) "The QR trusts this server’s self-signed certificate." else "Browsers may warn about the self-signed certificate.")
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy) InviteProgress()
                    error?.let { InviteStatus(Tint.Red, Icons.Rounded.PriorityHigh, it) }
                    creationState.error?.let { InviteStatus(Tint.Red, Icons.Rounded.PriorityHigh, it) }
                    if (creationState.uncertain) PillButton("Review invitations", onClick = { create = false; manage = true; refresh++ }, wide = true)
                }
                Spacer(Modifier.height(20.dp))
                BrandButton(if (account) "Create account QR" else "Create invite link", enabled = !busy && !creationState.uncertain && (account || (uses.toIntOrNull() ?: 0) in 1..999999)) {
                    error = null; notice = null
                    prefs.edit().putString("origin", origin).apply()
                    if (account) { select(""); rotation.start(origin, title, expiry); create = false }
                    else creation.create(origin, false, uses.toIntOrNull() ?: 1, title, expiry)
                }
            }
        }
    }
    if (manage) KitSheet(onDismiss = { manage = false }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { SheetHeader("Invitations", serverName,
                mark = { TintIcon(Tint.Purple, Icons.Rounded.ConfirmationNumber, size = 40.dp) }) }
            IconButton(onClick = { error = null; refresh++ }, enabled = !busy, modifier = Modifier.padding(top = 4.dp, end = 12.dp)) {
                Icon(Icons.Rounded.Refresh, "Refresh invitations", tint = colors.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = GroupGutter).padding(bottom = 32.dp)
            .animateContentSize(tween(300, easing = Emphasized)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!loaded) Group { InfoRow(Tint.Gray, Icons.Rounded.HourglassEmpty, "Not loaded") }
            else if (invitations.isEmpty()) Group { InfoRow(Tint.Gray, Icons.Rounded.ConfirmationNumber, "No invitations") }
            error?.let { InviteStatus(Tint.Red, Icons.Rounded.PriorityHigh, it) }
            if (creationState.uncertain && loaded) PillButton("I’ve checked", onClick = { creation.reviewed() }, wide = true)
            if (invitations.isNotEmpty()) Group {
                invitations.forEachIndexed { index, invitation ->
                    if (index > 0) RowDivider(60.dp)
                    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable(enabled = invitation.active && InviteLinks.origin(origin) != null && !busy, onClickLabel = "Show invitation QR") {
                                rotation.stop(); select(InviteLinks.invite(origin, invitation.token)); manage = false
                            }.heightIn(min = 60.dp).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            TintIcon(if (invitation.active) Tint.Purple else Tint.Gray, Icons.Rounded.ConfirmationNumber)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(invitation.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                InvitationSummary(invitation, numbers)
                            }
                        }
                        IconButton(onClick = { revoke = invitation }, enabled = !busy) { Icon(Icons.Rounded.DeleteOutline, "Revoke ${invitation.name}", tint = colors.error) }
                    }
                }
            }
        }
    }
    revoke?.let { invitation -> AlertDialog(onDismissRequest = { if (!busy) revoke = null }, containerColor = panelColor(), shape = RoundedCornerShape(28.dp),
        icon = { TintIcon(Tint.Red, Icons.Rounded.DeleteOutline, size = 40.dp) },
        title = { Text("Revoke ${invitation.name}?", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) },
        text = { Text("New sign-ups stop. Existing accounts stay.", color = colors.onSurfaceVariant) }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            revoking = true
            scope.launch {
                try {
                    request("DELETE", JSONObject().put("token", invitation.token))
                    if (selected?.token == invitation.token) select("")
                    notice = "Invitation revoked."; revoke = null; refresh++
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Revoke not confirmed. Refresh to check."; revoke = null; refresh++ }
                finally { revoking = false }
            }
        }) { Text("Revoke", color = colors.error, fontWeight = FontWeight.SemiBold) }
    }, dismissButton = { TextButton(onClick = { revoke = null }, enabled = !busy) { Text("Cancel", fontWeight = FontWeight.SemiBold) } }) }
    if (large && bitmap != null) Dialog(onDismissRequest = { large = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) { val previous = view.keepScreenOn; view.keepScreenOn = true; onDispose { view.keepScreenOn = previous } }
        Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(serverName, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.Black)
            Image(bitmap.asImageBitmap(), "QR code for $serverName", Modifier.fillMaxWidth().aspectRatio(1f))
            Text(if (personal) "Scan in Delta Chat · One person only" else "Scan with your camera", style = MaterialTheme.typography.bodyMedium, color = Color.Black.copy(alpha = 0.6f))
            PillButton("Close", onClick = { large = false }, wide = true)
        }
    }
}

/** A selectable row with a check, for one-of-many choices inside a group. */
@Composable
private fun InviteCheckRow(title: String, detail: String?, selected: Boolean, enabled: Boolean, mark: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled, Role.RadioButton, onClick).heightIn(min = if (mark != null) 68.dp else 52.dp)
        .padding(start = if (mark != null) 14.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        mark?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = if (mark != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (mark != null) FontWeight.SemiBold else FontWeight.Normal)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (selected) SelectedCheck()
    }
}

/** A short status line: a small mark for its meaning, then the message. */
@Composable
private fun InviteStatus(tint: Tint, icon: ImageVector, text: String, size: Dp = 22.dp) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TintIcon(tint, icon, size = size)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InviteProgress() = LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(2.dp)),
    color = MaterialTheme.colorScheme.primary, strokeCap = StrokeCap.Round)

@Composable
private fun InvitationRows(invitation: Invitation, numbers: NumberFormat) {
    ValueRow("Used", "${numbers.format(invitation.used)} of ${numbers.format(invitation.limit)}${if (invitation.pending > 0) " · ${numbers.format(invitation.pending)} pending" else ""}")
    RowDivider(16.dp)
    ValueRow("Status", invitation.statusLabel, valueColor = if (invitation.active) successText() else MaterialTheme.colorScheme.onSurfaceVariant)
    RowDivider(16.dp)
    ValueRow("Expires", invitation.expiry?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) ?: "No expiry")
}

@Composable
private fun InvitationSummary(invitation: Invitation, numbers: NumberFormat) {
    val expires = invitation.expiry?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) ?: "No expiry"
    Text("${numbers.format(invitation.used)} / ${numbers.format(invitation.limit)} used${if (invitation.pending > 0) " · ${numbers.format(invitation.pending)} pending" else ""} · ${invitation.statusLabel} · $expires",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun invitationBitmap(payload: String): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 1024, 1024)
    Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(1024 * 1024) { i -> if (matrix[i % 1024, i / 1024]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 1024, 0, 0, 1024, 1024)
    }
}.getOrNull()

private fun localInviteOrigin(context: Context): String = PhoneMailDomain.preferred(GuestNetworks.phone(context))?.origin.orEmpty()

/** A bar that empties over one rotation period, restarting with each new code. */
@Composable
private fun RotationTimer(issuedAt: Long, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(issuedAt) {
        progress.snapTo(1f)
        progress.animateTo(0f, tween(AccountQrRotation.PERIOD.toInt(), easing = LinearEasing))
    }
    LinearProgressIndicator(progress = { progress.value }, modifier = modifier.height(4.dp).clip(CircleShape).clearAndSetSemantics { },
        color = brandRed(), trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
}

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
