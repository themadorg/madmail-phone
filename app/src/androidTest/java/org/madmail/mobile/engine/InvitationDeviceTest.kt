package org.madmail.mobile.engine

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
