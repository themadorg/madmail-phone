package org.madmail.mobile

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.madmail.mobile.engine.*

@Composable
internal fun EngineControls() {
    val context = LocalContext.current
    val engine = remember { LocalEngine.get(context) }
    val state by engine.state.collectAsState()
    val access = rememberBackgroundAccess()
    val networks by rememberGuestNetworks()
    val offline = networks?.isEmpty() == true
    var versions by remember { mutableStateOf(false) }
    val start = { context.startForegroundService(Intent(context, HostingService::class.java)); Unit }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    Column {
        Group(Modifier.animateContentSize(tween(300, easing = Emphasized))) {
            HostingStatus(state.phase, enabled = engine.selected != null, unreachable = offline) { on ->
                if (!on) context.startService(Intent(context, HostingService::class.java).setAction("stop"))
                else if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
            }
            RowDivider(64.dp)
            NavRow({ TintIcon(Tint.Gray, Icons.Rounded.Inventory2) }, stringResource(R.string.common_engine_versions), value = state.version ?: engine.selected?.version) { versions = true }
            RowDivider(64.dp)
            BackgroundHostingAccess(access)
        }
        AnimatedVisibility(offline, enter = fadeIn(tween(220)) + expandVertically(tween(300, easing = Emphasized)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(260, easing = Emphasized))) {
            NoNetworkNotice(Modifier.padding(top = 16.dp))
        }
        // The notice above already says why a start failed for lack of a network.
        state.error?.takeUnless { offline && it == NO_NETWORK }?.let { Footer(it, MaterialTheme.colorScheme.error) }
        access.error?.let { Footer(it, MaterialTheme.colorScheme.error) }
    }
    if (versions) EngineVersionsSheet { versions = false }
}

/** Android's battery exemption for hosting, re-read on every resume so it always shows the real state. */
internal class BackgroundAccess(val allowed: Boolean, val error: String?, val request: () -> Unit)

@Composable
internal fun rememberBackgroundAccess(): BackgroundAccess {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var allowed by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var error by remember { mutableStateOf<String?>(null) }
    val settingsHint = stringResource(R.string.common_background_error)
    val owner = LocalLifecycleOwner.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allowed = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = power.isIgnoringBatteryOptimizations(context.packageName)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return BackgroundAccess(allowed, error) {
        error = null
        val intent = if (allowed) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { request.launch(intent) }.onFailure { error = settingsHint }
    }
}

