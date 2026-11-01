package org.madmail.mobile

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import org.madmail.mobile.core.AppLockState

/** UI-only gate. Never owns or stops the hosting service. Authentication is never saved. */
internal class AppLock(private val activity: MainActivity) {
    private val prefs = activity.getSharedPreferences("app-security", Context.MODE_PRIVATE)
    private var state by mutableStateOf(AppLockState(prefs.getBoolean("lock", false)))
    val enabled get() = state.enabled
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var signal: CancellationSignal? = null
    private var action: Boolean? = null // null = unlock; true/false = enable/disable
    private var generation = 0
    private val credential = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finish(it.resultCode == Activity.RESULT_OK)
    }
    val locked get() = state.locked

    fun protectWindow() {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun lock() { state = state.background() }

    fun dispose() {
        generation++
        signal?.cancel()
        busy = false
    }

    private fun finish(success: Boolean) {
        if (!busy) return
        busy = false
        signal = null
        // Never accept a late result while another app is in front, or on the lock screen.
        val present = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            !activity.getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (success && present) {
            val change = action
            if (change != null) {
                if (!prefs.edit().putBoolean("lock", change).commit()) {
                    error = "Couldn’t save app lock. Try again."
                    return
                }
            }
            state = state.authenticated(success, present, change)
            protectWindow()
            error = null
        } else if (error == null) error = "Not unlocked. Try again when you’re ready."
        action = null
    }

    @Suppress("DEPRECATION")
    fun authenticate(change: Boolean? = null) {
        if (busy) return
        error = null
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            error = "Set up a screen lock in Android Settings, then try again."
            return
        }
        action = change
        busy = true
        val title = when (change) { true -> "Enable app lock"; false -> "Turn off app lock"; null -> "Unlock Madmail" }
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val request = ++generation
                val cancellation = CancellationSignal().also { signal = it }
                BiometricPrompt.Builder(activity).setTitle(title)
                    .setSubtitle("Use your fingerprint or phone screen lock")
                    .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                    .build().authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (request == generation) finish(true)
                        }
                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            if (request == generation) { error = message.toString(); finish(false) }
                        }
                    })
            } else {
                val intent = keyguard.createConfirmDeviceCredentialIntent(title, "Confirm your phone screen lock")
                    ?: error("Device screen lock is unavailable")
                credential.launch(intent)
            }
        }.onFailure { error = "Authentication couldn’t open. Try again."; finish(false) }
    }
}

internal val LocalAppLock = staticCompositionLocalOf<AppLock> { error("App lock not provided") }

@Composable
internal fun AppLockScreen(lock: AppLock) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("Madmail is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Unlock to manage your servers. Hosting continues in the background.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Button(onClick = { lock.authenticate() }, enabled = !lock.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.LockOpen, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (lock.busy) "Waiting for authentication…" else "Unlock")
            }
            lock.error?.let { Spacer(Modifier.height(16.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun AppLockSettings() {
    val lock = LocalAppLock.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("On this phone", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = lock.enabled,
            enabled = !lock.busy, role = Role.Switch, onValueChange = { lock.authenticate(it) }),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("App lock", style = MaterialTheme.typography.bodyLarge)
                Text("Protect access to all saved servers", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = lock.enabled, enabled = !lock.busy, onCheckedChange = null)
        }
        Text("Use your phone’s screen lock. Locks when you leave Madmail; hosting keeps running.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lock.enabled) TextButton(onClick = { lock.lock() }) { Text("Lock now") }
        lock.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
