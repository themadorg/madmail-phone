package org.madmail.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.madmail.mobile.core.PortMode
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.core.ServerIdentity
import org.madmail.mobile.engine.EnginePhase
import org.madmail.mobile.engine.LocalEngine
import org.madmail.mobile.engine.RemoteEngine
import org.json.JSONObject
import java.text.NumberFormat

@Composable
internal fun OverviewScreen(name: String, identity: ServerIdentity, remote: RemoteServer?, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
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

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ServerMark(identity.mark, phone)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val statusColor = if (running) overviewHealthyColor() else colors.onSurfaceVariant
                        Icon(if (running) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(16.dp), tint = statusColor)
                        Text(if (phone) "This phone · ${engineState.phase.name}" else "${remoteState?.transport ?: "SSH"} · ${remoteState?.status ?: "Not connected"}", style = MaterialTheme.typography.bodyMedium, color = statusColor)
                    }
                }
            }
            if (phone) EngineControls() else RemoteControls(remote!!)
        }

        OverviewSection("Activity", Icons.Outlined.BarChart, if (overview == null) "Unavailable" else "Live") {
            OverviewDetail(Icons.Outlined.PeopleOutline, "Accounts", metric("users", "registered"))
            OverviewDetail(Icons.Outlined.Schedule, "Uptime", metric("uptime", "duration"))
            OverviewDetail(Icons.Outlined.QrCode, "Invite tokens", metric("tokens", "total"))
            OverviewDetail(Icons.Outlined.SyncAlt, "IMAP connections", metric("imap", "connections"))
        }
        OverviewSection("Messages", Icons.Outlined.MailOutline) {
            OverviewDetail(Icons.Outlined.NorthEast, "Sent", metric("sent_messages"))
            OverviewDetail(Icons.Outlined.Upload, "Outbound", metric("outbound_messages"))
            OverviewDetail(Icons.Outlined.SouthWest, "Received", metric("received_messages"))
        }
        OverviewSection(if (phone) "Device storage" else "Server storage", Icons.Outlined.Storage) {
            val disk = if (phone) remember(overview) { StatFs(context.filesDir.absolutePath) } else null
            val serverDisk = overview?.optJSONObject("disk")
            val total = disk?.totalBytes ?: serverDisk?.takeIf { it.has("total_bytes") }?.optLong("total_bytes")
            val available = disk?.availableBytes ?: serverDisk?.takeIf { it.has("available_bytes") }?.optLong("available_bytes")
            val used = if (disk != null) disk.totalBytes - disk.availableBytes else serverDisk?.takeIf { it.has("used_bytes") }?.optLong("used_bytes")
            OverviewDetail(Icons.Outlined.PieChartOutline, "Used", used?.let { Formatter.formatFileSize(context, it) } ?: "—")
            if (total != null && total > 0 && used != null) {
                val fraction = (used.toFloat() / total).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(4.dp), color = if (fraction > .9f) colors.error else colors.primary)
            }
            OverviewDetail(Icons.Outlined.DataUsage, "Available", available?.let { Formatter.formatFileSize(context, it) } ?: "—")
            OverviewDetail(Icons.Outlined.Storage, "Capacity", total?.let { Formatter.formatFileSize(context, it) } ?: "—")
        }
        OverviewSection("Federation", Icons.Outlined.Hub) {
            OverviewDetail(Icons.Outlined.Hub, "Connected peers", metric("email_servers", "connections"))
            OverviewDetail(Icons.Outlined.Language, "Domain peers", metric("email_servers", "domain_servers"))
            OverviewDetail(Icons.Outlined.Dns, "IP peers", metric("email_servers", "ip_servers"))
            OverviewDetail(Icons.Outlined.SouthWest, "Inbound", metric("federation_traffic", "inbound"))
            OverviewDetail(Icons.Outlined.Upload, "Outbound", metric("federation_traffic", "outbound"))
            OverviewDetail(Icons.Outlined.HourglassEmpty, "Queued", metric("federation_traffic", "queued"))
            OverviewDetail(Icons.Outlined.TimerOff, "Expired", metric("federation_traffic", "expired"))
            OverviewDetail(Icons.Outlined.Speed, "Mean latency", metric("federation_traffic", "mean_latency_ms").let { if (it == "—") it else "$it ms" })
            OverviewDetail(Icons.Outlined.CheckCircleOutline, "Perfect health", metric("federation_traffic", "health", "perfect"))
            OverviewDetail(Icons.Outlined.Share, "Federated", metric("federation_traffic", "health", "federated"))
            OverviewDetail(Icons.Outlined.ErrorOutline, "Unhealthy", metric("federation_traffic", "health", "bad"))
        }
        OverviewSection("Services", Icons.Outlined.Widgets) {
            OverviewDetail(Icons.Outlined.Call, "TURN relays", metric("turn", "relays"))
            OverviewDetail(Icons.Outlined.VpnLock, "Proxy connections", metric("shadowsocks", "connections"))
            OverviewDetail(Icons.Outlined.Public, "Proxy unique IPs", metric("shadowsocks", "unique_ips"))
            OverviewDetail(Icons.Outlined.NotificationsNone, "Push notifications", metric("push", "successful_notifications"))
            OverviewDetail(Icons.Outlined.History, "Message retention", metric("message_retention", "retention"))
        }
        OverviewSection("Connection", Icons.Outlined.Link) {
            OverviewDetail(Icons.Outlined.Inventory2, "Madmail version", version ?: metric("version"), copyable = true)
            if (phone) {
                OverviewDetail(Icons.Outlined.Upload, if (running) "SMTP TLS port" else "Preferred SMTP port", if (running) "11465" else ports.smtp.toString(), copyable = true)
                OverviewDetail(Icons.Outlined.Download, if (running) "IMAP TLS port" else "Preferred IMAP port", if (running) "11993" else ports.imap.toString(), copyable = true)
                if (running) OverviewDetail(Icons.Outlined.Lock, "HTTPS port", "18443", copyable = true)
                OverviewDetail(Icons.Outlined.Shield, if (running) "Access" else "Preferred access", if (running || !standardPorts) "No root required" else "Root")
            } else if (remoteState?.transport == "API") {
                OverviewDetail(Icons.Outlined.Api, "Admin API", remoteState.apiEndpoint ?: "Not configured", copyable = true)
            } else {
                OverviewDetail(Icons.Outlined.Dns, "Host", remote!!.host, copyable = true)
                OverviewDetail(Icons.Outlined.PersonOutline, "User", remote.user, copyable = true)
                OverviewDetail(Icons.Outlined.SettingsEthernet, "SSH port", remote.port.toString(), copyable = true)

            }
            TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 0.dp)) { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Connection settings") }
        }
    }
}

@Composable
private fun overviewHealthyColor(): Color = if (isSystemInDarkTheme()) Color(0xFF75D6AF) else Color(0xFF087653)

@Composable
private fun OverviewSection(title: String, icon: ImageVector, detail: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = colors.primary)
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium, color = if (detail == "Live") overviewHealthyColor() else colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outlineVariant)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun OverviewDetail(icon: ImageVector, label: String, value: String, copyable: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    val canCopy = copyable && value != "—"
    val modifier = if (canCopy) Modifier.clickable(role = Role.Button, onClickLabel = "Copy $label") {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        copied = true
    } else Modifier
    Row(modifier.fillMaxWidth().heightIn(min = if (copyable) 48.dp else 42.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(if (copied) "Copied" else value, Modifier.weight(.8f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, color = if (copied) overviewHealthyColor() else colors.onSurface)
        if (copyable) Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(16.dp), tint = if (canCopy) colors.primary else colors.outline)
    }
}