/** Background access as a row inside a group. Errors are the caller's to show under the group. */
@Composable
internal fun BackgroundHostingAccess(access: BackgroundAccess = rememberBackgroundAccess()) {
    val tap = rememberTap(access.request)
    Row(Modifier.fillMaxWidth().then(if (access.allowed) Modifier.clickable(role = Role.Button, onClick = tap) else Modifier).heightIn(min = 60.dp)
        .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TintIcon(if (access.allowed) Tint.Green else Tint.Orange, if (access.allowed) Icons.Rounded.BatteryFull else Icons.Rounded.BatteryAlert)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (access.allowed) stringResource(R.string.common_background_access) else stringResource(R.string.common_background_allow_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (access.allowed) stringResource(R.string.common_background_works) else stringResource(R.string.common_background_works_battery),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (access.allowed) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = Success)
            Text(stringResource(R.string.common_background_allowed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else PillButton(stringResource(R.string.common_background_allow), onClick = access.request)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineVersionsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { LocalEngine.get(context) }
    val state by engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    val updates = remember { AppUpdates(context) }
    var updateUrl by remember { mutableStateOf("") }
    var showPackageLink by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var releases by remember { mutableStateOf<List<GitRelease>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var stagedVersion by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(engine.selected?.version) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        loading = true; loadError = null
        try { releases = withContext(Dispatchers.IO) { ReleaseCatalog.list() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { loadError = e.message }
        finally { loading = false }
    }
    val download: (String) -> Unit = { url ->
        busy = true; error = null; stagedVersion = null
        scope.launch {
            try { stagedVersion = withContext(Dispatchers.IO) { updates.download(url) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    val packageField = remember { FocusRequester() }
    val canInstall = !busy && state.phase in listOf(EnginePhase.Stopped, EnginePhase.Failed)
    // A download in flight keeps the sheet open, so its result has somewhere to land.
    val downloading by rememberUpdatedState(busy)
    KitSheet(onDismiss = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !downloading })) {
        SheetHeader(stringResource(R.string.common_engine_versions_title), "themadorg/madmail", trailing = {
            IconButton(onClick = { refresh++ }, enabled = !loading && !busy) { Icon(Icons.Rounded.Refresh, stringResource(R.string.common_engine_refresh)) }
        })
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = GroupGutter).padding(bottom = 24.dp)
            .animateContentSize(tween(300, easing = Emphasized))) {
            if (loading) EngineProgress()
            // Releases with no Android build can't be used here; they're counted below instead of listed.
            val all = (engine.versions.map { it.version } + releases.map { it.version }).distinct().sortedWith { a, b -> compareVersions(b, a) }
            val versions = all.filter { version -> engine.versions.any { it.version == version } || releases.find { it.version == version }?.androidPackage != null }
            val hidden = all.size - versions.size
            if (versions.isNotEmpty()) Group {
                versions.forEachIndexed { index, version ->
                    val installed = engine.versions.find { it.version == version }
                    val release = releases.find { it.version == version }
                    val androidPackage = release?.androidPackage
                    if (index > 0) RowDivider(64.dp)
                    InfoRow(if (selected == version) Tint.Green else Tint.Gray, if (selected == version) Icons.Rounded.CheckCircle else Icons.Rounded.Inventory2, version, when {
                        selected == version -> stringResource(R.string.common_engine_in_use)
                        installed != null -> stringResource(R.string.common_engine_installed)
                        androidPackage != null -> stringResource(R.string.common_engine_available)
                        else -> stringResource(R.string.common_engine_unavailable)
                    }) {
                        if (installed != null && selected != version) PillButton(stringResource(R.string.common_engine_select), onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { engine.select(installed) } }
                                    .onSuccess { selected = version }.onFailure { error = it.message }
                            }
                        }, enabled = canInstall)
                        else if (installed == null && androidPackage != null) PillButton(stringResource(R.string.common_engine_download), onClick = { download(androidPackage.url) }, enabled = !busy)
                    }
                }
            }
            loadError?.let { Footer(it, MaterialTheme.colorScheme.error) }
            if (!loading && loadError == null && releases.isEmpty()) Footer(stringResource(R.string.common_engine_no_releases))
            if (!loading && hidden > 0) Footer(pluralStringResource(R.plurals.common_engine_hidden_releases, hidden, hidden))
            if (busy) { Spacer(Modifier.height(12.dp)); EngineProgress(); Footer(stringResource(R.string.common_engine_downloading)) }
            stagedVersion?.let { staged ->
                Spacer(Modifier.height(16.dp))
                PillButton(stringResource(R.string.common_engine_install, staged), onClick = { runCatching { updates.install() }.onFailure { error = it.message } }, filled = true, wide = true, enabled = canInstall)
            }
            error?.let { Footer(it, MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(20.dp))
            Group {
                ActionRow(if (showPackageLink) stringResource(R.string.common_engine_hide_link) else stringResource(R.string.common_engine_install_from_link), { showPackageLink = !showPackageLink }, enabled = !busy, icon = Icons.Rounded.Link)
                if (showPackageLink) {
                    RowDivider(16.dp)
                    FieldRow(stringResource(R.string.common_engine_url), updateUrl, { if (!busy) updateUrl = it }, "https://…", packageField, fieldKeyboard(KeyboardType.Uri, ImeAction.Done),
                        { if (!busy && updateUrl.startsWith("https://")) download(updateUrl) })
                    RowDivider(16.dp)
                    ActionRow(stringResource(R.string.common_engine_download), { download(updateUrl) }, enabled = !busy && updateUrl.startsWith("https://"), icon = Icons.Rounded.Download)
                }
            }
            if (showPackageLink) Footer(stringResource(R.string.common_engine_signed_url))
        }
    }
}

/** A thin rounded progress line, like the storage meter. */
@Composable
private fun EngineProgress(modifier: Modifier = Modifier) {
    LinearProgressIndicator(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(4.dp).clip(CircleShape),
        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), strokeCap = StrokeCap.Round,
        gapSize = 0.dp)
}
