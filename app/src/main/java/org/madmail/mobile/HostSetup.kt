@file:OptIn(ExperimentalSharedTransitionApi::class)

package org.madmail.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import org.madmail.mobile.core.RemoteServer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

/**
 * A place a remote Madmail server can live. Providers only prefill the sign-in user and hints; nothing is provisioned.
 * [logo] is the brand's mark (Simple Icons, CC0) drawn white on [tint]..[tint2]; providers without an open mark keep [monogram].
 */
internal data class RemoteProvider(val id: String, val name: String, val note: String, val hint: String, val user: String = "root",
    val monogram: String? = null, val tint: Long = 0, val tint2: Long = 0, val ink: Long = 0xFFFFFFFF, val apiOnly: Boolean = false,
    @DrawableRes val logo: Int? = null, val userHint: String = "", val keyHint: String = AUTHORIZED_KEYS)

// Most consoles only inject keys when a server is created, so authorized_keys is the instruction that always works.
private const val AUTHORIZED_KEYS = "Add it to ~/.ssh/authorized_keys on the server."

internal val existingInstance = RemoteProvider("api", "Madmail Admin API", "Already running Madmail", "Enter its address and admin API key.", apiOnly = true)

private val selfHosted = RemoteProvider("ssh", "Self-hosted server", "Any Linux server over SSH", "Its public IP address or hostname.",
    userHint = "Root, or an account with sudo.")

private val cloudProviders = listOf(
    RemoteProvider("digitalocean", "DigitalOcean", "Droplet · username root", "Shown on the Droplet’s page.", monogram = "DO", tint = 0xFF2693FF, tint2 = 0xFF0080FF,
        logo = R.drawable.ic_provider_digitalocean, userHint = "Droplets sign in as root.", keyHint = "Open the Droplet Console and add it to ~/.ssh/authorized_keys."),
    RemoteProvider("aws", "Amazon Web Services", "EC2 or Lightsail · username ubuntu", "Use the instance’s public IPv4.", "ubuntu", "aws", 0xFFFFB23F, 0xFFF08300, 0xFF232F3E,
        userHint = "Ubuntu uses ubuntu; Amazon Linux, ec2-user.", keyHint = "Use EC2 Instance Connect to add it to ~/.ssh/authorized_keys."),
    RemoteProvider("gcp", "Google Cloud", "Compute Engine · your own username", "Use the VM’s external IP.", "", "G", 0xFF5E97F6, 0xFF4285F4,
        logo = R.drawable.ic_provider_gcp, userHint = "Use your SSH key’s username.", keyHint = "Add it under the VM’s SSH keys, ending with your username."),
    RemoteProvider("hetzner", "Hetzner", "Cloud server · username root", "Shown in Cloud Console under Servers.", monogram = "H", tint = 0xFFDB304C, tint2 = 0xFFD50C2D,
        logo = R.drawable.ic_provider_hetzner, userHint = "Hetzner servers sign in as root.", keyHint = "Open the server’s Console and add it to ~/.ssh/authorized_keys."),
    // Linode's own mark was withdrawn from Simple Icons; Akamai's stands in for it.
    RemoteProvider("linode", "Akamai Linode", "Linode · username root", "Shown on the Linode’s Network tab.", monogram = "L", tint = 0xFF26A6DC, tint2 = 0xFF0096D6,
        logo = R.drawable.ic_provider_linode, userHint = "Linodes sign in as root.", keyHint = "Open the LISH Console and add it to ~/.ssh/authorized_keys."),
    RemoteProvider("vultr", "Vultr", "Cloud compute · username root", "Shown on the server’s Overview.", monogram = "V", tint = 0xFF268FFC, tint2 = 0xFF007BFC,
        logo = R.drawable.ic_provider_vultr, userHint = "Vultr servers sign in as root.", keyHint = "Open View Console and add it to ~/.ssh/authorized_keys."),
    RemoteProvider("oracle", "Oracle Cloud", "Ubuntu instance · username ubuntu", "Use the instance’s public IP.", "ubuntu", "O", 0xFFFF8A65, 0xFFD2501F,
        userHint = "Ubuntu uses ubuntu; Oracle Linux, opc.", keyHint = "Use Cloud Shell or the console to add it to ~/.ssh/authorized_keys."),
)

internal val remoteProviders = listOf(selfHosted) + cloudProviders

/** The provider picked in setup, held for this process only so the connection sheet can show its hints. Nothing is saved. */
internal object SetupProviders {
    private val picked = mutableMapOf<String, String>()
    private fun key(host: String, user: String, port: Int) = "$user@${host.lowercase()}:$port"
    fun remember(host: String, user: String, port: Int, id: String) { picked[key(host, user, port)] = id }
    fun of(server: RemoteServer) = picked[key(server.host, server.user, server.port)]?.let { id -> remoteProviders.firstOrNull { it.id == id } }
}

private enum class SetupStep { Intro, Choose, Providers, Address, Account, Existing, ApiKey, Phone }

/** The wizard steps that slide sideways, as (index, count) for the step dots. */
private fun SetupStep.wizard(): Pair<Int, Int>? = when (this) {
    SetupStep.Providers -> 0 to 3; SetupStep.Address -> 1 to 3; SetupStep.Account -> 2 to 3
    SetupStep.Existing -> 0 to 2; SetupStep.ApiKey -> 1 to 2
    else -> null
}

private class IntroPage(val title: String, val body: String, val terminal: String)

