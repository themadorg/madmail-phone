package org.madmail.mobile

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.madmail.mobile.core.AdminLoginLinks
import org.madmail.mobile.core.PortMode
import org.madmail.mobile.engine.ApiCredential

/**
 * An admin sign-in opened from outside the app (an admin.madmail.chat login link or madmail-admin://).
 * Held in memory only, never in saved state, until Add server takes it; the app lock still applies first.
 */
internal object AdminLoginInbox {
    var pending by mutableStateOf<ApiCredential?>(null)
    var invalid by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    private lateinit var appLock: AppLock
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLock = AppLock(this)
        appLock.protectWindow()
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = madmailColors(isSystemInDarkTheme()),
                typography = madmailTypography(LocalConfiguration.current.locales[0].language)) {
                CompositionLocalProvider(LocalAppLock provides appLock) {
                    ToastHost { if (appLock.locked) AppLockScreen(appLock) else MadmailApp() }
                }
            }
        }
        // A recreated activity, or one reopened from Recents, carries the original link again; it was already handled.
        if (savedInstanceState == null) receive(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }
    private fun receive(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val link = intent.data ?: return
        setIntent(Intent(intent).setData(null))
        AdminLoginLinks.parse(link.toString())?.let { AdminLoginInbox.pending = it; return }
        // The app claims admin.madmail.chat/ only for sign-in links; anything else there is the web panel, so it goes to the browser.
        if (link.scheme == "https" && openInBrowser(link)) return
        AdminLoginInbox.invalid = true
    }
    private fun openInBrowser(link: Uri) = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, link).addCategory(Intent.CATEGORY_BROWSABLE)
            .apply { selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER) })
    }.isSuccess
    override fun onPause() {
        appLock.lock()
        super.onPause()
    }
    override fun onDestroy() {
        appLock.dispose()
        super.onDestroy()
    }
}

@Composable
internal fun PhoneHostingSettings(serverId: String) {
    val context = LocalContext.current
    val prefs = remember(serverId) {
        context.getSharedPreferences("server-settings-$serverId", Context.MODE_PRIVATE).also { scoped ->
            if (serverId == "phone" && !scoped.contains("root_ports")) {
                val legacy = context.getSharedPreferences("server-settings", Context.MODE_PRIVATE)
                scoped.edit().putBoolean("root_ports", legacy.getBoolean("root_ports", false)).apply()
                legacy.edit().remove("root_ports").apply()
            }
        }
    }

    var rootPorts by remember { mutableStateOf(prefs.getBoolean("root_ports", false)) }
    val mode = if (rootPorts) PortMode.RootStandard else PortMode.Unprivileged
    Column {
        Caption(stringResource(R.string.common_ports_caption))
        Group {
            SwitchRow(Tint.Teal, Icons.Rounded.SettingsEthernet, stringResource(R.string.common_ports_standard), stringResource(R.string.common_ports_needs_root), rootPorts) {
                rootPorts = it; prefs.edit().putBoolean("root_ports", it).apply()
            }
            RowDivider(64.dp)
            ValueRow("SMTP TLS", mode.smtp.toString())
            RowDivider(16.dp)
            ValueRow("IMAP TLS", mode.imap.toString())
        }
        if (rootPorts) Footer(stringResource(R.string.common_ports_root_saved))
        Spacer(Modifier.height(24.dp))
        Caption(stringResource(R.string.common_data_caption))
        Group { InfoRow(Tint.Yellow, Icons.Rounded.Storage, stringResource(R.string.common_data_on_phone), stringResource(R.string.common_data_private_storage)) }
        Footer(stringResource(R.string.common_data_footer))
    }
}
