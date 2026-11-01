package org.madmail.mobile.engine

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.madmail.mobile.core.InviteLinks
import org.madmail.mobile.core.RemoteServer

internal data class CreationState(val busy: Boolean = false, val code: String? = null, val error: String? = null, val uncertain: Boolean = false)
/** Own mutations outside a screen's coroutine scope. Secrets are memory-only, scoped by server ID. */
internal class InvitationCreation private constructor(context: Context, remote: RemoteServer?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val local = LocalEngine.get(context)
    private val ssh = remote?.let { RemoteEngine.get(context, it) }
    private val mutable = MutableStateFlow(CreationState())
    val state = mutable.asStateFlow()
    @Synchronized fun create(origin: String, account: Boolean, uses: Int, title: String, expiry: String) {
        if (mutable.value.busy || mutable.value.uncertain || mutable.value.code != null) return
        require(InviteLinks.origin(origin) != null)
        mutable.value = CreationState(busy = true)
        scope.launch {
            var token: String? = null
            var mutationStarted = false
            try {
                if (!account) {
                    val host = if (ssh != null) ssh.browserInviteHost(origin) else local.browserInviteHost(origin)
                    if (host == null || host in listOf("madmail.local", "localhost", "127.0.0.1")) {
                        mutable.value = CreationState(error = "This server’s browser invitation page does not advertise a guest-ready mail address. Choose Account QR to use your selected Wi-Fi, VPN or server address.")
                        return@launch
                    }
                }
                val body = JSONObject().put("max_uses", if (account) 1 else uses).put("comment", title.ifBlank { if (account) "Personal account" else "Invitation" }).apply { if (expiry.isNotBlank()) put("expires_in", expiry) }
                mutationStarted = true
                token = (ssh?.invitationRequest("POST", body) ?: local.invitationRequest("POST", body)).getString("token")
                val code = if (account) {
                    val guest = ssh?.createGuest(token) ?: local.createGuest(token)
                    InviteLinks.login(guest.getString("dclogin_url"), origin)
                } else InviteLinks.invite(origin, token)
                mutable.value = CreationState(code = code)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val rejected = e is AdminRequestRejected && e.status in listOf(400, 401, 403, 404, 405, 422, 429)
                val detail = if (rejected) when ((e as AdminRequestRejected).status) {
                    403 -> "Registration is blocked by this server. Check Registration settings."
                    429 -> "The server is limiting requests. Wait before trying again."
                    400, 422 -> "The server rejected these invitation details. Check the values."
                    else -> "The server rejected this operation (${e.status}). Reconnect and check its settings."
                } else "The result wasn’t confirmed. Check invitations and accounts before trying again; the server may have completed it."
                mutable.value = CreationState(error = if (!mutationStarted) "Couldn’t check the server’s browser invitation page. Reconnect or choose Account QR." else (if (token != null) "The invitation was created, but the account QR could not be completed. Find the token in Manage invitations. " else "") + detail, uncertain = mutationStarted && !rejected)
            }
        }
    }
    fun consume() { mutable.value = mutable.value.copy(code = null) }
    fun reviewed() { if (!mutable.value.busy) mutable.value = mutable.value.copy(uncertain = false, error = null) }
    companion object {
        private val instances = mutableMapOf<String, InvitationCreation>()
        @Synchronized fun get(context: Context, id: String, remote: RemoteServer?) = instances.getOrPut(id) { InvitationCreation(context.applicationContext, remote) }
        @Synchronized fun forget(id: String) { instances.remove(id)?.scope?.cancel() }
    }
}
