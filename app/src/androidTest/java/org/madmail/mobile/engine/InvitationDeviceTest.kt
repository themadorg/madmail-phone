package org.madmail.mobile.engine

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
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
            assertEquals("madmail.local", engine.browserInviteHost(wifi.origin))
            val before = engine.invitationRequest("GET").getJSONArray("tokens").length()
            creation.create(wifi.origin, false, 2, "Blocked browser invitation test", "24h")
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
