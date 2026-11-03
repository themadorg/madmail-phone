package org.madmail.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.delay

// The Madmail kit: one warm page, white grouped cards, gradient squircle marks, one red pill per screen.
// Onboarding introduced it; every screen in the app is built from these parts.

/** One curve for every morph: quick start, long settle, no overshoot. */
internal val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal val ExpoOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
internal val ActionHeight = 60.dp

/** Horizontal gutter between the page edge and a group. */
internal val GroupGutter = 20.dp

private val dark @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** The logo's red, used for the single primary action on a screen. */
@Composable
internal fun brandRed() = if (dark) Color(0xFFD9252A) else Color(0xFFC41011)

/** The warm page and sheet colour. */
@Composable
internal fun panelColor() = MaterialTheme.colorScheme.background

/** Grouped rows sit on a lighter card than the page beneath them. */
@Composable
internal fun groupColor() = if (dark) Color(0xFF2B2726) else Color.White

/** Gradient pairs for squircle marks. One hue per meaning, like a settings list; never use these for text. */
internal enum class Tint(val top: Long, val bottom: Long) {
    Red(0xFFFF5A4F, 0xFFD3141B),
    Orange(0xFFFFB23F, 0xFFF08300),
    Yellow(0xFFFFD43B, 0xFFF2A900),
    Green(0xFF3DD68C, 0xFF14A05A),
    Teal(0xFF3FD4C8, 0xFF0E9E94),
    Blue(0xFF4C9BFF, 0xFF1A66E0),
    Indigo(0xFF7C83FF, 0xFF4B4FD8),
    Purple(0xFFC27CFF, 0xFF8A3FD8),
    Pink(0xFFFF6FA6, 0xFFE0306E),
    Gray(0xFF9AA3B2, 0xFF5E6878),
    Graphite(0xFF7A8699, 0xFF3B4352),
}

/** Success green for states that are actually confirmed. Fills only; use [successText] for words. */
internal val Success = Color(0xFF14A05A)

/** Success green dark or light enough to read as text on a group. */
@Composable
internal fun successText() = if (dark) Color(0xFF3DD68C) else Color(0xFF0B7A43)

/** The check on the selected row of a single choice. The row's own semantics announce selection. */
@Composable
internal fun SelectedCheck() = Icon(Icons.Rounded.Check, null, Modifier.size(22.dp), tint = brandRed())

@Composable
internal fun Squircle(top: Long, bottom: Long, icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brush.verticalGradient(listOf(Color(top), Color(bottom)))),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.58f), tint = Color.White)
    }
}

@Composable
internal fun TintIcon(tint: Tint, icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp) = Squircle(tint.top, tint.bottom, icon, modifier, size)

/** A rounded white card of rows. Rows inside are separated with [RowDivider]. */
@Composable
internal fun Group(modifier: Modifier = Modifier, fill: Color = groupColor(), content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(fill), content = content)
}

/** Small uppercase label above a group. */
@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelMedium,
        letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Small explanation under a group. */
@Composable
internal fun Footer(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = color)
}

/** Hairline between rows of a group, inset to where the row's text starts (70dp after a 40dp mark, 64dp after a 36dp mark, 16dp for plain rows). */
@Composable
internal fun RowDivider(inset: Dp = 70.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)))
}

/** Page title for top-level pages: large, semibold, with an optional line under it and an optional trailing slot. */
@Composable
internal fun PageTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

@Composable
private fun RowText(title: String, detail: String?, modifier: Modifier, titleColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = titleColor)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A tappable settings row: mark, title and one line, optional trailing value, chevron. */
@Composable
internal fun NavRow(mark: @Composable () -> Unit, title: String, detail: String? = null, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val tap = rememberTap(onClick)
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = tap).heightIn(min = 60.dp)
        .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        mark()
        RowText(title, detail, Modifier.weight(1f))
        value?.let { Text(it, Modifier.widthIn(max = 140.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

/** A text action inside a group, like "Remove server". Red only when it destroys something. */
@Composable
internal fun ActionRow(label: String, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null) {
    val colors = MaterialTheme.colorScheme
    val tap = rememberTap(onClick)
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = tap).heightIn(min = 52.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = if (destructive) colors.error else colors.onSurfaceVariant) }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (destructive) colors.error else colors.onSurface)
    }
}

