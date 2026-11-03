package org.madmail.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.engine.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteConnectionSheet(server: RemoteServer, engine: RemoteEngine, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val vault = remember(server.id) { RemoteCredentials(context, server.id) }
    val options = remember(server.id) { context.getSharedPreferences("remote-options-${server.id}", Context.MODE_PRIVATE) }
    var apiMode by remember { mutableStateOf(options.getString("preferred", "ssh") == "api") }
    var method by remember { mutableStateOf("key") }
    var publicKey by remember { mutableStateOf<String?>(null) }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(options.getString("api-endpoint", null) ?: "https://${server.host}:18443/api/admin") }
    var hasSavedApi by remember { mutableStateOf(false) }
    var savedApiEndpoint by remember { mutableStateOf<String?>(null) }
    var hasSavedTunnel by remember { mutableStateOf(false) }
    var customToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("Working") }
    var inspected by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(engine.trusted) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var replace by remember { mutableStateOf(false) }
    val fields = remember { List(4) { FocusRequester() } }
    fun runAction(label: String = "Working", block: suspend () -> Unit) {
        busy = true; busyLabel = label; error = null; notice = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn’t finish. Check the key, passphrase or connection." }
            finally { busy = false }
        }
    }
    fun saveKey() = runAction("Saving key") {
        val bytes = privateKey.toByteArray(); val phrase = passphrase.toByteArray()
        try { publicKey = withContext(Dispatchers.IO) { vault.importKey(bytes, phrase) }; privateKey = ""; passphrase = ""; method = "key"; notice = "Key saved." }
        finally { bytes.fill(0); phrase.fill(0) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAction("Loading key") {
            privateKey = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { String(it.readBytesLimited(65536)) } }
            notice = "Key loaded. Add its passphrase if any, then save."
        }
    }
    fun verifyHost() = runAction("Checking server") { try { withContext(Dispatchers.IO) { engine.inspectHost() }; inspected = true } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "Couldn’t verify the host." } }
    fun trustHost() = runAction("Trusting host") { engine.acceptHost(); accepted = true }
    fun connect() = runAction("Connecting") {
        withContext(Dispatchers.IO) {
            if (apiMode) {
                val normalized = requireNotNull(ApiEndpoint.normalize(endpoint))
                // A saved token can never silently follow an edited endpoint.
                val secret = token.ifBlank { vault.api()?.takeIf { it.endpoint == normalized }?.token ?: error("Enter a token for this URL.") }
                engine.connectApi(normalized, secret)
                if (engine.state.value.connected && engine.state.value.error == null) vault.saveApi(normalized, secret)
            } else if (method == "password") engine.connect(password)
            else {
                val apiToken = if (customToken) token.ifBlank { vault.tunnelToken() ?: error("Enter the API token.") } else null
                engine.connectKey(vault.ssh(), apiToken)
                if (apiToken != null && engine.state.value.connected && engine.state.value.error == null) vault.saveTunnelToken(apiToken)
            }
        }
        if (engine.state.value.connected && engine.state.value.error == null) { options.edit().putString("preferred", if (apiMode) "api" else "ssh").apply { if (apiMode) putString("api-endpoint", ApiEndpoint.normalize(endpoint)) }.apply(); password = ""; token = ""; onClose() }
        else error = engine.state.value.error ?: "Couldn’t connect. Check sign-in and server access."
    }
    LaunchedEffect(server.id) {
        try { withContext(Dispatchers.IO) { publicKey = vault.publicKey(); hasSavedTunnel = vault.tunnelToken() != null; vault.api()?.let { endpoint = it.endpoint; savedApiEndpoint = it.endpoint; hasSavedApi = true } } }
        catch (_: Exception) { error = "Couldn’t unlock saved credentials. Enter them again." }
    }
    KitSheet(onDismiss = { if (!busy) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy || it != SheetValue.Hidden })) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SheetHeader("Connect to ${server.host}", if (apiMode) "Admin API" else "SSH as ${server.user}") { TintIcon(Tint.Blue, Icons.Rounded.Dns, size = 40.dp) }
            Column(Modifier.padding(horizontal = GroupGutter).animateContentSize(tween(300, easing = Emphasized)), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Section("Connect with") {
                    Group(Modifier.selectableGroup()) {
                        PickRow(Tint.Graphite, Icons.Rounded.Terminal, "SSH", "Full control", !apiMode, !busy) { apiMode = false; token = ""; error = null }
                        RowDivider(60.dp)
                        PickRow(Tint.Teal, Icons.Rounded.Api, "Admin API", "Manage over HTTPS", apiMode, !busy) { apiMode = true; token = ""; error = null }
                    }
                }
                if (apiMode) Section("Admin API") {
                    Group {
                        FieldRow("API URL", endpoint, { if (!busy) { endpoint = it; token = "" } }, "https://host/api/admin", fields[0], fieldKeyboard(KeyboardType.Uri, ImeAction.Next), { fields[1].requestFocus() })
                        RowDivider(16.dp)
                        SecretField(if (hasSavedApi) "New token" else "API token", token, { if (!busy) token = it }, if (hasSavedApi) "Optional" else "Paste the token", fields[1], pasteable = true) { focusManager.clearFocus() }
                    }
                    Footer("Installs, updates and self-signed servers need SSH.")
                } else {
                    Section("Server identity") {
                        Group {
                            if (!inspected) InfoRow(Tint.Graphite, Icons.Rounded.Fingerprint, "Not verified yet")
                            else {
                                FingerprintRow(engine.fingerprint.orEmpty())
                                if (accepted) {
                                    RowDivider(60.dp)
                                    InfoRow(Tint.Green, Icons.Rounded.VerifiedUser, "Host verified")
                                }
                            }
                        }
                        if (inspected && !accepted) Footer("Check it matches your server before trusting.")
                    }
                    Section("Sign in with") {
                        Group(Modifier.selectableGroup()) {
                            PickRow(Tint.Graphite, Icons.Rounded.Key, if (publicKey == null) "Generate key" else "Saved key",
                                "On this phone", method == "key", !busy) { method = "key"; error = null }
                            RowDivider(60.dp)
                            PickRow(Tint.Graphite, Icons.Rounded.UploadFile, "Import key", "Use your own key", method == "import", !busy) { method = "import"; error = null }
                            RowDivider(60.dp)
                            PickRow(Tint.Graphite, Icons.Rounded.Password, "Password", "Not saved", method == "password", !busy) { method = "password"; error = null }
                        }
                        if (!customToken) Footer("Installs need root or passwordless sudo.")
                    }
                    when (method) {
                        "key" -> if (publicKey == null) Section(null) {
                            Group {
                                InfoRow(Tint.Graphite, Icons.Rounded.Key, "No key yet", "Generate one, then add it to ~/.ssh/authorized_keys.")
                                RowDivider(16.dp)
                                ActionRow("Generate SSH key", { runAction("Generating key") { publicKey = withContext(Dispatchers.IO) { vault.generate() }; notice = "Key generated. Add it to your server." } },
                                    enabled = !busy, icon = Icons.Rounded.Key)
                            }
                        } else Section("Public key") {
                            Group {
                                Text(publicKey!!, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                RowDivider(16.dp)
                                ActionRow("Copy public key", {
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Madmail SSH public key", publicKey))
                                    notice = "Copied. Add it to ${server.user}’s authorized_keys."
                                }, enabled = !busy, icon = Icons.Rounded.ContentCopy)
                            }
                            Footer("The private key stays on this phone.")
                        }
                        "import" -> Section("Private key") {
                            Group {
                                ActionRow("Choose key file", { importFile.launch(arrayOf("*/*")) }, enabled = !busy, icon = Icons.Rounded.UploadFile)
                                RowDivider(16.dp)
                                KeyField(privateKey) { if (!busy) privateKey = it.take(65536) }
                                RowDivider(16.dp)
                                SecretField("Passphrase", passphrase, { if (!busy) passphrase = it }, "If encrypted", fields[2]) { focusManager.clearFocus() }
                                RowDivider(16.dp)
                                ActionRow(if (publicKey == null) "Save SSH key" else "Replace saved key", { if (publicKey != null) replace = true else saveKey() },
                                    enabled = !busy && privateKey.isNotBlank(), icon = Icons.Rounded.Save)
                            }
                            Footer("Saved encrypted on this phone.")
                        }
                        else -> Section(null) {
                            Group { SecretField("Password", password, { if (!busy) password = it }, "SSH password", fields[2]) { focusManager.clearFocus() } }
                        }
                    }
                    if (method != "password") Section(null) {
                        Group {
                            SwitchRow(Tint.Graphite, Icons.Rounded.VpnKey, "Use an existing API token", null, customToken, !busy) { customToken = it }
                            if (customToken) {
                                RowDivider(16.dp)
                                SecretField("API token", token, { if (!busy) token = it }, if (hasSavedTunnel) "Optional" else "Paste the token", fields[3], pasteable = true) { focusManager.clearFocus() }
                            }
                        }
                        if (customToken) Footer("Manage over SSH without sudo. Can’t install, start or stop.")
                    }
                }
                if (busy || notice != null || error != null) Section(null) {
                    if (busy) Group {
                        InfoRow(Tint.Teal, Icons.Rounded.Sync, "$busyLabel…") { CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2.dp) }
                    }
                    notice?.let { Footer(it) }
                    error?.let { Footer(it, MaterialTheme.colorScheme.error) }
                }
                val canConnect = if (apiMode) ApiEndpoint.normalize(endpoint) != null && (token.isNotBlank() || hasSavedApi && ApiEndpoint.normalize(endpoint) == savedApiEndpoint) else inspected && accepted && (if (method == "password") password.isNotEmpty() else method == "key" && publicKey != null) && (!customToken || token.isNotBlank() || hasSavedTunnel)
                // One button whose label morphs through the steps: verify the host, trust it, then connect.
                val (label, ready, action) = when {
                    apiMode || inspected && accepted -> Triple("Connect", canConnect, ::connect)
                    !inspected -> Triple("Verify host", true, ::verifyHost)
                    else -> Triple("Trust and continue", true, ::trustHost)
                }
                BrandButton(label, enabled = !busy && ready) { action() }
            }
        }
    }
    if (replace) AlertDialog(onDismissRequest = { replace = false }, containerColor = panelColor(), shape = RoundedCornerShape(28.dp),
        title = { Text("Replace saved key?", fontWeight = FontWeight.SemiBold) },
        text = { Text("The old key is deleted from this phone. Remove it from the server yourself.") },
        confirmButton = { PillButton("Replace key", { replace = false; saveKey() }, filled = true) },
        dismissButton = { TextButton(onClick = { replace = false }) { Text("Cancel") } })
}

