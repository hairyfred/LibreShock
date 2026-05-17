package uk.hairyfred.libreshock.ble

import android.util.Log

/**
 * Gated debug logging — verbose Log.d calls only fire when [enabled] is true.
 * Toggle from Settings → "Enable debug logging". Errors and warnings stay
 * unconditionally logged (Log.e / Log.w) since they only fire on failure.
 */
object DebugLog {
    @Volatile
    var enabled: Boolean = false

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, msg)
    }
}