/** A row for facts rather than choices: mark, title, explanation, optional trailing control. */
@Composable
internal fun InfoRow(tint: Tint, icon: ImageVector, title: String, detail: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TintIcon(tint, icon)
        RowText(title, detail, Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A labelled switch row. The whole row toggles. */
@Composable
internal fun SwitchRow(tint: Tint, icon: ImageVector, title: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch) {
            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff); onChange(it)
        }.heightIn(min = 60.dp)
        .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TintIcon(tint, icon)
        RowText(title, detail, Modifier.weight(1f))
        Switch(checked, null, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Success, checkedBorderColor = Success, checkedThumbColor = Color.White))
    }
}

/**
 * Label on the left, value on the right, like a settings detail. Values that are useful elsewhere copy on tap,
 * with a brief check and a haptic.
 */
@Composable
internal fun ValueRow(label: String, value: String, copyable: Boolean = false, valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    stacked: Boolean = false, mark: (@Composable () -> Unit)? = null) {
    // Long values (fingerprints, DNS records) and large font sizes put the value under the label, in full.
    val stack = stacked || value.length > 28 || LocalDensity.current.fontScale >= 1.3f
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    Row(Modifier.fillMaxWidth().then(if (copyable) Modifier.clickable(onClickLabel = "Copy $label") {
            clipboard.setText(AnnotatedString(value)); haptics.performHapticFeedback(HapticFeedbackType.Confirm); copied = true
        } else Modifier).heightIn(min = 52.dp).padding(start = if (mark != null) 14.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        mark?.invoke()
        if (stack) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        } else {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            // Values hug the trailing edge; the label takes whatever is left.
            Text(value, Modifier.widthIn(max = 200.dp), style = MaterialTheme.typography.bodyLarge, color = valueColor, textAlign = TextAlign.End,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (copyable) Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, if (copied) "Copied" else null, Modifier.size(16.dp),
            tint = if (copied) Success else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

/**
 * An inline label and value input, like a settings form. Errors appear under the row, and a focused invalid row scrolls
 * itself (error included) clear of the keyboard and the action. At large font sizes the label stacks above the value.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FieldRow(label: String, value: String, onValue: (String) -> Unit, placeholder: String, focus: FocusRequester, keyboard: KeyboardOptions,
    onIme: () -> Unit, error: String? = null, secret: Boolean = false, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val stacked = density.fontScale >= 1.3f
    val reveal = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var labelWidth by remember { mutableStateOf(FieldLabel) }
    LaunchedEffect(error, focused) { if (focused && error != null) { delay(120); reveal.bringIntoView() } }
    val labelColor = if (error != null) colors.error else if (stacked) colors.onSurfaceVariant else colors.onSurface
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(reveal).graphicsLayer { alpha = if (enabled) 1f else 0.5f }
        .pointerInput(enabled) { if (enabled) detectTapGestures { focus.requestFocus() } }) {
        // The label is the field's accessible name, so it isn't announced as a separate stop.
        if (stacked) Text(label, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).clearAndSetSemantics {}, style = MaterialTheme.typography.bodyMedium, color = labelColor)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // The label column can grow to fit, but a word is never broken across lines.
            if (!stacked) Text(label, Modifier.widthIn(min = FieldLabel).clearAndSetSemantics {}.onSizeChanged { labelWidth = with(density) { it.width.toDp() } }.padding(end = 8.dp),
                style = text, color = labelColor, maxLines = 1, softWrap = false)
            BasicTextField(value, onValue, Modifier.weight(1f).padding(vertical = 14.dp).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label; if (error != null) error(error) },
                enabled = enabled, singleLine = true, textStyle = text.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary), keyboardOptions = keyboard,
                keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = text, color = colors.onSurfaceVariant.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                })
            trailing?.invoke()
        }
        if (error != null) Text(error, Modifier.padding(start = 16.dp + if (stacked) 0.dp else labelWidth, end = 16.dp, bottom = 12.dp).clearAndSetSemantics {}, style = MaterialTheme.typography.bodySmall, color = colors.error)
    }
}

private val FieldLabel = 96.dp

internal fun fieldKeyboard(type: KeyboardType, ime: ImeAction, capitalization: KeyboardCapitalization = KeyboardCapitalization.None) =
    KeyboardOptions(capitalization = capitalization, autoCorrectEnabled = type == KeyboardType.Text, keyboardType = type, imeAction = ime)

/** The primary action: a red pill with the arrow carried in a white disc. One per screen. */
@Composable
internal fun BrandButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    // Disabled turns neutral rather than fading the red, so the label stays readable.
    val red by animateColorAsState(if (enabled) brandRed() else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), tween(200, easing = Emphasized), label = "Action fill")
    val ink = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.fillMaxWidth().heightIn(min = ActionHeight).pressScale(source).clip(RoundedCornerShape(30.dp)).background(red)
        .clickable(source, ripple(color = Color.White), enabled = enabled, role = Role.Button, onClick = rememberTap(onClick)), contentAlignment = Alignment.Center) {
        AnimatedContent(label, label = "Action label", transitionSpec = { fadeIn(tween(200, 60)) togetherWith fadeOut(tween(100)) }) {
            Text(it, Modifier.padding(horizontal = 60.dp, vertical = 8.dp), color = ink, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(44.dp).clip(CircleShape).background(if (enabled) Color.White else Color.Transparent), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(22.dp), tint = if (enabled) red else ink)
        }
    }
}

/**
 * Secondary and compact actions. Tonal is a soft red pill; [filled] is a solid red pill without the arrow disc,
 * for a primary action that sits inside content rather than at the foot of the screen.
 */
@Composable
internal fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, filled: Boolean = false,
    enabled: Boolean = true, wide: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val container = when { !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f); filled -> brandRed(); else -> MaterialTheme.colorScheme.primaryContainer }
    val content = when { !enabled -> MaterialTheme.colorScheme.onSurfaceVariant; filled -> Color.White; else -> MaterialTheme.colorScheme.onPrimaryContainer }
    Row(modifier.then(if (wide) Modifier.fillMaxWidth() else Modifier).minimumInteractiveComponentSize().pressScale(source)
        .clip(CircleShape).background(container).graphicsLayer { alpha = if (enabled) 1f else 0.7f }
        .clickable(source, ripple(), enabled = enabled, role = Role.Button, onClick = rememberTap(onClick))
        .heightIn(min = if (wide) 52.dp else 40.dp).padding(horizontal = if (wide) 20.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        icon?.let { Icon(it, null, Modifier.size(18.dp), tint = content) }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = content)
    }
}

