package org.madmail.mobile.core

/** Invitations are server-owned, memory-only secrets, never saved in Android saved state. */
class ServerWorkspace {
    private val invitations = mutableMapOf<String, String>()
    fun invitation(serverId: String): String = invitations[serverId].orEmpty()
    fun updateInvitation(serverId: String, value: String) { invitations[serverId] = value }
    fun forget(serverId: String) { invitations.remove(serverId) }
}
