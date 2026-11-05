package org.madmail.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.engine.*
import kotlin.math.abs

/** One decision per step. Back retraces the steps actually taken, so a skipped step never reappears. */
private enum class RemoteLeg { Method, AddKey, Import, Password, Verify, Connect, ApiAddress, ApiKey, Done }

/** What the single button under a step says, whether it can be pressed, and what it does. */
private data class LegAction(val label: String, val ready: Boolean, val run: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteConnectionSheet(server: RemoteServer, engine: RemoteEngine, onClose: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val toaster = LocalToaster.current
    val haptics = LocalHapticFeedback.current
    val state by engine.state.collectAsState()
    // Only known when the server was added in setup during this run; otherwise the hints stay generic.
    val provider = remember(server.id) { SetupProviders.of(server) }
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
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf(resources.getString(R.string.remote_working)) }
    var inspected by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(engine.trusted) }
    var error by remember { mutableStateOf<String?>(null) }
    var replace by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var leg by remember { mutableStateOf(RemoteLeg.Method) }
    val history = remember { mutableStateListOf<RemoteLeg>() }
    var forward by remember { mutableStateOf(true) }
    val fields = remember { List(4) { FocusRequester() } }
    val normalized = ApiEndpoint.normalize(endpoint)
    val savedApiMatches = hasSavedApi && normalized == savedApiEndpoint

    fun show(next: RemoteLeg, push: Boolean) {
        focusManager.clearFocus(); error = null; forward = push
        if (push) history += leg
        // The API legs switch to the Admin API and the SSH legs back; Connect and Done keep whichever was chosen.
        val api = when (next) { RemoteLeg.ApiAddress, RemoteLeg.ApiKey -> true; RemoteLeg.Connect, RemoteLeg.Done -> apiMode; else -> false }
        if (api != apiMode) { apiMode = api; token = "" }
        leg = next
    }
    fun go(next: RemoteLeg) = show(next, push = true)
    fun back() { if (history.isNotEmpty() && !busy) show(history.removeAt(history.lastIndex), push = false) }
    fun afterSignIn() = go(if (accepted) RemoteLeg.Connect else RemoteLeg.Verify)
    fun runAction(label: String = resources.getString(R.string.remote_working), block: suspend () -> Unit) {
        busy = true; busyLabel = label; error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = resources.getString(R.string.remote_error_finish) }
            finally { busy = false }
        }
    }
    fun generateKey() = runAction(resources.getString(R.string.remote_generating_key)) {
        publicKey = withContext(Dispatchers.IO) { vault.generate() }
        toaster.show(resources.getString(R.string.remote_key_generated))
        go(RemoteLeg.AddKey)
    }
    fun saveKey() = runAction(resources.getString(R.string.remote_saving_key)) {
        val bytes = privateKey.toByteArray(); val phrase = passphrase.toByteArray()
        try { publicKey = withContext(Dispatchers.IO) { vault.importKey(bytes, phrase) }; privateKey = ""; passphrase = ""; method = "key" }
        finally { bytes.fill(0); phrase.fill(0) }
        toaster.show(resources.getString(R.string.remote_key_saved))
        afterSignIn()
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAction(resources.getString(R.string.remote_loading_key)) {
            privateKey = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { String(it.readBytesLimited(65536)) } }
            toaster.show(resources.getString(R.string.remote_key_loaded))
        }
    }
    fun verifyHost() = runAction(resources.getString(R.string.remote_checking_server)) { try { withContext(Dispatchers.IO) { engine.inspectHost() }; inspected = true } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: resources.getString(R.string.remote_error_verify_host) } }
    fun trustHost() = runAction(resources.getString(R.string.remote_trusting_host)) { engine.acceptHost(); accepted = true; go(RemoteLeg.Connect) }
    fun connect() = runAction(resources.getString(R.string.remote_connecting)) {
        // A host trusted earlier is still checked first, so a changed key is reported as such rather than as a failed sign-in.
        if (!apiMode && !inspected) {
            try { withContext(Dispatchers.IO) { engine.inspectHost() }; inspected = true }
            catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: resources.getString(R.string.remote_error_verify_host); return@runAction }
        }
        withContext(Dispatchers.IO) {
            if (apiMode) {
                val target = requireNotNull(ApiEndpoint.normalize(endpoint))
                // A saved token can never silently follow an edited endpoint.
                val secret = token.ifBlank { vault.api()?.takeIf { it.endpoint == target }?.token ?: error("Enter a token for this URL.") }
                engine.connectApi(target, secret)
                if (engine.state.value.connected && engine.state.value.error == null) vault.saveApi(target, secret)
            } else if (method == "password") engine.connect(password)
            else {
                val apiToken = if (customToken) token.ifBlank { vault.tunnelToken() ?: error("Enter the API token.") } else null
                engine.connectKey(vault.ssh(), apiToken)
                if (apiToken != null && engine.state.value.connected && engine.state.value.error == null) vault.saveTunnelToken(apiToken)
            }
        }
        if (engine.state.value.connected && engine.state.value.error == null) {
            options.edit().putString("preferred", if (apiMode) "api" else "ssh").apply { if (apiMode) putString("api-endpoint", ApiEndpoint.normalize(endpoint)) }.apply(); password = ""; token = ""
            forward = true; history.clear(); leg = RemoteLeg.Done
        }
        else error = engine.state.value.error ?: resources.getString(R.string.remote_error_connect)
    }
    fun copyKey() {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Madmail SSH public key", publicKey))
        toaster.show(resources.getString(R.string.remote_key_copied))
    }
    fun shareKey() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, publicKey)
        runCatching { context.startActivity(Intent.createChooser(send, resources.getString(R.string.remote_share_public_key))) }.onFailure { toaster.show(resources.getString(R.string.remote_error_share), error = true) }
    }
    LaunchedEffect(server.id) {
        try { withContext(Dispatchers.IO) { publicKey = vault.publicKey(); hasSavedTunnel = vault.tunnelToken() != null; vault.api()?.let { endpoint = it.endpoint; savedApiEndpoint = it.endpoint; hasSavedApi = true } } }
        catch (_: Exception) { error = resources.getString(R.string.remote_error_unlock) }
        // Open on the first thing still missing: a known server with saved sign-in goes straight to Connect.
        leg = when {
            apiMode -> if (hasSavedApi && ApiEndpoint.normalize(endpoint) == savedApiEndpoint) RemoteLeg.Connect else RemoteLeg.ApiAddress
            accepted && publicKey != null -> RemoteLeg.Connect
            else -> RemoteLeg.Method
        }
        loaded = true
    }
    // The fingerprint is fetched as soon as the step opens; trusting it stays a deliberate tap.
    LaunchedEffect(leg, loaded) { if (loaded && leg == RemoteLeg.Verify && !inspected && !accepted && !busy) verifyHost() }
    LaunchedEffect(error) { if (error != null) haptics.performHapticFeedback(HapticFeedbackType.Reject) }
    LaunchedEffect(leg) { if (leg == RemoteLeg.Done) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }

    val verify = if (accepted) 0 else 1
    val remaining = when (leg) {
        RemoteLeg.Method -> 2 + verify
        RemoteLeg.AddKey, RemoteLeg.Import, RemoteLeg.Password -> 1 + verify
        RemoteLeg.Verify, RemoteLeg.ApiKey -> 1
        RemoteLeg.ApiAddress -> 2
        RemoteLeg.Connect, RemoteLeg.Done -> 0
    }
    val count = history.size + 1 + remaining
    val canConnect = if (apiMode) normalized != null && (token.isNotBlank() || savedApiMatches)
        else accepted && (if (method == "password") password.isNotEmpty() else publicKey != null) && (!customToken || token.isNotBlank() || hasSavedTunnel)
    // One button under every step; its label morphs instead of the button moving.
    val (label, ready, action) = when (leg) {
        RemoteLeg.Method -> LegAction(if (method == "key" && publicKey == null) stringResource(R.string.remote_create_key) else stringResource(R.string.remote_continue), true) {
            when (method) { "key" -> if (publicKey == null) generateKey() else go(RemoteLeg.AddKey); "import" -> go(RemoteLeg.Import); else -> go(RemoteLeg.Password) }
        }
        RemoteLeg.AddKey -> LegAction(stringResource(R.string.remote_continue), publicKey != null) { afterSignIn() }
        RemoteLeg.Import -> LegAction(if (publicKey == null) stringResource(R.string.remote_save_key) else stringResource(R.string.remote_replace_key), privateKey.isNotBlank()) { if (publicKey != null) replace = true else saveKey() }
        RemoteLeg.Password -> LegAction(stringResource(R.string.remote_continue), password.isNotEmpty()) { afterSignIn() }
        RemoteLeg.Verify -> when {
            accepted -> LegAction(stringResource(R.string.remote_continue), true) { go(RemoteLeg.Connect) }
            inspected -> LegAction(stringResource(R.string.remote_trust_continue), true) { trustHost() }
            else -> LegAction(stringResource(R.string.remote_check_server), true) { verifyHost() }
        }
        RemoteLeg.Connect -> LegAction(stringResource(R.string.remote_connect), canConnect) { connect() }
        RemoteLeg.ApiAddress -> LegAction(stringResource(R.string.remote_continue), normalized != null) { go(RemoteLeg.ApiKey) }
        RemoteLeg.ApiKey -> LegAction(stringResource(R.string.remote_continue), token.isNotBlank() || savedApiMatches) { go(RemoteLeg.Connect) }
        RemoteLeg.Done -> LegAction(stringResource(R.string.remote_done), true) { onClose() }
    }
    val shift = with(LocalDensity.current) { 24.dp.roundToPx() }

    KitSheet(onDismiss = { if (!busy) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy || it != SheetValue.Hidden })) {
        // Registered inside the sheet so it reaches the sheet's own back dispatcher and steps back before the sheet closes.
        BackHandler(loaded && history.isNotEmpty() && !busy && leg != RemoteLeg.Done) { back() }
        val canBack = loaded && history.isNotEmpty() && leg != RemoteLeg.Done
        val showDots = loaded && leg != RemoteLeg.Done && count > 1
        // The bar folds away when there is nothing to show in it, so the title sits under the grabber.
        val barHeight by animateDpAsState(if (canBack || showDots) 48.dp else 4.dp, tween(320, easing = Emphasized), label = "Step bar")
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Box(Modifier.fillMaxWidth().height(barHeight).padding(horizontal = 8.dp)) {
                if (canBack) IconButton(onClick = rememberTap(::back), modifier = Modifier.align(Alignment.CenterStart), enabled = !busy) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.remote_back))
                }
                if (showDots) SheetDots(history.size, count, Modifier.align(Alignment.Center))
            }
            AnimatedContent(if (loaded) leg else null, label = "Connection step", transitionSpec = {
                val dir = if (forward) 1 else -1
                (fadeIn(tween(260, 60, Emphasized)) + slideInHorizontally(tween(320, easing = Emphasized)) { dir * shift }) togetherWith
                    (fadeOut(tween(120, easing = Emphasized)) + slideOutHorizontally(tween(320, easing = Emphasized)) { -dir * shift }) using
                    SizeTransform(clip = false) { _, _ -> tween(320, easing = Emphasized) }
            }) { shown ->
                if (shown == null) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2.dp)
                } else Column {
                    val mark: @Composable () -> Unit = {
                        if (apiMode) TintIcon(Tint.Teal, Icons.Rounded.Api, size = 40.dp)
                        else if (provider != null) ProviderMark(provider)
                        else TintIcon(Tint.Blue, Icons.Rounded.Dns, size = 40.dp)
                    }
                    when (shown) {
                        RemoteLeg.Method -> SheetHeader(stringResource(R.string.remote_how_sign_in), stringResource(R.string.remote_how_sign_in_sub, server.user, server.host), mark)
                        RemoteLeg.AddKey -> SheetHeader(stringResource(R.string.remote_add_key_title), provider?.let { stringResource(it.keyHintRes) } ?: stringResource(R.string.remote_add_key_hint, server.user), mark)
                        RemoteLeg.Import -> SheetHeader(stringResource(R.string.remote_import_title), stringResource(R.string.remote_saved_encrypted), mark)
                        RemoteLeg.Password -> SheetHeader(stringResource(R.string.remote_password_title), stringResource(R.string.remote_password_sub), mark)
                        RemoteLeg.Verify -> SheetHeader(stringResource(R.string.remote_verify_title), when { accepted -> stringResource(R.string.remote_verify_trusted); inspected -> stringResource(R.string.remote_verify_check); else -> stringResource(R.string.remote_verify_fingerprint) }, mark)
                        RemoteLeg.Connect -> SheetHeader(stringResource(R.string.remote_ready_title), if (apiMode) stringResource(R.string.remote_over_api) else stringResource(R.string.remote_needs_root), mark)
                        RemoteLeg.ApiAddress -> SheetHeader(stringResource(R.string.remote_api_address_title), stringResource(R.string.remote_api_address_sub), mark)
                        RemoteLeg.ApiKey -> SheetHeader(stringResource(R.string.remote_api_token_title), if (savedApiMatches) stringResource(R.string.remote_api_token_keep) else stringResource(R.string.remote_saved_encrypted), mark)
                        RemoteLeg.Done -> SheetHeader(stringResource(R.string.remote_connected_title), if (state.managed && state.version == null) stringResource(R.string.remote_next_install) else stringResource(R.string.remote_manage_home), mark)
                    }
                    Column(Modifier.padding(horizontal = GroupGutter).animateContentSize(tween(300, easing = Emphasized)), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        when (shown) {
                            RemoteLeg.Method -> {
                                Group(Modifier.selectableGroup()) {
                                    PickRow(Tint.Green, Icons.Rounded.Key, stringResource(R.string.remote_ssh_key), if (publicKey == null) stringResource(R.string.remote_ssh_key_recommended) else stringResource(R.string.remote_ssh_key_saved),
                                        method == "key", !busy) { method = "key"; error = null }
                                    RowDivider(64.dp)
                                    PickRow(Tint.Graphite, Icons.Rounded.UploadFile, stringResource(R.string.remote_import_key), stringResource(R.string.remote_use_your_own), method == "import", !busy) { method = "import"; error = null }
                                    RowDivider(64.dp)
                                    PickRow(Tint.Graphite, Icons.Rounded.Password, stringResource(R.string.remote_password), stringResource(R.string.remote_not_saved), method == "password", !busy) { method = "password"; error = null }
                                }
                                Group { ActionRow(stringResource(R.string.remote_use_api_instead), { go(RemoteLeg.ApiAddress) }, enabled = !busy, icon = Icons.Rounded.Api) }
                            }
                            RemoteLeg.AddKey -> Section(null) {
                                Group {
                                    Text(publicKey.orEmpty(), Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    RowDivider(16.dp)
                                    ActionRow(stringResource(R.string.remote_copy_key), ::copyKey, enabled = !busy, icon = Icons.Rounded.ContentCopy)
                                    RowDivider(16.dp)
                                    ActionRow(stringResource(R.string.remote_share_key), ::shareKey, enabled = !busy, icon = Icons.Rounded.Share)
                                }
                                Footer(stringResource(R.string.remote_private_stays))
                            }
                            RemoteLeg.Import -> Group {
                                ActionRow(stringResource(R.string.remote_choose_key_file), { importFile.launch(arrayOf("*/*")) }, enabled = !busy, icon = Icons.Rounded.UploadFile)
                                RowDivider(16.dp)
                                KeyField(privateKey) { if (!busy) privateKey = it.take(65536) }
                                RowDivider(16.dp)
                                SecretField(stringResource(R.string.remote_passphrase), passphrase, { if (!busy) passphrase = it }, stringResource(R.string.remote_if_encrypted), fields[2]) { focusManager.clearFocus() }
                            }
                            RemoteLeg.Password -> Group {
                                SecretField(stringResource(R.string.remote_password), password, { if (!busy) password = it }, stringResource(R.string.remote_ssh_password_hint), fields[2]) { if (password.isNotEmpty()) afterSignIn() else focusManager.clearFocus() }
                            }
                            RemoteLeg.Verify -> Group {
                                when {
                                    inspected -> {
                                        FingerprintRow(engine.fingerprint.orEmpty())
                                        if (accepted) {
                                            RowDivider(64.dp)
                                            InfoRow(Tint.Green, Icons.Rounded.VerifiedUser, stringResource(R.string.remote_host_verified))
                                        }
                                    }
                                    accepted -> InfoRow(Tint.Green, Icons.Rounded.VerifiedUser, stringResource(R.string.remote_host_verified), server.host)
                                    else -> InfoRow(Tint.Graphite, Icons.Rounded.Fingerprint, if (busy) stringResource(R.string.remote_checking) else stringResource(R.string.remote_not_checked), server.host) {
                                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2.dp)
                                    }
                                }
                            }
                            RemoteLeg.Connect -> {
                                Section(null) {
                                    Group {
                                        ValueRow(if (apiMode) stringResource(R.string.remote_address) else stringResource(R.string.remote_server), if (apiMode) normalized ?: endpoint else server.host)
                                        RowDivider(16.dp)
                                        ValueRow(stringResource(R.string.remote_sign_in), when { apiMode -> if (token.isBlank()) stringResource(R.string.remote_saved_token) else stringResource(R.string.remote_new_token); method == "password" -> stringResource(R.string.remote_password); else -> stringResource(R.string.remote_ssh_key) })
                                        RowDivider(16.dp)
                                        ActionRow(if (apiMode) stringResource(R.string.remote_change_address_token) else stringResource(R.string.remote_change_sign_in), { go(if (apiMode) RemoteLeg.ApiAddress else RemoteLeg.Method) }, enabled = !busy)
                                    }
                                    if (apiMode) Footer(stringResource(R.string.remote_needs_ssh))
                                }
                                if (!apiMode && method != "password") Section(null) {
                                    SheetDisclosure(stringResource(R.string.remote_advanced), advanced || customToken) { advanced = !(advanced || customToken); if (!advanced) customToken = false }
                                    AnimatedVisibility(advanced || customToken, enter = fadeIn(tween(200, 60)) + expandVertically(tween(320, easing = Emphasized)),
                                        exit = fadeOut(tween(100)) + shrinkVertically(tween(260, easing = Emphasized))) {
                                        Column {
                                            Group {
                                                SwitchRow(Tint.Graphite, Icons.Rounded.VpnKey, stringResource(R.string.remote_use_existing_token), null, customToken, !busy) { customToken = it }
                                                if (customToken) {
                                                    RowDivider(16.dp)
                                                    SecretField(stringResource(R.string.remote_api_token), token, { if (!busy) token = it }, if (hasSavedTunnel) stringResource(R.string.remote_optional) else stringResource(R.string.remote_paste_token), fields[3], pasteable = true) { focusManager.clearFocus() }
                                                }
                                            }
                                            Footer(if (customToken) stringResource(R.string.remote_token_footer_on) else stringResource(R.string.remote_token_footer_off))
                                        }
                                    }
                                }
                            }
                            RemoteLeg.ApiAddress -> {
                                Section(null) {
                                    Group {
                                        FieldRow(stringResource(R.string.remote_api_url), endpoint, { if (!busy) { endpoint = it; token = "" } }, "https://host/api/admin", fields[0], fieldKeyboard(KeyboardType.Uri, ImeAction.Done),
                                            { if (normalized != null) go(RemoteLeg.ApiKey) else focusManager.clearFocus() },
                                            if (endpoint.isNotBlank() && normalized == null) stringResource(R.string.remote_error_https_url) else null)
                                    }
                                    Footer(stringResource(R.string.remote_needs_ssh))
                                }
                                Group { ActionRow(stringResource(R.string.remote_use_ssh_instead), { go(RemoteLeg.Method) }, enabled = !busy, icon = Icons.Rounded.Terminal) }
                            }
                            RemoteLeg.ApiKey -> Group {
                                SecretField(if (hasSavedApi) stringResource(R.string.remote_new_token) else stringResource(R.string.remote_api_token), token, { if (!busy) token = it }, if (savedApiMatches) stringResource(R.string.remote_optional) else stringResource(R.string.remote_paste_token), fields[1], pasteable = true) {
                                    if (token.isNotBlank() || savedApiMatches) go(RemoteLeg.Connect) else focusManager.clearFocus()
                                }
                            }
                            RemoteLeg.Done -> Group {
                                InfoRow(Tint.Green, Icons.Rounded.CheckCircle, server.host, state.status)
                            }
                        }
                        // Progress and failures stay with the step that caused them.
                        if (busy && shown != RemoteLeg.Verify) Group {
                            InfoRow(Tint.Teal, Icons.Rounded.Sync, "$busyLabel…") { CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2.dp) }
                        }
                        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            BrandButton(label, Modifier.padding(horizontal = GroupGutter).padding(top = 24.dp), enabled = loaded && !busy && ready) { action() }
        }
    }
    if (replace) AlertDialog(onDismissRequest = { replace = false }, containerColor = panelColor(), shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.remote_replace_title), fontWeight = FontWeight.SemiBold) },
        text = { Text(stringResource(R.string.remote_replace_body)) },
        confirmButton = { PillButton(stringResource(R.string.remote_replace_key), { replace = false; saveKey() }, filled = true) },
        dismissButton = { TextButton(onClick = { replace = false }) { Text(stringResource(R.string.remote_cancel)) } })
}