/** A sheet in the kit: warm surface, rounded top, small grabber. Content uses groups like a page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KitSheet(onDismiss: () -> Unit, sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit) {
    // A full-height sheet stops below the status bar instead of running under the clock.
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.statusBarsPadding(), sheetState = sheetState, containerColor = panelColor(),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
            .semantics { contentDescription = "Drag handle" }) }) {
        // The sheet's window covers the app's, so it draws toasts itself while open.
        Box {
            Column(content = content)
            Box(Modifier.matchParentSize()) { ToastLayer(16.dp) }
        }
    }
}

/** A sheet's title row: semibold title, optional line, optional mark on the left. */
@Composable
internal fun SheetHeader(title: String, subtitle: String? = null, mark: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        mark?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

internal fun Modifier.pressScale(source: InteractionSource, pressedScale: Float = 0.97f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, tween(if (pressed) 90 else 220, easing = Emphasized), label = "Press scale")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Wraps a tap handler with the light tick every button and row in the kit gives. */
@Composable
internal fun rememberTap(onClick: () -> Unit): () -> Unit {
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onClick)
    return remember(haptics) { { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); latest() } }
}

/** One short confirmation or failure at a time. Show it with `LocalToaster.current.show("Saved.")`. */
@Stable
internal class Toaster {
    var current by mutableStateOf<ToastMessage?>(null)
        private set
    private var next = 0L
    /** The last few toasts with when they were shown, newest first, for the debug menu. */
    val history = mutableStateListOf<Pair<Long, ToastMessage>>()
    fun show(text: String, error: Boolean = false) {
        val message = ToastMessage(text, error, ++next)
        current = message
        history.add(0, System.currentTimeMillis() to message)
        if (history.size > 20) history.removeAt(history.lastIndex)
    }
    /** Windows that can draw a toast, bottom first; only the last one does. */
    internal val layers = mutableStateListOf<Any>()
    fun dismiss(id: Long) { if (current?.id == id) current = null }
}

