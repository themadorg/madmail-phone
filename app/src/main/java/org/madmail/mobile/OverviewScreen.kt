package org.madmail.mobile

import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.madmail.mobile.core.PortMode
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.core.ServerIdentity
import org.madmail.mobile.engine.EnginePhase
import org.madmail.mobile.engine.LocalEngine
import org.madmail.mobile.engine.RemoteEngine
import org.json.JSONObject
import java.text.NumberFormat

/** The live numbers for one server, on their own tab. Home keeps the controls; this page only reads. */
@Composable
internal fun StatisticsScreen(remote: RemoteServer?, onHome: () -> Unit) {
    val context = LocalContext.current
    val phone = remote == null
    val engineState by remember { LocalEngine.get(context) }.state.collectAsState()
    val remoteState = remote?.let { RemoteEngine.get(context, it).state.collectAsState().value }
    val running = if (phone) engineState.phase == EnginePhase.Running else remoteState?.status?.startsWith("Running") == true
    val overview = if (phone && running) engineState.overview else remoteState?.overview
    val numbers = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 } }
    fun metric(vararg path: String): String {
        var value: Any? = overview
        path.forEach { value = (value as? JSONObject)?.opt(it) }
        return when (val result = value) {
            null, JSONObject.NULL -> "—"
            is Number -> numbers.format(result)
            else -> result.toString()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // With nothing to read, say why once instead of leaving a page of dashes unexplained.
        if (overview == null) Group {
            InfoRow(Tint.Gray, Icons.Rounded.QueryStats, if (running) "Waiting for numbers" else "No live numbers",
                when {
                    running -> "They appear in a moment."
                    phone -> "Turn on hosting on Home to see live numbers."
                    else -> "Connect to the server on Home to see live numbers."
                })
            if (!running) { RowDivider(64.dp); ActionRow("Go to Home", onHome, icon = Icons.Rounded.Home) }
        }
        Section("Activity", if (overview == null) "Unavailable" else "Live") {
            Stat(Tint.Orange, Icons.Rounded.People, "Accounts", metric("users", "registered"))
            Stat(Tint.Pink, Icons.Rounded.Schedule, "Uptime", metric("uptime", "duration"))
            Stat(Tint.Purple, Icons.Rounded.QrCode2, "Invite tokens", metric("tokens", "total"))
            Stat(Tint.Teal, Icons.Rounded.SyncAlt, "IMAP connections", metric("imap", "connections"), last = true)
        }
        Section("Messages") {
            Stat(Tint.Blue, Icons.AutoMirrored.Rounded.CallMade, "Sent", metric("sent_messages"))
            Stat(Tint.Blue, Icons.Rounded.Outbox, "Outbound", metric("outbound_messages"))
            Stat(Tint.Blue, Icons.AutoMirrored.Rounded.CallReceived, "Received", metric("received_messages"), last = true)
        }
        Section(if (phone) "Device storage" else "Server storage") {
            val disk = if (phone) remember(overview) { StatFs(context.filesDir.absolutePath) } else null
            val serverDisk = overview?.optJSONObject("disk")
            val total = disk?.totalBytes ?: serverDisk?.takeIf { it.has("total_bytes") }?.optLong("total_bytes")
            val available = disk?.availableBytes ?: serverDisk?.takeIf { it.has("available_bytes") }?.optLong("available_bytes")
            val used = if (disk != null) disk.totalBytes - disk.availableBytes else serverDisk?.takeIf { it.has("used_bytes") }?.optLong("used_bytes")
            val meter = total != null && total > 0 && used != null
            Stat(Tint.Yellow, Icons.Rounded.PieChart, "Used", used?.let { Formatter.formatFileSize(context, it) } ?: "—", last = meter)
            if (total != null && total > 0 && used != null) {
                val fraction = (used.toFloat() / total).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(start = StatInset, end = 16.dp, bottom = 12.dp).height(6.dp).clip(CircleShape),
                    color = if (fraction > .9f) MaterialTheme.colorScheme.error else Color(Tint.Yellow.bottom), trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
                RowDivider(StatInset)
            }
            Stat(Tint.Yellow, Icons.Rounded.DataUsage, "Available", available?.let { Formatter.formatFileSize(context, it) } ?: "—")
            Stat(Tint.Yellow, Icons.Rounded.Storage, "Capacity", total?.let { Formatter.formatFileSize(context, it) } ?: "—", last = true)
        }
        Section("Federation") {
            Stat(Tint.Indigo, Icons.Rounded.Hub, "Connected peers", metric("email_servers", "connections"))
            Stat(Tint.Indigo, Icons.Rounded.Language, "Domain peers", metric("email_servers", "domain_servers"))
            Stat(Tint.Indigo, Icons.Rounded.Dns, "IP peers", metric("email_servers", "ip_servers"))
            Stat(Tint.Indigo, Icons.AutoMirrored.Rounded.CallReceived, "Inbound", metric("federation_traffic", "inbound"))
            Stat(Tint.Indigo, Icons.AutoMirrored.Rounded.CallMade, "Outbound", metric("federation_traffic", "outbound"))
            Stat(Tint.Pink, Icons.Rounded.HourglassEmpty, "Queued", metric("federation_traffic", "queued"))
            Stat(Tint.Orange, Icons.Rounded.TimerOff, "Expired", metric("federation_traffic", "expired"))
            Stat(Tint.Pink, Icons.Rounded.Speed, "Latency", metric("federation_traffic", "mean_latency_ms").let { if (it == "—") it else "$it ms" })
            Stat(Tint.Green, Icons.Rounded.CheckCircle, "Healthy", metric("federation_traffic", "health", "perfect"))
            Stat(Tint.Indigo, Icons.Rounded.Share, "Federated", metric("federation_traffic", "health", "federated"))
            Stat(Tint.Red, Icons.Rounded.ErrorOutline, "Unhealthy", metric("federation_traffic", "health", "bad"), last = true)
        }
        Section("Services") {
            Stat(Tint.Green, Icons.Rounded.Call, "TURN relays", metric("turn", "relays"))
            Stat(Tint.Graphite, Icons.Rounded.VpnLock, "Proxy connections", metric("shadowsocks", "connections"))
            Stat(Tint.Graphite, Icons.Rounded.Public, "Proxy IPs", metric("shadowsocks", "unique_ips"))
            Stat(Tint.Green, Icons.Rounded.Notifications, "Push sent", metric("push", "successful_notifications"))
            Stat(Tint.Yellow, Icons.Rounded.History, "Retention", metric("message_retention", "retention"), last = true)
        }
    }
}

