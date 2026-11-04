package org.madmail.mobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.madmail.mobile.core.GuestAddress
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.engine.AccountQrRotation
import org.madmail.mobile.engine.EnginePhase
import org.madmail.mobile.engine.GuestNetworks
import org.madmail.mobile.engine.LocalEngine
import org.madmail.mobile.engine.RemoteEngine
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tester-only diagnostics, opened by long-pressing Home in the dock. It reports what the app, the phone and the engine
 * already expose, and runs live checks only when asked. The engine's own output is drained unread (it can carry
 * credentials), so the log here is this app's logcat, which Android limits to the app's own lines.
 */
@Composable
internal fun DebugMenu(serverId: String, remote: RemoteServer?, onReplayIntro: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val engine = remember { LocalEngine.get(context) }
    val engineState by engine.state.collectAsState()
    // mailHost is a plain field, not state; re-read it whenever the phase moves.
    val mailHost = remember(engineState.phase) { engine.mailHost }
    val remoteEngine = remember(remote) { remote?.let { RemoteEngine.get(context, it) } }
    val remoteState = remoteEngine?.state?.collectAsState()?.value
    val app = remember { appInfo(context) }
    val device = remember { deviceRows(context) }
    val ledger = remember(serverId) { ledgerRows(context, serverId) }
    // Interface walks and directory sizes touch the system; keep them off the main thread.
    val networks by produceState<List<GuestAddress>?>(null) { value = withContext(Dispatchers.IO) { runCatching { GuestNetworks.phone(context) }.getOrDefault(emptyList()) } }
    val storage by produceState<List<Pair<String, String>>?>(null) { value = withContext(Dispatchers.IO) { storageRows(context, engine.dataDir) } }
    var checks by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }

    fun runChecks() {
        if (checking) return
        checking = true
        scope.launch {
            checks = withContext(Dispatchers.IO) {
                buildList {
                    if (remote == null) {
                        if (engineState.phase == EnginePhase.Running) for ((name, port) in listOf("SMTP" to 11465, "IMAP" to 11993, "HTTPS" to 18443)) add("$name :$port" to probe(port))
                        else add("Ports" to "skipped, hosting is ${engineState.phase.name.lowercase()}")
                    }
                    val admin: suspend (String) -> org.json.JSONObject = { resource -> remoteEngine?.adminRequest(resource, "GET") ?: engine.adminRequest(resource, "GET") }
                    add("Admin API" to timed { admin("/admin/settings") }.let { (ms, result) -> result.fold({ "ok · $ms ms" }, { "failed · ${it.message ?: it.javaClass.simpleName}" }) })
                    timed { admin("/admin/accounts") }.second.onSuccess { add("Accounts" to (it.optJSONArray("accounts")?.length() ?: 0).toString()) }
                    add("Checked" to clock(System.currentTimeMillis()))
                }
            }
            checking = false
        }
    }

    // Check results come first, right under the button that ran them.
    val sections = listOfNotNull(checks?.let { "Checks" to it }) + listOf(
        "App" to listOf("Version" to app.first, "Code" to app.second, "Debug build" to if (app.third) "Yes" else "No", "Package" to context.packageName,
            "Process" to "pid ${android.os.Process.myPid()} · up ${duration(SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())}",
            "Heap" to Runtime.getRuntime().let { "${Formatter.formatShortFileSize(context, it.totalMemory() - it.freeMemory())} of ${Formatter.formatShortFileSize(context, it.maxMemory())}" }),
        "Phone" to device,
        "Engine" to listOfNotNull("Phase" to engineState.phase.name, "Version" to (engineState.version ?: engine.selected?.version ?: "none"),
            "Bundled" to engine.versions.joinToString { it.version }.ifBlank { "none" }, "Mail host" to (mailHost ?: "none"),
            "Data dir" to engine.dataDir.path, engineState.error?.let { "Error" to it }),
        "Storage" to (storage ?: listOf("Data" to "…")),
        "Server" to listOfNotNull("ID" to serverId, "Kind" to if (remote == null) "Phone" else "Remote",
            remote?.let { "Address" to "${it.user}@${it.host}:${it.port}" }, remoteState?.let { "Transport" to it.transport },
            remoteState?.let { "Status" to it.status }, remoteState?.version?.let { "Remote version" to it }, remoteState?.error?.let { "Error" to it }),
        "Network" to (networks?.let { list -> list.map { it.network.label to "${it.host} (${it.device})" }.ifEmpty { listOf("Addresses" to "none") } }
            ?: listOf("Addresses" to "…")),
        "Account QR" to ledger,
    ) + listOfNotNull(
        toaster.history.takeIf { it.isNotEmpty() }?.let { history -> "Recent messages" to history.map { (at, toast) -> clock(at) to (if (toast.error) "⚠ " else "") + toast.text } })
    val report = sections.joinToString("\n\n") { (title, rows) -> "[$title]\n" + rows.joinToString("\n") { (label, value) -> "$label: $value" } }

    fun share(text: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun loadLog(then: (String) -> Unit = {}) = scope.launch { readLog().also { log = it; then(it) } }

    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SheetHeader("Debug", "For testing only")
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = GroupGutter),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Group {
                ActionRow(if (checking) "Checking…" else "Run checks", ::runChecks, icon = Icons.Rounded.NetworkCheck, enabled = !checking)
                RowDivider(48.dp)
                ActionRow(if (showLog) "Hide app log" else "Show app log", { showLog = !showLog; if (showLog) loadLog() }, icon = Icons.AutoMirrored.Rounded.Article)
            }
            AnimatedVisibility(showLog, enter = fadeIn(tween(200)) + expandVertically(tween(320, easing = Emphasized)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(260, easing = Emphasized))) {
                Column {
                    Caption("App log")
                    Group {
                        Text(log ?: "Loading…", Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE))
                            .horizontalScroll(rememberScrollState()).padding(12.dp), style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RowDivider(16.dp)
                        ActionRow("Reload log", { loadLog() }, icon = Icons.Rounded.Refresh)
                        RowDivider(48.dp)
                        ActionRow("Copy log", { loadLog { clipboard.setText(AnnotatedString(it)); toaster.show("Log copied.") } }, icon = Icons.Rounded.ContentCopy)
                    }
                }
            }
            sections.forEach { (title, rows) ->
                Column {
                    Caption(title)
                    Group {
                        rows.forEachIndexed { index, (label, value) ->
                            DebugLine(label, value)
                            if (index < rows.lastIndex) RowDivider(16.dp)
                        }
                    }
                }
            }
            Group {
                ActionRow("Copy diagnostics", { clipboard.setText(AnnotatedString(report)); toaster.show("Diagnostics copied.") }, icon = Icons.Rounded.ContentCopy)
                RowDivider(48.dp)
                ActionRow("Share diagnostics", { loadLog { share("$report\n\n[App log]\n$it", "Madmail diagnostics") } }, icon = Icons.Rounded.Share)
                RowDivider(48.dp)
                ActionRow("Clean up account QRs now", { AccountQrRotation.get(context, serverId, remote).tidy(); toaster.show("Cleanup started.") }, icon = Icons.Rounded.CleaningServices)
                RowDivider(48.dp)
                ActionRow("Open Android app info", {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, icon = Icons.Rounded.Info)
                RowDivider(48.dp)
                ActionRow("Restart app UI", { context.findActivity()?.recreate() ?: toaster.show("Couldn’t restart.", error = true) }, icon = Icons.Rounded.Refresh)
                RowDivider(48.dp)
                ActionRow("Replay onboarding", onReplayIntro, icon = Icons.Rounded.Replay)
            }
        }
    }
}

