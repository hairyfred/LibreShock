package uk.hairyfred.libreshock

import android.app.Application
import uk.hairyfred.libreshock.ble.ShockDevice

/**
 *  Process-scoped application class. Owns the single [ShockDevice]
 *  instance shared between [MainActivity] and the optional Remote API
 *  [uk.hairyfred.libreshock.server.RemoteApiService] — they need to
 *  share the BLE connection rather than each opening their own.
 *
 *  Activity/service code reads the device via the helper
 *  [Context.shockDevice] below.
 */
class LibreShockApp : Application() {
    val device: ShockDevice by lazy { ShockDevice(applicationContext) }
}

/** Read the process-scoped [ShockDevice] from anywhere with a Context. */
fun android.content.Context.shockDevice(): ShockDevice =
    (applicationContext as LibreShockApp).device
