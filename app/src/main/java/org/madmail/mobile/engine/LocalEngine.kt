package org.madmail.mobile.engine

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.madmail.mobile.core.PhoneMailDomain
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class EngineVersion(val version: String, val file: String, val sha256: String)
internal enum class EnginePhase { Stopped, Starting, Running, Stopping, Failed }
/** The start error when the phone has no Wi-Fi, hotspot or VPN address; screens that already explain this hide it. */
internal const val NO_NETWORK = "Connect to Wi-Fi, a hotspot or a VPN."

internal data class EngineState(val phase: EnginePhase = EnginePhase.Stopped, val version: String? = null, val error: String? = null, val overview: JSONObject? = null)

/** One owner per app process. The foreground service owns start/stop; UI observes only. */
internal class LocalEngine private constructor(private val context: Context) {
    private val mutable = MutableStateFlow(EngineState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var process: Process? = null
    // PR_SET_PDEATHSIG follows the creating Linux thread. Keep that thread alive
    // for the entire app process, rather than using a retiring coroutine worker.
    private val processOwner = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "MadmailProcessOwner") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null
    val dataDir = File(context.filesDir, "madmail/data")
    private val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
    val versions: List<EngineVersion> by lazy {
        val array = JSONArray(context.assets.open("engine/releases.json").bufferedReader().use { it.readText() })
        (0 until array.length()).map { array.getJSONObject(it) }.map { EngineVersion(it.getString("version"), it.getString("file"), it.getString("sha256")) }
            .filter { File(context.applicationInfo.nativeLibraryDir, it.file).isFile }
    }
    val selected: EngineVersion? get() = versions.find { it.version == prefs.getString("selected", null) } ?: versions.lastOrNull()
    suspend fun select(version: EngineVersion) = mutex.withLock {
        check(process == null) { "Stop hosting before changing versions." }
        val last = prefs.getString("lastStarted", null)
        check(last == null || compareVersions(version.version, last) >= 0) { "Restore a matching backup to use an older version." }
        check(version in versions)
        check(prefs.edit().putString("selected", version.version).commit()) { "Couldn’t save the version." }
    }

