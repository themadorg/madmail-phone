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
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteControls(server: RemoteServer) {
    val context = LocalContext.current
    val engine = remember(server.id) { RemoteEngine.get(context, server) }
    val state by engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    var connect by remember { mutableStateOf(PendingConnect.consume(server.id)) }
    var versions by remember { mutableStateOf(false) }
    val running = state.status.startsWith("Running")
    // The engine only reports busy, so the switch remembers which way it was flipped until the operation ends.
    var pending by remember(server.id) { mutableStateOf<PowerPhase?>(null) }
    val power = pending ?: when { running -> PowerPhase.On; state.error != null -> PowerPhase.Failed; else -> PowerPhase.Off }
    val transport = if (state.transport == "API") "Admin API · ${state.apiEndpoint ?: server.host}" else "SSH · ${server.address}"
    Column {
        Group(Modifier.animateContentSize(tween(300, easing = Emphasized))) {
            if (state.connected && state.managed && state.version != null) {
                PowerRow(power, when (power) { PowerPhase.Starting -> "Starting…"; PowerPhase.Stopping -> "Stopping…"; else -> state.status }, transport,
                    PowerConfirm(
                        PowerQuestion("Start this server?", "Madmail starts on ${server.host}.", "Start"),
                        PowerQuestion("Stop this server?", "People on ${server.host} can’t send or receive mail until you start it again.", "Stop")),
                    working = state.busy) { on ->
                    pending = if (on) PowerPhase.Starting else PowerPhase.Stopping
                    scope.launch {
                        try { withContext(Dispatchers.IO) { if (on) engine.start() else engine.stop() } } finally { pending = null }
                    }
                }
            } else Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                InfoRow(when { running -> Tint.Green; state.error != null -> Tint.Orange; else -> Tint.Gray },
                    when { running -> Icons.Rounded.CheckCircle; !state.connected -> Icons.Rounded.LinkOff; state.version == null -> Icons.Rounded.Inventory2; else -> Icons.Rounded.PowerSettingsNew },
                    state.status, transport) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(start = 64.dp, end = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.connected) PillButton("Connect", onClick = { connect = true }, icon = Icons.Rounded.Terminal, filled = true, enabled = !state.busy)
                else {
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
        KitSheet(onDismiss = { versions = false }) {
            SheetHeader("Madmail versions", server.host)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = GroupGutter).padding(bottom = 24.dp)
                .animateContentSize(tween(300, easing = Emphasized))) {
                Group { ValueRow("Current", state.version ?: "Not installed") }
                if (state.installedVersions.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Caption("Installed")
                    Group {
                        state.installedVersions.forEachIndexed { index, version ->
                            if (index > 0) RowDivider(64.dp)
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
                        if (index > 0) RowDivider(64.dp)
                        NavRow({ TintIcon(Tint.Gray, Icons.Rounded.Download) }, release.version, "${release.bytes / 1024 / 1024} MB",
                            enabled = state.version == null || compareVersions(release.version, state.version!!) >= 0) { selected = release }
                    }
                }
                Footer("Older versions can’t read newer data. Restore a matching backup first.")
                error?.let { Footer(it, MaterialTheme.colorScheme.error) }
            }
        }
        selected?.let { release ->
            KitDialog("${if (state.version == null) "Install" else "Update to"} Madmail ${release.version}?",
                "${server.address}\n\nRuns as its own service with a self-signed certificate. Updates pause hosting and back up first. Other installs aren’t touched.",
                "${if (state.version == null) "Install" else "Update"} & start", onDismiss = { selected = null },
                onConfirm = { selected = null; versions = false; scope.launch(Dispatchers.IO) { engine.install(release) } })
        }
    }
}

@Composable
private fun RemoteProgress() {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(4.dp).clip(CircleShape),
        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), strokeCap = StrokeCap.Round,
        gapSize = 0.dp)
}
