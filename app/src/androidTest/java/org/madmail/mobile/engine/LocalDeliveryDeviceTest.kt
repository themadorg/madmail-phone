package org.madmail.mobile.engine

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

class LocalDeliveryDeviceTest {
    @Test fun smtpFanoutCanBeFetchedThroughImapInAndroidSandbox() = runBlocking(Dispatchers.IO) {
        val engine = LocalEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val guests = mutableListOf<JSONObject>()
        val tokens = mutableListOf<String>()
        try {
            engine.start()
            repeat(3) {
                val token = engine.invitationRequest("POST", JSONObject().put("max_uses", 1).put("expires_in", "1h").put("comment", "Storage delivery regression")).getString("token")
                tokens += token
                guests += engine.createGuest(token)
            }
            val certificate = File(engine.dataDir, "certs/fullchain.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("local", certificate) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            val sender = guests[0].getString("email")
            val recipients = guests.drop(1).map { it.getString("email") }
            // Protocol fixture, not a claim of real PGP encryption. Real Delta Chat is tested separately.
            val body = "From: $sender\r\nTo: ${recipients.joinToString(", ")}\r\nSubject: Android storage regression\r\nContent-Type: multipart/encrypted; boundary=b\r\n\r\n--b\r\nContent-Type: application/pgp-encrypted\r\n\r\nVersion: 1\r\n--b--\r\n"
            (tls.socketFactory.createSocket("127.0.0.1", 11465) as SSLSocket).use { socket ->
                socket.soTimeout = 5000; socket.startHandshake()
                val input = socket.inputStream.bufferedReader()
                fun expect(code: String) {
                    for (i in 0..30) {
                        val line = input.readLine() ?: error("SMTP closed")
                        assertTrue("Expected SMTP $code", line.startsWith(code))
                        if (line.getOrNull(3) != '-') return
                    }
                    error("SMTP response exceeded limit")
                }
                fun send(line: String) { socket.outputStream.write("$line\r\n".toByteArray()); socket.outputStream.flush() }
                expect("220"); send("EHLO localhost"); expect("250")
                send("AUTH PLAIN " + Base64.getEncoder().encodeToString("\u0000$sender\u0000${guests[0].getString("password")}".toByteArray())); expect("235")
                send("MAIL FROM:<$sender>"); expect("250")
                recipients.forEach { send("RCPT TO:<$it>"); expect("250") }
                send("DATA"); expect("354"); send(body + "."); expect("250")
                send("QUIT"); expect("221")
            }
            guests.drop(1).forEach { guest ->
                (tls.socketFactory.createSocket("127.0.0.1", 11993) as SSLSocket).use { socket ->
                    socket.soTimeout = 5000; socket.startHandshake()
                    val input = socket.inputStream.bufferedReader()
                    assertTrue(input.readLine().startsWith("* OK"))
                    fun command(tag: String, command: String): List<String> {
                        socket.outputStream.write("$tag $command\r\n".toByteArray()); socket.outputStream.flush()
                        val lines = mutableListOf<String>()
                        for (i in 0..100) {
                            val line = input.readLine() ?: error("IMAP closed")
                            lines += line
                            if (line.startsWith("$tag ")) { assertTrue("IMAP command failed", line.startsWith("$tag OK")); return lines }
                        }
                        error("IMAP response exceeded limit")
                    }
                    command("a", "LOGIN \"${guest.getString("email")}\" \"${guest.getString("password")}\"")
                    assertTrue(command("b", "SELECT INBOX").any { it == "* 1 EXISTS" })
                    assertTrue(command("c", "FETCH 1 BODY.PEEK[]").any { it == "Subject: Android storage regression" })
                    command("d", "LOGOUT")
                }
            }
        } finally {
            try {
                guests.forEach { engine.adminRequest("/admin/accounts", "DELETE", JSONObject().put("username", it.getString("email"))) }
                tokens.forEach { engine.invitationRequest("DELETE", JSONObject().put("token", it)) }
            } finally { engine.stop() }
        }
    }
}
