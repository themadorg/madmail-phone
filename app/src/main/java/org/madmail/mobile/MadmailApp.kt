package org.madmail.mobile

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.blur.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MadmailApp() {
    var openHostingPreferences by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var editingServer by remember { mutableStateOf(false) }
    var chooseServer by rememberSaveable { mutableStateOf(false) }
    var setup by rememberSaveable { mutableStateOf(false) }
    var phoneSetup by rememberSaveable { mutableStateOf(false) }
    var debug by remember { mutableStateOf(false) }
    // Set from the debug menu to walk the first-run pages again without clearing the phone's server.
    var replayIntro by remember { mutableStateOf(false) }
    LaunchedEffect(setup) { if (!setup) replayIntro = false }
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val thisPhone = stringResource(R.string.common_this_phone)
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
    val toaster = LocalToaster.current
    val selectServer: (String) -> Unit = { selectedId = it; profiles.select(it) }
    val removeServer: (String) -> Unit = { id ->
        val saved = remotes.firstOrNull { it.id == id }
        // Unused account QRs are deleted first, while the server can still be reached.
        val cleanup = org.madmail.mobile.engine.AccountQrRotation.forget(context, id, saved)
        saved?.let { scope.launch(Dispatchers.IO) { cleanup.join(); org.madmail.mobile.engine.RemoteEngine.get(context, it).disconnect() } }
        workspace.forget(id)
        org.madmail.mobile.engine.InvitationCreation.forget(id)
        org.madmail.mobile.engine.SettingsController.forget(id)
        org.madmail.mobile.engine.RemoteCredentials(context, id).delete()
        context.getSharedPreferences("ssh-host-keys", android.content.Context.MODE_PRIVATE).edit { remove(id) }
        context.getSharedPreferences("remote-options-$id", android.content.Context.MODE_PRIVATE).edit { clear() }
        profiles.remove(id)
        remotes = profiles.load()
        selectedId = profiles.selected()
    }

    val haze = remember { HazeState() }
    val localState by org.madmail.mobile.engine.LocalEngine.get(context).state.collectAsState()
    val remoteState = remote?.let { key(it.id) { org.madmail.mobile.engine.RemoteEngine.get(context, it).state.collectAsState().value } }
    val power = if (remoteState == null) localState.phase.power() else when {
        remoteState.status.startsWith("Running") -> PowerPhase.On; remoteState.error != null -> PowerPhase.Failed; else -> PowerPhase.Off
    }

    val colors = MaterialTheme.colorScheme
    BackHandler(selectedId.isNotBlank() && page != 0 && !setup && !chooseServer) { page = 0; openHostingPreferences = false }
    BackHandler(phoneSetup || setup) { if (phoneSetup) phoneSetup = false else { setup = false; replayIntro = false } }
    Surface(color = colors.background) {
        Box(Modifier.fillMaxSize()) {
            if (selectedId.isBlank() || setup) {
                HostSetup(phoneSetup, phoneConfigured, identities["phone"]?.name.orEmpty(), firstRun = selectedId.isBlank() || replayIntro, onPhone = { phoneSetup = true },
                    onBack = { phoneSetup = false }, onCancel = if (selectedId.isNotBlank()) ({ setup = false; phoneSetup = false; replayIntro = false }) else null,
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
                            .onFailure { if (!existed) profiles.remove(saved.id); return@HostSetup resources.getString(R.string.common_error_save_key) }
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
                    val displayedName = displayedIdentity.name.ifBlank { displayedRemote?.host ?: thisPhone }
                    val displayedScroll = remember(destination) { androidx.compose.foundation.ScrollState(0) }
                    Column(Modifier.fillMaxSize().statusBarsPadding().hazeSource(haze).verticalScroll(displayedScroll)
                        .padding(horizontal = GroupGutter).padding(top = 28.dp, bottom = 140.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        // Same shape as Settings: the page is the title, the server rides under it.
                        if (displayedPage == 1) PageTitle(stringResource(R.string.common_statistics), displayedName)
                        if (displayedPage == 2) PageTitle(stringResource(R.string.common_invite), displayedName)
                        key(displayedId) {
                        // Finish cleaning up account QRs from earlier sessions without waiting for a visit to Invite.
                        LaunchedEffect(Unit) { org.madmail.mobile.engine.AccountQrRotation.get(context, displayedId, displayedRemote).tidy() }

                        when (displayedPage) {

                            0 -> {
                                OverviewScreen(displayedName, displayedIdentity, displayedRemote, onSettings = { openHostingPreferences = displayedRemote == null; page = 3 }) {
                                    // Invitations and account QRs live on the Invite page; Overview only links to it.
                                    Column {
                                        Caption(stringResource(R.string.common_invite))
                                        Group { NavRow({ TintIcon(Tint.Purple, Icons.Rounded.QrCode2) }, stringResource(R.string.common_connect_another_device)) { page = 2 } }
                                    }
                                }
                            }
                            1 -> StatisticsScreen(displayedRemote) { page = 0 }
                            2 -> RegistrationScreen(displayedId, displayedName, displayedRemote, workspace.invitation(displayedId)) { workspace.updateInvitation(displayedId, it) }
                            else -> ServerSettingsScreen(displayedId, displayedName, displayedRemote, initiallyShowHosting = openHostingPreferences, onNavigate = { scope.launch { displayedScroll.scrollTo(0) } }) {
                                removeServer(displayedId)
                            }

                        }

                        }
                    }
                }
                GlassDock(haze, page, identity.name.ifBlank { remote?.host ?: thisPhone }, identity.mark, remote == null, power, { openHostingPreferences = false; page = it }, { chooseServer = true }, { debug = true }, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
    if (chooseServer) KitSheet(onDismiss = { if (!editingServer) chooseServer = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !editingServer })) {
        ServerPicker(serverId, remotes, identities, phoneConfigured, { selectServer(it); page = 0; openHostingPreferences = false; chooseServer = false }, { chooseServer = false; setup = true }, { id, updated ->
            identityStore.save(id, updated)
            identities = identities + (id to updated)
        }, onEditing = { editingServer = it }, onOpen = { id, target -> selectServer(id); page = target; openHostingPreferences = false; chooseServer = false },
            onRemove = { removeServer(it); toaster.show(resources.getString(R.string.common_server_removed)); if (selectedId.isBlank()) chooseServer = false })
    }
    if (debug) KitSheet(onDismiss = { debug = false }) { DebugMenu(serverId, remote) { debug = false; replayIntro = true; setup = true } }
}

/** The tab bar: three labelled tabs under one sliding lozenge, and the current server beside them with its live state. */
@Composable
private fun GlassDock(haze: HazeState, page: Int, serverName: String, serverMark: String, phone: Boolean, power: PowerPhase, onSelect: (Int) -> Unit,
    onServers: () -> Unit, onDebug: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val haptics = LocalHapticFeedback.current
    val glass = HazeBlurStyle {
        backgroundColor(colors.surface)
        colorEffects(listOf(HazeColorEffect.tint(colors.surface.copy(alpha = .78f))))
        blurRadius(24.dp)
        noiseFactor(.015f)
        fallbackColorEffect(HazeColorEffect.tint(colors.surface.copy(alpha = .97f)))
    }
    val edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .30f else .85f), colors.outline.copy(alpha = .12f)))
    Row(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.weight(1f).height(DockHeight).clip(CircleShape).hazeBlur(HazeInput.Sources(haze), glass).border(1.dp, edge, CircleShape).padding(5.dp)) {
            val tab = maxWidth / DockTabs.size
            // One lozenge travels between tabs, so a tab change reads as movement instead of two fades.
            val slide by animateDpAsState(tab * page, spring(dampingRatio = 0.78f, stiffness = 420f), label = "Dock indicator")
            Box(Modifier.offset { IntOffset(slide.roundToPx(), 0) }.width(tab).fillMaxHeight().clip(CircleShape).background(colors.onSurface.copy(alpha = if (dark) .12f else .07f)))
            Row(Modifier.fillMaxSize().selectableGroup()) {
                DockTabs.forEachIndexed { index, item ->
                    // Changing tab ticks like a segmented control; tapping the current tab only gives the plain tap.
                    DockTab(item, page == index, rememberTap { if (index != page) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(index) },
                        // Long-pressing Home opens the debug sheet for testers; it is not announced as a feature anywhere else.
                        onLongClick = if (index == 0) ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); onDebug() }) else null)
                }
            }
        }
        DockServer(haze, glass, edge, serverName, serverMark, phone, power, onServers)
    }
}