/** Caption, then the section's groups and footers, kept tight; sections themselves are spaced by the parent. */
@Composable
private fun Section(caption: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        caption?.let { Caption(it) }
        content()
    }
}

/** Where the sheet is in its steps; the active dot stretches and glides as steps change. */
@Composable
private fun SheetDots(index: Int, count: Int, modifier: Modifier) {
    val description = stringResource(R.string.remote_step_of, index + 1, count)
    val position by animateFloatAsState(index.toFloat(), tween(320, easing = Emphasized), label = "Step dots")
    val active = brandRed()
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Row(modifier.semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { dot ->
            val weight = (1f - abs(position - dot)).coerceIn(0f, 1f)
            Box(Modifier.size(lerp(6.dp, 22.dp, weight), 6.dp).clip(CircleShape).background(lerp(idle, active, weight)))
        }
    }
}

/** A quiet "Advanced" toggle that opens extra settings below it. */
@Composable
private fun SheetDisclosure(label: String, open: Boolean, onToggle: () -> Unit) {
    val toggleLabel = if (open) stringResource(R.string.remote_hide_x, label) else stringResource(R.string.remote_show_x, label)
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(260, easing = Emphasized), label = "Disclosure")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = rememberTap(onToggle))
        .heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp).rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One option of a single choice, with a check on the selected row. */
