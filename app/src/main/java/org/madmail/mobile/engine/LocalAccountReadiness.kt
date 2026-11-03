package org.madmail.mobile.engine

import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Checks that a new account's IMAP and SMTP listeners answer over the pinned TLS certificate, without signing in.
 * Signing in here would settle the account's first login, and the app needs that to show whether a guest used it.
 */
internal object LocalAccountReadiness {
    fun verify(guest: JSONObject, certificateFile: File) {
        val parameters = guest.getString("dclogin_url").substringAfter('?').split('&').associate {
            val pair = it.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        require(parameters["is"] == "ssl" && parameters["ss"] == "ssl")
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
            check(connection.inputStream.bufferedReader().readLine()?.startsWith("* OK") == true) { "Account IMAP check failed." }
        }
        socket(parameters["sp"]).use { connection ->
            check(connection.inputStream.bufferedReader().readLine()?.startsWith("220") == true) { "Account SMTP check failed." }
            connection.outputStream.write("QUIT\r\n".toByteArray()); connection.outputStream.flush()
        }
    }
}