/** A dense label and value line; long values wrap instead of truncating, since testers copy them. */
@Composable
private fun DebugLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

/** Version name, version code and whether this is a debuggable build. BuildConfig is not generated, so read the package. */
@Suppress("DEPRECATION")
private fun appInfo(context: Context): Triple<String, String, Boolean> {
    val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        ?: return Triple("unknown", "unknown", debuggable)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    return Triple(info.versionName ?: "unknown", code.toString(), debuggable)
}

/** The phone and the system settings that decide whether hosting survives in the background. */
private fun deviceRows(context: Context): List<Pair<String, String>> {
    val power = context.getSystemService(PowerManager::class.java)
    val battery = context.getSystemService(BatteryManager::class.java)
    val yesNo = { on: Boolean -> if (on) "Yes" else "No" }
    return listOf(
        "Model" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "ABI" to Build.SUPPORTED_ABIS.joinToString(),
        "Locale" to "${Locale.getDefault().toLanguageTag()} · ${java.util.TimeZone.getDefault().id}",
        "Notifications" to yesNo(NotificationManagerCompat.from(context).areNotificationsEnabled()),
        "Battery unrestricted" to yesNo(power.isIgnoringBatteryOptimizations(context.packageName)),
        "Battery saver" to yesNo(power.isPowerSaveMode),
        "Battery" to "${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%${if (battery.isCharging) " · charging" else ""}",
    )
}

private fun storageRows(context: Context, dataDir: File): List<Pair<String, String>> {
    val size = { dir: File -> Formatter.formatShortFileSize(context, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) }
    val backups = File(context.filesDir, "madmail/backups").listFiles()?.size ?: 0
    return listOf("Server data" to size(dataDir), "Backups" to "$backups · ${size(File(context.filesDir, "madmail/backups"))}",
        "Free" to Formatter.formatShortFileSize(context, context.filesDir.usableSpace))
}

/** A summary of the account QR ledger: how many codes wait for cleanup and how many were kept by copy or share. */
private fun ledgerRows(context: Context, serverId: String): List<Pair<String, String>> = runCatching {
    val array = JSONArray(context.getSharedPreferences("account-qr-$serverId", Context.MODE_PRIVATE).getString("ledger", "[]"))
    val entries = (0 until array.length()).map { array.getJSONObject(it) }
    val oldest = entries.minOfOrNull { maxOf(it.optLong("issued"), it.optLong("replaced")) }
    listOfNotNull("Entries" to entries.size.toString(), "Kept" to entries.count { it.optBoolean("kept") }.toString(),
        oldest?.let { "Oldest" to "${duration(System.currentTimeMillis() - it)} ago" })
}.getOrDefault(listOf("Entries" to "unreadable"))

private fun probe(port: Int): String = timed { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1500) } }
    .let { (ms, result) -> result.fold({ "open · $ms ms" }, { "closed · ${it.message ?: it.javaClass.simpleName}" }) }

private inline fun <T> timed(block: () -> T): Pair<Long, Result<T>> {
    val started = SystemClock.elapsedRealtime()
    val result = runCatching(block)
    return (SystemClock.elapsedRealtime() - started) to result
}

/** This app's recent logcat, crashes from earlier runs included. Android only returns the app's own lines. */
private suspend fun readLog(): String = withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-b", "main,system,crash", "-t", "300").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }.ifBlank { "No log lines." }
    }.getOrElse { "Couldn’t read the log: ${it.message}" }
}

private fun clock(at: Long) = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(at))

private fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return when { minutes < 1 -> "${ms / 1000}s"; minutes < 60 -> "${minutes}m"; minutes < 1440 -> "${minutes / 60}h ${minutes % 60}m"; else -> "${minutes / 1440}d ${minutes / 60 % 24}h" }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
