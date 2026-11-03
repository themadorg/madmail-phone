package org.madmail.mobile.engine

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.madmail.mobile.core.InviteLinks
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

class InvitationDeviceTest {
    private fun tls(engine: LocalEngine): SSLContext {
        val certificate = File(engine.dataDir, "certs/fullchain.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("owned-engine", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }
    private fun imapLogin(engine: LocalEngine, email: String, password: String) =
        (tls(engine).socketFactory.createSocket("127.0.0.1", 11993) as SSLSocket).use { socket ->
            socket.soTimeout = 10000
            val input = socket.inputStream.bufferedReader()
            assertTrue(input.readLine()!!.startsWith("* OK"))
            socket.outputStream.write("a LOGIN \"$email\" \"$password\"\r\n".toByteArray()); socket.outputStream.flush()
            var reply: String
            do { reply = input.readLine()!! } while (!reply.startsWith("a "))
            assertTrue(reply, reply.startsWith("a OK"))
        }
    private fun smtpLogin(engine: LocalEngine, email: String, password: String) =
        (tls(engine).socketFactory.createSocket("127.0.0.1", 11465) as SSLSocket).use { socket ->
            socket.soTimeout = 10000
            val input = socket.inputStream.bufferedReader()
            fun reply(): String { var line: String; do { line = input.readLine()!! } while (line.getOrNull(3) == '-'); return line }
            fun send(line: String) { socket.outputStream.write("$line\r\n".toByteArray()); socket.outputStream.flush() }
            assertTrue(reply().startsWith("220")); send("EHLO localhost"); assertTrue(reply().startsWith("250"))
            send("AUTH PLAIN " + java.util.Base64.getEncoder().encodeToString("\u0000$email\u0000$password".toByteArray()))
            val auth = reply(); assertTrue(auth, auth.startsWith("235"))
            send("QUIT")
        }
    @Test fun accountQrSurvivesConcurrentClientLogins() = runBlocking(Dispatchers.IO) {
        val engine = LocalEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            engine.start()
            repeat(4) {
                val token = engine.invitationRequest("POST", JSONObject().put("max_uses", 5).put("expires_in", "1h").put("comment", "Concurrent account QR test")).getString("token")
                var email: String? = null
                try {
                    val guest = engine.createGuest(token)
                    email = guest.getString("email")
                    // Readiness only greets the ports, so the first login is still the guest's to make.
                    val entries = engine.invitationRequest("GET").getJSONArray("tokens")
                    val item = (0 until entries.length()).map { entries.getJSONObject(it) }.single { it.getString("token") == token }
                    assertEquals(1, item.getInt("used_count") + item.getInt("pending_reservations"))
                    // A client signs in to IMAP and SMTP at once; neither first login may remove the account.
                    val password = guest.getString("password")
                    coroutineScope {
                        (1..4).map { n -> async(Dispatchers.IO) {
                            if (n % 2 == 0) imapLogin(engine, email!!, password) else smtpLogin(engine, email!!, password)
                        } }.awaitAll()
                    }
                    val accounts = engine.adminRequest("/admin/accounts").getJSONArray("accounts")
                    assertTrue((0 until accounts.length()).any { accounts.getJSONObject(it).optString("username") == email })
                } finally {
                    email?.let { engine.adminRequest("/admin/accounts", "DELETE", JSONObject().put("username", it)) }
                    engine.invitationRequest("DELETE", JSONObject().put("token", token))
                }
            }
        } finally { engine.stop() }
    }
    @Test fun remoteInvitationsThroughSsh() = runBlocking(Dispatchers.IO) {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("remoteFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = org.madmail.mobile.core.RemoteServer("invite-fixture", "127.0.0.1", "fixture", 22222)
        val engine = RemoteEngine.get(context, server)
        val creation = InvitationCreation.get(context, server.id, server)
        var account: String? = null
        var token: String? = null
        context.getSharedPreferences("ssh-host-keys", 0).edit().remove(server.id).commit()
        try {
            engine.inspectHost(); engine.acceptHost(); engine.connect("test-only-madmail-fixture")
            assertTrue(engine.state.value.connected)
            engine.install(ReleaseCatalog.releases(engine.state.value.architecture!!).first { it.version == "2.30.5" })
            assertNull(engine.state.value.error)
            assertTrue(engine.guestAddresses().isNotEmpty())
            // Creation belongs to the server controller, with no screen coroutine or UI observer.
            creation.create("https://server.tailnet.ts.net:18443", true, 1, "Remote invitation test", "1h")
            val result = withTimeout(20000) { creation.state.first { !it.busy } }
            assertNull(result.error)
            assertTrue(result.code!!.contains("ih=server.tailnet.ts.net"))
            assertTrue(result.code.contains("ip=11993")); assertTrue(result.code.contains("sp=11465"))
            account = result.code.substringAfter("dclogin://").substringBefore('/')
            val entries = engine.invitationRequest("GET").getJSONArray("tokens")
            val item = (0 until entries.length()).map { entries.getJSONObject(it) }.single { it.optString("comment") == "Remote invitation test" }
            token = item.getString("token")
            assertEquals(1, item.getInt("used_count") + item.getInt("pending_reservations"))
        } finally {
            try {
                account?.let { engine.adminRequest("/admin/accounts", "DELETE", JSONObject().put("username", it)) }
                token?.let { engine.invitationRequest("DELETE", JSONObject().put("token", it)) }
            } finally {
                InvitationCreation.forget(server.id); engine.disconnect()
                context.getSharedPreferences("ssh-host-keys", 0).edit().remove(server.id).commit()
            }
        }
    }
    @Test fun phoneNetworkAndCreationController() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LocalEngine.get(context)
        val creation = InvitationCreation.get(context, "phone", null)
        var token: String? = null
        var account: String? = null
        try {
            engine.start()
            val addresses = GuestNetworks.phone(context)
            assertTrue("No network candidates on the connected test phone", addresses.isNotEmpty())
            val wifi = addresses.first { it.network == org.madmail.mobile.core.GuestNetwork.Wifi }
            assertTrue(GuestNetworks.checkPorts(wifi.origin).all { it.second })
            // The mail domain follows the chosen guest IP; a name never becomes the domain.
            engine.useMailHost(wifi.host)
            assertEquals(wifi.host, engine.browserInviteHost(wifi.origin))
            val before = engine.invitationRequest("GET").getJSONArray("tokens").length()
            creation.create("https://phone.tailnet.ts.net:18443", false, 2, "Blocked browser invitation test", "24h")
            val blocked = withTimeout(15000) { creation.state.first { !it.busy } }
            assertTrue(blocked.error!!.contains("Choose Account QR"))
            assertFalse(blocked.uncertain)
            assertEquals(before, engine.invitationRequest("GET").getJSONArray("tokens").length())
            creation.create(wifi.origin, true, 1, "Controller invitation test", "24h")
            // A second rapid tap must not create another token.
            creation.create(wifi.origin, true, 1, "Duplicate invitation test", "24h")
            val result = withTimeout(15000) { creation.state.first { !it.busy } }
            assertNull(result.error)
            account = result.code!!.substringAfter("dclogin://").substringBefore('/')
            assertTrue(result.code.contains("ih=${wifi.host}"))
            assertTrue(account.endsWith("@[${wifi.host}]"))
            val entries = engine.invitationRequest("GET").getJSONArray("tokens")
            val items = (0 until entries.length()).map { entries.getJSONObject(it) }
            token = items.single { it.optString("comment") == "Controller invitation test" }.getString("token")
            assertFalse(items.any { it.optString("comment") == "Duplicate invitation test" })
            assertEquals(result.code, InvitationCreation.get(context, "phone", null).state.value.code)
        } finally {
            try {
                account?.let { engine.adminRequest("/admin/accounts", "DELETE", JSONObject().put("username", it)) }
                token?.let { engine.invitationRequest("DELETE", JSONObject().put("token", it)) }
            } finally { InvitationCreation.forget("phone"); engine.stop() }
        }
    }
    @Test fun createAccountAuthenticateAndRevokeInvitation() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LocalEngine.get(context)
        var invitation: String? = null
        var account: String? = null
        try {
            engine.start()
            invitation = engine.invitationRequest("POST", JSONObject().put("max_uses", 1).put("expires_in", "1h").put("comment", "Automated invitation test")).getString("token")
            val guest = engine.createGuest(invitation)
            account = guest.getString("email")
            val password = guest.getString("password")
            val qr = InviteLinks.login(guest.getString("dclogin_url"), "https://192.168.1.2:18443")
            assertTrue(qr.contains("ip=11993")); assertTrue(qr.contains("sp=11465"))
            assertTrue(qr.contains("ih=192.168.1.2")); assertTrue(qr.contains("sc=3"))
            // Trust only this owned engine's certificate; authenticate the account behind the QR.
            val certificate = File(engine.dataDir, "certs/fullchain.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("local", certificate) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            (tls.socketFactory.createSocket("127.0.0.1", 11993) as SSLSocket).use { socket ->
                socket.soTimeout = 5000; socket.startHandshake()
                val input = socket.inputStream.bufferedReader()
                assertTrue(input.readLine().startsWith("* OK"))
                socket.outputStream.write("a LOGIN \"$account\" \"$password\"\r\n".toByteArray()); socket.outputStream.flush()
                var success = false
                for (i in 0..20) { val line = input.readLine() ?: break; if (line.startsWith("a ")) { success = line.startsWith("a OK"); break } }
                assertTrue("Guest IMAP login failed", success)
            }
            val entries = engine.invitationRequest("GET").getJSONArray("tokens")
            val item = (0 until entries.length()).map { entries.getJSONObject(it) }.single { it.getString("token") == invitation }
            assertEquals(1, item.getInt("used_count"))
            engine.invitationRequest("DELETE", JSONObject().put("token", invitation))
            val after = engine.invitationRequest("GET").getJSONArray("tokens")
            assertFalse((0 until after.length()).any { after.getJSONObject(it).getString("token") == invitation })
            invitation = null
        } finally {
            try {
                account?.let { AdminApi.request("http://127.0.0.1:18080/api/admin", File(engine.dataDir, "admin_token").readText().trim(), "/admin/accounts", "DELETE", JSONObject().put("username", it)) }
                invitation?.let { engine.invitationRequest("DELETE", JSONObject().put("token", it)) }
            } finally { engine.stop() }
        }
    }
}
