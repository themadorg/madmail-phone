package org.madmail.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.madmail.mobile.core.PortMode
import org.madmail.mobile.core.Registration

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
internal fun RegistrationScreen(serverName: String, initialEndpoint: String, onEndpointChange: (String) -> Unit) {
    val context = LocalContext.current
    // Invitation URLs can contain secrets: deliberately excluded from saved state and preferences.
    var endpoint by remember { mutableStateOf(initialEndpoint) }
    var copied by remember { mutableStateOf(false) }
    val payload = remember(endpoint) { Registration.payload(endpoint) }
    val qr = remember(payload) {
        payload?.let { value -> runCatching {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 768, 768)
            Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).apply {
                setPixels(IntArray(768 * 768) { i -> if (matrix[i % 768, i / 768]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 768, 0, 0, 768, 768)
            }
        }.getOrNull() }
    }
    Text("Invite to $serverName", style = MaterialTheme.typography.titleLarge)
    Text("When the server is ready, its registration code will appear here. Guests scan it in Delta Chat on another device.")
    Text("Already have a Madmail registration link? You can display it here. The app checks its format, but cannot verify that the server is reachable.")
    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it; onEndpointChange(it); copied = false }, label = { Text("HTTPS registration link") }, modifier = Modifier.fillMaxWidth(), isError = endpoint.isNotBlank() && payload == null, supportingText = { Text("Use the exact registration URL supplied by your server.") })
    if (qr != null) {
        Image(qr.asImageBitmap(), "Registration QR code. Scan in Delta Chat to create an account.", Modifier.fillMaxWidth().aspectRatio(1f))
        Text("Only share this code with people you want to invite.")
        Button(onClick = {
            val clip = ClipData.newPlainText("Madmail registration", payload)
            if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            copied = true
        }) { Text(if (copied) "Copied" else "Copy registration code") }
        TextButton(onClick = { endpoint = ""; onEndpointChange(""); copied = false }) { Text("Hide code") }
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
