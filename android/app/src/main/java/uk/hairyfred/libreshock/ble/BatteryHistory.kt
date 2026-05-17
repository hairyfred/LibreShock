package uk.hairyfred.libreshock.ble

import android.content.Context
import java.io.File

data class BatterySample(val timestampMs: Long, val percent: Int)

/**
 * Append-only log of (timestamp, percent) battery readings, persisted to
 * a plain CSV file in the app's private storage. Cheap to read/append; old
 * samples auto-trimmed past [maxAgeMs] on the next read.
 */
class BatteryHistory(context: Context) {
    private val file: File = File(context.filesDir, "battery_history.csv")

    /** Append a new sample. Silent on errors. */
    fun append(percent: Int, timestampMs: Long = System.currentTimeMillis()) {
        try {
            file.appendText("$timestampMs,$percent\n")
        } catch (_: Exception) {
        }
    }

    /** Return samples sorted by timestamp ascending. Older-than [maxAgeMs] are excluded. */
    fun samples(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): List<BatterySample> {
        if (!file.exists()) return emptyList()
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val out = mutableListOf<BatterySample>()
        try {
            file.forEachLine { line ->
                val parts = line.split(",")
                if (parts.size != 2) return@forEachLine
                val ts = parts[0].toLongOrNull() ?: return@forEachLine
                val pct = parts[1].toIntOrNull() ?: return@forEachLine
                if (ts >= cutoff) out.add(BatterySample(ts, pct))
            }
        } catch (_: Exception) {
        }
        return out.sortedBy { it.timestampMs }
    }

    /** Wipe the log. */
    fun clear() {
        try { file.delete() } catch (_: Exception) {}
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 14L * 24 * 3600 * 1000  // 14 days
    }
}
