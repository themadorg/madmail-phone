package org.madmail.mobile

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.text.style.TextOverflow
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
    BackHandler(selectedId.isNotBlank() && page != 0 && !setup && !chooseServer) { page = 0; openHostingPreferences = false }
    BackHandler(phoneSetup || setup) { if (phoneSetup) phoneSetup = false else setup = false }
    Surface(color = colors.background) {
        Box(Modifier.fillMaxSize()) {
            if (selectedId.isBlank() || setup) {
                HostSetup(phoneSetup, phoneConfigured, identities["phone"]?.name.orEmpty(), firstRun = selectedId.isBlank(), onPhone = { phoneSetup = true },
                    onBack = { phoneSetup = false }, onCancel = if (selectedId.isNotBlank()) ({ setup = false; phoneSetup = false }) else null,
                    onComplete = { name ->
                        val updated = (identities["phone"] ?: org.madmail.mobile.core.ServerIdentity()).copy(name = name)
                        identityStore.save("phone", updated)
                        identities = identities + ("phone" to updated)
                        profiles.configurePhone(); phoneConfigured = true; selectServer("phone"); setup = false; phoneSetup = false; page = 0 },
                    onSaveRemote = { host, user, port, apiEndpoint, apiKey ->
                        // Saving an address that is already listed reuses that server; only a newly created one is rolled back.
                        val existed = profiles.load().any { it.host.equals(host, ignoreCase = true) && it.user == user && it.port == port }
                        val saved = profiles.save(host, user, port)
                        // Stored encrypted; the connection sheet reuses it when the endpoint matches. A failed write leaves no half-saved server.
                        if (apiEndpoint != null && apiKey != null) runCatching { org.madmail.mobile.engine.RemoteCredentials(context, saved.id).saveApi(apiEndpoint, apiKey) }
                            .onFailure { if (!existed) profiles.remove(saved.id); return@HostSetup "Couldn’t save the key. Try again." }
                        context.getSharedPreferences("remote-options-${saved.id}", android.content.Context.MODE_PRIVATE).edit().putString("preferred", if (apiEndpoint == null) "ssh" else "api").apply { if (apiEndpoint != null) putString("api-endpoint", apiEndpoint) }.apply()
                        // Verification and sign-in follow straight on instead of leaving the user on an unconnected Overview.
                        PendingConnect.request(saved.id)
                        remotes = profiles.load()
                        selectServer(saved.id)
                        page = 0; setup = false; phoneSetup = false
                        null
                    })
            } else {
                AnimatedContent(targetState = serverId to page, transitionSpec = {
                    val forward = targetState.second >= initialState.second
                    (fadeIn(tween(260, delayMillis = 60, easing = Emphasized)) + slideInHorizontally(tween(320, easing = Emphasized)) { if (forward) 24 else -24 }) togetherWith
                        fadeOut(tween(120, easing = Emphasized))
                }, label = "Workspace navigation") { destination ->
                    val displayedPage = destination.second
                    val displayedId = destination.first
                    val displayedRemote = remotes.find { it.id == displayedId }
                    val displayedIdentity = identities[displayedId] ?: org.madmail.mobile.core.ServerIdentity()
                    val displayedName = displayedIdentity.name.ifBlank { displayedRemote?.host ?: "This phone" }
                    val displayedScroll = remember(destination) { androidx.compose.foundation.ScrollState(0) }
                    Column(Modifier.fillMaxSize().statusBarsPadding().hazeSource(haze).verticalScroll(displayedScroll)
                        .padding(horizontal = GroupGutter).padding(top = 28.dp, bottom = 140.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        if (displayedPage == 1) PageTitle("Invite", trailing = {
                            Text(displayedName, Modifier.widthIn(max = 160.dp).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        })
                        key(displayedId) {
                        // Finish cleaning up account QRs from earlier sessions without waiting for a visit to Invite.
                        LaunchedEffect(Unit) { org.madmail.mobile.engine.AccountQrRotation.get(context, displayedId, displayedRemote).tidy() }

                        when (displayedPage) {

                            0 -> {
                                OverviewScreen(displayedName, displayedIdentity, displayedRemote, onSettings = { openHostingPreferences = displayedRemote == null; page = 2 }) {
                                    // Invitations and account QRs live on the Invite page; Overview only links to it.
                                    Column {
                                        Caption("Invite")
                                        Group { NavRow({ TintIcon(Tint.Purple, Icons.Rounded.QrCode2) }, "Connect another device") { page = 1 } }
                                    }
                                }
                            }
                            1 -> RegistrationScreen(displayedId, displayedName, displayedRemote, workspace.invitation(displayedId)) { workspace.updateInvitation(displayedId, it) }
                            else -> ServerSettingsScreen(displayedId, displayedName, displayedRemote, initiallyShowHosting = openHostingPreferences, onNavigate = { scope.launch { displayedScroll.scrollTo(0) } }) {
                                // Unused account QRs are deleted first, while the server can still be reached.
                                val cleanup = org.madmail.mobile.engine.AccountQrRotation.forget(context, displayedId, displayedRemote)
                                displayedRemote?.let { saved -> scope.launch(Dispatchers.IO) { cleanup.join(); org.madmail.mobile.engine.RemoteEngine.get(context, saved).disconnect() } }
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
    if (chooseServer) KitSheet(onDismiss = { if (!editingServer) chooseServer = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !editingServer })) {
        ServerPicker(serverId, remotes, identities, phoneConfigured, { selectServer(it); page = 0; openHostingPreferences = false; chooseServer = false }, { chooseServer = false; setup = true }, { id, updated ->
            identityStore.save(id, updated)
            identities = identities + (id to updated)
        }, onEditing = { editingServer = it })
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
            val icons = listOf(Icons.Rounded.Home, Icons.Rounded.QrCode2, Icons.Rounded.Settings)
            listOf("Overview", "Invite", "Settings", "Server").forEachIndexed { index, label ->
                val selected = if (index == 3) choosingServer else page == index
                val fill by animateColorAsState(if (selected) colors.primary.copy(alpha = .12f) else Color.Transparent, tween(260, easing = Emphasized), label = "Dock selection")
                val tint by animateColorAsState(if (selected) colors.primary else colors.onSurfaceVariant, tween(260, easing = Emphasized), label = "Dock icon")
                Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(fill)
                    .selectable(selected, role = if (index == 3) Role.Button else Role.Tab, onClick = { if (index == 3) onServers() else onSelect(index) }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (index == 3) {
                        Box(Modifier.semantics { contentDescription = "Switch server" }) { ServerMark(serverMark, phone, compact = true) }
                        Spacer(Modifier.width(3.dp))
                        Icon(if (choosingServer) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = tint)
                    } else Icon(icons[index], label, Modifier.size(24.dp), tint = tint)
                }
            }
        }
        Box(Modifier.size(52.dp).clip(CircleShape).hazeEffect(haze, glass).border(1.dp, edge, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Set up a server", onClick = onAdd), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Set up a server", Modifier.size(28.dp), tint = brandRed())
        }
    }
}
