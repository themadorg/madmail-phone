package org.madmail.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSelectionTest {
    @Test fun freshInstallHasNoDefaultHost() {
        assertEquals("", resolveServerSelection(null, false, emptyList()))
        assertEquals("", resolveServerSelection("phone", false, emptyList()))
    }
    @Test fun existingPhoneDataPreservesWorkspace() {
        assertEquals("phone", resolveServerSelection(null, true, emptyList()))
    }
    @Test fun remoteOnlyDoesNotCreatePhone() {
        assertEquals("remote", resolveServerSelection(null, false, listOf("remote")))
        assertEquals("", resolveServerSelection("removed", false, emptyList()))
    }
    @Test fun selectionAndRemovalUseConfiguredHostsOnly() {
        assertEquals("b", resolveServerSelection("b", true, listOf("a", "b")))
        assertEquals("a", resolveServerSelection("removed", false, listOf("a")))
        assertEquals("phone", resolveServerSelection("removed", true, listOf("a")))
    }
}