// The terminal lines are what the app really does at each step: the ports it opens, the link a QR holds, the SSH it runs.
private val introPages = listOf(
    IntroPage("A mail server,\non your terms.", "Host Madmail for the people you invite.", "$ madmail --config madmail.conf run"),
    IntroPage("Runs on this phone.", "Over Wi-Fi, hotspot or VPN. No root needed.", "listening :11465 :11993 :18443"),
    IntroPage("Invite with a scan.", "Each guest scans their own QR in Delta Chat.", "dclogin://… · one QR per guest"),
    IntroPage("Or on your own server.", "Connect a VPS and manage it from here.", "ssh root@vps · systemctl status madmail"),
)

// One curve for every morph: quick start, long settle, no overshoot.
private val MorphBounds = BoundsTransform { _, _ -> tween(500, easing = Emphasized) }

@Composable
internal fun HostSetup(phone: Boolean, phoneConfigured: Boolean, initialName: String, firstRun: Boolean,
    onPhone: () -> Unit, onBack: () -> Unit, onCancel: (() -> Unit)?, onComplete: (String) -> Unit,
    onSaveRemote: (host: String, user: String, port: Int, apiEndpoint: String?, apiKey: String?) -> String?) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    var providers by rememberSaveable { mutableStateOf(false) }
    var providerId by rememberSaveable { mutableStateOf("") }
    // Kept across Back so picking the same provider again keeps an edited username.
    var lastProviderId by rememberSaveable { mutableStateOf("") }
    var existing by rememberSaveable { mutableStateOf(false) }
    // Which page of the remote or Admin API wizard is showing: 0 is the address, 1 the sign-in or key.
    var stage by rememberSaveable { mutableIntStateOf(0) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    // Drafts survive Back so nothing typed is lost; the API key is never written to saved state.
    var host by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("root") }
    var port by rememberSaveable { mutableStateOf("22") }
    var address by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val fields = remember { List(3) { FocusRequester() } }
    val pager = rememberPagerState { introPages.size }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val provider = remoteProviders.firstOrNull { it.id == providerId }
    val step = when {
        existing -> if (stage == 0) SetupStep.Existing else SetupStep.ApiKey
        provider != null -> if (stage == 0) SetupStep.Address else SetupStep.Account
        providers -> SetupStep.Providers; phone -> SetupStep.Phone; firstRun -> SetupStep.Intro; else -> SetupStep.Choose }
    fun tap() = haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
    fun back() {
        focus.clearFocus(); tried = false
        when (step) {
            SetupStep.Existing -> existing = false
            SetupStep.ApiKey, SetupStep.Account -> stage = 0
            SetupStep.Address -> providerId = ""
            SetupStep.Providers -> providers = false
            SetupStep.Phone -> onBack()
            SetupStep.Intro -> if (choosing) choosing = false else scope.launch { pager.animateScrollToPage(pager.currentPage - 1, animationSpec = tween(480, easing = Emphasized)) }
            SetupStep.Choose -> onCancel?.invoke()
        }
    }
    // Validation waits for the first tap on the action, then follows every keystroke. The action is never disabled.
    fun submit(errors: List<String?>, save: () -> Unit) {
        tried = true
        val first = errors.indexOfFirst { it != null }
        if (first >= 0) { haptics.performHapticFeedback(HapticFeedbackType.Reject); fields[first].requestFocus(); return }
        focus.clearFocus(); haptics.performHapticFeedback(HapticFeedbackType.Confirm); save()
    }
    val remoteErrors = if (provider == null) List(3) { null } else listOf(hostError(parseSshTarget(host.trim()).host), userError(user, provider), portError(port))
    val endpoint = apiEndpoint(address)
    val existingErrors = listOf(addressError(address, endpoint), keyError(apiKey))
    val addressNext = {
        // Typed rather than pasted "root@5.75.1.2" is understood on submit too, and prefills the next step.
        val opened = advanced
        val target = parseSshTarget(host.trim())
        host = target.host; target.user?.let { user = it }; target.port?.let { port = it.toString(); if (it != 22) advanced = true }
        val hostBad = hostError(host) != null; val portBad = portError(port) != null
        // A bad port hides under Advanced; open it first so the error is seen, and its field exists before it takes focus.
        if (portBad && !opened && !hostBad) { tried = true; advanced = true; haptics.performHapticFeedback(HapticFeedbackType.Reject) }
        else { if (portBad) advanced = true; submit(listOf(hostError(host), null, portError(port))) { tried = false; stage = 1 } }
    }
    val saveRemote = {
        submit(listOf(null, userError(user, provider ?: selfHosted), null)) {
            val number = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: run { stage = 0; return@submit }
            SetupProviders.remember(host.trim(), user.trim(), number, (provider ?: selfHosted).id)
            onSaveRemote(host.trim(), user.trim(), number, null, null)
        }
    }
    val existingNext = { submit(listOf(existingErrors[0], null)) { tried = false; stage = 1 } }
    val connect = {
        submit(listOf(null, existingErrors[1])) {
            val target = endpoint ?: run { stage = 0; return@submit }
            saveError = onSaveRemote(java.net.URI(target).host, "root", 22, target, apiKey)
            if (saveError != null) haptics.performHapticFeedback(HapticFeedbackType.Reject)
        }
    }
    val canGoBack = step != SetupStep.Choose && (step != SetupStep.Intro || choosing || pager.currentPage > 0)
    BackHandler(canGoBack) { back() }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) } }
    val choices: @Composable Morph.(Color) -> Unit = { fill ->
        ChoiceRows(phoneConfigured, fill,
            onPhone = { tap(); onPhone() },
            onRemote = { tap(); providers = true },
            onExisting = { tap(); tried = false; stage = 0; existing = true })
    }
    // Every step after the choice keeps the sheet's warm colour, so the sheet reads as having grown to fill the screen.
    val page by animateColorAsState(if (step == SetupStep.Intro) introColor() else panelColor(), tween(400, easing = Emphasized), label = "Setup page")
    val shift = with(LocalDensity.current) { 24.dp.roundToPx() }
    // The dots outlive the steps so they glide between them, and keep their last count while fading out.
    val current = step.wizard()
    var lastWizard by remember { mutableStateOf(0 to 3) }
    SideEffect { current?.let { lastWizard = it } }
    val dots = current ?: lastWizard
    val dotPosition by animateFloatAsState(dots.first.toFloat(), tween(320, easing = Emphasized), label = "Step dots")

    SharedTransitionLayout(Modifier.fillMaxSize().background(page)) { Box(Modifier.fillMaxSize()) {
        // Every screen draws its own action in the same slot, so nothing resizes between steps.
        // A focused field would otherwise swallow Back just to drop its focus, so the gesture would seem to do nothing.
        AnimatedContent(step, Modifier.fillMaxSize().systemBarsPadding().imePadding().onPreviewKeyEvent {
            if (!canGoBack || it.key != Key.Back) false else { if (it.type == KeyEventType.KeyUp) back(); true }
        }, label = "Onboarding step",
            transitionSpec = {
                // Wizard steps push sideways like the rest of the app; everything else keeps the morph's plain fade.
                if (initialState.wizard() != null && targetState.wizard() != null) {
                    val forward = targetState.ordinal > initialState.ordinal
                    (fadeIn(tween(260, 60, Emphasized)) + slideInHorizontally(tween(320, easing = Emphasized)) { if (forward) shift else -shift }) togetherWith
                        (fadeOut(tween(120, easing = Emphasized)) + slideOutHorizontally(tween(320, easing = Emphasized)) { if (forward) -shift else shift })
                } else fadeIn(tween(220)) togetherWith fadeOut(tween(100))
            }) { shown ->
            val morph = Morph(this@SharedTransitionLayout, this)
            when (shown) {
                SetupStep.Intro -> morph.Intro(pager, choosing, onOpen = { tap(); choosing = true }, onClose = { choosing = false }, choices = choices)
                SetupStep.Choose -> Column(Modifier.fillMaxSize().semantics { paneTitle = "Add a server" }) {
                    morph.TopBar(showLogo = true, showBack = false, onBack = ::back, onCancel = onCancel)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp)) {
                        morph.StepHeader("Add a server", "Choose where it runs.")
                        Spacer(Modifier.height(28.dp))
                        Box(Modifier.padding(horizontal = GroupGutter)) { morph.choices(groupColor()) }
                    }
                }
                SetupStep.Providers -> Column(Modifier.fillMaxSize().semantics { paneTitle = "Choose where your server runs" }) {
                    morph.TopBar(showLogo = false, showBack = true, onBack = ::back, onCancel = onCancel)
                    morph.ProvidersStep {
                        tap(); tried = false; stage = 0
                        if (it.id != lastProviderId) user = it.user
                        lastProviderId = it.id; providerId = it.id
                    }
                }
                SetupStep.Address -> Column(Modifier.fillMaxSize().semantics { paneTitle = "Server address" }) {
                    morph.TopBar(showLogo = false, showBack = true, onBack = ::back, onCancel = onCancel)
                    Box(Modifier.weight(1f)) {
                        morph.AddressStep(provider ?: selfHosted, host, port, advanced, if (tried) remoteErrors else List(3) { null }, fields,
                            onHost = { value ->
                                // A pasted "ssh root@203.0.113.5 -p 2222" fills the address, username and port. Typing stays literal so the field never rewrites mid-word.
                                if (value.length - host.length > 1) {
                                    val target = parseSshTarget(value)
                                    host = target.host; target.user?.let { user = it }; target.port?.let { port = it.toString(); if (it != 22) advanced = true }
                                } else host = value
                            },
                            onPort = { port = it.filter(Char::isDigit).take(5) }, onAdvanced = { advanced = !advanced }, onDone = addressNext)
                    }
                    morph.ActionSlot("Continue", addressNext)
                }
                SetupStep.Account -> Column(Modifier.fillMaxSize().semantics { paneTitle = "Sign in as" }) {
                    morph.TopBar(showLogo = false, showBack = true, onBack = ::back, onCancel = onCancel)
                    Box(Modifier.weight(1f)) {
                        morph.AccountStep(provider ?: selfHosted, user, if (tried) remoteErrors[1] else null, fields[1], onUser = { user = it.trim() }, onDone = saveRemote)
                    }
                    morph.ActionSlot("Continue", saveRemote)
                }
                SetupStep.Existing -> Column(Modifier.fillMaxSize().semantics { paneTitle = "Connect your Madmail" }) {
                    morph.TopBar(showLogo = false, showBack = true, onBack = ::back, onCancel = onCancel)
                    Box(Modifier.weight(1f)) {
                        morph.ExistingStep(address, endpoint, if (tried) existingErrors[0] else null, fields[0],
                            onAddress = { address = it.trim(); saveError = null }, onDone = existingNext)
                    }
                    morph.ActionSlot("Continue", existingNext)
                }
                SetupStep.ApiKey -> Column(Modifier.fillMaxSize().semantics { paneTitle = "Admin API key" }) {
                    morph.TopBar(showLogo = false, showBack = true, onBack = ::back, onCancel = onCancel)
                    Box(Modifier.weight(1f)) {
                        morph.ApiKeyStep(apiKey, saveError, if (tried) existingErrors[1] else null, fields[1],
                            onKey = { apiKey = it.trim(); saveError = null }, onDone = connect)
                    }
                    morph.ActionSlot("Connect", connect)
                }
                SetupStep.Phone -> Column(Modifier.fillMaxSize().semantics { paneTitle = "Host on this phone" }) {
                    morph.TopBar(showLogo = false, showBack = true, onBack = ::back, onCancel = onCancel)
                    Box(Modifier.weight(1f)) { morph.PhoneStep(name, fields[0], { name = it.take(80) }) { focus.clearFocus() } }
                    morph.ActionSlot(if (phoneConfigured) "Open phone server" else "Add this phone") {
                        focus.clearFocus(); haptics.performHapticFeedback(HapticFeedbackType.Confirm); onComplete(name.trim())
                    }
                }
            }
        }
        AnimatedVisibility(current != null, Modifier.align(Alignment.TopCenter).systemBarsPadding(), enter = fadeIn(tween(220, 80)), exit = fadeOut(tween(120))) {
            Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) { PageDots(dotPosition, dots.second, Modifier, "Step") }
        }
    } }
}