@Composable
internal fun OverviewScreen(name: String, identity: ServerIdentity, remote: RemoteServer?, onSettings: () -> Unit, connection: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val phone = remote == null
    val engine = remember { LocalEngine.get(context) }
    val engineState by engine.state.collectAsState()
    val remoteState = remote?.let { RemoteEngine.get(context, it).state.collectAsState().value }
    val running = if (phone) engineState.phase == EnginePhase.Running else remoteState?.status?.startsWith("Running") == true
    val overview = if (phone && running) engineState.overview else remoteState?.overview
    val numbers = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 } }
    fun metric(vararg path: String): String {
        var value: Any? = overview
        path.forEach { value = (value as? JSONObject)?.opt(it) }
        return when (val result = value) {
            null, JSONObject.NULL -> "—"
            is Number -> numbers.format(result)
            else -> result.toString()
        }
    }
    val preferences = context.getSharedPreferences("server-settings-phone", Context.MODE_PRIVATE)
    val legacy = context.getSharedPreferences("server-settings", Context.MODE_PRIVATE)
    val standardPorts = preferences.getBoolean("root_ports", legacy.getBoolean("root_ports", false))
    val ports = if (standardPorts) PortMode.RootStandard else PortMode.Unprivileged
    val version = if (phone) engineState.version ?: engine.selected?.version else remoteState?.version

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // One title per page. The line under it only says what the name doesn't: where it runs and how it's reached.
        val networks by rememberGuestNetworks()
        val shared = networks?.firstOrNull()
        val power = if (phone) engineState.phase.power() else when {
            running -> PowerPhase.On; remoteState?.error != null -> PowerPhase.Failed; else -> PowerPhase.Off
        }
        val via = if (remoteState?.transport == "API") "Admin API" else "SSH"
        val place = when {
            !phone -> if (name.equals(remote!!.host, ignoreCase = true)) via else "$via · ${remote!!.host}"
            shared != null -> "${shared.network.label} · ${shared.host}"
            networks == null -> "On this phone"
            else -> "No network"
        }
        val where = if (phone && name != "This phone") "This phone · $place" else place
        ServerHeader(name, identity.mark, phone, power, unreachable = phone && networks?.isEmpty() == true, where)

        if (phone) EngineControls() else RemoteControls(remote!!)

        connection()

        Section("Connection") {
            Stat(Tint.Gray, Icons.Rounded.Inventory2, "Version", version ?: metric("version"), copyable = true)
            if (phone) {
                Stat(Tint.Teal, Icons.Rounded.Upload, if (running) "SMTP TLS port" else "Preferred SMTP port", if (running) "11465" else ports.smtp.toString(), copyable = true)
                Stat(Tint.Teal, Icons.Rounded.Download, if (running) "IMAP TLS port" else "Preferred IMAP port", if (running) "11993" else ports.imap.toString(), copyable = true)
                if (running) Stat(Tint.Teal, Icons.Rounded.Lock, "HTTPS port", "18443", copyable = true)
                Stat(Tint.Graphite, Icons.Rounded.Shield, if (running) "Access" else "Preferred access", if (running || !standardPorts) "No root" else "Root")
            } else if (remoteState?.transport == "API") {
                Stat(Tint.Teal, Icons.Rounded.Api, "Admin API", remoteState.apiEndpoint ?: "Not configured", copyable = true)
            } else {
                Stat(Tint.Blue, Icons.Rounded.Dns, "Host", remote!!.host, copyable = true)
                Stat(Tint.Orange, Icons.Rounded.Person, "User", remote.user, copyable = true)
                Stat(Tint.Graphite, Icons.Rounded.Terminal, "SSH port", remote.port.toString(), copyable = true)
            }
            NavRow({ TintIcon(Tint.Gray, Icons.Rounded.Tune, size = StatMark) }, "Connection settings", onClick = onSettings)
        }
    }
}

