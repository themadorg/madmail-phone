package org.madmail.mobile.engine

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.madmail.mobile.core.GuestAddresses
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
                val originHost = java.net.URI(origin).host.removeSurrounding("[", "]")
                // The phone server takes the chosen IP as its mail domain before anything is created.
                if (ssh == null && GuestAddresses.ipv4(originHost) && !local.useMailHost(originHost)) {
                    mutable.value = CreationState(error = "Hosting couldn’t switch to $originHost. Check the phone’s address.")
                    return@launch
                }
                if (!account) {
                    val host = (if (ssh != null) ssh.browserInviteHost(origin) else local.browserInviteHost(origin))?.removeSurrounding("[", "]")
                    if (host == null || host in listOf("localhost", "127.0.0.1") || !host.equals(originHost, ignoreCase = true)) {
                        mutable.value = CreationState(error = "Invitations use ${host ?: "no address"}, not $originHost. Choose Account QR or switch address.")
                        return@launch
                    }
                }
                // An account token gets spare uses so IMAP and SMTP can finish their first logins in parallel.
                val body = JSONObject().put("max_uses", if (account) 5 else uses).put("comment", title.ifBlank { if (account) "Personal account" else "Invitation" }).apply { if (expiry.isNotBlank()) put("expires_in", expiry) }
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
                    403 -> "Registration blocked. Check Registration settings."
                    429 -> "Too many requests. Try again later."
                    400, 422 -> "The server rejected these values. Check them."
                    else -> "The server rejected this (${e.status}). Reconnect and check settings."
                } else "Not confirmed; it may have gone through. Check invitations before retrying."
                mutable.value = CreationState(error = if (!mutationStarted) "Couldn’t use this address. Reconnect or restart hosting. Choose Account QR instead." else (if (token != null) "Invitation created, but the account QR failed. Find it in Invitations. " else "") + detail, uncertain = mutationStarted && !rejected)
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
