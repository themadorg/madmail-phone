package org.madmail.mobile

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
            NavRow({ TintIcon(Tint.Gray, Icons.Rounded.Inventory2) }, "Versions", value = state.version ?: engine.selected?.version) { versions = true }
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
        runCatching { request.launch(intent) }.onFailure { error = "Allow it in Settings → Apps → Madmail → Battery." }
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
            Text(if (access.allowed) "Background access" else "Allow background hosting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (access.allowed) "Works with the screen off." else "Works with the screen off. Uses more battery.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (access.allowed) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = Success)
            Text("Allowed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else PillButton("Allow", onClick = access.request)
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
        SheetHeader("Madmail versions", "themadorg/madmail", trailing = {
            IconButton(onClick = { refresh++ }, enabled = !loading && !busy) { Icon(Icons.Rounded.Refresh, "Check for new versions") }
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
                        selected == version -> "In use"
                        installed != null -> "Installed"
                        androidPackage != null -> "Available"
                        else -> "Unavailable"
                    }) {
                        if (installed != null && selected != version) PillButton("Select", onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { engine.select(installed) } }
                                    .onSuccess { selected = version }.onFailure { error = it.message }
                            }
                        }, enabled = canInstall)
                        else if (installed == null && androidPackage != null) PillButton("Download", onClick = { download(androidPackage.url) }, enabled = !busy)
                    }
                }
            }
            loadError?.let { Footer(it, MaterialTheme.colorScheme.error) }
            if (!loading && loadError == null && releases.isEmpty()) Footer("No releases found.")
            if (!loading && hidden > 0) Footer("$hidden older ${if (hidden == 1) "release has" else "releases have"} no Android build.")
            if (busy) { Spacer(Modifier.height(12.dp)); EngineProgress(); Footer("Downloading…") }
            stagedVersion?.let { staged ->
                Spacer(Modifier.height(16.dp))
                PillButton("Install $staged", onClick = { runCatching { updates.install() }.onFailure { error = it.message } }, filled = true, wide = true, enabled = canInstall)
            }
            error?.let { Footer(it, MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(20.dp))
            Group {
                ActionRow(if (showPackageLink) "Hide link" else "Install from link", { showPackageLink = !showPackageLink }, enabled = !busy, icon = Icons.Rounded.Link)
                if (showPackageLink) {
                    RowDivider(16.dp)
                    FieldRow("URL", updateUrl, { if (!busy) updateUrl = it }, "https://…", packageField, fieldKeyboard(KeyboardType.Uri, ImeAction.Done),
                        { if (!busy && updateUrl.startsWith("https://")) download(updateUrl) })
                    RowDivider(16.dp)
                    ActionRow("Download", { download(updateUrl) }, enabled = !busy && updateUrl.startsWith("https://"), icon = Icons.Rounded.Download)
                }
            }
            if (showPackageLink) Footer("Signed update URL.")
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
