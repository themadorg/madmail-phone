package org.madmail.mobile.core

/** Only the enabled preference is persisted; a new activity always starts unauthenticated. */
data class AppLockState(val enabled: Boolean, val unlocked: Boolean = false) {
    val locked: Boolean get() = enabled && !unlocked
    fun background() = copy(unlocked = false)
    fun authenticated(success: Boolean, foreground: Boolean, change: Boolean? = null): AppLockState =
        if (success && foreground) AppLockState(change ?: enabled, unlocked = true) else this
}
