package org.madmail.mobile.core

import android.content.Context

data class ServerIdentity(val name: String = "", val mark: String = "default")

class ServerIdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("server-identities", Context.MODE_PRIVATE)
    fun read(id: String) = ServerIdentity(prefs.getString("$id.name", "").orEmpty(), prefs.getString("$id.mark", "default").orEmpty())
    fun save(id: String, identity: ServerIdentity) {
        prefs.edit().putString("$id.name", identity.name.trim()).putString("$id.mark", identity.mark).apply()
    }
}
