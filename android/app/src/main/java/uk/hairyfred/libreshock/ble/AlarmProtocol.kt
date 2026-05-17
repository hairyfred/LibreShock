package uk.hairyfred.libreshock.ble

/**
 * Alarm protocol implementation for the BLE shock device.
 *
 * Mirrors libreshock.py's protocol builders byte-for-byte. Verified against
 * captured vendor app packets in scripts/verify_combos.py (Python) and
 * VerifyProtocolTest.kt (Kotlin).
 *
 * See CLAUDE.md for the full protocol reference.
 */

/** Weekday bitmask values for AlarmConfig.weekdays. */
object Weekday {
    const val SUNDAY = 0x01
    const val MONDAY = 0x02
    const val TUESDAY = 0x04
    const val WEDNESDAY = 0x08
    const val THURSDAY = 0x10
    const val FRIDAY = 0x20
    const val SATURDAY = 0x40
    const val WEEKDAYS = MONDAY or TUESDAY or WEDNESDAY or THURSDAY or FRIDAY
    const val WEEKENDS = SATURDAY or SUNDAY
    const val EVERYDAY = 0x7F
}

/** Configuration for a single stim (vibe, beep, or zap) in an alarm. */
data class AlarmAction(
    val enabled: Boolean = false,
    val count: Int = 5,
    val intensity: Int = 50,
)

/** Full alarm configuration. */
data class AlarmConfig(
    val hour: Int = 8,
    val minute: Int = 0,
    val name: String = "alarm",
    /** Day-of-week bitmask. 0 = one-shot today, [Weekday.EVERYDAY] = every day. */
    val weekdays: Int = Weekday.EVERYDAY,
    val snooze: Boolean = true,
    val enabled: Boolean = true,
    /** Seconds between successive stimuli within a single firing. */
    val stimulusInterval: Int = 15,
    val vibration: AlarmAction = AlarmAction(),
    val beep: AlarmAction = AlarmAction(),
    val zap: AlarmAction = AlarmAction(),
)

private const val MC_FLAG_ENABLED = 0x80
private const val MC_FLAG_VIBE = 0x01
private const val MC_FLAG_VIBE2 = 0x04
private const val PC_FLAG_ENABLED = 0x80
private const val PC_FLAG_BEEP = 0x01
private const val PC_FLAG_BEEP2 = 0x04
private const val ZC_FLAG_ENABLED = 0x80

private val DEFAULT_PROFILE = "Single 1".toByteArray(Charsets.UTF_8)

/** CRC-16/CCITT-FALSE: poly=0x1021, init=0xFFFF, no reflect. */
internal fun crc16Ccitt(data: ByteArray): Int {
    var crc = 0xFFFF
    for (byte in data) {
        crc = crc xor ((byte.toInt() and 0xFF) shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) {
                ((crc shl 1) xor 0x1021) and 0xFFFF
            } else {
                (crc shl 1) and 0xFFFF
            }
        }
    }
    return crc
}

private fun intToBcd(value: Int): Int = ((value / 10) shl 4) or (value % 10)

private fun bcdToInt(value: Int): Int = ((value shr 4) * 10) + (value and 0x0F)

private fun u16Le(value: Int): ByteArray =
    byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

/** Build a TLV (Tag + 2-byte LE length + data) block. */
private fun tlv(tag: String, data: ByteArray): ByteArray {
    val tagBytes = tag.toByteArray(Charsets.US_ASCII)
    require(tagBytes.size == 2) { "TLV tag must be 2 ASCII chars, got '$tag'" }
    return tagBytes + u16Le(data.size) + data
}

private fun buildMcBlock(config: AlarmConfig): ByteArray {
    // Inner MC: [flags, count=0x0c, intensity, 0xfa, 0xfa]
    val flags = MC_FLAG_ENABLED or MC_FLAG_VIBE or MC_FLAG_VIBE2
    return tlv("MC", byteArrayOf(
        flags.toByte(),
        0x0c,
        config.vibration.intensity.toByte(),
        0xfa.toByte(),
        0xfa.toByte(),
    ))
}

private fun buildPcBlock(config: AlarmConfig): ByteArray {
    val flags = PC_FLAG_ENABLED or PC_FLAG_BEEP or PC_FLAG_BEEP2
    return tlv("PC", byteArrayOf(
        flags.toByte(),
        0x0c,
        config.beep.intensity.toByte(),
        0xfa.toByte(),
        0xfa.toByte(),
    ))
}

private fun buildZcBlock(config: AlarmConfig): ByteArray {
    val count = config.zap.count.coerceIn(1, 15)
    val flags = ZC_FLAG_ENABLED or count
    return tlv("ZC", byteArrayOf(flags.toByte(), config.zap.intensity.toByte()))
}

