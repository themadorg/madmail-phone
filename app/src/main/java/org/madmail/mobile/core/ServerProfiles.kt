package org.madmail.mobile.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RemoteServer(val id: String, val host: String, val user: String, val port: Int) {
    val address: String get() = "$user@$host · $port"
}

/** Only connection metadata; credentials and host trust must use separate secure storage. */
class ServerProfiles(context: Context) {
    private val prefs = context.getSharedPreferences("server-profiles", Context.MODE_PRIVATE)
    fun load(): List<RemoteServer> = runCatching {
        val items = JSONArray(prefs.getString("remotes", "[]"))
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            RemoteServer(item.getString("id"), item.getString("host"), item.getString("user"), item.getInt("port"))
        }
    }.getOrDefault(emptyList())
    fun selected(): String = prefs.getString("selected", "phone") ?: "phone"
    fun select(id: String) { prefs.edit().putString("selected", id).apply() }
    fun save(host: String, user: String, port: Int): RemoteServer {
        val servers = load()
        val existing = servers.find { it.host.equals(host, ignoreCase = true) && it.user == user && it.port == port }
        if (existing != null) return existing
        val server = RemoteServer(UUID.randomUUID().toString(), host, user, port)
        write(servers + server)
        return server
    }
    fun remove(id: String) {
        write(load().filterNot { it.id == id })
        if (selected() == id) select("phone")
    }
    private fun write(servers: List<RemoteServer>) {
        val items = JSONArray()
        servers.forEach { server -> items.put(JSONObject().put("id", server.id).put("host", server.host).put("user", server.user).put("port", server.port)) }
        prefs.edit().putString("remotes", items.toString()).apply()
    }
}
