package org.madmail.mobile

import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            ServerMark(identity.mark, phone, size = 56.dp)
            Spacer(Modifier.height(18.dp))
            Text(name, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
            Spacer(Modifier.height(6.dp))
            Text(if (phone) "Hosted on this phone" else if (remoteState?.transport == "API") "Admin API · ${remote!!.host}" else "SSH · ${remote!!.host}",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (phone) EngineControls() else RemoteControls(remote!!)

        connection()

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

private val StatMark = 28.dp

/** Where a stat row's label starts: 14dp padding, the mark, 12dp gap. Dividers and the storage meter align to it. */
private val StatInset = 54.dp

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
