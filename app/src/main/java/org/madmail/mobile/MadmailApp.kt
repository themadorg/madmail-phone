package org.madmail.mobile

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import org.madmail.mobile.core.ServerIdentityStore
import org.madmail.mobile.core.ServerWorkspace
import org.madmail.mobile.core.ServerProfiles
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MadmailApp() {
    var openHostingPreferences by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var editingServer by remember { mutableStateOf(false) }
    var chooseServer by rememberSaveable { mutableStateOf(false) }
    var setup by rememberSaveable { mutableStateOf(false) }
    var phoneSetup by rememberSaveable { mutableStateOf(false) }
    var addServer by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles = remember { ServerProfiles(context) }
    var remotes by remember { mutableStateOf(profiles.load()) }
    var selectedId by remember { mutableStateOf(profiles.selected()) }
    var phoneConfigured by remember { mutableStateOf(profiles.phoneConfigured()) }
    val remote = remotes.find { it.id == selectedId }
    val serverId = remote?.id ?: "phone"
    val identityStore = remember { ServerIdentityStore(context) }
    var identities by remember { mutableStateOf((listOf("phone") + remotes.map { it.id }).associateWith { identityStore.read(it) }) }
    val identity = identities[serverId] ?: org.madmail.mobile.core.ServerIdentity()

    val workspace = remember { ServerWorkspace() }
    val selectServer: (String) -> Unit = { selectedId = it; profiles.select(it) }

    val haze = remember { HazeState() }

    val colors = MaterialTheme.colorScheme
    BackHandler(selectedId.isNotBlank() && page != 0 && !setup && !chooseServer && !addServer) { page = 0; openHostingPreferences = false }
    BackHandler(phoneSetup || setup) { if (phoneSetup) phoneSetup = false else setup = false }
    Surface(color = colors.background) {
        Box(Modifier.fillMaxSize()) {
            if (selectedId.isBlank() || setup) {
                HostSetup(phoneSetup, phoneConfigured, identities["phone"]?.name.orEmpty(), onPhone = { phoneSetup = true }, onRemote = { addServer = true },
                    onBack = { phoneSetup = false }, onCancel = if (selectedId.isNotBlank()) ({ setup = false; phoneSetup = false }) else null,
                    onComplete = { name ->
                        val updated = (identities["phone"] ?: org.madmail.mobile.core.ServerIdentity()).copy(name = name)
                        identityStore.save("phone", updated)
                        identities = identities + ("phone" to updated)
                        profiles.configurePhone(); phoneConfigured = true; selectServer("phone"); setup = false; phoneSetup = false; page = 0 })
            } else {
                AnimatedContent(targetState = serverId to page, transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) + slideInHorizontally(tween(280)) { if (targetState.second >= initialState.second) it / 12 else -it / 12 }) togetherWith fadeOut(tween(120))
                }, label = "Workspace navigation") { destination ->
                    val displayedPage = destination.second
                    val displayedId = destination.first
                    val displayedRemote = remotes.find { it.id == displayedId }
                    val displayedIdentity = identities[displayedId] ?: org.madmail.mobile.core.ServerIdentity()
                    val displayedName = displayedIdentity.name.ifBlank { displayedRemote?.host ?: "This phone" }
                    val displayedScroll = remember(destination) { androidx.compose.foundation.ScrollState(0) }
                    Column(Modifier.fillMaxSize().statusBarsPadding().hazeSource(haze).verticalScroll(displayedScroll)
                        .padding(horizontal = 24.dp).padding(top = 28.dp, bottom = 140.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        if (displayedPage == 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(listOf("Overview", "Invite", "Settings")[displayedPage], modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                            if (displayedPage == 1) Text(displayedName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                        key(displayedId) {

                        when (displayedPage) {

                            0 -> {
                                OverviewScreen(displayedName, displayedIdentity, displayedRemote, onSettings = { openHostingPreferences = displayedRemote == null; page = 2 }) {
                                    if (displayedRemote == null) RegistrationScreen(displayedId, displayedName, null, workspace.invitation(displayedId)) { workspace.updateInvitation(displayedId, it) }
                                    else TextButton(onClick = { page = 1 }) { Icon(Icons.Outlined.QrCode2, null); Spacer(Modifier.width(8.dp)); Text("Connect another device") }
                                }
                            }
                            1 -> RegistrationScreen(displayedId, displayedName, displayedRemote, workspace.invitation(displayedId)) { workspace.updateInvitation(displayedId, it) }
                            else -> ServerSettingsScreen(displayedId, displayedName, displayedRemote, initiallyShowHosting = openHostingPreferences, onNavigate = { scope.launch { displayedScroll.scrollTo(0) } }) {
                                displayedRemote?.let { saved -> scope.launch(Dispatchers.IO) { org.madmail.mobile.engine.RemoteEngine.get(context, saved).disconnect() } }
                                workspace.forget(displayedId)
                                org.madmail.mobile.engine.InvitationCreation.forget(displayedId)
                                org.madmail.mobile.engine.RemoteCredentials(context, displayedId).delete()
                                context.getSharedPreferences("ssh-host-keys", android.content.Context.MODE_PRIVATE).edit().remove(displayedId).apply()
                                context.getSharedPreferences("remote-options-$displayedId", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                                profiles.remove(displayedId)
                                remotes = profiles.load()
                                selectedId = profiles.selected()
                            }

                        }

                        }
                    }
                }
                GlassDock(haze, page, chooseServer, identity.mark, remote == null, { openHostingPreferences = false; page = it }, { chooseServer = true }, { setup = true }, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
    if (chooseServer) ModalBottomSheet(onDismissRequest = { if (!editingServer) chooseServer = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !editingServer })) {
        ServerPicker(serverId, remotes, identities, phoneConfigured, { selectServer(it); page = 0; openHostingPreferences = false; chooseServer = false }, { chooseServer = false; setup = true }, { id, updated ->
            identityStore.save(id, updated)
            identities = identities + (id to updated)
        }, onEditing = { editingServer = it })

    }
    if (addServer) ModalBottomSheet(onDismissRequest = { addServer = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        RemoteSetup(onClose = { addServer = false }, onSave = { host, user, port, apiEndpoint ->
            val saved = profiles.save(host, user, port)
            context.getSharedPreferences("remote-options-${saved.id}", android.content.Context.MODE_PRIVATE).edit().putString("preferred", if (apiEndpoint == null) "ssh" else "api").apply { if (apiEndpoint != null) putString("api-endpoint", apiEndpoint) }.apply()
            remotes = profiles.load()
            selectServer(saved.id)
            page = 0
            addServer = false
            setup = false
            phoneSetup = false
        })
    }
}

@Composable
private fun GlassDock(haze: HazeState, page: Int, choosingServer: Boolean, serverMark: String, phone: Boolean, onSelect: (Int) -> Unit, onServers: () -> Unit, onAdd: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(36.dp)
    val glass = HazeStyle(backgroundColor = colors.surface, tint = HazeTint(colors.surface.copy(alpha = .78f)), blurRadius = 24.dp, noiseFactor = .015f, fallbackTint = HazeTint(colors.surface.copy(alpha = .97f)))
    val edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .30f else .85f), colors.outline.copy(alpha = .12f)))
    Row(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).clip(shape).hazeEffect(haze, glass).border(1.dp, edge, shape).padding(6.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            val icons = listOf(Icons.Outlined.Home, Icons.Outlined.QrCode2, Icons.Outlined.Settings, Icons.Outlined.Dns)
            listOf("Overview", "Invite", "Settings", "Server").forEachIndexed { index, label ->
                val selected = if (index == 3) choosingServer else page == index
                val fill by animateColorAsState(if (selected) colors.primary.copy(alpha = .14f) else Color.Transparent, label = "Dock selection")
                Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(fill)
                    .selectable(selected, role = if (index == 3) Role.Button else Role.Tab, onClick = { if (index == 3) onServers() else onSelect(index) }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (index == 3) Box(Modifier.semantics { contentDescription = "Switch server" }) { ServerMark(serverMark, phone, compact = true) }
                    else Icon(icons[index], label, Modifier.size(24.dp), tint = if (selected) colors.primary else colors.onSurfaceVariant)

                    if (index == 3) {
                        Spacer(Modifier.width(3.dp))
                        Icon(if (choosingServer) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                    }
                }

            }

        }
        FilledIconButton(onClick = onAdd, modifier = Modifier.size(52.dp).clip(CircleShape).hazeEffect(haze, glass).border(1.dp, edge, CircleShape), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent, contentColor = colors.primary)) {
            Icon(Icons.Outlined.Add, "Set up a server", Modifier.size(28.dp))
        }
    }
}

@Composable
private fun RemoteSetup(onClose: () -> Unit, onSave: (String, String, Int, String?) -> Unit) {
    var apiOnly by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("root") }
    var port by rememberSaveable { mutableStateOf("22") }
    var review by rememberSaveable { mutableStateOf(false) }
    val apiAddress = org.madmail.mobile.engine.ApiEndpoint.normalize(host)
    val valid = if (apiOnly) apiAddress != null else host.isNotBlank() && host.none { it.isWhitespace() || it in "/@;" } && user.matches(Regex("[a-zA-Z_][a-zA-Z0-9_-]*")) && port.toIntOrNull() in 1..65535
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(Icons.Outlined.Dns, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Set up a remote server", style = MaterialTheme.typography.headlineSmall)
        Text("Connect with an SSH key, password, or admin API token.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!apiOnly, { apiOnly = false; review = false }, label = { Text("SSH") })
            FilterChip(apiOnly, { apiOnly = true; review = false }, label = { Text("Admin API") })
        }
        OutlinedTextField(host, { host = it; review = false }, label = { Text(if (apiOnly) "Admin API URL" else "Server address") }, placeholder = { Text(if (apiOnly) "https://mail.example.org/api/admin" else "IP address or hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (!apiOnly) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(user, { user = it; review = false }, label = { Text("SSH user") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(port, { port = it; review = false }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(96.dp))
        }
        Text("You’ll verify the server identity, choose authentication, and review the installation before any changes are made.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (review) {
            Text("Connection details", style = MaterialTheme.typography.titleMedium)
            Text(if (apiOnly) apiAddress.orEmpty() else "$user@$host · port $port")
            Text("Next, generate or import an SSH key, or connect with the admin API.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { review = true }, enabled = valid && !review, modifier = Modifier.fillMaxWidth()) { Text("Review details") }
        if (review && valid) Button(onClick = { onSave(if (apiOnly) java.net.URI(apiAddress!!).host else host.trim(), if (apiOnly) "root" else user.trim(), if (apiOnly) 22 else port.toInt(), if (apiOnly) apiAddress else null) }, modifier = Modifier.fillMaxWidth()) { Text("Save server") }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Done") }
    }
}
