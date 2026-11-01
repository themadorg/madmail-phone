package org.madmail.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.madmail.mobile.core.PortMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = madmailColors(isSystemInDarkTheme())) {
                MadmailApp()
            }
        }
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
    Text("Connection ports", style = MaterialTheme.typography.titleLarge)
    Text("Standard ports can make connecting easier. They require root access on this phone. Most phones should use the default ports.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Prefer standard ports", modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = rootPorts, onCheckedChange = { rootPorts = it; prefs.edit().putBoolean("root_ports", it).apply() })
    }
    Text("SMTP TLS: ${mode.smtp}\nIMAP TLS: ${mode.imap}")
    Text(if (rootPorts) "Preference saved. Root access has not been requested. Root support will be added with the server engine; if unavailable, setup will offer default ports." else "Default ports work without root access.")
    HorizontalDivider()
    Text("Data stays on this phone", style = MaterialTheme.typography.titleMedium)
    Text("The server will store accounts and encrypted mail in private app storage. Uninstalling the app will remove that data. Backup and restore are planned before a production release.")
}
