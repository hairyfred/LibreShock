package uk.hairyfred.libreshock.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepHistoryTest {

    @Test
    fun buildsRequestPayloads() {
        // All-sessions, range start
        assertArrayEquals(
            byteArrayOf(0x03, 0, 0, 0, 0, 0, 0, 0, 0),
            buildSleepRequest(queryType = 0x03, sessionId = 0, rangeEnd = false),
        )
        // All-sessions, range end
        assertArrayEquals(
            byteArrayOf(0x03, 0, 0, 0, 0,
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()),
            buildSleepRequest(queryType = 0x03, sessionId = 0, rangeEnd = true),
        )
        // Specific session 0x4d, range start
        assertArrayEquals(
            byteArrayOf(0x03, 0x4d, 0, 0, 0, 0, 0, 0, 0),
            buildSleepRequest(queryType = 0x03, sessionId = 0x4d, rangeEnd = false),
        )
        // 0x82 query type, session 0x01f1, range end
        assertArrayEquals(
            byteArrayOf(
                0x82.toByte(), 0xf1.toByte(), 0x01, 0, 0,
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            ),
            buildSleepRequest(queryType = 0x82, sessionId = 0x01f1, rangeEnd = true),
        )
    }

    @Test
    fun parsesEmptyOnShortInput() {
        assertTrue(parseSleepSessions(byteArrayOf()).isEmpty())
        assertTrue(parseSleepSessions(ByteArray(10)).isEmpty())
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun parsesSingleSessionFromMinimalIndex() {
        // 14-byte response header (count=1, byte_count=17)
        val responseHeader = bytes(0x43, 0x00, 0x44, 0x00, 0x00, 0x00,
                                   0x01, 0, 0, 0, 0x11, 0, 0, 0)
        // Sub-header: first-session op (0x3f), len=5, sid=68, ts=1774033206 LE
        val subHeader = bytes(0x3f, 0x03, 0x05, 0x00,
                              0x44, 0x00, 0x00, 0x00,
                              0x36, 0x99, 0xbd, 0x69)
        val body = bytes(1, 2, 3, 4, 5)
        val sessions = parseSleepSessions(responseHeader + subHeader + body)
        assertEquals(1, sessions.size)
        assertEquals(68, sessions[0].sid)
        assertEquals(1774033206L, sessions[0].timestampSeconds)
        assertArrayEquals(body, sessions[0].body)
    }

    @Test
    fun parsesMultipleConcatenatedSessions() {
        val responseHeader = bytes(0x43, 0x00, 0x44, 0, 0, 0, 0x02, 0, 0, 0, 0x1e, 0, 0, 0)
        val first = bytes(0x3f, 0x03, 0x03, 0x00, 0x44, 0, 0, 0,
                          0x36, 0x99, 0xbd, 0x69, 0x11, 0x22, 0x33)
        val second = bytes(0x7f, 0x03, 0x03, 0x00, 0x45, 0, 0, 0,
                           0x40, 0x99, 0xbd, 0x69, 0x44, 0x55, 0x66)
        val sessions = parseSleepSessions(responseHeader + first + second)
        assertEquals(2, sessions.size)
        assertEquals(68, sessions[0].sid)
        assertEquals(69, sessions[1].sid)
        assertArrayEquals(bytes(0x11, 0x22, 0x33), sessions[0].body)
        assertArrayEquals(bytes(0x44, 0x55, 0x66), sessions[1].body)
    }

    @Test
    fun decodesActivityNightInto3Stages() {
        // Construct a session body with one 0x21 wrapper: bedtime = some
        // anchor + 10 activity bytes (mix of Deep/Sleep/Awake samples).
        val anchor = 1778711439L  // 2026-05-13 23:30:39 BST
        val bedtimeLE = bytes(
            (anchor and 0xff).toInt(),
            ((anchor shr 8) and 0xff).toInt(),
            ((anchor shr 16) and 0xff).toInt(),
            ((anchor shr 24) and 0xff).toInt(),
        )
        // 2 Awake (high), 4 Sleep (mid), 4 Deep (zero)
        val activity = bytes(100, 90, 10, 20, 30, 40, 0, 0, 1, 0)
        val wrapper = bytes(0x21, 2 + 4 + activity.size, 0, 0) + bedtimeLE + activity
        val nights = decodeSleepNights(wrapper, anchor, estimateRemSplit = false)
        assertEquals(1, nights.size)
        val n = nights[0]
        assertEquals(anchor, n.bedtimeSeconds)
        assertEquals(anchor + 10 * 300, n.wakeTimeSeconds)
        assertEquals(2 * 300, n.awakeSeconds)
        assertEquals(4 * 300, n.sleepSeconds)
        assertEquals(4 * 300, n.deepSeconds)
        assertEquals(false, n.approximateSplit)
        assertEquals(0, n.lightSeconds)
        assertEquals(0, n.remSeconds)
    }

    @Test
    fun decodesActivityNightWithEstimatedRemSplit() {
        val anchor = 1778711439L
        val bedtimeLE = bytes(
            (anchor and 0xff).toInt(),
            ((anchor shr 8) and 0xff).toInt(),
            ((anchor shr 16) and 0xff).toInt(),
            ((anchor shr 24) and 0xff).toInt(),
        )
        // 10 sleep-band bytes: 4 low-activity (REM-leaning), 6 higher (Light)
        val activity = bytes(2, 3, 5, 7, 10, 15, 20, 25, 30, 40)
        val wrapper = bytes(0x21, 2 + 4 + activity.size, 0, 0) + bedtimeLE + activity
        val nights = decodeSleepNights(wrapper, anchor, estimateRemSplit = true)
        val n = nights[0]
        assertEquals(true, n.approximateSplit)
        assertEquals(10 * 300, n.sleepSeconds)
        // 40% of 10 = 4 windows -> REM 4*300, Light 6*300
        assertEquals(4 * 300, n.remSeconds)
        assertEquals(6 * 300, n.lightSeconds)
    }
}
