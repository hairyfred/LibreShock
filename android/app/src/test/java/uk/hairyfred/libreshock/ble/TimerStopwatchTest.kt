package uk.hairyfred.libreshock.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerStopwatchTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Five captures from the May 19 2026 Timer & Stopwatch decode session.
    @Test
    fun timerZap0PctEvery5s() {
        val cfg = TnsConfig(
            mode = TnsMode.TIMER, durationSeconds = 0,
            intervals = listOf(TnsInterval(stim = TnsStim.ZAP, intensity = 0, everySeconds = 5)),
        )
        assertArrayEquals(hex("220a0012f5020100f00403210500"), buildTnsConfig(cfg))
    }

    @Test
    fun timerZap100PctEvery5s() {
        val cfg = TnsConfig(
            mode = TnsMode.TIMER, durationSeconds = 0,
            intervals = listOf(TnsInterval(stim = TnsStim.ZAP, intensity = 100, everySeconds = 5)),
        )
        assertArrayEquals(hex("220a0012f5020100f00403340500"), buildTnsConfig(cfg))
    }

    @Test
    fun timerBeep75PlusZap100() {
        val cfg = TnsConfig(
            mode = TnsMode.TIMER, durationSeconds = 0,
            intervals = listOf(
                TnsInterval(stim = TnsStim.BEEP, intensity = 75, everySeconds = 10),
                TnsInterval(stim = TnsStim.ZAP, intensity = 100, everySeconds = 1),
            ),
        )
        assertArrayEquals(hex("220e0012f5020100f008022f0a0003340100"), buildTnsConfig(cfg))
    }

    @Test
    fun timerVibe50PlusZap100() {
        val cfg = TnsConfig(
            mode = TnsMode.TIMER, durationSeconds = 0,
            intervals = listOf(
                TnsInterval(stim = TnsStim.VIBE, intensity = 50, everySeconds = 5),
                TnsInterval(stim = TnsStim.ZAP, intensity = 100, everySeconds = 1),
            ),
        )
        assertArrayEquals(hex("220e0012f5020100f008012a050003340100"), buildTnsConfig(cfg))
    }

    @Test
    fun stopwatch2MinZap50PlusZap100() {
        val cfg = TnsConfig(
            mode = TnsMode.STOPWATCH, durationSeconds = 120,
            intervals = listOf(
                TnsInterval(stim = TnsStim.ZAP, intensity = 50, everySeconds = 5),
                TnsInterval(stim = TnsStim.ZAP, intensity = 100, everySeconds = 1),
            ),
        )
        assertArrayEquals(hex("220e0013f5020178f008032a050003340100"), buildTnsConfig(cfg))
    }

    @Test
    fun intensityRoundtripIsStable() {
        // Every 0-100 percent must re-encode to itself after going through
        // both directions, even though tnsIntensityToByte uses truncation.
        for (pct in 0..100) {
            val byte = tnsIntensityToByte(pct)
            val back = tnsIntensityFromByte(byte)
            // Round-trip may differ by up to one step because the byte
            // range (19) is smaller than the percent range (100) — but
            // the byte must always round-trip exactly.
            assertEquals("byte $byte (pct $pct -> $back)", byte, tnsIntensityToByte(back))
        }
    }
}
