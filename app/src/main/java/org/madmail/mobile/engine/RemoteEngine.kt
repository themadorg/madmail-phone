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

internal data class RemoteState(val connected: Boolean = false, val managed: Boolean = false, val transport: String = "SSH", val apiEndpoint: String? = null, val busy: Boolean = false, val status: String = "Not connected", val version: String? = null, val architecture: String? = null, val installedVersions: List<String> = emptyList(), val overview: JSONObject? = null, val error: String? = null)
internal class RemoteEngine private constructor(private val context: Context, val server: RemoteServer) {
    private val mutex = Mutex()
    private val options = context.getSharedPreferences("remote-options-${server.id}", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(RemoteState(transport = if (options.getString("preferred", "ssh") == "api") "API" else "SSH", apiEndpoint = options.getString("api-endpoint", null)))
    val state = mutable.asStateFlow()
    private val trust = context.getSharedPreferences("ssh-host-keys", Context.MODE_PRIVATE)
    private var directApi: ApiCredential? = null
    private var suppliedToken: String? = null
    private var session: Session? = null
    private var tunnel: Int? = null
    private var elevation = "sudo -n "
    private var offered: ByteArray? = null
    val guestOrigin: String? get() = directApi?.endpoint?.let { java.net.URI(it).let { uri -> "https://${uri.rawAuthority}" } }
    val fingerprint: String? get() = offered?.let { "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(it)) }
    val trusted: Boolean get() = trust.contains(server.id)
    private fun newSession(credential: SshCredential? = null): Session {
        val ssh = JSch()
        credential?.let { ssh.addIdentity("madmail-mobile", it.privateKey, null, it.passphrase.takeIf { bytes -> bytes.isNotEmpty() }) }
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
            setConfig("PreferredAuthentications", if (credential == null) "password" else "publickey")
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
    suspend fun connect(password: String) = connectSsh(password, null, null)
    suspend fun connectKey(credential: SshCredential, apiToken: String? = null) {
        try { connectSsh(null, credential, apiToken?.takeIf { it.isNotBlank() }) }
        finally { credential.privateKey.fill(0); credential.passphrase.fill(0) }
    }
    suspend fun connectApi(endpoint: String, token: String) = operation {
        val address = requireNotNull(ApiEndpoint.normalize(endpoint)) { "Enter an HTTPS admin API URL." }
        require(token.isNotBlank() && token.none { it.isISOControl() })
        session?.disconnect(); session = null; tunnel = null; suppliedToken = null; directApi = null
        mutable.value = RemoteState(busy = true, transport = "API", apiEndpoint = address)
        val overview = AdminApi.overview(address, token)
        directApi = ApiCredential(address, token)
        mutable.value = RemoteState(connected = true, transport = "API", apiEndpoint = address, status = "Running", overview = overview)
    }
    private suspend fun connectSsh(password: String?, credential: SshCredential?, apiToken: String?) = operation {
        check(trusted)
        session?.disconnect(); session = null; tunnel = null; directApi = null; suppliedToken = apiToken
        mutable.value = RemoteState(busy = true)
        val next = newSession(credential)
        password?.let { next.setPassword(it.toByteArray()) }
        try { next.connect(15000); session = next } catch (e: Exception) { next.disconnect(); throw e }
        finally { next.setPassword(ByteArray(0)) }
        val architecture = exec("uname -m").trim().let { when(it) { "aarch64", "arm64" -> "arm64"; "x86_64" -> "amd64"; else -> error("Unsupported server architecture.") } }
        check(exec("uname -s").trim() == "Linux") { "Remote hosting requires Linux with systemd." }
        if (suppliedToken != null) {
            mutable.value = RemoteState(busy = true, architecture = architecture)
            refreshLocked()
            return@operation
        }
        elevation = if (exec("id -u").trim() == "0") "" else "sudo -n "
        exec("command -v systemctl >/dev/null && ${elevation}true")
        uploadManager()
        mutable.value = RemoteState(connected = true, managed = true, busy = true, architecture = architecture)
        refreshLocked()
    }
    suspend fun refresh() = operation { refreshLocked() }
    private fun apiAccess(): ApiCredential {
        directApi?.let { return it }
        val token = suppliedToken ?: managed("token").trim().also { check(it.matches(Regex("[a-fA-F0-9]{64}"))) }
        if (tunnel == null) tunnel = requireSession().setPortForwardingL("127.0.0.1", 0, "127.0.0.1", 18080)
        return ApiCredential("http://127.0.0.1:$tunnel/api/admin", token)
    }
    private fun refreshLocked() {
        if (directApi != null || suppliedToken != null) {
            val api = apiAccess()
            val overview = AdminApi.overview(api.endpoint, api.token)
            mutable.value = mutable.value.copy(connected = true, status = "Running", overview = overview)
            return
        }
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
    suspend fun adminRequest(resource: String, method: String = "GET", body: JSONObject = JSONObject()): JSONObject = mutex.withLock {
        check(mutable.value.connected && mutable.value.status.startsWith("Running")) { "Connect and start this server first." }
        val api = apiAccess()
        AdminApi.request(api.endpoint, api.token, resource, method, body)
    }
    suspend fun restart() = operation { check(mutable.value.managed) { "Restart requires the managed SSH service." }; managed("stop"); managed("start"); awaitReadyLocked() }
    suspend fun invitationRequest(method: String, body: JSONObject = JSONObject()): JSONObject = adminRequest("/admin/registration-token", method, body)
    private fun webEndpoint(path: String): String {
        val api = apiAccess()
        return java.net.URI(api.endpoint).resolve(path).toString()
    }
    suspend fun createGuest(token: String): JSONObject = mutex.withLock {
        check(mutable.value.connected)
        AdminApi.newAccount(webEndpoint("/new"), token)
    }

    suspend fun guestAddresses(): List<org.madmail.mobile.core.GuestAddress> = mutex.withLock {
        org.madmail.mobile.core.GuestAddresses.remote(exec("ip -o -4 addr show scope global"))
    }
    suspend fun browserInviteHost(origin: String): String? = mutex.withLock {
        check(mutable.value.connected)
        AdminApi.browserInviteHost(webEndpoint("/inv/address-check"), origin)
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
        check(mutable.value.managed) { "Installation requires the managed SSH connection." }
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
        directApi = null; suppliedToken = null
        mutable.value = RemoteState(transport = mutable.value.transport, apiEndpoint = mutable.value.apiEndpoint)
    }
    private var scriptPath: String? = null
    private fun uploadManager() {
        val path = "/tmp/madmail-mobile-${UUID.randomUUID()}"
        sftp { channel -> context.assets.open("remote/manage.sh").use { channel.put(it, path) }; channel.chmod(384, path) }
        scriptPath = path
    }
    private fun managed(args: String, timeout: Long = 45000): String {
        check(mutable.value.managed) { "This operation requires the app-managed SSH installation." }
        return exec("${elevation}sh ${shellQuote(checkNotNull(scriptPath))} $args", timeout)
    }
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
            mutable.value = mutable.value.copy(connected = mutable.value.connected && (directApi != null || session?.isConnected == true), overview = null, error = e.message ?: "SSH operation failed.")
        } finally {
            if (!mutable.value.connected) { session?.disconnect(); session = null; tunnel = null; suppliedToken = null; directApi = null }
            mutable.value = mutable.value.copy(busy = false)
        }
    }
    companion object {
        private val instances = mutableMapOf<String, RemoteEngine>()
        @Synchronized fun get(context: Context, server: RemoteServer) = instances.getOrPut(server.id) { RemoteEngine(context.applicationContext, server) }
    }
}
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
