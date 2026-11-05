package org.madmail.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.core.ServerIdentity

/** A server's squircle: red for this phone, blue for a remote server, carrying the chosen icon or emoji. */
@Composable
internal fun ServerMark(mark: String, phone: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, size: Dp = 40.dp) {
    val side = if (compact) 26.dp else size
    val tint = if (phone) Tint.Red else Tint.Blue
    Box(modifier.size(side).clip(RoundedCornerShape(side * 0.28f)).background(Brush.verticalGradient(listOf(Color(tint.top), Color(tint.bottom)))),
        contentAlignment = Alignment.Center) {
        if (mark.startsWith("emoji:")) Text(mark.removePrefix("emoji:"), fontSize = (side.value * 0.52f).sp, maxLines = 1)
        else Icon(markIcon(mark, phone), null, Modifier.size(side * 0.56f), tint = Color.White)
    }
}

private fun markIcon(mark: String, phone: Boolean) = when (mark) {
    "cloud" -> Icons.Rounded.Cloud
    "home" -> Icons.Rounded.Home
    "globe" -> Icons.Rounded.Public
    "storage" -> Icons.Rounded.Storage
    "shield" -> Icons.Rounded.Shield
    else -> if (phone) Icons.Rounded.PhoneAndroid else Icons.Rounded.Dns
}

