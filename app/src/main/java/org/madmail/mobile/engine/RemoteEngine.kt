package org.madmail.mobile.engine

import android.content.Context
import com.jcraft.jsch.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.madmail.mobile.core.RemoteServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal data class RemoteState(val connected: Boolean = false, val busy: Boolean = false, val status: String = "Not connected", val version: String? = null, val architecture: String? = null, val installedVersions: List<String> = emptyList(), val overview: JSONObject? = null, val error: String? = null)
internal class RemoteEngine private constructor(private val context: Context, val server: RemoteServer) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(RemoteState())
    val state = mutable.asStateFlow()
    private val trust = context.getSharedPreferences("ssh-host-keys", Context.MODE_PRIVATE)
    private var session: Session? = null
    private var tunnel: Int? = null
    private var elevation = "sudo -n "
    private var offered: ByteArray? = null
    val fingerprint: String? get() = offered?.let { "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(it)) }
    val trusted: Boolean get() = trust.contains(server.id)
    private fun newSession(): Session {
        val ssh = JSch()
        ssh.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray): Int {
                offered = key.clone()
                val stored = trust.getString(server.id, null) ?: return HostKeyRepository.NOT_INCLUDED
                return if (MessageDigest.isEqual(Base64.getDecoder().decode(stored), key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID() = "Madmail pinned host keys"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        return ssh.getSession(server.user, server.host, server.port).apply {
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "password")
            timeout = 15000; setServerAliveInterval(15000); setServerAliveCountMax(2)
        }
    }
    suspend fun inspectHost() = mutex.withLock {
        offered = null
        val probe = newSession()
        try { probe.connect(15000) } catch (_: JSchException) { check(offered != null) { "Unable to reach the SSH host." } }
        finally { probe.disconnect() }
        val stored = trust.getString(server.id, null)
        check(stored == null || stored == Base64.getEncoder().encodeToString(offered)) { "The SSH host key changed. Verify the server outside this app before reconnecting." }
    }
    fun acceptHost() {
        check(!trusted) { "An existing host key cannot be replaced automatically." }
        check(trust.edit().putString(server.id, Base64.getEncoder().encodeToString(checkNotNull(offered))).commit()) { "Unable to save host trust." }
    }
    suspend fun connect(password: String) = operation {
        check(trusted)
        session?.disconnect(); tunnel = null
        val next = newSession()
        next.setPassword(password.toByteArray())
        try { next.connect(15000); session = next } catch (e: Exception) { next.disconnect(); throw e }
        finally { next.setPassword(ByteArray(0)) }
        val architecture = exec("uname -m").trim().let { when(it) { "aarch64", "arm64" -> "arm64"; "x86_64" -> "amd64"; else -> error("Unsupported server architecture.") } }
        check(exec("uname -s").trim() == "Linux") { "Remote hosting requires Linux with systemd." }
        elevation = if (exec("id -u").trim() == "0") "" else "sudo -n "
        exec("command -v systemctl >/dev/null && ${elevation}true")
        uploadManager()
        mutable.value = RemoteState(connected = true, busy = true, architecture = architecture)
        refreshLocked()
    }
    suspend fun refresh() = operation { refreshLocked() }
    private fun refreshLocked() {
        val lines = managed("status").trim().lines()
        val status = lines.first()
        val overview = if (status == "running") runCatching {
            val token = managed("token").trim()
            check(token.matches(Regex("[a-fA-F0-9]{64}")))
            if (tunnel == null) tunnel = requireSession().setPortForwardingL("127.0.0.1", 0, "127.0.0.1", 18080)
            AdminApi.overview("http://127.0.0.1:$tunnel/api/admin", token)
        }.getOrNull() else null
        mutable.value = mutable.value.copy(connected = true, status = when(status) { "running" -> if (overview == null) "Running · health unavailable" else "Running"; "stopped" -> "Stopped"; else -> "Not installed" }, version = lines.getOrNull(1)?.takeIf { Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(it) }, installedVersions = if (status == "absent") emptyList() else managed("versions").trim().lines().filter { Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(it) }, overview = overview)
    }
    suspend fun removeVersion(version: String) = operation {
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version))
        check(version != mutable.value.version)
        managed("remove ${shellQuote(version)}")
        refreshLocked()
    }
    suspend fun start() = operation { managed("start"); awaitReadyLocked() }
    private fun awaitReadyLocked() {
        repeat(30) {
            refreshLocked()
            if (mutable.value.overview != null) return
            Thread.sleep(500)
        }
        error("The service did not become healthy. Refresh status before taking further action; no automatic rollback was attempted.")
    }
    suspend fun stop() = operation { managed("stop"); refreshLocked() }
    suspend fun install(release: UpstreamRelease) = operation {
        val old = mutable.value.version
        check(old == null || compareVersions(release.version, old) >= 0) { "Downgrades require restoring a matching backup." }
        val file = File(context.cacheDir, "remote/${server.id}/${release.version}.part")
        ReleaseCatalog.download(release, file)
        val upload = "/tmp/madmail-mobile-${UUID.randomUUID()}"
        try {
            sftp { it.put(file.path, upload); it.chmod(384, upload) }
            managed("install ${shellQuote(release.version)} ${shellQuote(release.digest)} ${shellQuote(upload)}", 180000)
            awaitReadyLocked()
        } finally {
            file.delete()
            runCatching { sftp { it.rm(upload) } }
        }
    }
    suspend fun disconnect() = mutex.withLock {
        runCatching { scriptPath?.let { path -> sftp { it.rm(path) } } }; scriptPath = null
        session?.disconnect(); session = null; tunnel = null
        mutable.value = RemoteState()
    }
    private var scriptPath: String? = null
    private fun uploadManager() {
        val path = "/tmp/madmail-mobile-${UUID.randomUUID()}"
        sftp { channel -> context.assets.open("remote/manage.sh").use { channel.put(it, path) }; channel.chmod(384, path) }
        scriptPath = path
    }
    private fun managed(args: String, timeout: Long = 45000): String = exec("${elevation}sh ${shellQuote(checkNotNull(scriptPath))} $args", timeout)
    private fun requireSession(): Session = checkNotNull(session?.takeIf { it.isConnected }) { "SSH is disconnected. Reconnect and refresh status before retrying." }
    private fun sftp(block: (ChannelSftp) -> Unit) {
        val channel = requireSession().openChannel("sftp") as ChannelSftp
        try { channel.connect(15000); block(channel) } finally { channel.disconnect() }
    }
    private fun exec(command: String, timeout: Long = 15000): String {
        val channel = requireSession().openChannel("exec") as ChannelExec
        val output = ByteArrayOutputStream()
        val bounded = object : java.io.OutputStream() {
            override fun write(value: Int) { check(output.size() < 2 * 1024 * 1024); output.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { check(output.size() + length <= 2 * 1024 * 1024); output.write(bytes, offset, length) }
        }
        try {
            channel.setCommand(command); channel.setOutputStream(bounded)
            // Error streams may contain account data. Report exit status without retaining them.
            channel.setErrStream(object : java.io.OutputStream() { override fun write(value: Int) {} })
            channel.connect(15000)
            val deadline = System.nanoTime() + timeout * 1_000_000
            while (!channel.isClosed) { check(System.nanoTime() < deadline) { "SSH operation timed out. Refresh status before retrying; the remote operation may have completed." }; Thread.sleep(100) }
            check(channel.exitStatus == 0) { "Remote command failed (${channel.exitStatus}). Verify sudo access and refresh server status before retrying." }
            return output.toString("UTF-8")
        } finally { channel.disconnect() }
    }
    private suspend fun operation(block: () -> Unit) = mutex.withLock {
        mutable.value = mutable.value.copy(busy = true, error = null)
        try { block() } catch (e: Exception) {
            mutable.value = mutable.value.copy(connected = mutable.value.connected && session?.isConnected == true, overview = null, error = e.message ?: "SSH operation failed.")
        } finally {
            if (!mutable.value.connected) { session?.disconnect(); session = null; tunnel = null }
            mutable.value = mutable.value.copy(busy = false)
        }
    }
    companion object {
        private val instances = mutableMapOf<String, RemoteEngine>()
        @Synchronized fun get(context: Context, server: RemoteServer) = instances.getOrPut(server.id) { RemoteEngine(context.applicationContext, server) }
    }
}
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
