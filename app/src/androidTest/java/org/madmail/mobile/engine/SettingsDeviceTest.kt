package org.madmail.mobile.engine

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.madmail.mobile.core.serverSettings
import java.io.File

class SettingsDeviceTest {
    @Test fun remoteSettingsAndRestartThroughSsh() = runBlocking(Dispatchers.IO) {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("remoteFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = org.madmail.mobile.core.RemoteServer("settings-fixture", "127.0.0.1", "fixture", 22222)
        val engine = RemoteEngine.get(context, server)
        val controller = SettingsController.get(context, server.id, server)
        context.getSharedPreferences("ssh-host-keys", 0).edit().remove(server.id).commit()
        try {
            engine.inspectHost(); engine.acceptHost(); engine.connect("test-only-madmail-fixture")
            assertTrue(engine.state.value.connected)
            val release = ReleaseCatalog.releases(engine.state.value.architecture!!).first { it.version == "2.30.5" }
            engine.install(release)
            assertNull(engine.state.value.error)
            controller.refresh()
            withTimeout(15000) { controller.state.first { it.values != null && !it.busy && !it.stale } }
            controller.save(serverSettings.single { it.key == "language" }, "fa")
            withTimeout(15000) { controller.state.first { !it.busy && it.values?.optJSONObject("language")?.optString("value") == "fa" } }
            controller.restart()
            withTimeout(45000) { controller.state.first { !it.busy && it.message == "Server restarted." } }
            assertEquals("Running", engine.state.value.status)
            assertEquals("fa", engine.adminRequest("/admin/settings/language").getString("value"))
        } finally {
            SettingsController.forget(server.id); engine.disconnect()
            context.getSharedPreferences("ssh-host-keys", 0).edit().remove(server.id).commit()
            context.getSharedPreferences("settings-state-${server.id}", 0).edit().clear().commit()
        }
    }
    @Test fun settingsRoundTripAndManagedRestart() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LocalEngine.get(context)
        val controller = SettingsController.get(context, "phone", null)
        var original: JSONObject? = null
        try {
            engine.start()
            val certificate = File(engine.dataDir, "certs/fullchain.pem").readBytes()
            original = engine.adminRequest("/admin/settings/language")
            val next = if (original.optString("value") == "fa") "en" else "fa"
            controller.refresh()
            withTimeout(10000) { controller.state.first { it.values != null && !it.busy && !it.stale } }
            controller.save(serverSettings.single { it.key == "language" }, next)
            val saved = withTimeout(10000) { controller.state.first { !it.busy && it.values?.optJSONObject("language")?.optString("value") == next } }
            assertNull(saved.error)
            assertEquals(next, engine.adminRequest("/admin/settings/language").getString("value"))
            controller.restart()
            withTimeout(20000) { controller.state.first { !it.busy && it.message == "Server restarted." } }
            assertEquals(EnginePhase.Running, engine.state.value.phase)
            assertArrayEquals(certificate, File(engine.dataDir, "certs/fullchain.pem").readBytes())
            assertEquals(next, engine.adminRequest("/admin/settings/language").getString("value"))
        } finally {
            try { original?.let { engine.adminRequest("/admin/settings/language", "POST", JSONObject().put("action", if (it.optBoolean("is_set")) "set" else "reset").put("value", it.optString("value"))) } }
            finally { SettingsController.forget("phone"); engine.stop() }
        }
    }
}