@Composable
private fun PickRow(tint: Tint, icon: ImageVector, title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = rememberTap(onClick)).heightIn(min = 60.dp)
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
    val toggleLabel = if (reveal) stringResource(R.string.remote_hide_x, label) else stringResource(R.string.remote_show_x, label)
    val pasteLabel = stringResource(R.string.remote_paste)
    val clipboard = LocalClipboardManager.current
    FieldRow(label, value, onValue, placeholder, focus, fieldKeyboard(KeyboardType.Password, ImeAction.Done), onDone, secret = !reveal,
        trailing = if (value.isEmpty() && !pasteable) null else ({
            if (value.isEmpty()) TextButton(onClick = { clipboard.getText()?.text?.let(onValue) }) { Text(pasteLabel) }
            else IconButton(onClick = { reveal = !reveal }) {
                Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, toggleLabel,
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
                if (value.isEmpty()) Text(stringResource(R.string.remote_paste_key), style = text, color = colors.onSurfaceVariant.copy(alpha = 0.85f))
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
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.remote_copy_fingerprint)) {
            clipboard.setText(AnnotatedString(fingerprint)); haptics.performHapticFeedback(HapticFeedbackType.Confirm); copied = true
        }.heightIn(min = 60.dp).padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TintIcon(Tint.Graphite, Icons.Rounded.Fingerprint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.remote_host_fingerprint), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(fingerprint, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, if (copied) stringResource(R.string.remote_copied) else null, Modifier.size(16.dp),
            tint = if (copied) Success else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}