internal data class ToastMessage(val text: String, val error: Boolean, val id: Long)

internal val LocalToaster = staticCompositionLocalOf { Toaster() }

/** A confirmation in the kit: warm surface, large radius, cancel as text and the action as a pill. */
@Composable
internal fun KitDialog(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = panelColor(), shape = RoundedCornerShape(28.dp),
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                content?.let { Column(content = it) }
            }
        },
        confirmButton = { PillButton(confirm, onConfirm, filled = true, enabled = confirmEnabled) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/**
 * Hosts toasts for everything inside it. The toast floats in its own window above the dock, holds for a moment and
 * slides away; it never moves the layout under it. Sheets open in windows of their own, so each adds a [ToastLayer]
 * and the topmost window draws the toast.
 */
@Composable
internal fun ToastHost(content: @Composable () -> Unit) {
    val toaster = remember { Toaster() }
    val haptics = LocalHapticFeedback.current
    val message = toaster.current
    LaunchedEffect(message?.id) {
        if (message == null) return@LaunchedEffect
        haptics.performHapticFeedback(if (message.error) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
        delay(if (message.error) 3600 else 2200)
        toaster.dismiss(message.id)
    }
    CompositionLocalProvider(LocalToaster provides toaster) {
        Box(Modifier.fillMaxSize()) {
            content()
            ToastLayer(ToastLift, aboveNavigationBar = true)
        }
    }
}

/** Draws the current toast at the bottom of its parent, [lift] above it, while this is the topmost layer. */
@Composable
internal fun ToastLayer(lift: Dp, aboveNavigationBar: Boolean = false) {
    val toaster = LocalToaster.current
    val layer = remember { Any() }
    DisposableEffect(toaster) { toaster.layers.add(layer); onDispose { toaster.layers.remove(layer) } }
    val visible = remember { MutableTransitionState(false) }
    var shown by remember { mutableStateOf<ToastMessage?>(null) }
    val message = toaster.current?.takeIf { toaster.layers.lastOrNull() == layer }
    LaunchedEffect(message?.id) { if (message != null) shown = message; visible.targetState = message != null }
    val bottom = (if (aboveNavigationBar) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp) + lift - ToastShadowRoom
    val offset = with(LocalDensity.current) { bottom.roundToPx() }
    if (visible.currentState || visible.targetState) shown?.let { toast ->
        Popup(Alignment.BottomCenter, IntOffset(0, -offset), properties = PopupProperties(focusable = false, clippingEnabled = false)) {
            AnimatedVisibility(visible, enter = fadeIn(tween(180)) + slideInVertically(tween(420, easing = ExpoOut)) { it } + scaleIn(tween(420, easing = ExpoOut), 0.92f),
                exit = fadeOut(tween(160)) + slideOutVertically(tween(220, easing = Emphasized)) { it / 2 }) {
                ToastPill(toast)
            }
        }
    }
}

/** The popup window is only as big as its content, so the pill keeps room around it for its shadow to fall into. */
private val ToastShadowRoom = 20.dp
/** Space between the bottom of the window and a toast, so it clears the floating dock. */
private val ToastLift = 104.dp

@Composable
private fun ToastPill(toast: ToastMessage) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(horizontal = 24.dp, vertical = ToastShadowRoom)
        .shadow(8.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.28f)).clip(CircleShape).background(colors.inverseSurface)
        .padding(start = 14.dp, end = 20.dp, top = 12.dp, bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (toast.error) brandRed() else Success), contentAlignment = Alignment.Center) {
            Icon(if (toast.error) Icons.Rounded.PriorityHigh else Icons.Rounded.Check, null, Modifier.size(15.dp), tint = Color.White)
        }
        Text(toast.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.inverseOnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