    suspend fun start() = mutex.withLock { startLocked() }
    private suspend fun startLocked() {
        if (process != null) return
        val version = selected ?: error("No compatible engine for this device.")
        mutable.value = EngineState(EnginePhase.Starting, version.version)
        try {
            val standard = context.getSharedPreferences("server-settings-phone", Context.MODE_PRIVATE).getBoolean("root_ports", false)
            check(!standard) { "Turn off Standard ports to host on this phone." }
            val binary = File(context.applicationInfo.nativeLibraryDir, version.file)
            check(sha256(binary) == version.sha256) { "Engine check failed. Reinstall from a trusted build." }
            val previous = prefs.getString("lastStarted", null)
            check(previous == null || compareVersions(version.version, previous) >= 0) { "Data is from a newer version. Restore a matching backup." }
            if (previous != null && previous != version.version && dataDir.exists()) {
                val backup = File(context.filesDir, "madmail/backups/${System.currentTimeMillis()}-$previous")
                val required = dataDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                check(context.filesDir.usableSpace > required + 64L * 1024 * 1024) { "Free up space for the backup." }
                check(dataDir.copyRecursively(File(backup, "data"), overwrite = false)) { "Couldn’t back up server data." }
                File(dataDir.parentFile, "chatmail.toml").takeIf { it.exists() }?.copyTo(File(backup, "chatmail.toml"))
                File(backup, "version").writeText(previous)
            }
            dataDir.mkdirs()
            val config = File(dataDir.parentFile, "chatmail.toml")
            mailHost = prepareConfig(config)
            // Preflight before launching avoids mistaking another listener for this engine.
            for (port in listOf(1125, 11465, 11993, 18080, 18443)) {
                java.net.ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("0.0.0.0", port)) }
            }
            // A new version may migrate on first boot. Record before launch; never silently roll it back.
            check(prefs.edit().putString("lastStarted", version.version).commit()) { "Couldn’t save the data version." }
            val child = processOwner.submit<Process> { ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libengine_launcher.so").path,
                binary.path, "--config", config.path, "--state-dir", dataDir.path, "run")
                .directory(dataDir).redirectErrorStream(true).start() }.get()
            process = child
            // Output can contain credentials and account data. Drain without retaining or logging it.
            scope.launch { runCatching { child.inputStream.use { input -> val buffer = ByteArray(4096); while (input.read(buffer) >= 0) { } } } }
            var overview: JSONObject? = null
            repeat(40) {
                check(child.isAlive) { "Madmail exited during startup (code ${child.exitValue()})." }
                if (overview == null) {
                    overview = runCatching { readOverview() }.getOrNull()
                    if (overview != null) for (port in listOf(11465, 11993, 18443)) Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
                    if (overview == null) delay(500)
                }
            }
            check(overview != null) { "Madmail didn’t start. Check that its ports are free." }
            mutable.value = EngineState(EnginePhase.Running, version.version, overview = overview)
            monitor = scope.launch {
                while (isActive && child.isAlive) {
                    delay(5000)
                    if (!child.isAlive) break
                    val update = runCatching { readOverview() }
                    if (!isActive) return@launch
                    mutable.value = mutable.value.copy(overview = update.getOrNull(), error = if (update.isFailure) "Statistics unavailable." else null)
                }
                if (isActive) {
                    mutex.withLock {
                        if (process === child) { process = null; mutable.value = EngineState(EnginePhase.Failed, version.version, "Madmail stopped unexpectedly.") }
                    }
                }
            }
        } catch (e: Exception) {
            terminate()
            mutable.value = EngineState(EnginePhase.Failed, version.version, e.message ?: "Unable to start Madmail.")
            throw e
        }
    }

    /**
     * Writes the phone's guest IP as the mail domain and returns it. Every earlier domain stays in
     * local_domains, so older accounts keep signing in and receiving mail. Returns null when an upgraded
     * install has no IP yet; it keeps its old config until a later start finds one.
     */
    private fun prepareConfig(config: File): String? {
        val old = config.takeIf { it.exists() }?.readText()
        val backups = File(context.filesDir, "madmail/backups")
        // The history lives only in this file. If it no longer parses, recover it from the newest domain backup rather than dropping it.
        val (previousPrimary, previousLocals) = PhoneMailDomain.previous(old).takeIf { old == null || it.first != null }
            ?: domainBackups(backups).asReversed().asSequence().map { PhoneMailDomain.previous(File(it, "chatmail.toml").takeIf(File::isFile)?.readText()) }
                .firstOrNull { it.first != null }
            ?: error("Can’t read the server config. Restore it from a backup.")
        val saved = context.getSharedPreferences("invite-phone", Context.MODE_PRIVATE).getString("origin", null)
        val ip = PhoneMailDomain.resolve(saved, GuestNetworks.phone(context), previousPrimary)
            ?: if (old != null && PhoneMailDomain.previous(old).first != null) return null
            else error(NO_NETWORK)
        val rendered = PhoneMailDomain.render(ip, previousPrimary, previousLocals)
        if (rendered == old) return ip
        if (old != null) {
            val backup = File(backups, "${System.currentTimeMillis()}-domain").apply { mkdirs() }
            File(backup, "chatmail.toml").writeText(old)
            // Self-signed certificates are generated only when missing. Copy the pair aside, then remove both.
            if (previousPrimary != "[$ip]") {
                val pair = listOf("fullchain.pem", "privkey.pem").map { File(dataDir, "certs/$it") }.filter(File::exists)
                pair.forEach { it.copyTo(File(backup, it.name), overwrite = true) }
                pair.forEach { check(it.delete()) { "Couldn’t replace the certificate." } }
            }
            // Keep the first backup (the original domain) and the five newest.
            domainBackups(backups).let { all -> all.drop(1).dropLast(5).forEach { it.deleteRecursively() } }
        }
        val temporary = File(config.parentFile, "chatmail.toml.tmp")
        java.io.FileOutputStream(temporary).use { it.write(rendered.toByteArray()); it.fd.sync() }
        check(temporary.renameTo(config)) { "Couldn’t update the server address." }
        return ip
    }
    private fun domainBackups(backups: File) = backups.listFiles { file -> file.isDirectory && file.name.endsWith("-domain") }.orEmpty()
        .sortedBy { it.name.substringBefore('-').toLongOrNull() ?: 0L }

    suspend fun stop() = mutex.withLock {
        mutable.value = mutable.value.copy(phase = EnginePhase.Stopping)
        monitor?.cancel(); monitor = null
        val graceful = terminate()
        mutable.value = EngineState(if (graceful) EnginePhase.Stopped else EnginePhase.Failed, selected?.version,
            if (graceful) null else "Madmail was force-stopped. Check data before updating.")
    }
    private fun terminate(): Boolean {
        val child = process ?: return true
        child.destroy()
        val graceful = child.waitFor(15, TimeUnit.SECONDS)
        if (!graceful) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) }
        if (!child.isAlive) { process = null; mailHost = null }
        return graceful
    }
    private fun readOverview(): JSONObject {
        val token = File(dataDir, "admin_token").readText().trim()
        return AdminApi.overview("http://127.0.0.1:18080/api/admin", token)
    }
    suspend fun adminRequest(resource: String, method: String = "GET", body: JSONObject = JSONObject()): JSONObject = mutex.withLock {
        check(mutable.value.phase == EnginePhase.Running) { "Start hosting to manage settings." }
        AdminApi.request("http://127.0.0.1:18080/api/admin", File(dataDir, "admin_token").readText().trim(), resource, method, body)
    }
    suspend fun restart() = mutex.withLock { restartLocked() }
    private suspend fun restartLocked() {
        check(mutable.value.phase == EnginePhase.Running) { "Start hosting first." }
        mutable.value = mutable.value.copy(phase = EnginePhase.Stopping)
        monitor?.cancel(); monitor = null
        if (!terminate()) {
            mutable.value = mutable.value.copy(phase = EnginePhase.Failed, error = "Server didn’t stop cleanly. Start it from Home.")
            error("Restart failed after an unclean stop.")
        }
        startLocked()
    }
    suspend fun invitationRequest(method: String, body: JSONObject = JSONObject()): JSONObject = mutex.withLock {
        check(mutable.value.phase == EnginePhase.Running) { "Start hosting to manage invitations." }
        AdminApi.request("http://127.0.0.1:18080/api/admin", File(dataDir, "admin_token").readText().trim(), "/admin/registration-token", method, body)
    }
    /** [created] sees the account as soon as the server has made it, before the readiness check runs. */
    suspend fun createGuest(token: String, created: (JSONObject) -> Unit = {}): JSONObject = mutex.withLock {
        check(mutable.value.phase == EnginePhase.Running) { "Start hosting to create an account." }
        AdminApi.newAccount("http://127.0.0.1:18080/new", token).also {
            created(it)
            LocalAccountReadiness.verify(it, File(dataDir, "certs/fullchain.pem"))
        }
    }
    /** The IP the running engine uses as its mail domain. */
    @Volatile var mailHost: String? = null
        private set
    /**
     * Moves the running engine onto [ip] as its mail domain. Returns false only when [ip] is on this phone
     * but the engine could not switch to it. The restart runs in the engine's scope, so leaving a screen never
     * cancels it halfway. Callers save the guest origin first; the start reads it.
     */
    suspend fun useMailHost(ip: String): Boolean = scope.async {
        mutex.withLock {
            // An address that is not on this phone cannot become its mail domain; restarting would not change it.
            if (mutable.value.phase != EnginePhase.Running || mailHost == ip || GuestNetworks.phone(context).none { it.host == ip }) return@withLock
            restartLocked()
        }
        mailHost == ip || GuestNetworks.phone(context).none { it.host == ip }
    }.await()
    suspend fun browserInviteHost(origin: String): String? = mutex.withLock {
        check(mutable.value.phase == EnginePhase.Running)
        AdminApi.browserInviteHost("http://127.0.0.1:18080/inv/address-check", origin)
    }
    companion object {
        @Volatile private var instance: LocalEngine? = null
        fun get(context: Context): LocalEngine = instance ?: synchronized(this) {
            instance ?: LocalEngine(context.applicationContext).also { instance = it }
        }
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun compareVersions(a: String, b: String): Int {
    val left = a.removePrefix("v").split('.').map { it.toInt() }
    val right = b.removePrefix("v").split('.').map { it.toInt() }
    for (i in 0 until maxOf(left.size, right.size)) { val comparison = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 }); if (comparison != 0) return comparison }
    return 0
}
internal class AdminRequestRejected(val status: Int) : IllegalStateException("Server rejected the request ($status).")