private class SshTarget(val host: String, val user: String?, val port: Int?)

/** Accepts what provider dashboards show: a bare address, user@host, host:port, or a full ssh command with its options. */
private fun parseSshTarget(raw: String): SshTarget {
    if (raw.none { it == '@' || it.isWhitespace() } && !Regex("^[^:]+:\\d+$").matches(raw)) return SshTarget(raw, null, null)
    val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
    if (parts.firstOrNull() == "ssh") parts.removeAt(0)
    var port: Int? = null
    var user: String? = null
    var target: String? = null
    var index = 0
    while (index < parts.size) {
        val part = parts[index++]
        if (!part.startsWith("-") || part.length < 2) { if (target == null) target = part; continue }
        val letter = part[1]
        if (letter !in SSH_VALUE_OPTIONS) continue
        // Options take their value attached (-p2222) or as the next word (-p 2222).
        val value = part.drop(2).ifEmpty { parts.getOrNull(index++) } ?: continue
        when (letter) { 'p' -> port = value.toIntOrNull() ?: port; 'l' -> user = value }
    }
    var host = target ?: ""
    if ('@' in host) { user = host.substringBefore('@').takeIf { it.isNotEmpty() } ?: user; host = host.substringAfter('@') }
    if (host.count { it == ':' } == 1) host.substringAfter(':').toIntOrNull()?.let { port = it; host = host.substringBefore(':') }
    return SshTarget(host, user, port)
}

