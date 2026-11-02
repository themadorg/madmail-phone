package org.madmail.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.madmail.mobile.engine.EnginePhase

@Composable
internal fun HostingStatus(phase: EnginePhase) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (phase == EnginePhase.Running) colors.primaryContainer else colors.surfaceContainerLow, label = "Hosting state color")
    Surface(color = fill, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        AnimatedContent(phase, transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(tween(280), initialScale = .96f)) togetherWith fadeOut(tween(100))
        }, label = "Hosting state") { current ->
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (current == EnginePhase.Starting || current == EnginePhase.Stopping) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(when (current) { EnginePhase.Running -> Icons.Outlined.CheckCircle; EnginePhase.Failed -> Icons.Outlined.ErrorOutline; else -> Icons.Outlined.PowerSettingsNew }, null, Modifier.size(28.dp), tint = if (current == EnginePhase.Failed) colors.error else colors.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when (current) { EnginePhase.Running -> "Hosting is live"; EnginePhase.Starting -> "Starting your server…"; EnginePhase.Stopping -> "Stopping hosting…"; EnginePhase.Failed -> "Hosting needs attention"; else -> "Hosting is off" }, style = MaterialTheme.typography.titleMedium)
                    Text(when (current) { EnginePhase.Running -> "Ready to connect another device."; EnginePhase.Starting -> "Checking the mail and web listeners."; EnginePhase.Stopping -> "Closing server connections."; EnginePhase.Failed -> "Check the error below before trying again."; else -> "Start when you’re ready to accept connections." }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
