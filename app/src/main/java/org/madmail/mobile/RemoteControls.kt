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
import androidx.compose.ui.res.stringResource
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
    val transport = if (state.transport == "API") stringResource(R.string.remote_transport_api, state.apiEndpoint ?: server.host) else "SSH · ${server.address}"
    Column {
        Group(Modifier.animateContentSize(tween(300, easing = Emphasized))) {
            if (state.connected && state.managed && state.version != null) {
                PowerRow(power, when (power) { PowerPhase.Starting -> stringResource(R.string.remote_starting); PowerPhase.Stopping -> stringResource(R.string.remote_stopping); else -> state.status }, transport,
                    PowerConfirm(
                        PowerQuestion(stringResource(R.string.remote_start_title), stringResource(R.string.remote_start_body, server.host), stringResource(R.string.remote_start)),
                        PowerQuestion(stringResource(R.string.remote_stop_title), stringResource(R.string.remote_stop_body, server.host), stringResource(R.string.remote_stop))),
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
                if (!state.connected) PillButton(stringResource(R.string.remote_connect), onClick = { connect = true }, icon = Icons.Rounded.Terminal, filled = true, enabled = !state.busy)
                else {
                    if (state.managed) PillButton(if (state.version == null) stringResource(R.string.remote_install_madmail) else stringResource(R.string.remote_versions), onClick = { versions = true },
                        icon = Icons.Rounded.Inventory2, filled = state.version == null, enabled = !state.busy)
                    PillButton(stringResource(R.string.remote_refresh), onClick = { scope.launch(Dispatchers.IO) { engine.refresh() } }, icon = Icons.Rounded.Refresh, enabled = !state.busy)
                }
            }
            if (state.connected) {
                RowDivider(16.dp)
                ActionRow(stringResource(R.string.remote_disconnect), { scope.launch(Dispatchers.IO) { engine.disconnect() } }, enabled = !state.busy)
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
            SheetHeader(stringResource(R.string.remote_versions_title), server.host)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = GroupGutter).padding(bottom = 24.dp)
                .animateContentSize(tween(300, easing = Emphasized))) {
                Group { ValueRow(stringResource(R.string.remote_current), state.version ?: stringResource(R.string.remote_not_installed)) }
                if (state.installedVersions.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Caption(stringResource(R.string.remote_installed))
                    Group {
                        state.installedVersions.forEachIndexed { index, version ->
                            if (index > 0) RowDivider(64.dp)
                            val current = version == state.version
                            InfoRow(if (current) Tint.Green else Tint.Gray, if (current) Icons.Rounded.CheckCircle else Icons.Rounded.Inventory2, version, if (current) stringResource(R.string.remote_selected) else null) {
                                if (!current) PillButton(stringResource(R.string.remote_remove), onClick = { versions = false; scope.launch(Dispatchers.IO) { engine.removeVersion(version) } })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Caption(stringResource(R.string.remote_available))
                if (loading) RemoteProgress()
                if (releases.isNotEmpty()) Group {
                    releases.forEachIndexed { index, release ->
                        if (index > 0) RowDivider(64.dp)
                        NavRow({ TintIcon(Tint.Gray, Icons.Rounded.Download) }, release.version, stringResource(R.string.remote_size_mb, release.bytes / 1024 / 1024),
                            enabled = state.version == null || compareVersions(release.version, state.version!!) >= 0) { selected = release }
                    }
                }
                Footer(stringResource(R.string.remote_versions_footer))
                error?.let { Footer(it, MaterialTheme.colorScheme.error) }
            }
        }
        selected?.let { release ->
            KitDialog(if (state.version == null) stringResource(R.string.remote_install_title, release.version) else stringResource(R.string.remote_update_title, release.version),
                stringResource(R.string.remote_install_body, server.address),
                if (state.version == null) stringResource(R.string.remote_install_start) else stringResource(R.string.remote_update_start), onDismiss = { selected = null },
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
