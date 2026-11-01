package org.madmail.mobile.engine

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.madmail.mobile.core.RemoteServer
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class EngineDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun localStartStopRestartKeepsIdentity() = runBlocking(Dispatchers.IO) {
        val engine = LocalEngine.get(context)
        try {
            engine.start()
            assertEquals(EnginePhase.Running, engine.state.value.phase)
            assertNotNull(engine.state.value.overview?.getJSONObject("users"))
            val cert = File(engine.dataDir, "certs/fullchain.pem").readBytes()
            val token = File(engine.dataDir, "admin_token").readBytes()
            engine.start() // serialized idempotent start, not another child
            engine.stop()
            assertEquals(EnginePhase.Stopped, engine.state.value.phase)
            for (port in listOf(11465,11993,18443)) {
                assertTrue(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1",port),500) } }.isFailure)
            }
            engine.start()
            assertEquals(EnginePhase.Running, engine.state.value.phase)
            assertArrayEquals(cert, File(engine.dataDir, "certs/fullchain.pem").readBytes())
            assertArrayEquals(token, File(engine.dataDir, "admin_token").readBytes())
        } finally { engine.stop() }
    }
    @Test fun releaseCatalogKeepsVersionsWithoutCompatibleAssets() {
        val releases = ReleaseCatalog.parse(org.json.JSONArray("""[
          {"tag_name":"v2.30.5","assets":[]},
          {"tag_name":"v2.31.0","assets":[
            {"name":"madmail-x86.apk","browser_download_url":"https://github.com/themadorg/madmail/releases/download/v2.31.0/madmail-x86.apk"},
            {"name":"madmail-arm64.apk","browser_download_url":"https://github.com/themadorg/madmail/releases/download/v2.31.0/madmail-arm64.apk"},
            {"name":"universal.apk","browser_download_url":"https://untrusted.example/package.apk"}
          ]},
          {"tag_name":"v3.0.0","prerelease":true,"assets":[]},
          {"tag_name":"v4.0.0","draft":true,"assets":[]}
        ]"""))
        assertEquals(listOf("2.30.5", "2.31.0"), releases.map { it.version })
        assertNull(releases[0].androidPackage)
        assertEquals("madmail-arm64.apk", releases[1].androidPackage?.name)
        assertEquals(2, releases[1].assets.size)
    }
    @Test fun wrongAndroidPackageIsRejected() {
        val wrong = File(context.cacheDir, "invalid-update.apk").apply { writeText("not an apk") }
        try { assertThrows(IllegalStateException::class.java) { AppUpdates(context).verify(wrong) } }
        finally { wrong.delete() }
    }
    @Test fun remoteFixtureLifecycle() = runBlocking(Dispatchers.IO) {
        // Run explicitly with -e remoteFixture true; never points at a user server.
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("remoteFixture") == "true")
        val server = RemoteServer("integration-fixture", "127.0.0.1", "fixture", 22222)
        val engine = RemoteEngine.get(context, server)
        context.getSharedPreferences("ssh-host-keys", 0).edit().remove(server.id).commit()
        try {
            engine.inspectHost()
            assertNotNull(engine.fingerprint)
            assertFalse(engine.trusted)
            engine.acceptHost()
            engine.connect("test-only-madmail-fixture")
            assertTrue(engine.state.value.error, engine.state.value.connected)
            assertEquals("Not installed", engine.state.value.status)
            val release = ReleaseCatalog.releases(engine.state.value.architecture!!).first { it.version == "2.30.5" }
            engine.install(release)
            assertNull(engine.state.value.error)
            engine.refresh()
            assertEquals("Running", engine.state.value.status)
            assertNotNull(engine.state.value.overview)
            engine.stop()
            assertEquals("Stopped", engine.state.value.status)
            engine.start()
            assertEquals("Running", engine.state.value.status)
            // Reinstall same version exercises cold backup and preservation.
            engine.install(release)
            assertNull(engine.state.value.error)
            assertEquals("2.30.5", engine.state.value.version)
            assertTrue(engine.state.value.installedVersions.contains("2.30.5"))
            engine.removeVersion("2.30.5")
            assertNotNull(engine.state.value.error)
            engine.refresh()
            assertEquals("Running", engine.state.value.status)
            engine.disconnect()
            context.getSharedPreferences("ssh-host-keys",0).edit().putString(server.id, "AAAA").commit()
            var rejected = false
            try { engine.inspectHost() } catch (_: IllegalStateException) { rejected = true }
            assertTrue("Changed host keys must be rejected", rejected)
        } finally {
            engine.disconnect()
            context.getSharedPreferences("ssh-host-keys",0).edit().remove(server.id).commit()
        }
    }
}
