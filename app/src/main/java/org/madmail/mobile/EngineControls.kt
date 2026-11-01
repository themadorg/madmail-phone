package org.madmail.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.madmail.mobile.engine.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EngineControls() {
    val context = LocalContext.current
    val engine = remember { LocalEngine.get(context) }
    val state by engine.state.collectAsState()
    var versions by remember { mutableStateOf(false) }
    val start = { context.startForegroundService(Intent(context, HostingService::class.java)); Unit }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (state.phase == EnginePhase.Running) context.startService(Intent(context, HostingService::class.java).setAction("stop"))
                else if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
            }, enabled = state.phase != EnginePhase.Starting && state.phase != EnginePhase.Stopping && engine.selected != null, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(if (state.phase == EnginePhase.Running) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(when (state.phase) { EnginePhase.Running -> "Stop hosting"; EnginePhase.Starting -> "Starting…"; EnginePhase.Stopping -> "Stopping…"; else -> "Start hosting" })
            }
            TextButton(onClick = { versions = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Inventory2, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Versions") }
        }
        BackgroundHostingAccess()
        if (state.error != null) Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (versions) EngineVersionsDialog { versions = false }
}

@Composable
private fun BackgroundHostingAccess() {
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
    TextButton(onClick = {
        error = null
        val intent = if (allowed) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { request.launch(intent) }.onFailure { error = "Open Android Settings → Apps → Madmail → Battery and allow background use." }
    }, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(if (allowed) Icons.Outlined.BatteryFull else Icons.Outlined.BatteryAlert, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (allowed) "Background access allowed" else "Allow background hosting")
    }
    if (!allowed) Text("Keep connections available with the screen off. Uses more battery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun EngineVersionsDialog(onDismiss: () -> Unit) {
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
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Madmail versions") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("themadorg/madmail", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { refresh++ }, enabled = !loading && !busy) { Text("Refresh") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (loadError != null) Text(loadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val versions = (engine.versions.map { it.version } + releases.map { it.version }).distinct()
                .sortedWith { a, b -> compareVersions(b, a) }
            versions.forEach { version ->
                val installed = engine.versions.find { it.version == version }
                val release = releases.find { it.version == version }
                val androidPackage = release?.androidPackage
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(version, style = MaterialTheme.typography.titleMedium)
                        Text(when {
                            selected == version -> "Selected · Installed"
                            installed != null -> "Installed"
                            androidPackage != null -> "Android package available"
                            else -> "No Android package"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (installed != null && selected != version) TextButton(onClick = {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { engine.select(installed) } }
                                .onSuccess { selected = version }.onFailure { error = it.message }
                        }
                    }, enabled = !busy && state.phase in listOf(EnginePhase.Stopped, EnginePhase.Failed)) { Text("Select") }
                    else if (installed == null && androidPackage != null) TextButton(onClick = { download(androidPackage.url) }, enabled = !busy) { Text("Download") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (!loading && loadError == null && releases.isEmpty()) Text("No published stable releases found.", style = MaterialTheme.typography.bodySmall)
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Downloading & verifying…", style = MaterialTheme.typography.bodySmall) }
            if (stagedVersion != null) Button(onClick = { runCatching { updates.install() }.onFailure { error = it.message } }, enabled = !busy && state.phase in listOf(EnginePhase.Stopped, EnginePhase.Failed)) { Text("Install $stagedVersion") }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { showPackageLink = !showPackageLink }, enabled = !busy) { Text(if (showPackageLink) "Hide package link" else "Use a package link") }
            if (showPackageLink) {
                OutlinedTextField(value = updateUrl, onValueChange = { updateUrl = it }, label = { Text("Signed Android update URL") }, singleLine = true, enabled = !busy)
                TextButton(onClick = { download(updateUrl) }, enabled = !busy && updateUrl.startsWith("https://")) { Text("Download update") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } })
}