/** Caption, then the section's groups and footers, kept tight; sections themselves are spaced by the parent. */
@Composable
private fun Section(caption: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        caption?.let { Caption(it) }
        content()
    }
}

/** One option of a single choice, with a check on the selected row. */
@Composable
private fun PickRow(tint: Tint, icon: ImageVector, title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).heightIn(min = 60.dp)
        .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TintIcon(tint, icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) SelectedCheck()
        }
    }
}

/** A masked single-line value with Paste while empty and show/hide once filled. */
@Composable
private fun SecretField(label: String, value: String, onValue: (String) -> Unit, placeholder: String, focus: FocusRequester, pasteable: Boolean = false, onDone: () -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    FieldRow(label, value, onValue, placeholder, focus, fieldKeyboard(KeyboardType.Password, ImeAction.Done), onDone, secret = !reveal,
        trailing = if (value.isEmpty() && !pasteable) null else ({
            if (value.isEmpty()) TextButton(onClick = { clipboard.getText()?.text?.let(onValue) }) { Text("Paste") }
            else IconButton(onClick = { reveal = !reveal }) {
                Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide $label" else "Show $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }))
}

/** A few masked lines for a pasted private key; it lives in a group, so it has no outline of its own. */
@Composable
private fun KeyField(value: String, onValue: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.bodyLarge
    BasicTextField(value, onValue, Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 16.dp),
        textStyle = text.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary), minLines = 2, maxLines = 4,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (value.isEmpty()) VisualTransformation.None else PasswordVisualTransformation(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text("Or paste the key", style = text, color = colors.onSurfaceVariant.copy(alpha = 0.85f))
                inner()
            }
        })
}

/** The host's fingerprint in full, so it can be compared character by character; tap to copy. */
@Composable
private fun FingerprintRow(fingerprint: String) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Copy fingerprint") {
            clipboard.setText(AnnotatedString(fingerprint)); haptics.performHapticFeedback(HapticFeedbackType.Confirm); copied = true
        }.heightIn(min = 60.dp).padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TintIcon(Tint.Graphite, Icons.Rounded.Fingerprint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Host fingerprint", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(fingerprint, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, if (copied) "Copied" else null, Modifier.size(16.dp),
            tint = if (copied) Success else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}