@Composable
internal fun ServerPicker(selectedId: String, remotes: List<RemoteServer>, identities: Map<String, ServerIdentity>, phoneConfigured: Boolean, onSelect: (String) -> Unit, onAdd: () -> Unit, onSave: (String, ServerIdentity) -> Unit, onEditing: (Boolean) -> Unit,
    onOpen: (String, Int) -> Unit, onRemove: (String) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var peek by remember { mutableStateOf<Pair<String, Rect>?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    var editMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val entries = (if (phoneConfigured) listOf(Triple("phone", stringResource(R.string.remote_this_phone), stringResource(R.string.remote_on_this_device))) else emptyList()) + remotes.map {
        val options = context.getSharedPreferences("remote-options-${it.id}", android.content.Context.MODE_PRIVATE)
        Triple(it.id, it.host, if (options.getString("preferred", "ssh") == "api") resources.getString(R.string.remote_transport_api, it.host) else it.address)
    }
    SideEffect { onEditing(editing != null) }
    DisposableEffect(Unit) { onDispose { onEditing(false) } }
    val editId = editing
    if (editId != null) {
        val entry = entries.first { it.first == editId }
        key(editId) {
            IdentityEditor(entry.second, editId == "phone", identities[editId] ?: ServerIdentity(), { editing = null }) {
                onSave(editId, it); editing = null; editMode = false
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().imePadding().animateContentSize(tween(300, easing = Emphasized)).padding(bottom = 24.dp)) {
            removing?.let { id ->
                val name = identities[id]?.name?.ifBlank { null } ?: entries.firstOrNull { it.first == id }?.second ?: id
                KitDialog(stringResource(R.string.remote_remove_title, name), stringResource(R.string.remote_remove_body), stringResource(R.string.remote_remove),
                    onConfirm = { removing = null; onRemove(id) }, onDismiss = { removing = null })
            }
            SheetHeader(stringResource(R.string.remote_servers))
            Column(Modifier.fillMaxWidth().padding(horizontal = GroupGutter)) {
                if (entries.size > 5) {
                    SearchGroup(query) { query = it }
                    Spacer(Modifier.height(16.dp))
                }
                val visible = entries.filter { (id, host, address) -> "${identities[id]?.name.orEmpty()} $host $address".contains(query, ignoreCase = true) }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Group(Modifier.selectableGroup()) {
                        visible.forEach { (id, fallback, address) ->
                            val identity = identities[id] ?: ServerIdentity()
                            val title = identity.name.ifBlank { fallback }
                            var bounds by remember { mutableStateOf(Rect.Zero) }
                            ServerRow(identity, id == "phone", title, if (editMode) stringResource(R.string.remote_name_and_icon) else address,
                                selected = id == selectedId, editMode = editMode, Modifier.onGloballyPositioned { bounds = Rect(it.positionOnScreen(), it.size.toSize()) },
                                onLongClick = if (editMode) null else ({ peek = id to bounds })) { if (editMode) editing = id else onSelect(id) }
                            peek?.takeIf { it.first == id }?.let { (_, anchor) ->
                                PeekMenu(anchor, buildList {
                                    if (id != selectedId) add(MenuAction(stringResource(R.string.remote_open), Icons.AutoMirrored.Rounded.OpenInNew) { onSelect(id) })
                                    add(MenuAction(stringResource(R.string.remote_statistics), Icons.Rounded.BarChart) { onOpen(id, 1) })
                                    add(MenuAction(stringResource(R.string.remote_invite), Icons.Rounded.QrCode2) { onOpen(id, 2) })
                                    add(MenuAction(stringResource(R.string.remote_settings), Icons.Rounded.Settings) { onOpen(id, 3) })
                                    add(MenuAction(stringResource(R.string.remote_name_and_icon), Icons.Rounded.Edit) { editing = id })
                                    add(MenuAction(stringResource(R.string.remote_copy_address), Icons.Rounded.ContentCopy) {
                                        val origin = if (id == "phone") localInviteOrigin(context) else fallback
                                        if (origin.isBlank()) toaster.show(resources.getString(R.string.remote_no_shared_network), error = true)
                                        else { clipboard.setText(AnnotatedString(origin)); toaster.show(resources.getString(R.string.remote_address_copied)) }
                                    })
                                    if (id != "phone") add(MenuAction(stringResource(R.string.remote_remove_server), Icons.Rounded.Delete, destructive = true) { removing = id })
                                }, onDismiss = { peek = null }) {
                                    ServerRow(identity, id == "phone", title, address, selected = id == selectedId, editMode = false, Modifier, null) {}
                                }
                            }
                            // Server rows: 14dp padding, 36dp mark, 14dp gap, so the text starts at 64dp.
                            RowDivider(64.dp)
                        }
                        NavRow({ TintIcon(Tint.Red, Icons.Rounded.Add) }, stringResource(R.string.remote_add_server), onClick = onAdd)
                    }
                    if (visible.isEmpty()) Footer(stringResource(R.string.remote_no_matches))
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    PillButton(if (editMode) stringResource(R.string.remote_done) else stringResource(R.string.remote_edit), { editMode = !editMode }, icon = if (editMode) Icons.Rounded.Check else Icons.Rounded.Edit)
                }
            }
        }
    }
}

@Composable
private fun ServerRow(identity: ServerIdentity, phone: Boolean, title: String, detail: String, selected: Boolean, editMode: Boolean,
    modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val tap = rememberTap(onClick)
    Row(modifier.fillMaxWidth().semantics { this.selected = selected && !editMode }
        .combinedClickable(role = if (editMode) Role.Button else Role.RadioButton, onLongClickLabel = stringResource(R.string.remote_more_actions), onClick = tap,
            onLongClick = onLongClick)
        .heightIn(min = 60.dp).padding(start = 14.dp, end = if (editMode) 10.dp else 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ServerMark(identity.mark, phone, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (editMode) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.remote_edit_appearance), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        else if (selected) SelectedCheck()
    }
}

@Composable
private fun SearchGroup(query: String, onQuery: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.bodyLarge
    val focus = LocalFocusManager.current
    val findLabel = stringResource(R.string.remote_find_server)
    Group {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
            BasicTextField(query, onQuery, Modifier.weight(1f).padding(vertical = 14.dp).semantics { contentDescription = findLabel }, singleLine = true,
                textStyle = text.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(findLabel, style = text, color = colors.onSurfaceVariant.copy(alpha = 0.85f))
                        inner()
                    }
                })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun IdentityEditor(fallback: String, phone: Boolean, initial: ServerIdentity, onCancel: () -> Unit, onSave: (ServerIdentity) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var mark by rememberSaveable { mutableStateOf(initial.mark) }
    var emojiMode by rememberSaveable { mutableStateOf(initial.mark.startsWith("emoji:")) }
    var customVisible by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(if (initial.mark.startsWith("emoji:")) initial.mark.removePrefix("emoji:") else "") }
    var discard by remember { mutableStateOf(false) }
    val dirty = name.trim() != initial.name || mark != initial.mark
    val goBack: () -> Unit = { if (dirty) discard = true else onCancel() }
    BackHandler(onBack = goBack)
    if (discard) AlertDialog(onDismissRequest = { discard = false }, containerColor = panelColor(), shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.remote_discard_title), fontWeight = FontWeight.SemiBold) },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.remote_discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.remote_keep_editing)) } })
    val focus = LocalFocusManager.current
    val nameFocus = remember { FocusRequester() }
    val emojiFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = GroupGutter, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.remote_back_to_servers)) }
            Text(stringResource(R.string.remote_appearance), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            PillButton(stringResource(R.string.remote_done), { focus.clearFocus(); onSave(ServerIdentity(name.trim(), mark)) }, filled = true, enabled = dirty)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = GroupGutter)) {
            Group {
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ServerMark(mark, phone)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name.ifBlank { fallback }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.remote_preview), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                RowDivider(16.dp)
                FieldRow(stringResource(R.string.remote_name), name, { if (it.length <= 60) name = it }, fallback, nameFocus,
                    fieldKeyboard(KeyboardType.Text, ImeAction.Done, KeyboardCapitalization.Words), onIme = { focus.clearFocus() })
            }
            Spacer(Modifier.height(24.dp))
            Caption(stringResource(R.string.remote_mark))
            Group {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
                    listOf(stringResource(R.string.remote_icon), stringResource(R.string.remote_emoji)).forEachIndexed { index, label ->
                        SegmentedButton(selected = emojiMode == (index == 1), onClick = { focus.clearFocus(); emojiMode = index == 1 },
                            shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {},
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer, inactiveContainerColor = groupColor(),
                                activeBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), inactiveBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) {
                            Text(label, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                FlowRow(Modifier.fillMaxWidth().padding(14.dp).selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 6) {
                    val choices = if (emojiMode) listOf("🏠", "☁️", "🌍", "🚀", "🌱", "🦊", "🐙", "🌙", "⚡", "🛰️", "🏔️", "💌").map { "emoji:$it" } else listOf("default", "cloud", "home", "globe", "storage", "shield")
                    choices.forEach { option ->
                        val active = mark == option
                        val description = if (option.startsWith("emoji:")) option.removePrefix("emoji:") else markDescription(option)
                        val shape = RoundedCornerShape(15.dp)
                        Box(Modifier.size(48.dp).clip(shape).border(2.dp, if (active) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                            .semantics { contentDescription = description }
                            .selectable(active, role = Role.RadioButton, onClick = { mark = option; if (emojiMode) custom = option.removePrefix("emoji:"); focus.clearFocus() }),
                            contentAlignment = Alignment.Center) {
                            ServerMark(option, phone, size = 38.dp)
                        }
                    }
                }
                if (emojiMode) {
                    RowDivider(16.dp)
                    if (!customVisible) ActionRow(stringResource(R.string.remote_other_emoji), { customVisible = true }, icon = Icons.Rounded.Add)
                    else FieldRow(stringResource(R.string.remote_emoji), custom, { value ->
                        if (value.length <= 32 && !value.contains('\n')) {
                            custom = value
                            val first = firstSymbol(value.trim())
                            if (first.isNotBlank()) mark = "emoji:$first"
                        }
                    }, stringResource(R.string.remote_any_emoji), emojiFocus, fieldKeyboard(KeyboardType.Text, ImeAction.Done), onIme = { focus.clearFocus() })
                }
            }
            if (emojiMode && customVisible) Footer(stringResource(R.string.remote_first_emoji_only))
        }
    }
}

@Composable
private fun markDescription(option: String): String = stringResource(when (option) {
    "cloud" -> R.string.remote_icon_cloud
    "home" -> R.string.remote_icon_home
    "globe" -> R.string.remote_icon_globe
    "storage" -> R.string.remote_icon_storage
    "shield" -> R.string.remote_icon_shield
    else -> R.string.remote_icon_default
})

private fun firstSymbol(value: String): String {
    if (value.isEmpty()) return ""
    val iterator = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(value) }
    iterator.first()
    val end = iterator.next()
    return if (end == android.icu.text.BreakIterator.DONE) value else value.substring(0, end)
}
