package org.madmail.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerWorkspaceTest {
    @Test fun invitationsAreIsolatedAcrossServersAndRemoval() {
        val workspace = ServerWorkspace()
        workspace.updateInvitation("phone", "https://phone.example/new?t=phone-secret")
        workspace.updateInvitation("remote-a", "https://remote.example/new?t=remote-secret")
        assertEquals("", workspace.invitation("remote-b"))
        assertEquals("https://phone.example/new?t=phone-secret", workspace.invitation("phone"))
        workspace.forget("remote-a")
        assertEquals("", workspace.invitation("remote-a"))
        assertEquals("https://phone.example/new?t=phone-secret", workspace.invitation("phone"))
        assertEquals("", ServerWorkspace().invitation("phone"))
    }
}
