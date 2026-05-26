package uk.hairyfred.libreshock.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uk.hairyfred.libreshock.MainActivity
import uk.hairyfred.libreshock.ble.NotifyOpcode
import uk.hairyfred.libreshock.shockDevice
import uk.hairyfred.libreshock.ui.AlarmNotifier

/**
 *  Foreground service that owns the always-on side of LibreShock — the
 *  bits that need to keep working even when MainActivity is destroyed
 *  (swiped away from Recents, OS reclaim, etc.). A persistent
 *  notification is the price of staying alive in the background.
 *
 *  Two independent duties, gated by separate Settings toggles:
 *
 *  - **Remote API** (`PREF_ENABLED`) — Ktor HTTP server on
 *    `0.0.0.0:<port>`; external devices can trigger vibrate / beep / zap /
 *    alarm-stop. Off by default. See [ApiRoutes].
 *  - **Background alarm notifications** (`PREF_BG_ALARMS`) — collect
 *    [device.alarmEvents] and post a system notification via
 *    [AlarmNotifier] when the watch reports an alarm firing. Survives
 *    the app being swiped away. Off by default.
 *
 *  Service starts when EITHER toggle is on; stops when both are off.
 *  Both subsystems share the application-scoped [ShockDevice] so a
 *  single BLE connection serves both.
 */
class RemoteApiService : Service() {

    companion object {
        private const val TAG = "RemoteApiService"
        private const val CHANNEL_ID = "remote_api"
        private const val NOTIFICATION_ID = 7261  // distinct from alarm-fire

        /** True while the service is alive — lets the Settings UI render
         *  the toggle's current state without binding. Set in onCreate,
         *  cleared in onDestroy. */
        @Volatile var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, RemoteApiService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteApiService::class.java))
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LibreShock background",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent notification while a LibreShock " +
                    "background feature is running (Remote API server " +
                    "and/or alarm-fire notifications)."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var server: ApplicationEngine? = null
    private var alarmJob: Job? = null
    private var alarmReceiver: BroadcastReceiver? = null
    private lateinit var serviceScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        running = true
        val prefs = ApiAuth.prefs(this)
        if (prefs.getBoolean(ApiAuth.PREF_ENABLED, false)) startKtorServer()
        if (prefs.getBoolean(ApiAuth.PREF_BG_ALARMS, false)) startAlarmListener()
        Log.i(TAG, "started — api=${prefs.getBoolean(ApiAuth.PREF_ENABLED, false)} " +
            "bg_alarms=${prefs.getBoolean(ApiAuth.PREF_BG_ALARMS, false)}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Don't restart automatically if the OS kills us — the user toggles
        // this explicitly and we don't want a zombie service after a crash.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        server = null
        alarmReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        alarmReceiver = null
        alarmJob?.cancel()
        alarmJob = null
        serviceScope.cancel()
        running = false
        Log.i(TAG, "stopped")
    }

    private fun startKtorServer() {
        val device = shockDevice()
        val port = ApiAuth.port(ApiAuth.prefs(this))
        // Bind to 0.0.0.0 so the API is reachable from any device on the
        // local network. Token auth is the only line of defence — the
        // Settings sub-screen warns users to keep the network trusted.
        server = embeddedServer(CIO, host = "0.0.0.0", port = port) {
            installRemoteApiRoutes(this@RemoteApiService, device)
        }.start(wait = false)
    }

    private fun startAlarmListener() {
        val device = shockDevice()
        // Collect alarm-fire notifications from the watch and post a system
        // notification + handle Stop/Snooze actions even when MainActivity
        // is dead. We don't have access to MainActivity's `alarms` cache
        // here, so the notification is "dumb" — no guarantor-aware text.
        // Users can still tap the notification to open the app and get
        // the full in-app dialog (QR scanner, puzzle, etc.).
        alarmJob = serviceScope.launch {
            device.alarmEvents.collect { event ->
                when (event.opcode) {
                    NotifyOpcode.ALARM_FIRING ->
                        AlarmNotifier.notifyFiring(this@RemoteApiService, event.alarmId)
                    NotifyOpcode.STOP_OK, NotifyOpcode.SNOOZE_OK ->
                        AlarmNotifier.cancel(this@RemoteApiService)
                    else -> {}
                }
            }
        }

        // Handle Stop/Snooze actions from the notification.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    AlarmNotifier.ACTION_STOP -> serviceScope.launch {
                        try { device.stopAlarm() } catch (_: Exception) {}
                        AlarmNotifier.cancel(this@RemoteApiService)
                    }
                    AlarmNotifier.ACTION_SNOOZE -> serviceScope.launch {
                        try { device.snoozeAlarm() } catch (_: Exception) {}
                        AlarmNotifier.cancel(this@RemoteApiService)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AlarmNotifier.ACTION_STOP)
            addAction(AlarmNotifier.ACTION_SNOOZE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        alarmReceiver = receiver
    }

    private fun buildNotification(): Notification {
        val prefs = ApiAuth.prefs(this)
        val api = prefs.getBoolean(ApiAuth.PREF_ENABLED, false)
        val bg = prefs.getBoolean(ApiAuth.PREF_BG_ALARMS, false)
        val text = when {
            api && bg -> "Remote API + background alarm notifications"
            api -> "Remote API server running"
            bg -> "Listening for alarm-fire notifications"
            else -> "Background — tap to open"
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("LibreShock background")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentIntent(tapIntent)
            .build()
    }
}
