package org.madmail.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
internal fun HostSetup(phone: Boolean, phoneConfigured: Boolean, initialName: String, firstRun: Boolean,
    onPhone: () -> Unit, onRemote: () -> Unit, onBack: () -> Unit, onCancel: (() -> Unit)?, onComplete: (String) -> Unit) {
    var welcome by rememberSaveable { mutableStateOf(firstRun) }
    var review by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    val focus = LocalFocusManager.current
    val stage = when { welcome -> "welcome"; !phone -> "choose"; review -> "ready"; else -> "phone" }
    fun back() {
        focus.clearFocus()
        when (stage) {
            "ready" -> review = false
            "phone" -> { review = false; onBack() }
            "choose" -> if (firstRun) welcome = true else onCancel?.invoke()
        }
    }
    BackHandler(stage == "ready" || stage == "phone" || stage == "choose" && firstRun) { back() }
    val colors = MaterialTheme.colorScheme
    AnimatedContent(stage, transitionSpec = { fadeIn(tween(240, 60)) togetherWith fadeOut(tween(120)) }, label = "Onboarding step") { shown ->
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (shown != "welcome" && (shown != "choose" || firstRun)) {
                    IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous setup step") }
                } else Spacer(Modifier.width(12.dp))
                Text("Madmail", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                onCancel?.let { TextButton(onClick = it) { Text("Cancel") } }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Surface(color = colors.primaryContainer, shape = RoundedCornerShape(20.dp)) {
                    Icon(when (shown) { "welcome" -> Icons.Outlined.MarkEmailUnread; "choose" -> Icons.Outlined.Dns; "phone" -> Icons.Outlined.PhoneAndroid; else -> Icons.Outlined.CheckCircle },
                        null, Modifier.padding(20.dp).size(36.dp), tint = colors.onPrimaryContainer)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when (shown) { "welcome" -> "A mail server,\non your terms."; "choose" -> "Where will it run?"; "phone" -> "Make this phone\nyour server."; else -> "Ready when you are." },
                        style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text(when (shown) {
                        "welcome" -> "Run Madmail on this phone or manage a remote server. Invite people with a QR code; they use a mail client such as Delta Chat."
                        "choose" -> "Choose your first host. You can manage more than one and switch between them later."
                        "phone" -> "Give it a name you’ll recognize in your server list."
                        else -> "Your setup is ready to save. Hosting will stay off until you start it."
                    }, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                when (shown) {
                    "welcome" -> {
                        SetupDetail(Icons.Outlined.Dns, "Choose where to host", "Use this device or connect your own server.")
                        SetupDetail(Icons.Outlined.QrCode2, "Invite with a scan", "Generate a personal account QR when your server is online.")
                        SetupDetail(Icons.Outlined.Tune, "Stay in control", "Manage accounts, server settings and hosting from one place.")
                    }
                    "choose" -> {
                        HostOption(Icons.Outlined.PhoneAndroid, "This phone", if (phoneConfigured) "Open the phone server you already set up." else "For devices on your Wi-Fi, hotspot or VPN. Keep this phone on and connected.") { review = false; onPhone() }
                        HostOption(Icons.Outlined.Dns, "Remote server", "Connect using SSH or an Admin API. Your server runs independently of this phone.", onRemote)
                    }
                    "phone" -> {
                        OutlinedTextField(name, { name = it.take(80) }, label = { Text("Server name") }, placeholder = { Text("This phone") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                            supportingText = { Text("Only the name shown in this app. You can change it later.") })
                        SetupDetail(Icons.Outlined.Wifi, "Share a network", "Guests need to reach this phone over Wi-Fi, hotspot or VPN. You can choose the address when creating an invitation.")
                        SetupDetail(Icons.Outlined.BatteryFull, "Keep it available", "Hosting needs this phone to stay on. Background access helps keep connections available when the screen is off.")
                        BackgroundHostingAccess()
                    }
                    "ready" -> {
                        SetupDetail(Icons.Outlined.PhoneAndroid, name.trim().ifBlank { "This phone" }, "Hosted on this device · no root access needed")
                        HorizontalDivider()
                        SetupDetail(Icons.Outlined.PlayArrow, "Start hosting", "Use Start hosting on Overview when you’re ready.")
                        SetupDetail(Icons.Outlined.QrCode2, "Connect another device", "Generate a connection QR and scan it in Delta Chat. Share each personal account with one person.")
                        Text("Network and registration options are available in Settings.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (shown != "choose") Surface(color = colors.background) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        focus.clearFocus()
                        when (shown) { "welcome" -> welcome = false; "phone" -> review = true; "ready" -> onComplete(name.trim()) }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(when (shown) { "welcome" -> "Get started"; "phone" -> "Review setup"; else -> if (phoneConfigured) "Open phone server" else "Finish setup" })
                        Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
                    }
                    if (shown == "welcome") TextButton(onClick = { welcome = false; onRemote() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("I already have a remote server") }
                }
            }
        }
    }
}

@Composable
private fun SetupDetail(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HostOption(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