/** ssh options that consume a value, so the value is never mistaken for the destination. */
private const val SSH_VALUE_OPTIONS = "BbcDEeFIiJLlmOoPpQRSWw"

private fun hostError(host: String) = when {
    host.isBlank() -> "Enter an IP address or hostname."
    host.trim().any { it.isWhitespace() || it in "/@;" } -> "Use just the address, like 203.0.113.5."
    else -> null
}

private fun userError(user: String, provider: RemoteProvider) = when {
    user.isBlank() -> if (provider.id == "gcp") "Enter your SSH key’s username." else "Enter a username."
    !user.matches(Regex("[a-zA-Z_][a-zA-Z0-9_-]*")) -> "Usernames use letters, numbers, - and _."
    else -> null
}

private fun portError(port: String) = if (port.toIntOrNull() in 1..65535) null else "Use a port from 1 to 65535."

/** A bare domain gets https:// and normalize adds /api/admin, so "mail.example.org" is enough. */
private fun apiEndpoint(address: String): String? {
    val value = address.trim().ifEmpty { return null }
    return org.madmail.mobile.engine.ApiEndpoint.normalize(if ("://" in value) value else "https://$value")
}

private fun addressError(address: String, endpoint: String?) = when {
    address.isBlank() -> "Enter your Madmail’s address."
    address.trim().startsWith("http://", ignoreCase = true) -> "Use an https:// address."
    endpoint == null -> "Enter an address like mail.example.org."
    else -> null
}

private fun keyError(key: String) = when {
    key.isBlank() -> "Paste the admin API key."
    key.length > 8192 -> "Key too long. Copy only the key."
    key.any { it.isISOControl() } -> "Key has invalid characters."
    else -> null
}

/** Shared-transition helpers bound to the step currently being shown. */
private class Morph(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope) {
    @Composable
    fun Modifier.element(key: String): Modifier = with(shared) {
        this@element.sharedElement(rememberSharedContentState(key), visibility, boundsTransform = MorphBounds)
    }

    @Composable
    fun Modifier.bounds(key: String): Modifier = with(shared) {
        this@bounds.sharedBounds(rememberSharedContentState(key), visibility, boundsTransform = MorphBounds,
            enter = fadeIn(tween(220, 80)), exit = fadeOut(tween(120)), resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds)
    }

    /** Content that is not shared settles in after the morph starts, in reading order. */
    fun Modifier.arrive(order: Int): Modifier = with(visibility) {
        this@arrive.animateEnterExit(
            enter = fadeIn(tween(260, 40 + order * 35)) + slideInVertically(tween(420, 40 + order * 35, ExpoOut)) { 24 },
            exit = fadeOut(tween(100)))
    }

    /** Fade only, no rise, timed to land after a flying mark has passed so the two never overlap. */
    fun Modifier.settle(delay: Int): Modifier = with(visibility) {
        this@settle.animateEnterExit(enter = fadeIn(tween(180, delay, LinearOutSlowInEasing)), exit = fadeOut(tween(100)))
    }
}

@Composable
private fun Morph.TopBar(showLogo: Boolean, showBack: Boolean, onBack: () -> Unit, onCancel: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp)) {
        if (showBack) IconButton(onClick = rememberTap(onBack), modifier = Modifier.align(Alignment.CenterStart).element("back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
        }
        if (showLogo) Image(painterResource(R.drawable.madmail_logo), null, Modifier.align(Alignment.Center).size(34.dp).element("logo"))
        onCancel?.let { TextButton(onClick = it, modifier = Modifier.align(Alignment.CenterEnd).element("cancel")) { Text("Cancel") } }
    }
}

