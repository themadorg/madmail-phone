package org.madmail.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.engine.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteControls(server: RemoteServer) {
    val context = LocalContext.current
    val engine = remember(server.id) { RemoteEngine.get(context, server) }
    val state by engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    var connect by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.connected) Button(onClick = { connect = true }, enabled = !state.busy) { Icon(Icons.Outlined.Terminal, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Connect server") }
        else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.managed && state.version != null) Button(onClick = { scope.launch(Dispatchers.IO) { if (state.status.startsWith("Running")) engine.stop() else engine.start() } }, enabled = !state.busy) { Icon(if (state.status.startsWith("Running")) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.status.startsWith("Running")) "Stop" else "Start") }
                if (state.managed) TextButton(onClick = { versions = true }, enabled = !state.busy) { Icon(Icons.Outlined.Inventory2, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.version == null) "Install Madmail" else "Versions") }
                TextButton(onClick = { scope.launch(Dispatchers.IO) { engine.refresh() } }, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Refresh") }
            }
            TextButton(onClick = { scope.launch(Dispatchers.IO) { engine.disconnect() } }, enabled = !state.busy) { Text("Disconnect") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (connect) RemoteConnectionSheet(server, engine) { connect = false }
    if (versions) {
        var releases by remember { mutableStateOf<List<UpstreamRelease>>(emptyList()) }
        var error by remember { mutableStateOf<String?>(null) }
        var selected by remember { mutableStateOf<UpstreamRelease?>(null) }
        var loading by remember { mutableStateOf(true) }
        LaunchedEffect(server.id) {
            try { releases = withContext(Dispatchers.IO) { ReleaseCatalog.releases(checkNotNull(state.architecture)) } }
            catch (e: Exception) { error = e.message }
            finally { loading = false }
        }
        AlertDialog(onDismissRequest = { versions = false }, title = { Text("Server versions") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Current: ${state.version ?: "Not installed"}")
                if (state.installedVersions.isNotEmpty()) Text("Installed", style = MaterialTheme.typography.labelLarge)
                state.installedVersions.forEach { version ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(version + if (version == state.version) " · Selected" else "")
                        if (version != state.version) TextButton(onClick = { versions = false; scope.launch(Dispatchers.IO) { engine.removeVersion(version) } }) { Text("Remove") }
                    }
                }
                Text("Available releases", style = MaterialTheme.typography.labelLarge)
                Text("Older versions cannot open newer server data. Restore a matching backup before downgrading.", style = MaterialTheme.typography.bodySmall)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                releases.forEach { release ->
                    OutlinedButton(onClick = { selected = release }, enabled = state.version == null || compareVersions(release.version, state.version!!) >= 0) { Text("${release.version} · ${release.bytes / 1024 / 1024} MB") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { versions = false }) { Text("Close") } })
        selected?.let { release ->
            AlertDialog(onDismissRequest = { selected = null }, title = { Text("${if (state.version == null) "Install" else "Update to"} Madmail ${release.version}?") }, text = {
                Text("${server.address}\n\nCreates a dedicated madmail-mobile service, downloads and verifies the official release, and starts it on ports 11465, 11993 and 18443. Updates stop hosting and make a private backup before switching. Existing installations outside this app are left untouched. TLS starts with a self-signed certificate.")
            }, confirmButton = { TextButton(onClick = { selected = null; versions = false; scope.launch(Dispatchers.IO) { engine.install(release) } }) { Text("${if (state.version == null) "Install" else "Update"} & start") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } })
        }
    }
}