private class DockItem(@StringRes val label: Int, val active: ImageVector, val idle: ImageVector)
// Page numbers are the tab order: 0 Home, 1 Statistics, 2 Invite, 3 Settings.
private val DockTabs = listOf(DockItem(R.string.common_tab_home, Icons.Rounded.Home, Icons.Outlined.Home), DockItem(R.string.common_tab_stats, Icons.Rounded.BarChart, Icons.Outlined.BarChart),
    DockItem(R.string.common_invite, Icons.Rounded.QrCode2, Icons.Outlined.QrCode2),
    DockItem(R.string.common_tab_settings, Icons.Rounded.Settings, Icons.Outlined.Settings))
private val DockHeight = 64.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.DockTab(item: DockItem, active: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(if (active) colors.primary else colors.onSurfaceVariant, tween(220, easing = Emphasized), label = "Dock tint")
    // The icon dips and springs back when its tab is chosen, but not when the dock first appears.
    val pop = remember { Animatable(1f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active && settled) { pop.snapTo(0.8f); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f)) }
        settled = true
    }
    // The sliding lozenge is the selection feedback, so taps skip the ripple and only press in.
    val source = remember { MutableInteractionSource() }
    val press = if (onLongClick != null) Modifier.combinedClickable(source, null, role = Role.Tab, onLongClickLabel = "Debug menu", hapticFeedbackEnabled = false,
        onLongClick = onLongClick, onClick = onClick)
    else Modifier.clickable(source, null, role = Role.Tab, onClick = onClick)
    Column(Modifier.weight(1f).fillMaxHeight().pressScale(source, 0.92f).clip(CircleShape).semantics(mergeDescendants = true) { selected = active }.then(press),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        Icon(if (active) item.active else item.idle, null, Modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, tint = tint)
        Text(stringResource(item.label), style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, color = tint, maxLines = 1)
    }
}

