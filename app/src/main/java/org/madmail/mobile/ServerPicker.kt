package org.madmail.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.madmail.mobile.core.RemoteServer
import org.madmail.mobile.core.ServerIdentity

@Composable
internal fun ServerMark(mark: String, phone: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val icon = when (mark) {
        "cloud" -> Icons.Outlined.Cloud
        "home" -> Icons.Outlined.Home
        "globe" -> Icons.Outlined.Public
        "storage" -> Icons.Outlined.Storage
        "shield" -> Icons.Outlined.Shield
        else -> if (phone) Icons.Outlined.PhoneAndroid else Icons.Outlined.Dns
    }
    val emoji = mark.startsWith("emoji:")
    Box(modifier.size(if (compact) 26.dp else 48.dp).clip(RoundedCornerShape(if (compact) 8.dp else 16.dp))
        .background(if (compact) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (emoji) Text(mark.removePrefix("emoji:"), fontSize = if (compact) 21.sp else 26.sp, maxLines = 1)
        else Icon(icon, null, Modifier.size(if (compact) 24.dp else 25.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun ServerPicker(selectedId: String, remotes: List<RemoteServer>, identities: Map<String, ServerIdentity>, onSelect: (String) -> Unit, onAdd: () -> Unit, onSave: (String, ServerIdentity) -> Unit, onEditing: (Boolean) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var editMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val entries = listOf(Triple("phone", "This phone", "On this device")) + remotes.map {
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
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            if (entries.size > 5) OutlinedTextField(query, { query = it }, placeholder = { Text("Find a server") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            val visible = entries.filter { (id, host, address) -> "${identities[id]?.name.orEmpty()} $host $address".contains(query, ignoreCase = true) }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).selectableGroup()) {
                visible.forEach { (id, fallback, address) ->
                    val identity = identities[id] ?: ServerIdentity()
                    val selected = id == selectedId
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (selected && !editMode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f) else androidx.compose.ui.graphics.Color.Transparent)
                        .selectable(selected = selected && !editMode, role = if (editMode) Role.Button else Role.RadioButton, onClick = { if (editMode) editing = id else onSelect(id) })
                        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ServerMark(identity.mark, id == "phone")
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(identity.name.ifBlank { fallback }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (editMode) "Change name and icon" else address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (editMode) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Edit appearance", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (selected) Icon(Icons.Outlined.CheckCircle, "Current server", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (visible.isEmpty()) Text("No servers match “$query”", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAdd, modifier = Modifier.weight(1f).heightIn(min = 52.dp), contentPadding = PaddingValues(12.dp)) {
                    Icon(Icons.Outlined.AddCircleOutline, null, Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Add remote server", Modifier.weight(1f))
                }
                IconButton(onClick = { editMode = !editMode }) {
                    Icon(if (editMode) Icons.Outlined.Check else Icons.Outlined.Edit,
                        if (editMode) "Finish editing servers" else "Edit server appearance",
                        tint = if (editMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard appearance changes?") }, text = { Text("Your saved name and icon will stay as they were.") }, confirmButton = { TextButton(onClick = onCancel) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to servers") }
            Text("Appearance", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { focus.clearFocus(); onSave(ServerIdentity(name.trim(), mark)) }, enabled = dirty) { Text("Done") }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ServerMark(mark, phone)
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { fallback }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Preview", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(name, { if (it.length <= 60) name = it }, label = { Text("Server name") }, placeholder = { Text(fallback) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Icon", "Emoji").forEachIndexed { index, label ->
                    SegmentedButton(selected = emojiMode == (index == 1), onClick = { focus.clearFocus(); emojiMode = index == 1 }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                }
            }
            FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 6) {
                val choices = if (emojiMode) listOf("🏠", "☁️", "🌍", "🚀", "🌱", "🦊", "🐙", "🌙", "⚡", "🛰️", "🏔️", "💌").map { "emoji:$it" } else listOf("default", "cloud", "home", "globe", "storage", "shield")
                choices.forEach { option ->
                    val active = mark == option
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .semantics { contentDescription = if (option.startsWith("emoji:")) option.removePrefix("emoji:") else "$option icon" }
                        .selectable(active, role = Role.RadioButton, onClick = { mark = option; if (emojiMode) custom = option.removePrefix("emoji:"); focus.clearFocus() }), contentAlignment = Alignment.Center) {
                        ServerMark(option, phone, compact = true)
                    }
                }
            }
            if (emojiMode) {
                if (!customVisible) TextButton(onClick = { customVisible = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Choose another emoji") }
                else OutlinedTextField(custom, { value ->
                    if (value.length <= 32 && !value.contains('\n')) {
                        custom = value
                        val first = firstSymbol(value.trim())
                        if (first.isNotBlank()) mark = "emoji:$first"
                    }
                }, label = { Text("Custom emoji") }, supportingText = { Text("Use the emoji keyboard. Only the first symbol is used.") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
            }
            Text("Appearance is saved on this phone. Your server’s address stays the same.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
