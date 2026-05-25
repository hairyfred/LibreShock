package uk.hairyfred.libreshock.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-exact verification of [buildAlarmBlock] against captured vendor-app packets.
 * Mirrors scripts/verify_combos.py.
 *
 * Capture sources (in repo at OpenShock/captures/):
 *  - combos_5.btsnoop  : 5 stim combinations at 06:01-06:05 (no day mask)
 *  - days_3.btsnoop    : single-day captures (Wed, Mon, Sat)
 */
class AlarmProtocolTest {

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // --- 5 stim combinations (TM flag 0x80 = no day mask) ---

    @Test
    fun vibeOnly() {
        val captured = hexToBytes(
            "48413900414e0500616c61726d544d040000010680574401001e574902000f00" +
            "534e010001414f0100014d4809004d430500850c32fafa494402000100"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 6, minute = 1, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = false),
                zap = AlarmAction(enabled = false),
            ),
            alarmId = 1,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun beepOnly() {
        val captured = hexToBytes(
            "48413900414e0500616c61726d544d040000020680574401001e574902000f00" +
            "534e010001414f0100015048090050430500850c32fafa494402000200"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 6, minute = 2, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = false),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = false),
            ),
            alarmId = 2,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun zapOnly() {
        val captured = hexToBytes(
            "48413600414e0500616c61726d544d040000030680574401001e574902000f00" +
            "534e010001414f0100015a4806005a4302008132494402000300"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 6, minute = 3, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = false),
                beep = AlarmAction(enabled = false),
                zap = AlarmAction(enabled = true, count = 1, intensity = 50),
            ),
            alarmId = 3,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun vibePlusBeep() {
        val captured = hexToBytes(
            "48414600414e0500616c61726d544d040000040680574401001e574902000f00" +
            "534e010001414f0100014d4809004d430500850c32fafa5048090050430500" +
            "850c32fafa494402000400"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 6, minute = 4, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = false),
            ),
            alarmId = 4,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun beepPlusZap() {
        val captured = hexToBytes(
            "48414300414e0500616c61726d544d040000050680574401001e574902000f00" +
            "534e010001414f0100015048090050430500850c32fafa5a4806005a430200" +
            "8132494402000500"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 6, minute = 5, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = false),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = true, count = 1, intensity = 50),
            ),
            alarmId = 5,
        )
        assertArrayEquals(captured, ours)
    }

    // --- Day-of-week captures (all 3 stims enabled, day in TM flag byte) ---

    @Test
    fun wednesdayOnly() {
        val captured = hexToBytes(
            "48415000414e0500616c61726d544d040000110688574401001e574902000f00" +
            "534e010001414f0100014d4809004d430500850c32fafa5048090050430500" +
            "850c32fafa5a4806005a4302008132494402000100"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 6, minute = 11, name = "alarm",
                weekdays = Weekday.WEDNESDAY, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = true, count = 1, intensity = 50),
            ),
            alarmId = 1,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun mondayOnly() {
        val captured = hexToBytes(
            "48415000414e0500616c61726d544d040000120682574401001e574902000f00" +
            "534e010001414f0100014d4809004d430500850c32fafa5048090050430500" +
            "850c32fafa5a4806005a4302008132494402000200"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 6, minute = 12, name = "alarm",
                weekdays = Weekday.MONDAY, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = true, count = 1, intensity = 50),
            ),
            alarmId = 2,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun saturdayOnly() {
        val captured = hexToBytes(
            "48415000414e0500616c61726d544d0400001306c0574401001e574902000f00" +
            "534e010001414f0100014d4809004d430500850c32fafa5048090050430500" +
            "850c32fafa5a4806005a4302008132494402000300"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 6, minute = 13, name = "alarm",
                weekdays = Weekday.SATURDAY, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = true, count = 1, intensity = 50),
            ),
            alarmId = 3,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun jumpingJacksOneRep() {
        // 8am alarm, weekdays-bit 0, vibe(50)+zap(5x70), AO=0x02 + JL=1.
        val captured = hexToBytes(
            "48414800414e0500616c61726d544d040000000880574401001e574902000500" +
            "534e010001414f0100024a4c0100014d4809004d430500850c32fafa5a480600" +
            "5a4302008546494402000200"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 8, minute = 0, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 5,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = false),
                zap = AlarmAction(enabled = true, count = 5, intensity = 70),
                guarantor = Guarantor.JUMPING_JACKS, jumpingJacksCount = 1,
            ),
            alarmId = 2,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun jumpingJacksThreeReps() {
        val captured = hexToBytes(
            "48414800414e0500616c61726d544d040000000880574401001e574902000500" +
            "534e010001414f0100024a4c0100034d4809004d430500850c32fafa5a480600" +
            "5a4302008546494402000200"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 8, minute = 0, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 5,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = false),
                zap = AlarmAction(enabled = true, count = 5, intensity = 70),
                guarantor = Guarantor.JUMPING_JACKS, jumpingJacksCount = 3,
            ),
            alarmId = 2,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun qrCodeGuarantor() {
        // Same alarm shape, AO=0x04, no JL.
        val captured = hexToBytes(
            "48414300414e0500616c61726d544d040000000880574401001e574902000500" +
            "534e010001414f0100044d4809004d430500850c32fafa5a4806005a43020085" +
            "46494402000200"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 8, minute = 0, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 5,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = false),
                zap = AlarmAction(enabled = true, count = 5, intensity = 70),
                guarantor = Guarantor.QR_CODE,
            ),
            alarmId = 2,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun puzzleGuarantor() {
        val captured = hexToBytes(
            "48414300414e0500616c61726d544d040000000880574401001e574902000500" +
            "534e010001414f0100804d4809004d430500850c32fafa5a4806005a43020085" +
            "46494402000200"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 8, minute = 0, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 5,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = false),
                zap = AlarmAction(enabled = true, count = 5, intensity = 70),
                guarantor = Guarantor.PUZZLE,
            ),
            alarmId = 2,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun parsesGuarantorRoundtrip() {
        // Build → parse → verify the guarantor and JL count survive.
        val pkt = buildAlarmPacket(listOf(
            AlarmConfig(
                hour = 7, minute = 30, name = "morning",
                weekdays = Weekday.EVERYDAY,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 80),
                guarantor = Guarantor.JUMPING_JACKS, jumpingJacksCount = 15,
            ),
        ))
        val parsed = parseAlarms(pkt)
        assertEquals(1, parsed.size)
        assertEquals(Guarantor.JUMPING_JACKS, parsed[0].guarantor)
        assertEquals(15, parsed[0].jumpingJacksCount)
    }

    // Additional wake-up features — May 19 2026 captures, each isolated to
    // exactly one toggle on the 13:47 vibe+beep+zap test alarm.
    @Test
    fun snoozeZapSetsSnBit2() {
        val captured = hexToBytes(
            "48415000414e0500616c61726d544d040000471380574401001e574902000f00" +
            "534e010003414f0100014d4809004d430500850c32fafa5048090050430500" +
            "850c32fafa5a4806005a4302008132494402000300"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 13, minute = 47, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = true, count = 1, intensity = 50),
                snoozeZap = true,
            ),
            alarmId = 3,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun disableSnoozeClearsSnBit0() {
        val captured = hexToBytes(
            "48415000414e0500616c61726d544d040000471380574401001e574902000f00" +
            "534e010000414f0100014d4809004d430500850c32fafa5048090050430500" +
            "850c32fafa5a4806005a4302008132494402000300"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 13, minute = 47, name = "alarm",
                weekdays = 0, snooze = false, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = true, count = 1, intensity = 50),
            ),
            alarmId = 3,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun lightSleepSetsAoBit3() {
        val captured = hexToBytes(
            "48415000414e0500616c61726d544d040000471380574401001e574902000f00" +
            "534e010001414f0100094d4809004d430500850c32fafa5048090050430500" +
            "850c32fafa5a4806005a4302008132494402000400"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 13, minute = 47, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = true, count = 1, intensity = 50),
                lightSleep = true,
            ),
            alarmId = 4,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun escalatingAddsEsTlv() {
        val captured = hexToBytes(
            "48415500414e0500616c61726d544d040000471380574401001e574902000f00" +
            "534e010001414f01002145530100054d4809004d430500850c32fafa504809" +
            "0050430500850c32fafa5a4806005a4302008132494402000400"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 13, minute = 47, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = true, count = 1, intensity = 50),
                escalating = true,
            ),
            alarmId = 4,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun smartAlarmAddsSmTlv() {
        val captured = hexToBytes(
            "48415700414e0500616c61726d544d040000471380574401001e574902000f00" +
            "534e010001414f010041534d03000f05064d4809004d430500850c32fafa50" +
            "48090050430500850c32fafa5a4806005a4302008132494402000400"
        )
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 13, minute = 47, name = "alarm",
                weekdays = 0, snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = true, count = 5, intensity = 50),
                zap = AlarmAction(enabled = true, count = 1, intensity = 50),
                smartAlarm = true,
            ),
            alarmId = 4,
        )
        assertArrayEquals(captured, ours)
    }

    @Test
    fun additionalFeaturesRoundtrip() {
        val pkt = buildAlarmPacket(listOf(
            AlarmConfig(
                hour = 7, minute = 30, name = "morning",
                weekdays = Weekday.EVERYDAY,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 80),
                snooze = true, snoozeZap = true,
                lightSleep = true, escalating = true, smartAlarm = true,
            ),
        ))
        val parsed = parseAlarms(pkt)
        assertEquals(1, parsed.size)
        assertEquals(true, parsed[0].snoozeZap)
        assertEquals(true, parsed[0].lightSleep)
        assertEquals(true, parsed[0].escalating)
        assertEquals(true, parsed[0].smartAlarm)
        assertEquals(Guarantor.NONE, parsed[0].guarantor)
    }

    @Test
    fun crc16IsCcittFalse() {
        // CRC of "123456789" is 0x29B1 for CRC-16/CCITT-FALSE.
        assertEquals(0x29B1, crc16Ccitt("123456789".toByteArray()))
    }

    /** Disabling an alarm must clear the TM armed bit AND the AO byte —
     *  the watch fires based on TM byte 3's high bit, not AO, so leaving
     *  TM=0x80 set caused disabled alarms to still trigger (fixed v0.1.11). */
    @Test
    fun disabledAlarmClearsTmArmedBit() {
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 7, minute = 30, name = "alarm",
                weekdays = 0x3E,  // weekdays
                enabled = false,  // <- the disable toggle
                snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = false),
                zap = AlarmAction(enabled = false),
            ),
            alarmId = 1,
        )
        // Locate TM tag. TLV layout: 2-byte tag + 2-byte u16-LE length +
        // payload. TM payload is 4 bytes [00, minBcd, hourBcd, flag|mask] —
        // byte 3 is at tag-offset + 4 + 3 = +7.
        val tmIdx = indexOfTag(ours, "TM")
        assertEquals(true, tmIdx >= 0)
        val tmByte3 = ours[tmIdx + 7].toInt() and 0xFF
        assertEquals("TM byte 3 must have bit 0x80 cleared when disabled",
            0, tmByte3 and 0x80)
        assertEquals("TM byte 3 low bits preserve the day mask",
            0x3E, tmByte3 and 0x7F)
        // AO byte must also be 0x00 for a disabled alarm. AO is a single-
        // byte payload at tag-offset + 4.
        val aoIdx = indexOfTag(ours, "AO")
        assertEquals(true, aoIdx >= 0)
        val aoByte = ours[aoIdx + 4].toInt() and 0xFF
        assertEquals("AO must be 0 for a disabled alarm", 0, aoByte)
    }

    /** Re-enabling the same alarm puts the armed bit back. */
    @Test
    fun reEnabledAlarmSetsTmArmedBit() {
        val ours = buildAlarmBlock(
            AlarmConfig(
                hour = 7, minute = 30, name = "alarm",
                weekdays = 0x3E,
                enabled = true,
                snooze = true, stimulusInterval = 15,
                vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
                beep = AlarmAction(enabled = false),
                zap = AlarmAction(enabled = false),
            ),
            alarmId = 1,
        )
        val tmIdx = indexOfTag(ours, "TM")
        val tmByte3 = ours[tmIdx + 7].toInt() and 0xFF
        assertEquals(0x80 or 0x3E, tmByte3)
        val aoIdx = indexOfTag(ours, "AO")
        val aoByte = ours[aoIdx + 4].toInt() and 0xFF
        assertEquals(0x01, aoByte)
    }

    /** Find the index of a 2-byte ASCII tag (e.g. "TM", "AO") in a block,
     *  returning -1 if absent. The tag header lives at the returned index;
     *  the 2-byte u16-LE length follows, then the payload. */
    private fun indexOfTag(block: ByteArray, tag: String): Int {
        val t = tag.toByteArray(Charsets.US_ASCII)
        for (i in 0..(block.size - 4)) {
            if (block[i] == t[0] && block[i + 1] == t[1]) return i
        }
        return -1
    }
}
