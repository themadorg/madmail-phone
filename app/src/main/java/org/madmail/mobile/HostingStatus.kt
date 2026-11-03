package org.madmail.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.madmail.mobile.engine.EnginePhase

/** The phone's hosting state as the first row of a [Group]: green when live, gray when off, red when it failed. */
@Composable
internal fun HostingStatus(phase: EnginePhase) {
    AnimatedContent(phase, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, transitionSpec = {
        (fadeIn(tween(220, 60)) + slideInVertically(tween(320, 60, Emphasized)) { 12 }) togetherWith fadeOut(tween(100))
    }, label = "Hosting state") { current ->
        val busy = current == EnginePhase.Starting || current == EnginePhase.Stopping
        InfoRow(
            when (current) { EnginePhase.Running -> Tint.Green; EnginePhase.Failed -> Tint.Red; else -> Tint.Gray },
            when (current) { EnginePhase.Running -> Icons.Rounded.CheckCircle; EnginePhase.Failed -> Icons.Rounded.ErrorOutline; EnginePhase.Stopped -> Icons.Rounded.PowerSettingsNew; else -> Icons.Rounded.HourglassTop },
            when (current) { EnginePhase.Running -> "Hosting is live"; EnginePhase.Starting -> "Starting…"; EnginePhase.Stopping -> "Stopping…"; EnginePhase.Failed -> "Hosting failed"; else -> "Hosting is off" },
            when (current) { EnginePhase.Running -> "Ready for connections."; EnginePhase.Failed -> "See the error below."; else -> null },
        ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
    }
}