internal object AdminApi {
    fun browserInviteHost(endpoint: String, origin: String): String? {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1500; connection.readTimeout = 3000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Host", java.net.URI(requireNotNull(org.madmail.mobile.core.InviteLinks.origin(origin))).rawAuthority)
            check(connection.responseCode == 200)
            val html = connection.inputStream.use { String(it.readBytesLimited(512 * 1024)) }
            return Regex("const host = \"([^\"]+)\"").find(html)?.groupValues?.get(1)
        } finally { connection.disconnect() }
    }
    fun overview(endpoint: String, token: String): JSONObject = request(endpoint, token, "/admin/overview", "GET")
    fun request(endpoint: String, token: String, resource: String, method: String, body: JSONObject = JSONObject()): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 1500; connection.readTimeout = 3000
            connection.instanceFollowRedirects = false; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().put("method", method).put("resource", resource).put("body", body)
                .put("headers", JSONObject().put("Authorization", "Bearer $token"))
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            check(connection.responseCode == 200) { "Admin endpoint unavailable." }
            val bytes = connection.inputStream.use { it.readBytesLimited(2 * 1024 * 1024) }
            val envelope = JSONObject(String(bytes))
            val status = envelope.getInt("status")
            if (status !in 200..299) throw AdminRequestRejected(status)
            return envelope.optJSONObject("body") ?: JSONObject()
        } finally { connection.disconnect() }
    }
    fun newAccount(endpoint: String, token: String): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 3000; connection.readTimeout = 10000
            connection.instanceFollowRedirects = false; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(JSONObject().put("token", token).toString().toByteArray()) }
            if (connection.responseCode !in 200..299) throw AdminRequestRejected(connection.responseCode)
            return JSONObject(String(connection.inputStream.use { it.readBytesLimited(64 * 1024) }))
        } finally { connection.disconnect() }
    }
}
internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break; check(output.size() + count <= limit) { "Response too large." }; output.write(buffer, 0, count) }
    return output.toByteArray()
}
