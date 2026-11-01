package org.madmail.mobile.engine

import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** Settle the bundled engine's one-use reservation before clients race IMAP against SMTP. */
internal object LocalAccountReadiness {
    fun verify(guest: JSONObject, certificateFile: File) {
        val parameters = guest.getString("dclogin_url").substringAfter('?').split('&').associate {
            val pair = it.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        require(parameters["is"] == "ssl" && parameters["ss"] == "ssl")
        val email = guest.getString("email")
        val password = guest.getString("password")
        require(listOf(email, password).none { value -> value.any { it == '\r' || it == '\n' || it == '\u0000' } })
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val certificate = certificateFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("owned-engine", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        fun socket(port: String?): SSLSocket {
            val number = requireNotNull(port?.toIntOrNull()); require(number in 1..65535)
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress("127.0.0.1", number), 3000)
                return (tls.socketFactory.createSocket(raw, "127.0.0.1", number, true) as SSLSocket).apply {
                    soTimeout = 5000
                    startHandshake()
                }
            } catch (e: Exception) { raw.close(); throw e }
        }
        socket(parameters["ip"]).use { connection ->
            val input = connection.inputStream.bufferedReader()
            check(input.readLine()?.startsWith("* OK") == true) { "Account IMAP check failed." }
            connection.outputStream.write("a LOGIN ${quote(email)} ${quote(password)}\r\n".toByteArray())
            connection.outputStream.flush()
            var authenticated = false
            for (i in 0..20) {
                val line = input.readLine() ?: break
                if (line.startsWith("a ")) { authenticated = line.startsWith("a OK"); break }
            }
            check(authenticated) { "Account IMAP authentication failed." }
        }
        socket(parameters["sp"]).use { connection ->
            val input = connection.inputStream.bufferedReader()
            fun expect(code: String) {
                for (i in 0..30) {
                    val line = input.readLine() ?: error("Account SMTP check failed.")
                    check(line.startsWith(code)) { "Account SMTP authentication failed." }
                    if (line.getOrNull(3) != '-') return
                }
                error("Account SMTP response exceeded limit.")
            }
            fun send(line: String) { connection.outputStream.write("$line\r\n".toByteArray()); connection.outputStream.flush() }
            expect("220"); send("EHLO localhost"); expect("250")
            send("AUTH PLAIN " + Base64.getEncoder().encodeToString("\u0000$email\u0000$password".toByteArray()))
            expect("235")
            send("QUIT")
        }
    }
}