/** The current server's mark, as tall as the tabs. Its dot shows whether the server is up; tapping it switches or adds servers. */
@Composable
private fun DockServer(haze: HazeState, glass: HazeBlurStyle, edge: Brush, name: String, mark: String, phone: Boolean, power: PowerPhase, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val status = stringResource(when (power) { PowerPhase.On -> R.string.common_dock_status_live; PowerPhase.Starting -> R.string.common_dock_status_starting; PowerPhase.Stopping -> R.string.common_dock_status_stopping
        PowerPhase.Failed -> R.string.common_dock_status_failed; PowerPhase.Off -> R.string.common_dock_status_off })
    val description = stringResource(R.string.common_dock_server_description, name, status)
    val dot by animateColorAsState(when (power) {
        PowerPhase.On -> Success; PowerPhase.Failed -> colors.error; PowerPhase.Starting, PowerPhase.Stopping -> Success.copy(alpha = .45f); PowerPhase.Off -> colors.outline
    }, tween(280, easing = Emphasized), label = "Server state")
    Box(Modifier.size(DockHeight).pressScale(source, 0.92f)) {
        Box(Modifier.matchParentSize().clip(CircleShape).hazeBlur(HazeInput.Sources(haze), glass).border(1.dp, edge, CircleShape)
            .clickable(source, ripple(), role = Role.Button, onClickLabel = stringResource(R.string.common_switch_server), onClick = rememberTap(onClick))
            .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            ServerMark(mark, phone, size = 38.dp)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp).size(14.dp).clip(CircleShape).background(colors.surface).padding(2.5.dp)
            .clip(CircleShape).background(dot))
    }
}
