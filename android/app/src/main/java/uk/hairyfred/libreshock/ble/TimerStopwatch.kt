package uk.hairyfred.libreshock.ble

/**
 *  Timer & Stopwatch protocol — Pavlok "Timer & Stopwatch" screen.
 *
 *  Wire opcode is 0x22, written to char 7001 in service 156e7000 (the
 *  same characteristic as button rebinding, opcode 0x02 for that).
 *  Watch parses the payload, switches its display to Timer/Stopwatch
 *  mode, and arms the recurring stim intervals. Start/stop is done
 *  from the watch buttons (long-press middle by default).
 *
 *  Decoded May 19 2026 from 5 isolated captures (0%/100% intensity
 *  extremes, vibe/beep/zap stim classes, timer + stopwatch modes).
 *  Mirrors `libreshock.py` byte-for-byte — see verify_combos.py and
 *  the TimerStopwatchTest.kt suite.
 */

/** Watch mode in the Timer & Stopwatch screen. */
enum class TnsMode(val byte: Int) {
    /** Countdown for a fixed duration (seconds, 0..255). */
    TIMER(0x12),
    /** Open-ended counter; ignores duration. */
    STOPWATCH(0x13),
}

/** Stim class for a Timer/Stopwatch interval — same numbering as the
 *  per-button action classes (1=vibe, 2=beep, 3=zap). */
enum class TnsStim(val byte: Int) {
    VIBE(0x01),
    BEEP(0x02),
    ZAP(0x03),
}

/** A single recurring stim during a Timer/Stopwatch run. */
data class TnsInterval(
    val stim: TnsStim = TnsStim.ZAP,
    /** 0-100 user-facing percentage; encoder maps onto the watch's
     *  internal 0x21-0x34 range (a safety cap, since intervals can
     *  fire every second). */
    val intensity: Int = 50,
    /** Repeat interval in seconds (1..255). */
    val everySeconds: Int = 5,
)

/** Timer & Stopwatch configuration. The vendor app constrains intervals
 *  so only one stim can share a given `everySeconds` value, but you can
 *  combine multiple intervals with different timings. */
data class TnsConfig(
    val mode: TnsMode = TnsMode.STOPWATCH,
    /** Timer countdown duration in seconds (0..255). Ignored when
     *  [mode] = STOPWATCH. */
    val durationSeconds: Int = 0,
    val intervals: List<TnsInterval> = emptyList(),
)

internal const val TNS_INTENSITY_MIN = 0x21  // 33 — 0%
internal const val TNS_INTENSITY_MAX = 0x34  // 52 — 100%
private const val TNS_INTENSITY_RANGE = TNS_INTENSITY_MAX - TNS_INTENSITY_MIN  // 19

/** Map a 0-100 percent onto the watch's 0x21-0x34 intensity byte.
 *  Vendor app uses integer truncation, not rounding — byte-exact
 *  verified for 0/50/75/100% in TimerStopwatchTest. */
fun tnsIntensityToByte(percent: Int): Int {
    val p = percent.coerceIn(0, 100)
    return TNS_INTENSITY_MIN + (p * TNS_INTENSITY_RANGE) / 100
}

/** Inverse of [tnsIntensityToByte]. Returns 0-100. */
fun tnsIntensityFromByte(byteValue: Int): Int {
    val clamped = byteValue.coerceIn(TNS_INTENSITY_MIN, TNS_INTENSITY_MAX)
    // Round up so the parsed percent re-encodes to the same byte.
    return ((clamped - TNS_INTENSITY_MIN) * 100 + TNS_INTENSITY_RANGE - 1) / TNS_INTENSITY_RANGE
}

/** Build the BLE write payload for a Timer/Stopwatch config.
 *
 *  Packet:
 *      22 <body_len:u16-LE> <body> 00
 *  Body:
 *      <mode> f5 02 01 <duration> f0 <indicator> <intervals_data>
 *
 *  Each interval is 3 bytes (`<stim> <intensity> <every_seconds>`);
 *  multiple intervals are joined with a `0x00` separator. The indicator
 *  byte equals `1 + len(intervals_data)` — effectively the offset from
 *  itself to the byte right after the last interval. */
fun buildTnsConfig(config: TnsConfig): ByteArray {
    val chunks = config.intervals.map { iv ->
        val secs = iv.everySeconds.coerceIn(1, 0xFF)
        byteArrayOf(
            iv.stim.byte.toByte(),
            tnsIntensityToByte(iv.intensity).toByte(),
            secs.toByte(),
        )
    }
    val intervalsData = if (chunks.isEmpty()) byteArrayOf() else {
        chunks.reduce { acc, next -> acc + byteArrayOf(0x00) + next }
    }
    val indicator = 1 + intervalsData.size
    val duration = config.durationSeconds.coerceIn(0, 0xFF)
    val body = byteArrayOf(
        config.mode.byte.toByte(),
        0xf5.toByte(), 0x02, 0x01,
        duration.toByte(),
        0xf0.toByte(),
        indicator.toByte(),
    ) + intervalsData
    return byteArrayOf(0x22) +
        byteArrayOf((body.size and 0xFF).toByte(), ((body.size shr 8) and 0xFF).toByte()) +
        body + byteArrayOf(0x00)
}
