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
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        } else if (error == null) error = "Not unlocked. Try again."
        action = null
    }

    @Suppress("DEPRECATION")
    fun authenticate(change: Boolean? = null) {
        if (busy) return
        error = null
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            error = "Set a screen lock in Settings, then try again."
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
                    .setSubtitle("Fingerprint or screen lock")
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
                val intent = keyguard.createConfirmDeviceCredentialIntent(title, "Confirm screen lock")
                    ?: error("Device screen lock is unavailable")
                credential.launch(intent)
            }
        }.onFailure { error = "Couldn’t open unlock. Try again."; finish(false) }
    }
}

internal val LocalAppLock = staticCompositionLocalOf<AppLock> { error("App lock not provided") }

@Composable
internal fun AppLockScreen(lock: AppLock) {
    Surface(Modifier.fillMaxSize(), color = panelColor()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = GroupGutter, vertical = 24.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                TintIcon(Tint.Graphite, Icons.Rounded.Lock, size = 56.dp)
                Spacer(Modifier.height(24.dp))
                Text("Madmail is locked", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text("Hosting keeps running.", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                AnimatedVisibility(lock.error != null, enter = fadeIn(tween(240, easing = Emphasized)) + expandVertically(tween(300, easing = Emphasized)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(240, easing = Emphasized))) {
                    Group(Modifier.padding(top = 24.dp)) { InfoRow(Tint.Red, Icons.Rounded.ErrorOutline, lock.error.orEmpty()) }
                }
            }
            BrandButton(if (lock.busy) "Waiting…" else "Unlock", enabled = !lock.busy) { lock.authenticate() }
        }
    }
}

@Composable
internal fun AppLockSettings() {
    val lock = LocalAppLock.current
    Column(Modifier.animateContentSize(tween(300, easing = Emphasized))) {
        Group {
            SwitchRow(Tint.Graphite, Icons.Rounded.Lock, "App lock", null, lock.enabled, enabled = !lock.busy) { lock.authenticate(it) }
            if (lock.enabled) {
                RowDivider(60.dp)
                ActionRow("Lock now", { lock.lock() })
            }
        }
        Footer("Locks when you leave. Hosting keeps running.")
        lock.error?.let { Footer(it, color = MaterialTheme.colorScheme.error) }
    }
}
