package org.madmail.mobile.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.dropWhile
import org.madmail.mobile.MainActivity

class HostingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session by lazy { getSharedPreferences("hosting-session", Context.MODE_PRIVATE) }
    private var requested = false
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("hosting", "Hosting", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            LocalEngine.get(this@HostingService).state.dropWhile { it.phase !in listOf(EnginePhase.Starting, EnginePhase.Running) }.collect { state ->
                if (state.phase == EnginePhase.Running) getSystemService(NotificationManager::class.java).notify(41, notification("Hosting · ${state.version}"))
                if (state.phase == EnginePhase.Failed || (state.phase == EnginePhase.Stopped && !requested)) {
                    requested = false
                    session.edit().putBoolean("requested", false).commit()
                    wake?.let { if (it.isHeld) it.release() }; wake = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            requested = false
            // Persist before stopping: a process death must not undo the user's Stop action.
            session.edit().putBoolean("requested", false).commit()
            scope.launch { withContext(Dispatchers.IO) { LocalEngine.get(this@HostingService).stop() }; stopSelfResult(startId) }
            return START_NOT_STICKY
        } else {
            // A null intent is Android restoring a killed sticky service, not a new user request.
            if (intent == null && !session.getBoolean("requested", false)) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            requested = true
            session.edit().putBoolean("requested", true).commit()
            startForeground(41, notification("Starting Madmail…"))
            if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "madmail:hosting").apply { acquire() }
            scope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { LocalEngine.get(this@HostingService).start() } }
                if (result.isFailure) {
                    requested = false
                    session.edit().putBoolean("requested", false).commit()
                    stopSelfResult(startId)
                }
            }
        }
        return START_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, HostingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "hosting").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Madmail")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        val engine = LocalEngine.get(this)
        val needsStop = engine.state.value.phase in listOf(EnginePhase.Starting, EnginePhase.Running, EnginePhase.Stopping)
        scope.launch {
            if (needsStop) withContext(Dispatchers.IO) { engine.stop() }
            scope.cancel()
        }
        super.onDestroy()
    }
}
