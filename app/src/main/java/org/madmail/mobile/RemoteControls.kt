package org.madmail.mobile

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.engine.*

/** A server just added in setup opens its connection sheet once, so verification follows straight on. */
internal object PendingConnect {
    private val ids = mutableSetOf<String>()
    fun request(id: String) { ids += id }
    fun consume(id: String) = ids.remove(id)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteControls(server: RemoteServer) {
    val context = LocalContext.current
    val engine = remember(server.id) { RemoteEngine.get(context, server) }
    val state by engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    var connect by remember { mutableStateOf(PendingConnect.consume(server.id)) }
    var versions by remember { mutableStateOf(false) }
    val running = state.status.startsWith("Running")
    Column {
        Group(Modifier.animateContentSize(tween(300, easing = Emphasized))) {
            Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                InfoRow(when { running -> Tint.Green; state.error != null -> Tint.Orange; else -> Tint.Gray },
                    when { running -> Icons.Rounded.CheckCircle; !state.connected -> Icons.Rounded.LinkOff; state.version == null -> Icons.Rounded.Inventory2; else -> Icons.Rounded.PowerSettingsNew },
                    state.status, if (state.transport == "API") "Admin API · ${state.apiEndpoint ?: server.host}" else "SSH · ${server.address}") {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(start = 60.dp, end = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.connected) PillButton("Connect", onClick = { connect = true }, icon = Icons.Rounded.Terminal, filled = true, enabled = !state.busy)
                else {
                    if (state.managed && state.version != null) PillButton(if (running) "Stop" else "Start", onClick = {
                        scope.launch(Dispatchers.IO) { if (state.status.startsWith("Running")) engine.stop() else engine.start() }
                    }, icon = if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, filled = !running, enabled = !state.busy)
                    if (state.managed) PillButton(if (state.version == null) "Install Madmail" else "Versions", onClick = { versions = true },
                        icon = Icons.Rounded.Inventory2, filled = state.version == null, enabled = !state.busy)
                    PillButton("Refresh", onClick = { scope.launch(Dispatchers.IO) { engine.refresh() } }, icon = Icons.Rounded.Refresh, enabled = !state.busy)
                }
            }
            if (state.connected) {
                RowDivider(16.dp)
                ActionRow("Disconnect", { scope.launch(Dispatchers.IO) { engine.disconnect() } }, enabled = !state.busy)
            }
        }
        state.error?.let { Footer(it, MaterialTheme.colorScheme.error) }
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
        AlertDialog(onDismissRequest = { versions = false }, containerColor = panelColor(), shape = RoundedCornerShape(28.dp),
            title = { Text("Versions", fontWeight = FontWeight.SemiBold) }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).animateContentSize(tween(300, easing = Emphasized))) {
                Group { ValueRow("Current", state.version ?: "Not installed") }
                if (state.installedVersions.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Caption("Installed")
                    Group {
                        state.installedVersions.forEachIndexed { index, version ->
                            if (index > 0) RowDivider(60.dp)
                            val current = version == state.version
                            InfoRow(if (current) Tint.Green else Tint.Gray, if (current) Icons.Rounded.CheckCircle else Icons.Rounded.Inventory2, version, if (current) "Selected" else null) {
                                if (!current) PillButton("Remove", onClick = { versions = false; scope.launch(Dispatchers.IO) { engine.removeVersion(version) } })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Caption("Available")
                if (loading) RemoteProgress()
                if (releases.isNotEmpty()) Group {
                    releases.forEachIndexed { index, release ->
                        if (index > 0) RowDivider(60.dp)
                        NavRow({ TintIcon(Tint.Gray, Icons.Rounded.Download) }, release.version, "${release.bytes / 1024 / 1024} MB",
                            enabled = state.version == null || compareVersions(release.version, state.version!!) >= 0) { selected = release }
                    }
                }
                Footer("Older versions can’t read newer data. Restore a matching backup first.")
                error?.let { Footer(it, MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { versions = false }) { Text("Close") } })
        selected?.let { release ->
            AlertDialog(onDismissRequest = { selected = null }, containerColor = panelColor(), shape = RoundedCornerShape(28.dp),
                title = { Text("${if (state.version == null) "Install" else "Update to"} Madmail ${release.version}?", fontWeight = FontWeight.SemiBold) }, text = {
                Text("${server.address}\n\nRuns as its own service with a self-signed certificate. Updates pause hosting and back up first. Other installs aren’t touched.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }, confirmButton = { TextButton(onClick = { selected = null; versions = false; scope.launch(Dispatchers.IO) { engine.install(release) } }) { Text("${if (state.version == null) "Install" else "Update"} & start", fontWeight = FontWeight.SemiBold) } },
                dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } })
        }
    }
}

@Composable
private fun RemoteProgress() {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(4.dp).clip(CircleShape),
        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), strokeCap = StrokeCap.Round,
        gapSize = 0.dp)
}
