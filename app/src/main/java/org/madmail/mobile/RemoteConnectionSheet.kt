package org.madmail.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.engine.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RemoteConnectionSheet(server: RemoteServer, engine: RemoteEngine, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var inspected by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(engine.trusted) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var replace by remember { mutableStateOf(false) }
    fun runAction(block: suspend () -> Unit) {
        busy = true; error = null; notice = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Couldn’t complete this step. Check the key, passphrase or connection details." }
            finally { busy = false }
        }
    }
    fun saveKey() = runAction {
        val bytes = privateKey.toByteArray(); val phrase = passphrase.toByteArray()
        try { publicKey = withContext(Dispatchers.IO) { vault.importKey(bytes, phrase) }; privateKey = ""; passphrase = ""; method = "key"; notice = "SSH key saved on this phone." }
        finally { bytes.fill(0); phrase.fill(0) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAction {
            privateKey = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { String(it.readBytesLimited(65536)) } }
            notice = "Private key loaded. Enter its passphrase if encrypted, then save."
        }
    }
    LaunchedEffect(server.id) {
        try { withContext(Dispatchers.IO) { publicKey = vault.publicKey(); hasSavedTunnel = vault.tunnelToken() != null; vault.api()?.let { endpoint = it.endpoint; savedApiEndpoint = it.endpoint; hasSavedApi = true } } }
        catch (_: Exception) { error = "Saved credentials could not be unlocked. Enter new credentials." }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy || it != SheetValue.Hidden })) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Connect to ${server.host}", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!apiMode, { apiMode = false; token = ""; error = null }, enabled = !busy, label = { Text("SSH") }, leadingIcon = { Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp)) })
                FilterChip(apiMode, { apiMode = true; token = ""; error = null }, enabled = !busy, label = { Text("Admin API") }, leadingIcon = { Icon(Icons.Outlined.Api, null, Modifier.size(18.dp)) })
            }
            if (apiMode) {
                Text("Manage an existing server over HTTPS. Installation, updates and process control require SSH.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(endpoint, { endpoint = it; token = "" }, label = { Text("Admin API URL") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text(if (hasSavedApi) "New API token (optional)" else "API token") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("The API token is saved encrypted after connecting. HTTPS must be trusted by Android; for self-signed servers, use the SSH tunnel.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("key" to if (publicKey == null) "Generate key" else "Saved key", "import" to "Import key", "password" to "Password").forEach { (value, label) -> FilterChip(method == value, { method = value; error = null }, enabled = !busy, label = { Text(label) }) }
                }
                if (method == "key") {
                    if (publicKey == null) {
                        Text("Generate a key for this server, then add its public key to the SSH user’s ~/.ssh/authorized_keys.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(enabled = !busy, onClick = { runAction { publicKey = withContext(Dispatchers.IO) { vault.generate() }; notice = "Key generated. Add the public key to your server before connecting." } }) { Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(8.dp)); Text("Generate SSH key") }
                    } else {
                        Text("Saved public key", style = MaterialTheme.typography.labelLarge)
                        Text(publicKey!!, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !busy, onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Madmail SSH public key", publicKey))
                            notice = "Public key copied. Add it to ${server.user}’s authorized_keys."
                        }) { Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy public key") }
                        Text("The private key stays encrypted on this phone. Only the public key goes on your server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (method == "import") {
                    OutlinedButton(enabled = !busy, onClick = { importFile.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Choose private key file") }
                    OutlinedTextField(privateKey, { privateKey = it.take(65536) }, label = { Text("Or paste private key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), minLines = 2, maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Key passphrase (if encrypted)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text("The key and passphrase are saved encrypted on this phone.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !busy && privateKey.isNotBlank(), onClick = { if (publicKey != null) replace = true else saveKey() }) { Text(if (publicKey == null) "Save SSH key" else "Replace saved key") }
                } else {
                    OutlinedTextField(password, { password = it }, label = { Text("SSH password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text("Password is used for this connection only.", style = MaterialTheme.typography.bodySmall)
                }
                if (method != "password") {
                    Row(Modifier.fillMaxWidth().toggleable(value = customToken, enabled = !busy, role = Role.Checkbox, onValueChange = { customToken = it }), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Checkbox(customToken, onCheckedChange = null, enabled = !busy); Text("Use an existing server’s API token", Modifier.padding(top = 12.dp)) }
                    if (customToken) {
                        OutlinedTextField(token, { token = it }, label = { Text(if (hasSavedTunnel) "New API token (optional)" else "Admin API token") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        Text("Management uses the SSH tunnel to 127.0.0.1:18080. No sudo, installation or process changes. The API token is saved encrypted on this phone after a successful connection.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!inspected) OutlinedButton(enabled = !busy, onClick = { runAction { try { withContext(Dispatchers.IO) { engine.inspectHost() }; inspected = true } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "Couldn’t verify the SSH host." } } }) { Icon(Icons.Outlined.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Verify SSH host") }
                if (inspected) {
                    Text(engine.fingerprint.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    if (!accepted) {
                        Text("Compare this fingerprint with your server before trusting it.", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = { runAction { engine.acceptHost(); accepted = true } }) { Text("Trust this host") }
                    } else Text("SSH host verified", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (!customToken) Text("Installation and version management need root or passwordless sudo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val canConnect = if (apiMode) ApiEndpoint.normalize(endpoint) != null && (token.isNotBlank() || hasSavedApi && ApiEndpoint.normalize(endpoint) == savedApiEndpoint) else inspected && accepted && (if (method == "password") password.isNotEmpty() else method == "key" && publicKey != null) && (!customToken || token.isNotBlank() || hasSavedTunnel)
            Button(enabled = !busy && canConnect, onClick = { runAction {
                withContext(Dispatchers.IO) {
                    if (apiMode) {
                        val normalized = requireNotNull(ApiEndpoint.normalize(endpoint))
                        // A saved token can never silently follow an edited endpoint.
                        val secret = token.ifBlank { vault.api()?.takeIf { it.endpoint == normalized }?.token ?: error("Enter a token for the changed endpoint.") }
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
                else error = engine.state.value.error ?: "Connection failed. Check authentication and server access."
            } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.Link, null); Spacer(Modifier.width(8.dp)); Text("Connect") }
        }
    }
    if (replace) AlertDialog(onDismissRequest = { replace = false }, title = { Text("Replace this server’s saved key?") }, text = { Text("The old private key will be removed from this phone. Its public key stays on the server until you remove it there.") }, confirmButton = { TextButton(onClick = { replace = false; saveKey() }) { Text("Replace key") } }, dismissButton = { TextButton(onClick = { replace = false }) { Text("Cancel") } })
}
