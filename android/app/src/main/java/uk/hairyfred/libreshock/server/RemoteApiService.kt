package uk.hairyfred.libreshock.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import uk.hairyfred.libreshock.MainActivity
import uk.hairyfred.libreshock.shockDevice

/**
 *  Foreground service that hosts the optional Remote API. A persistent
 *  notification is the price of keeping the BLE connection + HTTP listener
 *  alive while the app is backgrounded — Android won't let us do that
 *  without one.
 *
 *  Iteration 1: skeleton only. Starts/stops, shows the notification, and
 *  uses the application-scoped [ShockDevice]. Ktor + endpoints are wired
 *  in by iteration 2.
 */
class RemoteApiService : Service() {

    companion object {
        private const val TAG = "RemoteApiService"
        private const val CHANNEL_ID = "remote_api"
        private const val NOTIFICATION_ID = 7261  // distinct from alarm-fire

        /** True while the service is alive — lets the Settings UI render the
         *  toggle's current state without binding to the service. Set in
         *  onCreate / cleared in onDestroy. */
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
                "Remote API server",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent notification while LibreShock's " +
                    "Remote API server is running. Required by Android to " +
                    "keep the BLE connection alive in the background."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var server: ApplicationEngine? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        running = true
        startKtorServer()
        Log.i(TAG, "started on port ${ApiAuth.port(ApiAuth.prefs(this))}")
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

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("LibreShock Remote API")
            .setContentText("Server running — tap to open the app")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentIntent(tapIntent)
            .build()
    }
}
