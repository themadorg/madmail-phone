package org.madmail.mobile.engine

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.core.ServerSetting
import org.madmail.mobile.core.SettingKind

internal data class SettingsState(
    val values: JSONObject? = null, val busy: Boolean = false, val stale: Boolean = true,
    val message: String? = null, val error: String? = null, val restartRequired: Boolean = false,
    val accounts: JSONObject? = null, val quota: JSONObject? = null, val dkim: JSONObject? = null,
)

/** A controller owns one immutable server. Writes survive page navigation and never auto-retry. */
internal class SettingsController private constructor(private val context: Context, private val id: String, private val remote: RemoteServer?) {
    init { require(if (remote == null) id == "phone" else remote.id == id) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("settings-state-$id", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(SettingsState(restartRequired = prefs.getBoolean("restart", false)))
    val state = mutable.asStateFlow()
    suspend fun request(resource: String, method: String = "GET", body: JSONObject = JSONObject()): JSONObject =
        if (remote == null) LocalEngine.get(context).adminRequest(resource, method, body) else RemoteEngine.get(context, remote).adminRequest(resource, method, body)

    fun refresh() { scope.launch { mutex.withLock {
        mutable.value = mutable.value.copy(busy = true, error = null)
        try { mutable.value = mutable.value.copy(values = request("/admin/settings"), stale = false) }
        catch (_: Exception) { mutable.value = mutable.value.copy(stale = true, error = "Couldn’t refresh settings. Check the connection and try again.") }
        finally { mutable.value = mutable.value.copy(busy = false) }
    } } }
    fun loadDetails(page: String) { scope.launch { mutex.withLock {
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            when (page) {
                "accounts" -> mutable.value = mutable.value.copy(accounts = request("/admin/accounts"), quota = request("/admin/quota"))
                "federation" -> mutable.value = mutable.value.copy(dkim = request("/admin/dkim"))
            }
        } catch (_: Exception) { mutable.value = mutable.value.copy(error = "Couldn’t load this section. Refresh to try again.") }
        finally { mutable.value = mutable.value.copy(busy = false) }
    } } }
    fun save(setting: ServerSetting, value: String, reset: Boolean = false) {
        if (setting.kind == SettingKind.Managed || (!reset && setting.kind != SettingKind.Toggle && !setting.valid(value, remote == null))) {
            mutable.value = mutable.value.copy(error = "Choose a valid value before saving.")
            return
        }
        val body = when {
            setting.key == "federation_enabled" -> JSONObject().put("enabled", value == "true")
            setting.key == "federation_policy" -> JSONObject().put("policy", value)
            setting.key == "registration" -> JSONObject().put("action", if (value == "true") "open" else "close")
            setting.kind == SettingKind.Toggle -> JSONObject().put("action", if (value == "true") "enable" else "disable")
            else -> JSONObject().put("action", if (reset) "reset" else "set").apply { if (!reset) put("value", value) }
        }
        mutate(setting.route, "POST", body, setting.restart, "${setting.title} saved.")
    }
    fun mutate(resource: String, method: String, body: JSONObject, restart: Boolean = false, success: String = "Saved.", refreshAccounts: Boolean = false) {
        scope.launch { mutex.withLock {
            if (mutable.value.stale) { mutable.value = mutable.value.copy(error = "Refresh settings before making another change."); return@withLock }
            mutable.value = mutable.value.copy(busy = true, error = null, message = null)
            var written = false
            try {
                val result = request(resource, method, body)
                written = true
                val needsRestart = mutable.value.restartRequired || restart || result.optBoolean("restart_required")
                prefs.edit().putBoolean("restart", needsRestart).apply()
                mutable.value = mutable.value.copy(message = success, restartRequired = needsRestart)
                val values = request("/admin/settings")
                mutable.value = mutable.value.copy(values = values, stale = false)
                if (refreshAccounts) mutable.value = mutable.value.copy(accounts = request("/admin/accounts"), quota = request("/admin/quota"))
            } catch (e: Exception) {
                if (!written && e is AdminRequestRejected && e.status in listOf(400, 401, 403, 404, 405, 422)) {
                    mutable.value = mutable.value.copy(error = if (e.status == 400 || e.status == 422) "The server rejected this value. Check its format and try again." else "This operation is not available on the connected server (${e.status}).")
                } else mutable.value = mutable.value.copy(stale = true, error = if (written) "Saved, but refreshed values are unavailable. Refresh before making another change." else "The change wasn’t confirmed and may have been applied. Refresh before trying again.")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        } }
    }
    fun restart() { scope.launch { mutex.withLock {
        mutable.value = mutable.value.copy(busy = true, error = null, message = null)
        try {
            if (remote == null) LocalEngine.get(context).restart() else {
                val engine = RemoteEngine.get(context, remote)
                engine.restart()
                check(engine.state.value.status == "Running" && engine.state.value.error == null)
            }
            val values = request("/admin/settings")
            prefs.edit().putBoolean("restart", false).apply()
            mutable.value = mutable.value.copy(values = values, stale = false, restartRequired = false, message = "Server restarted.")
        } catch (_: Exception) { mutable.value = mutable.value.copy(stale = true, error = "Restart wasn’t confirmed. Check the server in Overview before trying again.") }
        finally { mutable.value = mutable.value.copy(busy = false) }
    } } }
    companion object {
        private val instances = mutableMapOf<String, SettingsController>()
        @Synchronized fun get(context: Context, id: String, remote: RemoteServer?): SettingsController = instances.getOrPut(id) { SettingsController(context.applicationContext, id, remote) }
        @Synchronized fun forget(id: String) { instances.remove(id)?.scope?.cancel() }
    }
}
