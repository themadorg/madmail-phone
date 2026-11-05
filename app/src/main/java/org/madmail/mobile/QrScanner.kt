package org.madmail.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import org.madmail.mobile.core.QrFrameReader
import java.util.concurrent.Executors

/**
 * Full-screen camera that reads one QR code. [onText] returns true when the code was what the caller wanted
 * (the scanner then closes) and false to keep looking, with [rejected] shown under the viewfinder.
 * Frames are decoded on the phone with zxing; nothing leaves the device.
 */
@Composable
internal fun QrScanner(title: String, hint: String, rejected: String, onText: (String) -> Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var lastRejected by remember { mutableStateOf<String?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }
    val accept by rememberUpdatedState(onText)
    var done by remember { mutableStateOf(false) }
    // Coming back from system settings with the camera now allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!granted && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { granted = true; denied = false }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    if (granted) LaunchedEffect(lifecycleOwner) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrElse { cameraFailed = true; return@LaunchedEffect }
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val reader = QrFrameReader()
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
            .build()
        var buffer = ByteArray(0)
        analysis.setAnalyzer(executor) { image ->
            image.use {
                val plane = it.planes[0]
                val size = plane.rowStride * it.height
                if (buffer.size != size) buffer = ByteArray(size)
                val data = plane.buffer.apply { rewind() }
                data.get(buffer, 0, minOf(data.remaining(), size))
                reader.read(buffer, plane.rowStride, it.width, it.height)?.let { text -> main.execute {
                    if (done || text == lastRejected) return@execute
                    if (accept(text)) done = true else lastRejected = text
                } }
            }
        }
        try {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            awaitCancellation()
        } catch (e: IllegalArgumentException) {
            cameraFailed = true // No back camera.
        } finally {
            analysis.clearAnalyzer()
            provider.unbind(preview, analysis)
            executor.shutdown()
            buffer.fill(0)
        }
    }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted && !cameraFailed) AndroidView({ view }, Modifier.fillMaxSize())
            // A plain frame where the code should sit; decoding uses the whole picture.
            Box(Modifier.align(Alignment.Center).size(260.dp).border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(28.dp)))
            Row(Modifier.fillMaxWidth().systemBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = rememberTap(onClose)) { Icon(Icons.Rounded.Close, stringResource(R.string.host_setup_scan_close), tint = Color.White) }
                Text(title, Modifier.weight(1f).padding(end = 48.dp), color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val message = when {
                    cameraFailed -> stringResource(R.string.host_setup_scan_no_camera)
                    denied -> stringResource(R.string.host_setup_scan_permission)
                    lastRejected != null -> rejected
                    else -> hint
                }
                Text(message, color = if (lastRejected != null && !denied && !cameraFailed) Color(0xFFFFB4AB) else Color.White,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                if (denied) PillButton(stringResource(R.string.host_setup_scan_open_settings), {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
                }, filled = true)
            }
        }
    }
}
