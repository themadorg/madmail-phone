package org.madmail.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
internal fun ServerPicker(selectedId: String, remotes: List<RemoteServer>, identities: Map<String, ServerIdentity>, phoneConfigured: Boolean, onSelect: (String) -> Unit, onAdd: () -> Unit, onSave: (String, ServerIdentity) -> Unit, onEditing: (Boolean) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var editMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val entries = (if (phoneConfigured) listOf(Triple("phone", "This phone", "On this device")) else emptyList()) + remotes.map {
        val options = context.getSharedPreferences("remote-options-${it.id}", android.content.Context.MODE_PRIVATE)
        Triple(it.id, it.host, if (options.getString("preferred", "ssh") == "api") "Admin API · ${it.host}" else it.address)
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
            SheetHeader("Servers")
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
                            ServerRow(identity, id == "phone", identity.name.ifBlank { fallback }, if (editMode) "Name and icon" else address,
                                selected = id == selectedId, editMode = editMode) { if (editMode) editing = id else onSelect(id) }
                            // Server rows: 14dp padding, 36dp mark, 14dp gap, so the text starts at 64dp.
                            RowDivider(64.dp)
                        }
                        NavRow({ TintIcon(Tint.Red, Icons.Rounded.Add) }, "Add server", onClick = onAdd)
                    }
                    if (visible.isEmpty()) Footer("No matches")
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    PillButton(if (editMode) "Done" else "Edit", { editMode = !editMode }, icon = if (editMode) Icons.Rounded.Check else Icons.Rounded.Edit)
                }
            }
        }
    }
}

@Composable
private fun ServerRow(identity: ServerIdentity, phone: Boolean, title: String, detail: String, selected: Boolean, editMode: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected && !editMode, role = if (editMode) Role.Button else Role.RadioButton, onClick = rememberTap(onClick))
        .heightIn(min = 60.dp).padding(start = 14.dp, end = if (editMode) 10.dp else 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ServerMark(identity.mark, phone, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (editMode) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Edit appearance", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        else if (selected) SelectedCheck()
    }
}

@Composable
private fun SearchGroup(query: String, onQuery: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.bodyLarge
    val focus = LocalFocusManager.current
    Group {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
            BasicTextField(query, onQuery, Modifier.weight(1f).padding(vertical = 14.dp).semantics { contentDescription = "Find a server" }, singleLine = true,
                textStyle = text.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Find a server", style = text, color = colors.onSurfaceVariant.copy(alpha = 0.85f))
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
        title = { Text("Discard changes?", fontWeight = FontWeight.SemiBold) },
        confirmButton = { TextButton(onClick = onCancel) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
    val focus = LocalFocusManager.current
    val nameFocus = remember { FocusRequester() }
    val emojiFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = GroupGutter, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to servers") }
            Text("Appearance", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            PillButton("Done", { focus.clearFocus(); onSave(ServerIdentity(name.trim(), mark)) }, filled = true, enabled = dirty)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = GroupGutter)) {
            Group {
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ServerMark(mark, phone)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name.ifBlank { fallback }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Preview", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                RowDivider(16.dp)
                FieldRow("Name", name, { if (it.length <= 60) name = it }, fallback, nameFocus,
                    fieldKeyboard(KeyboardType.Text, ImeAction.Done, KeyboardCapitalization.Words), onIme = { focus.clearFocus() })
            }
            Spacer(Modifier.height(24.dp))
            Caption("Mark")
            Group {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
                    listOf("Icon", "Emoji").forEachIndexed { index, label ->
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
                        val shape = RoundedCornerShape(15.dp)
                        Box(Modifier.size(48.dp).clip(shape).border(2.dp, if (active) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                            .semantics { contentDescription = if (option.startsWith("emoji:")) option.removePrefix("emoji:") else "$option icon" }
                            .selectable(active, role = Role.RadioButton, onClick = { mark = option; if (emojiMode) custom = option.removePrefix("emoji:"); focus.clearFocus() }),
                            contentAlignment = Alignment.Center) {
                            ServerMark(option, phone, size = 38.dp)
                        }
                    }
                }
                if (emojiMode) {
                    RowDivider(16.dp)
                    if (!customVisible) ActionRow("Other emoji", { customVisible = true }, icon = Icons.Rounded.Add)
                    else FieldRow("Emoji", custom, { value ->
                        if (value.length <= 32 && !value.contains('\n')) {
                            custom = value
                            val first = firstSymbol(value.trim())
                            if (first.isNotBlank()) mark = "emoji:$first"
                        }
                    }, "Any emoji", emojiFocus, fieldKeyboard(KeyboardType.Text, ImeAction.Done), onIme = { focus.clearFocus() })
                }
            }
            if (emojiMode && customVisible) Footer("Only the first emoji is used.")
        }
    }
}

private fun firstSymbol(value: String): String {
    if (value.isEmpty()) return ""
    val iterator = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(value) }
    iterator.first()
    val end = iterator.next()
    return if (end == android.icu.text.BreakIterator.DONE) value else value.substring(0, end)
}