@Composable
private fun Morph.ActionSlot(label: String, onClick: () -> Unit) {
    BrandButton(label, Modifier.padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp).bounds("action"), onClick = onClick)
}

@Composable
private fun Morph.Intro(pager: PagerState, choosing: Boolean, onOpen: () -> Unit, onClose: () -> Unit, choices: @Composable Morph.(Color) -> Unit) {
    val position = pager.currentPage + pager.currentPageOffsetFraction
    val outer = this
    val scope = rememberCoroutineScope()
    // One number drives the whole morph: 0 is the Get started button, 1 is the open sheet. A drag on the sheet scrubs it.
    val progress = remember { Animatable(if (choosing) 1f else 0f) }
    LaunchedEffect(choosing) { progress.animateTo(if (choosing) 1f else 0f, tween(if (choosing) 520 else 440, easing = Emphasized)) }
    val sheetShown by remember { derivedStateOf { progress.value > 0f } }
    val faceShown by remember { derivedStateOf { progress.value < 0.35f } }
    val sheetHeight = remember { intArrayOf(1) }
    val red = brandRed(); val panel = panelColor()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && !choosing) 0.97f else 1f, tween(if (pressed) 90 else 220, easing = Emphasized), label = "Press scale")
    val drag = rememberDraggableState { delta -> scope.launch { progress.snapTo((progress.value - delta / sheetHeight[0]).coerceIn(0f, 1f)) } }
    Box(Modifier.fillMaxSize()) {
        // The pages recede a little behind the sheet instead of moving.
        Column(Modifier.fillMaxSize().graphicsLayer { val s = 1f - 0.04f * progress.value; scaleX = s; scaleY = s }) {
            Spacer(Modifier.height(56.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !choosing) { index ->
                    val page = introPages[index]
                    // Each page decodes again when it settles, so swiping back replays it like a fresh screen.
                    val settled = pager.settledPage == index
                    var plays by remember { mutableIntStateOf(0) }
                    LaunchedEffect(settled) { if (settled) plays++ }
                    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(top = StageHeight + 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        DecodeText(page.title, plays, Modifier.semantics { heading() }, MaterialTheme.typography.displaySmall, red.copy(alpha = 0.85f),
                            TextAlign.Center, FontWeight.SemiBold, (-0.5).sp, 42.sp)
                        Spacer(Modifier.height(14.dp))
                        Text(page.body, Modifier.widthIn(max = 360.dp), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(18.dp))
                        TerminalLine(page.terminal, plays, Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            .padding(horizontal = 10.dp, vertical = 5.dp), MaterialTheme.typography.labelMedium, successText())
                    }
                }
                // Drawn over the pager without touch handling: swipes anywhere move the pages and the stage follows the finger.
                Stage(position, Modifier.fillMaxWidth().height(StageHeight))
            }
            PageDots(position, introPages.size, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp))
            Spacer(Modifier.height(ActionHeight + 24.dp))
        }
        // The dim reaches under the status and navigation bars, like a system sheet's.
        val bars = WindowInsets.systemBars
        Box(Modifier.layout { measurable, constraints ->
            val top = bars.getTop(this); val bottom = bars.getBottom(this)
            val dim = measurable.measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight + top + bottom))
            layout(constraints.maxWidth, constraints.maxHeight) { dim.place(0, -top) }
        }.drawBehind { drawRect(Color.Black.copy(alpha = 0.32f * progress.value)) }
            .then(if (choosing) Modifier.clickable(interactionSource = null, indication = null, onClick = onClose) else Modifier))
        // A single surface: its width, height, corners and colour are all functions of progress, so it never jumps between two layouts.
        Layout(listOf(
            {
                if (sheetShown) Column(Modifier.graphicsLayer { alpha = ((progress.value - 0.3f) / 0.7f).coerceIn(0f, 1f); translationY = (1f - progress.value) * 24.dp.toPx() }
                    .draggable(drag, Orientation.Vertical, enabled = choosing, onDragStopped = { velocity ->
                        if (progress.value < 0.75f || velocity > 1200f) onClose() else progress.animateTo(1f, tween(300, easing = Emphasized))
                    }).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(36.dp, 5.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)))
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 16.dp)) {
                        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("How will you host?", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose, role = Role.Button), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, "Close", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    outer.choices(groupColor())
                }
            },
            {
                if (faceShown) Box(Modifier.graphicsLayer { alpha = 1f - (progress.value / 0.3f).coerceIn(0f, 1f) }
                    .then(if (choosing) Modifier else Modifier.clickable(source, ripple(color = Color.White), role = Role.Button, onClick = onOpen)),
                    contentAlignment = Alignment.Center) {
                    Text("Get started", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(22.dp), tint = red)
                    }
                }
            },
        ), Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            .graphicsLayer {
                val p = progress.value
                shape = RoundedCornerShape(lerp(ActionHeight / 2, 34.dp, p)); clip = true
                shadowElevation = p * 24.dp.toPx(); scaleX = press; scaleY = press
            }
            // Red hands over to the sheet early so the in-between colour never lingers.
            .drawBehind { drawRect(lerp(red, panel, (progress.value / 0.45f).coerceIn(0f, 1f))) }) { (sheet, face), constraints ->
            val p = progress.value
            val full = constraints.maxWidth
            val sheetWidth = full - 24.dp.roundToPx(); val buttonWidth = full - 48.dp.roundToPx(); val action = ActionHeight.roundToPx()
            // The sheet is always measured at its final width, so its text never reflows while the surface grows around it.
            val content = sheet.firstOrNull()?.measure(Constraints.fixedWidth(sheetWidth))
            if (content != null) sheetHeight[0] = content.height
            val width = lerp(buttonWidth, sheetWidth, p); val height = lerp(action, maxOf(sheetHeight[0], action), p)
            val button = face.firstOrNull()?.measure(Constraints.fixed(width, action))
            layout(width, height) {
                content?.place((width - sheetWidth) / 2, 0)
                button?.place(0, height - action)
            }
        }
    }
}

