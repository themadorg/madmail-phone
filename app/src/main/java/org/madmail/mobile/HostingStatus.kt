package org.madmail.mobile

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.madmail.mobile.engine.EnginePhase

/** A server's power, shared by the phone and remote servers so both read as one on/off switch. */
internal enum class PowerPhase { Off, Starting, On, Stopping, Failed;
    val busy get() = this == Starting || this == Stopping
    /** Where the knob sits: it moves as soon as a start or stop is asked for, and the spinner covers the wait. */
    val on get() = this == On || this == Starting
}

internal fun EnginePhase.power() = when (this) {
    EnginePhase.Stopped -> PowerPhase.Off; EnginePhase.Starting -> PowerPhase.Starting; EnginePhase.Running -> PowerPhase.On
    EnginePhase.Stopping -> PowerPhase.Stopping; EnginePhase.Failed -> PowerPhase.Failed
}

/** The phone's hosting power as the first row of a [Group]: tap anywhere on the row to switch hosting on or off. */
@Composable
internal fun HostingStatus(phase: EnginePhase, enabled: Boolean = true, unreachable: Boolean = false, onToggle: (Boolean) -> Unit) {
    PowerRow(phase.power(),
        stringResource(when (phase) { EnginePhase.Running -> R.string.common_hosting_live; EnginePhase.Starting -> R.string.common_hosting_starting; EnginePhase.Stopping -> R.string.common_hosting_stopping
            EnginePhase.Failed -> R.string.common_hosting_failed; else -> R.string.common_hosting_off }),
        when {
            phase == EnginePhase.Running && unreachable -> stringResource(R.string.common_hosting_detail_unreachable)
            phase == EnginePhase.Running -> stringResource(R.string.common_hosting_detail_ready); phase == EnginePhase.Failed -> stringResource(R.string.common_hosting_detail_error)
            !enabled -> stringResource(R.string.common_hosting_detail_choose_version); else -> null
        },
        PowerConfirm(
            PowerQuestion(stringResource(R.string.common_hosting_on_title), stringResource(R.string.common_hosting_on_text), stringResource(R.string.common_hosting_on_action)),
            PowerQuestion(stringResource(R.string.common_hosting_off_title), stringResource(R.string.common_hosting_off_text), stringResource(R.string.common_hosting_off_action))),
        enabled = enabled, onToggle = onToggle)
}

/** What a power switch asks before it acts, one question for each direction. */
internal data class PowerConfirm(val on: PowerQuestion, val off: PowerQuestion)
internal data class PowerQuestion(val title: String, val text: String, val action: String)

/**
 * A large power row. The whole row is the switch; [working] shows progress in the knob and ignores taps
 * while something else (a refresh, an install) holds the server.
 */
@Composable
internal fun PowerRow(power: PowerPhase, title: String, detail: String?, confirm: PowerConfirm, enabled: Boolean = true, working: Boolean = false,
    onToggle: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    // Switching a mail server on or off affects everyone on it, so a tap only asks; the knob moves once confirmed.
    var asking by remember { mutableStateOf<Boolean?>(null) }
    // Confirm and Reject mark transitions only, so opening a screen on a running or failed server stays silent.
    var last by remember { mutableStateOf(power) }
    LaunchedEffect(power) {
        if (power == PowerPhase.On && last == PowerPhase.Starting) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (power == PowerPhase.Failed && last != PowerPhase.Failed) haptics.performHapticFeedback(HapticFeedbackType.Reject)
        last = power
    }
    val spinning = power.busy || working
    val state = stringResource(when (power) { PowerPhase.On -> R.string.common_power_on; PowerPhase.Starting -> R.string.common_power_starting; PowerPhase.Stopping -> R.string.common_power_stopping; else -> R.string.common_power_off })
    Row(Modifier.fillMaxWidth()
        .toggleable(power.on, enabled = enabled && !spinning, role = Role.Switch) {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick); asking = it
        }
        .semantics { stateDescription = state; liveRegion = LiveRegionMode.Polite }
        .heightIn(min = 76.dp).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)
        .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val mark = when (power) { PowerPhase.On -> Tint.Green; PowerPhase.Failed -> Tint.Red; else -> Tint.Gray }
        Crossfade(mark, animationSpec = tween(220), label = "Power mark") { tint ->
            TintIcon(tint, if (tint == Tint.Red) Icons.Rounded.ErrorOutline else Icons.Rounded.PowerSettingsNew)
        }
        AnimatedContent(title to detail, Modifier.weight(1f), transitionSpec = {
            (fadeIn(tween(220, 60)) + slideInVertically(tween(320, 60, Emphasized)) { 12 }) togetherWith fadeOut(tween(100))
        }, label = "Power state") { (heading, line) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = if (power == PowerPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        PowerSwitch(power, spinning)
    }
    asking?.let { on ->
        val question = if (on) confirm.on else confirm.off
        KitDialog(question.title, question.text, question.action, onDismiss = { asking = null }, onConfirm = {
            asking = null
            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff); onToggle(on)
        })
    }
}

/** A switch a size up from the platform's, with a power glyph in the knob that turns into progress while it waits. */
@Composable
private fun PowerSwitch(power: PowerPhase, spinning: Boolean) {
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val track by animateColorAsState(when (power) { PowerPhase.On -> Success; PowerPhase.Starting -> Success.copy(alpha = 0.55f); else -> off },
        tween(280, easing = Emphasized), label = "Power track")
    val glyph by animateColorAsState(if (power == PowerPhase.On) Success else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        tween(280, easing = Emphasized), label = "Power glyph")
    val knob by animateDpAsState(if (power.on) KnobTravel else KnobInset, tween(280, easing = Emphasized), label = "Power knob")
    Box(Modifier.size(TrackWidth, TrackHeight).clip(CircleShape).background(track)) {
        Box(Modifier.offset(x = knob).align(Alignment.CenterStart).size(KnobSize).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center) {
            Crossfade(spinning, animationSpec = tween(180), label = "Power knob content") { busy ->
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = if (power == PowerPhase.Stopping) glyph else Success, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(16.dp), tint = glyph)
            }
        }
    }
}

private val TrackWidth = 60.dp
private val TrackHeight = 36.dp
private val KnobSize = 30.dp
private val KnobInset = 3.dp
private val KnobTravel = TrackWidth - KnobSize - KnobInset
