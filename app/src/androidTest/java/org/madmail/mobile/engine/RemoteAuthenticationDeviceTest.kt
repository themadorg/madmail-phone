package org.madmail.mobile.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.madmail.mobile.core.RemoteServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class RemoteAuthenticationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun prepareKeysAndVerifyEncryptedStorage() {
        val generated = RemoteCredentials(context, "auth-generated")
        val imported = RemoteCredentials(context, "auth-imported")
        generated.delete(); imported.delete()
        val public = generated.generate()
        assertEquals(public, RemoteCredentials(context, "auth-generated").generate())
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        val phrase = "fixture-only-passphrase".toByteArray()
        val private = ByteArrayOutputStream().also { pair.writePrivateKey(it, phrase) }.toByteArray()
        try {
            try { imported.importKey(private, "wrong".toByteArray()); fail("Wrong passphrase accepted") } catch (_: IllegalStateException) { }
            assertFalse(imported.hasKey())
            val importedPublic = imported.importKey(private, phrase)
            File(context.cacheDir, "auth-fixture.pub").writeText("$public\n$importedPublic\n")
            generated.saveApi("https://localhost:22333", "fixture-api-token")
            assertEquals("fixture-api-token", RemoteCredentials(context, "auth-generated").api()!!.token)
            assertNull(imported.api())
            val raw = context.getSharedPreferences("remote-credentials", 0).all.toString()
            assertFalse(raw.contains("fixture-api-token")); assertFalse(raw.contains("fixture-only-passphrase")); assertFalse(raw.contains("PRIVATE KEY"))
        } finally { private.fill(0); phrase.fill(0); pair.dispose() }
    }
    @Test fun authenticateGeneratedAndImportedKeys() = runBlocking(Dispatchers.IO) {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("remoteFixture") == "true")
        for (id in listOf("auth-generated", "auth-imported")) {
            val server = RemoteServer(id, "127.0.0.1", "fixture", 22222)
            val engine = RemoteEngine.get(context, server)
            context.getSharedPreferences("ssh-host-keys", 0).edit().remove(id).commit()
            try {
                engine.inspectHost(); engine.acceptHost()
                engine.connectKey(RemoteCredentials(context, id).ssh())
                assertNull(engine.state.value.error); assertTrue(engine.state.value.connected); assertTrue(engine.state.value.managed)
                if (engine.state.value.version == null) engine.install(ReleaseCatalog.releases(engine.state.value.architecture!!).first { it.version == "2.30.5" })
                assertNull(engine.state.value.error)
                assertTrue(engine.adminRequest("/admin/settings").has("registration"))
            } finally { engine.disconnect(); context.getSharedPreferences("ssh-host-keys", 0).edit().remove(id).commit() }
        }
    }
    @Test fun suppliedApiTokenUsesKeyTunnelWithoutLifecycleAccess() = runBlocking(Dispatchers.IO) {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("remoteFixture") == "true")
        val server = RemoteServer("auth-existing", "127.0.0.1", "fixture", 22222)
        val engine = RemoteEngine.get(context, server)
        context.getSharedPreferences("ssh-host-keys", 0).edit().remove(server.id).commit()
        try {
            engine.inspectHost(); engine.acceptHost()
            engine.connectKey(RemoteCredentials(context, "auth-generated").ssh(), "wrong-token")
            assertFalse(engine.state.value.connected)
            val token = File(context.cacheDir, "auth-fixture-token").readText().trim()
            engine.connectKey(RemoteCredentials(context, "auth-generated").ssh(), token)
            assertTrue(engine.state.value.connected); assertFalse(engine.state.value.managed)
            assertEquals("SSH", engine.state.value.transport)
            assertTrue(engine.adminRequest("/admin/settings").has("registration"))
            val vault = RemoteCredentials(context, server.id)
            vault.saveTunnelToken(token)
            assertEquals(token, RemoteCredentials(context, server.id).tunnelToken())
            vault.delete()
            assertNull(RemoteCredentials(context, server.id).tunnelToken())
            engine.restart()
            assertNotNull(engine.state.value.error)
        } finally {
            engine.disconnect(); context.getSharedPreferences("ssh-host-keys", 0).edit().remove(server.id).commit()
            File(context.cacheDir, "auth-fixture-token").delete()
        }
    }
    @Test fun cleanupFixtureCredentials() {
        for (id in listOf("auth-generated", "auth-imported", "auth-existing")) RemoteCredentials(context, id).delete()
        File(context.cacheDir, "auth-fixture.pub").delete()
    }
    @Test fun directApiRequiresTlsAndTokenAndCannotRestart() = runBlocking(Dispatchers.IO) {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("apiFixture") == "true")
        val server = RemoteServer("api-fixture", "localhost", "unused", 22)
        val engine = RemoteEngine.get(context, server)
        engine.connectApi("http://localhost:22333", "fixture-api-token")
        assertFalse(engine.state.value.connected)
        engine.connectApi("https://localhost:22333", "fixture-api-token")
        assertFalse("An untrusted certificate must fail", engine.state.value.connected)
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        val certificate = File("/data/local/tmp/madmail-auth-cert.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        try {
            // Test-only pin of this fixture certificate; hostname checking stays enabled.
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            engine.connectApi("https://localhost:22333", "wrong")
            assertFalse(engine.state.value.connected)
            engine.connectApi("https://localhost:22333", "fixture-api-token")
            assertTrue(engine.state.value.connected); assertFalse(engine.state.value.managed); assertEquals("API", engine.state.value.transport)
            assertTrue(engine.adminRequest("/admin/settings").has("registration"))
            engine.restart()
            assertNotNull(engine.state.value.error)
            engine.connectApi("https://localhost:22333/redirect", "fixture-api-token")
            assertFalse(engine.state.value.connected)
        } finally { engine.disconnect(); HttpsURLConnection.setDefaultSSLSocketFactory(previous) }
    }
}
