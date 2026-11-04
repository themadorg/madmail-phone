package org.madmail.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal class MenuAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * A long-press preview with its actions, in the manner of an iOS context menu. The pressed row lifts out of a dimmed
 * page at the place it was, and the actions open under it, or above it when there's no room below.
 * [anchor] is the row's bounds on screen. Choosing an action plays the exit first, then runs it.
 */
@Composable
internal fun PeekMenu(anchor: Rect, actions: List<MenuAction>, onDismiss: () -> Unit, preview: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 520f)) }
    val closing = remember { booleanArrayOf(false) }
    fun close(then: (() -> Unit)? = null) {
        if (closing[0]) return
        closing[0] = true
        scope.launch { progress.animateTo(0f, tween(150)); onDismiss(); then?.invoke() }
    }
    val colors = MaterialTheme.colorScheme
    var below = true
    // A dialog window edge to edge, not a popup: a popup is held inside the visible frame, so its dim would stop above the navigation bar.
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.run { setDimAmount(0f); setWindowAnimations(0) } }
        var origin by remember { mutableStateOf<Offset?>(null) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }.graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.34f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { close() })
        Layout(content = {
            Box(Modifier.graphicsLayer {
                val s = 1f + 0.035f * progress.value
                scaleX = s; scaleY = s
            }.shadow(16.dp * progress.value.coerceIn(0f, 1f), RoundedCornerShape(20.dp), clip = false)
                .clip(RoundedCornerShape(20.dp)).background(groupColor())) { preview() }
            Column(Modifier.width(250.dp).graphicsLayer {
                val p = progress.value
                alpha = p.coerceIn(0f, 1f)
                val s = 0.82f + 0.18f * p
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0.1f, if (below) 0f else 1f)
            }.shadow(12.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(16.dp)).background(groupColor())) {
                actions.forEachIndexed { index, action ->
                    if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = colors.onSurface.copy(alpha = 0.1f))
                    val tint = if (action.destructive) colors.error else colors.onSurface
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { close(action.onClick) }.padding(horizontal = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(action.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = tint,
                            fontWeight = if (action.destructive) FontWeight.Medium else null)
                        Icon(action.icon, null, Modifier.size(20.dp), tint = tint)
                    }
                }
            }
        }, Modifier.fillMaxSize()) { measurables, constraints ->
            // The row was measured on screen; this window may not start at the screen's corner.
            val at = origin ?: return@Layout layout(constraints.maxWidth, constraints.maxHeight) {}
            val anchor = anchor.translate(-at.x, -at.y)
            val width = anchor.width.toInt(); val height = anchor.height.toInt()
            val row = measurables[0].measure(Constraints.fixed(width, height))
            val menu = measurables[1].measure(Constraints())
            val gap = 10.dp.roundToPx()
            val margin = 16.dp.roundToPx()
            below = anchor.bottom + gap + menu.height <= constraints.maxHeight - margin
            layout(constraints.maxWidth, constraints.maxHeight) {
                row.place(anchor.left.toInt(), anchor.top.toInt())
                val x = anchor.left.toInt().coerceIn(margin, (constraints.maxWidth - menu.width - margin).coerceAtLeast(margin))
                val y = if (below) anchor.bottom.toInt() + gap else (anchor.top.toInt() - gap - menu.height).coerceAtLeast(margin)
                menu.place(x, y)
            }
        }
    }
}
