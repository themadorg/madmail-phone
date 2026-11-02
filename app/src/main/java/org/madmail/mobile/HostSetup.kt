package org.madmail.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun HostSetup(phone: Boolean, phoneConfigured: Boolean, initialName: String, onPhone: () -> Unit, onRemote: () -> Unit,
    onBack: () -> Unit, onCancel: (() -> Unit)?, onComplete: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AnimatedContent(phone, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "Hosting setup") { showPhone ->
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (showPhone) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Choose where to host") }
            Spacer(Modifier.height(16.dp))
            Icon(if (showPhone) Icons.Outlined.PhoneAndroid else Icons.Outlined.Dns, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (showPhone) "Host on this phone" else "Where would you like to host?", style = MaterialTheme.typography.headlineLarge)
            Text(if (showPhone) "Share a mail server with devices on your Wi-Fi, hotspot or VPN. Your phone needs to stay on and connected."
                else "Choose where Madmail runs. You can add more servers later.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showPhone) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Server name") }, placeholder = { Text("This phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Phone hosting", style = MaterialTheme.typography.titleLarge)
                Text("Uses unprivileged ports; no root access needed. You control when hosting starts and stops.")
                Text("Once hosting is running, generate a random account QR for another device. Advanced network and registration options are in Settings.")
                Button(onClick = { onComplete(name.trim()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (phoneConfigured) "Open phone server" else "Set up this phone")
                }
                Text("Hosting starts only when you tap Start hosting.", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = onPhone, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Icon(Icons.Outlined.PhoneAndroid, null); Spacer(Modifier.width(12.dp)); Text("This phone")
                }
                OutlinedButton(onClick = onRemote, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Icon(Icons.Outlined.Dns, null); Spacer(Modifier.width(12.dp)); Text("Remote server")
                }
                Text("Connect a remote server using SSH or an Admin API.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            onCancel?.let { TextButton(onClick = it) { Text("Cancel") } }
        }
    }
}