private val StatMark = 32.dp

/** Where a stat row's label starts: 14dp padding, the mark, 12dp gap. Dividers and the storage meter align to it. */
private val StatInset = 58.dp

/** Caption, then the section's rows in a group. A live state rides on the caption's right edge. */
@Composable
private fun Section(title: String, state: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Caption(title, Modifier.weight(1f))
            state?.let {
                Row(Modifier.padding(end = 16.dp, bottom = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (it == "Live") Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = Success)
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Group(content = content)
    }
}

/** A label and value with a small mark. Dashes mean no data yet and are never copyable. */
@Composable
private fun Stat(tint: Tint, icon: ImageVector, label: String, value: String, copyable: Boolean = false, last: Boolean = false) {
    ValueRow(label, value, copyable = copyable && value != "—", mark = { TintIcon(tint, icon, size = StatMark) })
    if (!last) RowDivider(StatInset)
}

/**
 * The top of Home, laid out like a contact card: the server's mark large, its name, and one line saying where it runs.
 * The dot before that line is the power state at a glance; the switch below it is where it changes.
 */
@Composable
private fun ServerHeader(name: String, mark: String, phone: Boolean, power: PowerPhase, unreachable: Boolean, where: String) {
    val colors = MaterialTheme.colorScheme
    val dot = when {
        power == PowerPhase.On && unreachable -> Color(Tint.Orange.bottom)
        power == PowerPhase.On -> Success
        power == PowerPhase.Failed -> colors.error
        power.busy -> Success.copy(alpha = 0.45f)
        else -> colors.outline
    }
    val state = when (power) {
        PowerPhase.On -> if (unreachable) "Live, not reachable" else "Live"
        PowerPhase.Starting -> "Starting"; PowerPhase.Stopping -> "Stopping"; PowerPhase.Failed -> "Failed"; PowerPhase.Off -> "Off"
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ServerMark(mark, phone, size = 84.dp)
        Spacer(Modifier.height(14.dp))
        Text(name, Modifier.padding(horizontal = 24.dp).semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.padding(horizontal = 24.dp).semantics(mergeDescendants = true) { stateDescription = state }, verticalAlignment = Alignment.CenterVertically) {
            StatusDot(dot, pulse = power == PowerPhase.On && !unreachable)
            Spacer(Modifier.width(8.dp))
            Text(where, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small status dot. While [pulse] is on, a soft ring breathes out of it, the way a live call indicator does. */
@Composable
private fun StatusDot(color: Color, pulse: Boolean) {
    val animated by animateColorAsState(color, tween(320), label = "Status dot")
    // The ring's clock only exists while it pulses, so an idle dot costs no frames.
    if (pulse && !motionOff()) {
        val ring = rememberInfiniteTransition(label = "Status ring")
        val phase by ring.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearOutSlowInEasing)), label = "Ring phase")
        Box(Modifier.size(10.dp).drawBehind {
            drawCircle(animated.copy(alpha = 0.45f * (1f - phase)), radius = size.minDimension / 2 * (1f + 1.4f * phase))
            drawCircle(animated)
        })
    } else Box(Modifier.size(10.dp).drawBehind { drawCircle(animated) })
}