private val StageHeight = 300.dp

private class StageFrame(val width: Float, val height: Float, val radius: Float, val logo: Float, val logoX: Float)

// Keyframes per intro page: the logo alone, a phone around it, a QR code built on it, a server it lives in.
private val stageFrames = listOf(
    StageFrame(132f, 132f, 66f, 132f, 0f),
    StageFrame(136f, 240f, 32f, 64f, 0f),
    StageFrame(212f, 212f, 28f, 46f, 0f),
    StageFrame(244f, 136f, 26f, 62f, -64f),
)

private const val EASTER_EGG = "https://madmail.chat/mobile"

// A real code: scanning the intro's QR opens the Madmail mobile page. High correction survives the logo in the middle.
private val easterEgg = Encoder.encode(EASTER_EGG, ErrorCorrectionLevel.H).matrix

@Composable
private fun Morph.Stage(position: Float, modifier: Modifier) {
    val index = floor(position).toInt().coerceIn(0, stageFrames.size - 2)
    val t = (position - index).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    val a = stageFrames[index]; val b = stageFrames[index + 1]
    fun near(page: Int) = (1f - abs(position - page)).coerceIn(0f, 1f)
    val bodyWidth = lerp(a.width, b.width, t); val bodyHeight = lerp(a.height, b.height, t)
    val radius = lerp(a.radius, b.radius, t)
    val logoSize = lerp(a.logo, b.logo, t); val logoX = lerp(a.logoX, b.logoX, t)
    val bodyAlpha = position.coerceIn(0f, 1f)
    val group = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val phone = near(1); val qr = near(2); val server = near(3)
    Box(modifier.semantics { if (qr > 0.9f) contentDescription = "QR code for $EASTER_EGG" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = bodyWidth.dp.toPx(); val h = bodyHeight.dp.toPx()
            val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2)
            val corner = CornerRadius(radius.dp.toPx())
            val fill = lerp(group, Color.White, qr)
            drawRoundRect(fill.copy(alpha = fill.alpha * bodyAlpha), topLeft, Size(w, h), corner)
            if (phone > 0f) {
                drawRoundRect(ink.copy(alpha = 0.85f * phone), topLeft, Size(w, h), corner, style = Stroke(2.5.dp.toPx()))
                val pill = 34.dp.toPx()
                drawRoundRect(ink.copy(alpha = 0.5f * phone), Offset(size.width / 2 - pill / 2, topLeft.y + 12.dp.toPx()), Size(pill, 5.dp.toPx()), CornerRadius(3.dp.toPx()))
                for (ring in 1..3) {
                    val r = (10 + ring * 11).dp.toPx()
                    drawArc(accent.copy(alpha = phone * (1f - ring * 0.2f)), 225f, 90f, false,
                        Offset(size.width / 2 - r, topLeft.y - 6.dp.toPx() - r), Size(r * 2, r * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            if (qr > 0f) {
                val grid = easterEgg.width; val quiet = 18.dp.toPx()
                val cell = (minOf(w, h) - quiet * 2) / grid
                val origin = Offset(size.width / 2 - cell * grid / 2, size.height / 2 - cell * grid / 2)
                val module = Color.Black.copy(alpha = qr)
                for (y in 0 until grid) for (x in 0 until grid) {
                    // Slight overlap avoids hairline seams between neighbouring modules.
                    if (easterEgg.get(x, y).toInt() == 1) drawRect(module, origin + Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
                val plate = (logoSize + 10).dp.toPx()
                drawRoundRect(Color.White.copy(alpha = qr), Offset(size.width / 2 - plate / 2, size.height / 2 - plate / 2), Size(plate, plate), CornerRadius(plate / 2))
            }
            if (server > 0f) {
                val left = size.width / 2 - 8.dp.toPx(); val right = topLeft.x + w - 20.dp.toPx()
                for ((row, offset) in listOf(-22, 22).withIndex()) {
                    val bar = 30.dp.toPx(); val top = size.height / 2 + offset.dp.toPx() - bar / 2
                    drawRoundRect(ink.copy(alpha = 0.08f * server), Offset(left, top), Size(right - left, bar), CornerRadius(10.dp.toPx()))
                    drawCircle(accent.copy(alpha = server * if (row == 0) 1f else 0.45f), 4.dp.toPx(), Offset(right - 14.dp.toPx(), top + bar / 2))
                    drawRoundRect(ink.copy(alpha = 0.18f * server), Offset(left + 12.dp.toPx(), top + bar / 2 - 2.dp.toPx()), Size((right - left) * 0.42f, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
            }
        }
        Image(painterResource(R.drawable.madmail_logo), "Madmail", Modifier.offset(x = logoX.dp).size(logoSize.dp).element("logo"))
    }
}

@Composable
private fun PageDots(position: Float, count: Int, modifier: Modifier, label: String = "Page") {
    val active = brandRed()
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Row(modifier.semantics { contentDescription = "$label ${(position + 0.5f).toInt() + 1} of $count" }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val weight = (1f - abs(position - index)).coerceIn(0f, 1f)
            Box(Modifier.size(lerp(6.dp, 22.dp, weight), 6.dp).clip(CircleShape).background(lerp(idle, active, weight)))
        }
    }
}

@Composable
private fun Morph.StepHeader(title: String, body: String) {
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.arrive(0).semantics { heading() }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
        Text(body, Modifier.arrive(1), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Colourful squircle marks, one hue per way of hosting, like a settings list. */
internal enum class HostKind(val top: Long, val bottom: Long, val icon: ImageVector) {
    Phone(0xFFFF5A4F, 0xFFD3141B, Icons.Rounded.PhoneAndroid),
    Remote(0xFF4C9BFF, 0xFF1A66E0, Icons.Rounded.Dns),
    Existing(0xFF3DD68C, 0xFF14A05A, Icons.Rounded.Link),
    Ssh(0xFF7A8699, 0xFF3B4352, Icons.Rounded.Terminal),
}

@Composable
internal fun HostIcon(kind: HostKind, modifier: Modifier = Modifier, size: Dp = 40.dp) = Squircle(kind.top, kind.bottom, kind.icon, modifier, size)

@Composable
private fun Morph.ChoiceRows(phoneConfigured: Boolean, fill: Color, onPhone: () -> Unit, onRemote: () -> Unit, onExisting: () -> Unit) {
    Group(fill = fill) {
        ChoiceRow({ HostIcon(HostKind.Phone, Modifier.element("mark-phone")) }, "Host on my phone",
            if (phoneConfigured) "Already set up" else "Wi-Fi, hotspot or VPN", onPhone)
        RowDivider()
        ChoiceRow({ HostIcon(HostKind.Remote, Modifier.element("mark-remote")) }, "Host on a remote server", "SSH, DigitalOcean, AWS and more", onRemote)
        RowDivider()
        ChoiceRow({ HostIcon(HostKind.Existing, Modifier.element("mark-existing")) }, "I already run Madmail", "Admin address and key", onExisting)
    }
}

/** A settings-style row: mark, title and one line, chevron. The mark is the shared element that flies into the next step. */
@Composable
private fun ChoiceRow(mark: @Composable () -> Unit, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 68.dp)
        .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        mark()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

/** The tapped choice's mark lands here; the words settle in just after it has passed, so it never crosses visible text. */
@Composable
private fun Morph.Hero(title: String, body: String, mark: @Composable () -> Unit, delay: Int = 330) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        mark()
        Spacer(Modifier.height(18.dp))
        Text(title, Modifier.settle(delay).semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
        Spacer(Modifier.height(8.dp))
        Text(body, Modifier.settle(delay + 40), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Shared page frame for every step after the choice: hero, then grouped content that arrives just after it.
 * Steps whose mark stays put (it was already there on the previous step) pass a short [delay].
 */
@Composable
private fun Morph.StepPage(title: String, body: String, mark: @Composable () -> Unit, delay: Int = 330, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
        Hero(title, body, mark, delay)
        Spacer(Modifier.height(28.dp))
        Column(Modifier.padding(horizontal = GroupGutter).arrive(3), content = content)
    }
}

@Composable
private fun Morph.ProvidersStep(onPick: (RemoteProvider) -> Unit) {
    StepPage("Where is your server?", "We’ll prefill the sign-in.", { HostIcon(HostKind.Remote, Modifier.element("mark-remote"), 56.dp) }) {
        Caption("Your own")
        Group { ChoiceRow({ ProviderMark(selfHosted, Modifier.element("mark-${selfHosted.id}")) }, selfHosted.name, selfHosted.note) { onPick(selfHosted) } }
        Spacer(Modifier.height(24.dp))
        Caption("Cloud providers")
        Group {
            cloudProviders.forEachIndexed { index, provider ->
                if (index > 0) RowDivider()
                ChoiceRow({ ProviderMark(provider, Modifier.element("mark-${provider.id}")) }, provider.name, provider.note) { onPick(provider) }
            }
        }
        Footer("Nothing is created or charged.")
    }
}

@Composable
internal fun ProviderMark(provider: RemoteProvider, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val logo = provider.logo
    if (logo != null) {
        // Brand marks fill their whole 24-unit box, unlike Material icons, so they sit a little smaller than the kit's glyphs.
        Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brush.verticalGradient(listOf(Color(provider.tint), Color(provider.tint2)))),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(logo), null, Modifier.size(size * 0.5f), tint = Color.White)
        }
        return
    }
    if (provider.monogram == null) return HostIcon(if (provider.apiOnly) HostKind.Existing else HostKind.Ssh, modifier, size)
    val shape = RoundedCornerShape(size * 0.28f)
    // A white tile (Google) needs a hairline to keep its edge on a white card.
    val edge = if (Color(provider.tint).luminance() > 0.8f) Modifier.border(0.5.dp, Color.Black.copy(alpha = 0.1f), shape) else Modifier
    Box(modifier.size(size).clip(shape).background(Brush.verticalGradient(listOf(Color(provider.tint), Color(provider.tint2)))).then(edge),
        contentAlignment = Alignment.Center) {
        Text(provider.monogram, color = Color(provider.ink), fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
            fontSize = (size.value * if (provider.monogram.length > 2) 0.34f else 0.4f).sp)
    }
}

/** Step 2 of a remote server: one address field; the port waits under Advanced. */
@Composable
private fun Morph.AddressStep(provider: RemoteProvider, host: String, port: String, advanced: Boolean, errors: List<String?>, fields: List<FocusRequester>,
    onHost: (String) -> Unit, onPort: (String) -> Unit, onAdvanced: () -> Unit, onDone: () -> Unit) {
    StepPage("Server address", provider.hint, { ProviderMark(provider, Modifier.element("mark-${provider.id}"), 56.dp) }) {
        Group { FieldRow("Address", host, onHost, "203.0.113.5", fields[0], fieldKeyboard(KeyboardType.Uri, ImeAction.Done), onDone, errors[0]) }
        Footer("Pasting an SSH command works too.")
        Spacer(Modifier.height(16.dp))
        Disclosure("Advanced", advanced, onAdvanced)
        AnimatedVisibility(advanced, enter = fadeIn(tween(200, 60)) + expandVertically(tween(320, easing = Emphasized)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(260, easing = Emphasized))) {
            Group { FieldRow("Port", port, onPort, "22", fields[2], fieldKeyboard(KeyboardType.Number, ImeAction.Done), onDone, errors[2]) }
        }
    }
}

/** Step 3 of a remote server: the account to sign in as, prefilled from the provider. */
@Composable
private fun Morph.AccountStep(provider: RemoteProvider, user: String, error: String?, field: FocusRequester, onUser: (String) -> Unit, onDone: () -> Unit) {
    StepPage("Sign in as", provider.userHint, { ProviderMark(provider, Modifier.element("mark-${provider.id}"), 56.dp) }, delay = 60) {
        Group {
            FieldRow("Username", user, onUser, if (provider.id == "gcp") "your-username" else "root", field, fieldKeyboard(KeyboardType.Ascii, ImeAction.Done), onDone, error)
        }
        Footer("Next, choose a key or password.")
    }
}

@Composable
private fun Morph.ExistingStep(address: String, endpoint: String?, error: String?, field: FocusRequester, onAddress: (String) -> Unit, onDone: () -> Unit) {
    StepPage("Connect your Madmail", "Its address, like mail.example.org.", { HostIcon(HostKind.Existing, Modifier.element("mark-existing"), 56.dp) }) {
        Group { FieldRow("Address", address, onAddress, "mail.example.org", field, fieldKeyboard(KeyboardType.Uri, ImeAction.Done), onDone, error) }
        if (error == null) endpoint?.let { Footer("Connects to $it") }
    }
}

@Composable
private fun Morph.ApiKeyStep(key: String, saveError: String?, error: String?, field: FocusRequester, onKey: (String) -> Unit, onDone: () -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    StepPage("Admin API key", "Stored encrypted on this phone.", { HostIcon(HostKind.Existing, Modifier.element("mark-existing"), 56.dp) }, delay = 60) {
        Group {
            FieldRow("Admin key", key, onKey, "Paste the key", field, fieldKeyboard(KeyboardType.Password, ImeAction.Done), onDone, error, secret = !reveal) {
                if (key.isEmpty()) TextButton(onClick = { clipboard.getText()?.text?.let(onKey) }) { Text("Paste") }
                else IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "Hide key" else "Show key",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        saveError?.let { Footer(it, MaterialTheme.colorScheme.error) }
    }
}

/** A quiet "Advanced" toggle that opens extra fields below it. */
@Composable
private fun Disclosure(label: String, open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(260, easing = Emphasized), label = "Disclosure")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = if (open) "Hide $label" else "Show $label", role = Role.Button, onClick = rememberTap(onToggle))
        .heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp).rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Morph.PhoneStep(name: String, field: FocusRequester, onName: (String) -> Unit, onDone: () -> Unit) {
    val access = rememberBackgroundAccess()
    StepPage("Make this phone your server", "Guests connect from their own phones.", { HostIcon(HostKind.Phone, Modifier.element("mark-phone"), 56.dp) }) {
        Group {
            FieldRow("Name", name, onName, "This phone", field, fieldKeyboard(KeyboardType.Text, ImeAction.Done, KeyboardCapitalization.Words), onDone,
                trailing = if (name.isEmpty()) null else ({
                    IconButton(onClick = { onName("") }) { Icon(Icons.Rounded.Cancel, "Clear name", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
                }))
        }
        Footer("Only shown in this app.")
        Spacer(Modifier.height(24.dp))
        Caption("Before you start")
        Group {
            val networks by rememberGuestNetworks()
            val shared = networks?.firstOrNull()
            val context = LocalContext.current
            // Read live, so joining Wi-Fi from the panel turns the row green without leaving the step.
            InfoRow(if (networks?.isEmpty() == true) Tint.Orange else Tint.Blue, if (networks?.isEmpty() == true) Icons.Rounded.WifiOff else Icons.Rounded.Wifi, "Share a network",
                when {
                    shared != null -> "On ${shared.network.label}. Guests join the same one."
                    networks == null -> "Same Wi-Fi, hotspot or VPN as guests."
                    else -> "Mobile data won’t work. Join Wi-Fi, a hotspot or a VPN."
                }) {
                Crossfade(networks?.isNotEmpty(), label = "Network status") { connected ->
                    when (connected) {
                        true -> Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = Success)
                            Text("Connected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        false -> Box(Modifier.minimumInteractiveComponentSize().clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(role = Role.Button, onClick = { context.openWifiSettings() }).padding(horizontal = 14.dp, vertical = 7.dp)) {
                            Text("Wi-Fi", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                        }
                        null -> Unit
                    }
                }
            }
            RowDivider(64.dp)
            InfoRow(Tint.Green, Icons.Rounded.BatteryFull, "Keep it running",
                if (access.allowed) "Stays reachable with the screen off." else "Lets guests reach it with the screen off. Uses more battery.") {
                if (access.allowed) Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = Color(0xFF14A05A))
                    Text("Allowed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Box(Modifier.minimumInteractiveComponentSize().clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(role = Role.Button, onClick = access.request).padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text("Allow", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        access.error?.let { Footer(it, MaterialTheme.colorScheme.error) }
        Footer("Start hosting from Home.")
    }
}

/** The intro is lighter than the panel so the choice sheet reads as rising over it. */
@Composable
private fun introColor() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF141212) else Color(0xFFFCFAF9)
