package com.mob8n.engine

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.mob8n.Mob8NApp
import com.mob8n.core.HostState
import com.mob8n.core.LOG_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Host #2 (DESIGN D4, §7.2): specialUse FGS started only when the listener is not alive and a runtime trigger or long run needs a
 * live process. Hosts attachRuntimeTriggers(); stops itself after 60 s idle once nothing needs it.
 */
class HostService : Service() {
    private var attached: AutoCloseable? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val engine = Mob8NApp.of(this).engine
        try {
            Notifs.ensureChannels(this)
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, Notifs.HOST_NOTIFICATION_ID, Notifs.hostNotification(this), type)
        } catch (e: Exception) {   // ForegroundServiceStartNotAllowedException / InvalidForegroundServiceTypeException
            Log.w(LOG_TAG, "HostService.startForeground: ${e.message}")
            stopSelf(); return
        }
        HostState.serviceRunning = true
        engine.refreshStatus()
        attached = engine.attachRuntimeTriggers()
        scope.launch { idleWatch(engine) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    /** Stop after 60 s with no active run and no process hold (chat turns, DESIGN4 V6) when the listener can host runtime triggers, or when none are in use. */
    private suspend fun idleWatch(engine: Engine) {
        var idleSince = System.currentTimeMillis()
        while (scope.isActive) {
            delay(IDLE_POLL_MS)
            val hub = engine.hub
            val idle = hub.activeRuns == 0 && engine.holds == 0 && (HostState.listenerConnected || !hub.runtimeTriggersInUse())
            if (!idle) { idleSince = System.currentTimeMillis(); continue }
            if (System.currentTimeMillis() - idleSince >= IDLE_STOP_MS) { stopSelf(); return }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { attached?.close() } catch (e: Exception) { Log.w(LOG_TAG, "detach: ${e.message}") }
        attached = null
        HostState.serviceRunning = false
        runCatching { Mob8NApp.of(this).engine.refreshStatus() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.mob8n.engine.HOST_STOP"
        const val IDLE_STOP_MS = 60_000L
        const val IDLE_POLL_MS = 10_000L
    }
}
