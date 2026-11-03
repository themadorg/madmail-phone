package org.madmail.mobile

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.madmail.mobile.core.PortMode

class MainActivity : ComponentActivity() {
    private lateinit var appLock: AppLock
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLock = AppLock(this)
        appLock.protectWindow()
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = madmailColors(isSystemInDarkTheme())) {
                CompositionLocalProvider(LocalAppLock provides appLock) {
                    if (appLock.locked) AppLockScreen(appLock) else MadmailApp()
                }
            }
        }
    }
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
        Caption("Connection ports")
        Group {
            SwitchRow(Tint.Teal, Icons.Rounded.SettingsEthernet, "Standard ports", "Needs root", rootPorts) {
                rootPorts = it; prefs.edit().putBoolean("root_ports", it).apply()
            }
            RowDivider(60.dp)
            ValueRow("SMTP TLS", mode.smtp.toString())
            RowDivider(16.dp)
            ValueRow("IMAP TLS", mode.imap.toString())
        }
        if (rootPorts) Footer("Saved. Root support isn’t available yet.")
        Spacer(Modifier.height(24.dp))
        Caption("Data")
        Group { InfoRow(Tint.Yellow, Icons.Rounded.Storage, "Stays on this phone", "Private app storage") }
        Footer("Uninstalling deletes it. No backup yet.")
    }
}