/**
 * Build a single alarm block: `HA + length(2 LE) + content`.
 *
 * The third byte of the resulting block varies with content size — when
 * printable it appears as 'C', 'P', '9', '6', 'F' etc. in hex dumps. These
 * are NOT tag types; they're just the low byte of the length field.
 */
internal fun buildAlarmBlock(config: AlarmConfig, alarmId: Int): ByteArray {
    val nameBytes = config.name.toByteArray(Charsets.UTF_8).take(20).toByteArray()
    val an = tlv("AN", nameBytes)

    // TM: [0x00, minute_bcd, hour_bcd, 0x80|day_mask]. High bit 0x80 marks armed.
    val dayMask = config.weekdays and 0x7F
    val tm = tlv("TM", byteArrayOf(
        0x00,
        intToBcd(config.minute).toByte(),
        intToBcd(config.hour).toByte(),
        (0x80 or dayMask).toByte(),
    ))

    // WD is a device constant in all observed vendor packets.
    val wd = tlv("WD", byteArrayOf(0x1E))
    val wi = tlv("WI", u16Le(config.stimulusInterval))
    val sn = tlv("SN", byteArrayOf(if (config.snooze) 0x01 else 0x00))
    val ao = tlv("AO", byteArrayOf(if (config.enabled) 0x01 else 0x00))
    val id = tlv("ID", u16Le(alarmId))

    var content = an + tm + wd + wi + sn + ao
    if (config.vibration.enabled) content += tlv("MH", buildMcBlock(config))
    if (config.beep.enabled) content += tlv("PH", buildPcBlock(config))
    if (config.zap.enabled) content += tlv("ZH", buildZcBlock(config))
    content += id

    return "HA".toByteArray(Charsets.US_ASCII) + u16Le(content.size) + content
}

/** Build a complete alarm packet wrapped in the AH/AP envelope with CRC-16. */
fun buildAlarmPacket(
    alarms: List<AlarmConfig>,
    profile: ByteArray = DEFAULT_PROFILE,
): ByteArray {
    val ap = "AP".toByteArray(Charsets.US_ASCII) + u16Le(profile.size) + profile
    var content = ap
    alarms.forEachIndexed { i, cfg ->
        content += buildAlarmBlock(cfg, alarmId = i + 1)
    }

    val header = "AH".toByteArray(Charsets.US_ASCII)
    val length = content.size + 2
    val pktForCrc = header + u16Le(length) + byteArrayOf(0x00, 0x00) + content
    val crc = crc16Ccitt(pktForCrc)
    return header + u16Le(length) + u16Le(crc) + content
}

/** CTRL char commands (write to characteristic 5001). */
object CtrlCommand {
    fun enterWriteMode(profile: ByteArray = DEFAULT_PROFILE): ByteArray =
        byteArrayOf(0x01, 0x00) + profile

    fun exitWriteMode(profile: ByteArray = DEFAULT_PROFILE): ByteArray =
        byteArrayOf(0x00, 0x00) + profile

    fun queryAlarms(profile: ByteArray = DEFAULT_PROFILE): ByteArray =
        byteArrayOf(0x06, 0x00) + profile

    /** Stop a currently-firing alarm. No profile name. */
    val STOP_ALARM = byteArrayOf(0x02, 0x00)

    /** Snooze a currently-firing alarm. No profile name. */
    val SNOOZE_ALARM = byteArrayOf(0x03, 0x01)
}

/** Notification opcodes on the alarm-notify characteristic (5003). */
object NotifyOpcode {
    /** Watch reports an alarm is firing. Format: [0x54, 0x00, alarm_id, 0x00]. */
    const val ALARM_FIRING = 0x54

    /** Watch confirms snooze handled. Format: [0x55, 0x00, alarm_id, 0x00]. */
    const val SNOOZE_OK = 0x55

    /** Watch confirms stop handled / generic write-OK. Format: [0x56, 0x00, alarm_id, 0x00]. */
    const val STOP_OK = 0x56
}

/** Parse an alarm-notify event from the watch. Returns null for unknown opcodes. */
data class NotifyEvent(val opcode: Int, val alarmId: Int)

fun parseNotifyEvent(data: ByteArray): NotifyEvent? {
    if (data.size < 4) return null
    val op = data[0].toInt() and 0xFF
    val id = data[2].toInt() and 0xFF
    return when (op) {
        NotifyOpcode.ALARM_FIRING, NotifyOpcode.SNOOZE_OK, NotifyOpcode.STOP_OK -> NotifyEvent(op, id)
        else -> null
    }
}
