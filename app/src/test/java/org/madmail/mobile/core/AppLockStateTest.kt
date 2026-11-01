package org.madmail.mobile.core

import org.junit.Assert.*
import org.junit.Test

class AppLockStateTest {
    @Test fun coldStartAndRecreationRequireAuthentication() {
        val unlocked = AppLockState(true).authenticated(true, true)
        assertFalse(unlocked.locked)
        assertTrue(AppLockState(unlocked.enabled).locked)
    }
    @Test fun backgroundInvalidatesAnUnlockedSession() {
        assertTrue(AppLockState(true).authenticated(true, true).background().locked)
    }
    @Test fun cancellationAndLateBackgroundResultsCannotUnlockOrDisable() {
        val locked = AppLockState(true)
        assertEquals(locked, locked.authenticated(false, true, false))
        assertEquals(locked, locked.authenticated(true, false, false))
        assertEquals(locked, locked.authenticated(true, false))
    }
    @Test fun preferenceChangesRequireForegroundSuccess() {
        val disabled = AppLockState(false)
        assertFalse(disabled.authenticated(false, true, true).enabled)
        assertTrue(disabled.authenticated(true, true, true).enabled)
        assertFalse(AppLockState(true).authenticated(true, true, false).enabled)
    }
}
