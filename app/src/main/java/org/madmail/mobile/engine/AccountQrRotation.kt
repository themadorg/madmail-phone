package org.madmail.mobile.engine

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.madmail.mobile.core.GuestAddresses
import org.madmail.mobile.core.InviteLinks
import org.madmail.mobile.core.RemoteServer

internal data class RotationState(val active: Boolean = false, val code: String? = null, val issuedAt: Long = 0, val error: String? = null, val token: String? = null)

/**
 * Shows a fresh account QR every [PERIOD] while the Invite page is visible. Each code is its own account.
 * A replaced code is deleted once [GRACE] has passed with nobody signed in, unless it was copied or shared.
 * The ledger of issued accounts survives app restarts so nothing is left behind; passwords are never stored.
 */
internal class AccountQrRotation private constructor(context: Context, serverId: String, remote: RemoteServer?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val local = LocalEngine.get(context)
    private val ssh = remote?.let { RemoteEngine.get(context, it) }
    private val prefs = context.getSharedPreferences("account-qr-$serverId", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(RotationState())
    val state = mutable.asStateFlow()
    private val lock = Mutex()
    private var loop: Job? = null
    private var sweeper: Job? = null
    private var origin = ""
    private var label = ""
    private var expiry = "24h"
    private var current: Entry? = null
    /** Bumped by pause(): a rotate still in flight from an older loop must not publish its code. */
    private var generation = 0
    /** Guards ledger read-modify-write, so keep() never waits behind network calls. */
    private val ledgerLock = Any()

    /** One issued account. [replaced] is 0 while shown; [kept] means it left the screen by copy or share. */
    private data class Entry(val token: String, val username: String?, val replaced: Long, val kept: Boolean, val issued: Long)

    @Synchronized fun start(origin: String, label: String = "", expiry: String = "24h") {
        this.origin = origin; this.label = label; this.expiry = expiry
        mutable.value = RotationState(active = true)
        resume()
    }
    /** Called while the page is visible and the server is ready. */
    @Synchronized fun resume() {
        if (!mutable.value.active || loop?.isActive == true || InviteLinks.origin(origin) == null) return
        val gen = generation
        loop = scope.launch {
            var failures = 0
            while (isActive) {
                val started = System.currentTimeMillis()
                runCatching { lock.withLock { rotate(gen) } }.onSuccess { failures = 0 }.onFailure { e ->
                    if (e is CancellationException) throw e
                    failures++
                    publish(gen) { it.copy(error = "New account QR failed. Retrying…") }
                }
                runCatching { lock.withLock { sweep(System.currentTimeMillis()) } }
                // Back off while creation keeps failing, so a broken server is not handed an account every 5 s.
                val period = PERIOD * (1 shl failures.coerceAtMost(4))
                delay((period - (System.currentTimeMillis() - started)).coerceAtLeast(500))
            }
        }
    }
    /** The page left the screen. The shown code starts its grace period and is not shown again. */
    @Synchronized fun pause() {
        loop?.cancel(); loop = null; generation++
        scope.launch { lock.withLock { retireCurrent() }; scheduleSweep() }
        mutable.value = mutable.value.copy(code = null, error = null)
    }
    @Synchronized fun stop() { pause(); mutable.value = RotationState() }
    /** The shown code was copied or shared: it may be used later, so it is never deleted automatically. */
    fun keep(token: String?) { if (token != null) edit { entries -> entries.map { if (it.token == token) it.copy(kept = true) else it } } }
    /** The guest-facing address changed: rewrite the shown code now; the next ones use it too. */
    @Synchronized fun retarget(updated: String) {
        origin = updated
        mutable.value.code?.let { code -> mutable.value = mutable.value.copy(code = InviteLinks.login(code, updated)) }
    }
    /** Tokens issued for rotation. They are hidden from the invitation list. */
    fun tokens(): Set<String> = ledger().map { it.token }.toSet()
    /** Cleans up after earlier sessions; safe to call whenever the server is ready. */
    fun tidy() = scheduleSweep()
    /** Keeps sweeping in the background, off screen too, until every replaced code has been dealt with. */
    @Synchronized private fun scheduleSweep() {
        if (sweeper?.isActive == true) return
        sweeper = scope.launch {
            while (isActive) {
                runCatching { lock.withLock { sweep(System.currentTimeMillis()) } }.onFailure { if (it is CancellationException) throw it }
                if (lock.withLock { ledger().none { !it.kept && it.token != current?.token } }) break
                delay(SWEEP_INTERVAL)
            }
        }
    }

    private suspend fun rotate(gen: Int) {
        val target = origin
        val host = java.net.URI(target).host.removeSurrounding("[", "]")
        if (ssh == null && GuestAddresses.ipv4(host)) check(local.useMailHost(host)) { "Hosting couldn’t switch to $host." }
        // Spare uses let IMAP and SMTP finish their first logins in parallel; the token never leaves this app.
        val body = JSONObject().put("max_uses", 5).put("comment", label.ifBlank { "Account QR" }).apply { if (expiry.isNotBlank()) put("expires_in", expiry) }
        val token = request("/admin/registration-token", "POST", body).getString("token")
        val now = System.currentTimeMillis()
        // Record the token before creating the account, so a failure below still gets cleaned up.
        edit { it + Entry(token, null, 0, false, now) }
        // The username is recorded as soon as the server answers, before the readiness check can fail.
        val record: (JSONObject) -> Unit = { created -> edit { entries -> entries.map { if (it.token == token) it.copy(username = created.getString("email")) else it } } }
        val guest = ssh?.createGuest(token, record) ?: local.createGuest(token, record)
        val entry = Entry(token, guest.getString("email"), 0, false, now)
        val code = InviteLinks.login(guest.getString("dclogin_url"), target)
        val shown = publish(gen) { it.copy(code = code, issuedAt = System.currentTimeMillis(), error = null, token = token) }
        // Paused while the account was being made: it was never shown, so it goes straight to cleanup.
        if (!shown) edit { entries -> entries.map { if (it.token == token) it.copy(replaced = now) else it } }
        else { retireCurrent(); current = entry }
    }
    /** Updates the shown state only if no pause() happened since [gen]. */
    private fun publish(gen: Int, update: (RotationState) -> RotationState): Boolean = synchronized(this) {
        if (gen != generation) return false
        mutable.value = update(mutable.value)
        true
    }
    private fun retireCurrent() {
        val entry = current ?: return
        current = null
        val now = System.currentTimeMillis()
        edit { entries -> entries.map { if (it.token == entry.token) it.copy(replaced = now) else it } }
    }
    /** Deletes replaced accounts nobody signed in to, and the tokens of those that were used. */
    private suspend fun sweep(now: Long) {
        // Anything but the shown code is due once its grace has passed, including codes from a session that ended abruptly.
        val due = ledger().filter { it.token != current?.token && now - maxOf(it.replaced, it.issued) >= GRACE }
        if (due.isEmpty()) return
        val accounts = request("/admin/accounts", "GET").optJSONArray("accounts") ?: JSONArray()
        val firstLogin = (0 until accounts.length()).map { accounts.getJSONObject(it) }.associate { it.optString("username") to it.optLong("first_login_at") }
        for (entry in due) {
            val login = entry.username?.let { firstLogin[it] }
            when {
                // Signed in: the token is spent, so removing it cannot affect the account.
                login != null && login > 1 -> forgetToken(entry)
                // The guest had a month to sign in. Removing the token keeps it out of the invitation list for good.
                entry.kept -> if (now - maxOf(entry.replaced, entry.issued) > KEPT_LIMIT) forgetToken(entry)
                // Never signed in: delete it. The engine blocks the address, so the old QR cannot recreate it.
                login == 1L -> { request("/admin/accounts", "DELETE", JSONObject().put("username", entry.username)); forgetToken(entry) }
                else -> forgetToken(entry)
            }
        }
    }
    private suspend fun forgetToken(entry: Entry) {
        try { request("/admin/registration-token", "DELETE", JSONObject().put("token", entry.token)) }
        catch (e: AdminRequestRejected) { if (e.status != 404) throw e }
        drop(entry)
    }
    private fun drop(entry: Entry) = edit { entries -> entries.filter { it.token != entry.token } }
    private fun edit(transform: (List<Entry>) -> List<Entry>) = synchronized(ledgerLock) { save(transform(ledger())) }
    private suspend fun request(resource: String, method: String, body: JSONObject = JSONObject()): JSONObject =
        ssh?.adminRequest(resource, method, body) ?: local.adminRequest(resource, method, body)

    private fun ledger(): List<Entry> = runCatching {
        val array = JSONArray(prefs.getString("ledger", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map {
            Entry(it.getString("token"), it.optString("username").ifBlank { null }, it.optLong("replaced"), it.optBoolean("kept"), it.optLong("issued"))
        }
    }.getOrDefault(emptyList())
    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("token", it.token).put("username", it.username ?: "").put("replaced", it.replaced).put("kept", it.kept).put("issued", it.issued)) }
        check(prefs.edit().putString("ledger", array.toString()).commit()) { "Couldn’t save the account QR." }
    }

    companion object {
        const val PERIOD = 5_000L
        /** Long enough for a guest to scan and confirm in Delta Chat. */
        const val GRACE = 120_000L
        private const val SWEEP_INTERVAL = 15_000L
        /** Copied codes are tracked this long, then left alone on the server. */
        private const val KEPT_LIMIT = 30L * 24 * 60 * 60_000
        private val instances = mutableMapOf<String, AccountQrRotation>()
        @Synchronized fun get(context: Context, id: String, remote: RemoteServer?) = instances.getOrPut(id) { AccountQrRotation(context.applicationContext, id, remote) }
        /** The server is being removed: clean up every unused account now, ignoring the grace period, then drop the ledger. */
        @Synchronized fun forget(context: Context, id: String, remote: RemoteServer?): Job {
            val rotation = get(context, id, remote)
            instances.remove(id)
            rotation.stop()
            return rotation.scope.launch {
                withTimeoutOrNull(20_000) { runCatching { rotation.lock.withLock { rotation.retireCurrent(); rotation.sweep(System.currentTimeMillis() + GRACE + 1_000) } } }
                rotation.prefs.edit().clear().commit()
            }.also { job -> job.invokeOnCompletion { rotation.scope.cancel() } }
        }
    }
}
