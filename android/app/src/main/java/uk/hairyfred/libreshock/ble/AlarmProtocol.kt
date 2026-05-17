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

/** Parse a list-alarms response into AlarmConfigs. Mirrors Python's list_alarms. */
fun parseAlarms(raw: ByteArray): List<AlarmConfig> {
    var pos = 0
    // Skip the AH/length/crc header if present
    if (raw.size >= 6 && raw[0].toInt().toChar() == 'A' && raw[1].toInt().toChar() == 'H') {
        pos = 6
    }
    // Skip past the AP profile TLV if present
    val apPos = indexOfTag(raw, "AP", pos)
    if (apPos >= 0 && apPos + 4 <= raw.size) {
        val apLen = u16LeAt(raw, apPos + 2)
        pos = apPos + 4 + apLen
    }

    val alarms = mutableListOf<AlarmConfig>()
    while (pos + 4 <= raw.size) {
        if (raw[pos].toInt().toChar() != 'H' || raw[pos + 1].toInt().toChar() != 'A') {
            pos++
            continue
        }
        val contentLen = u16LeAt(raw, pos + 2)
        val blockStart = pos + 4
        val blockEnd = blockStart + contentLen
        if (blockEnd > raw.size) break
        parseAlarmBlock(raw, blockStart, blockEnd)?.let { alarms.add(it) }
        pos = blockEnd
    }
    return alarms
}

private fun parseAlarmBlock(buf: ByteArray, start: Int, end: Int): AlarmConfig? {
    var name = "alarm"
    var hour = 0
    var minute = 0
    var dayMask = 0
    var stimulusInterval = 15
    var snooze = true
    var enabled = true
    var vibeIntensity = 0
    var beepIntensity = 0
    var zapIntensity = 0
    var zapCount = 1
    var hasVibe = false
    var hasBeep = false
    var hasZap = false

    var p = start
    while (p + 4 <= end) {
        val tag = String(buf, p, 2, Charsets.US_ASCII)
        val len = u16LeAt(buf, p + 2)
        val valStart = p + 4
        val valEnd = valStart + len
        if (valEnd > end) break

        when (tag) {
            "AN" -> name = String(buf, valStart, len, Charsets.UTF_8)
            "TM" -> if (len == 4) {
                minute = bcdToInt(buf[valStart + 1].toInt() and 0xFF)
                hour = bcdToInt(buf[valStart + 2].toInt() and 0xFF)
                dayMask = buf[valStart + 3].toInt() and 0x7F
            }
            "WI" -> if (len == 2) stimulusInterval = u16LeAt(buf, valStart)
            "SN" -> if (len >= 1) snooze = buf[valStart].toInt() != 0
            "AO" -> if (len >= 1) enabled = buf[valStart].toInt() != 0
            "MH" -> findTagWithin(buf, valStart, valEnd, "MC")?.let { (vs, _) ->
                if (vs + 2 < end) {
                    hasVibe = true
                    vibeIntensity = buf[vs + 2].toInt() and 0xFF
                }
            }
            "PH" -> findTagWithin(buf, valStart, valEnd, "PC")?.let { (vs, _) ->
                if (vs + 2 < end) {
                    hasBeep = true
                    beepIntensity = buf[vs + 2].toInt() and 0xFF
                }
            }
            "ZH" -> findTagWithin(buf, valStart, valEnd, "ZC")?.let { (vs, _) ->
                if (vs + 1 < end) {
                    val flags = buf[vs].toInt() and 0xFF
                    hasZap = true
                    zapCount = (flags and 0x0F).coerceAtLeast(1)
                    zapIntensity = buf[vs + 1].toInt() and 0xFF
                }
            }
        }
        p = valEnd
    }

    return AlarmConfig(
        hour = hour, minute = minute, name = name,
        weekdays = dayMask, snooze = snooze, enabled = enabled,
        stimulusInterval = stimulusInterval,
        vibration = if (hasVibe) AlarmAction(enabled = true, count = 5, intensity = vibeIntensity)
                    else AlarmAction(enabled = false),
        beep = if (hasBeep) AlarmAction(enabled = true, count = 5, intensity = beepIntensity)
               else AlarmAction(enabled = false),
        zap = if (hasZap) AlarmAction(enabled = true, count = zapCount, intensity = zapIntensity)
              else AlarmAction(enabled = false),
    )
}

private fun u16LeAt(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

private fun indexOfTag(buf: ByteArray, tag: String, from: Int): Int {
    val a = tag[0].code.toByte()
    val b = tag[1].code.toByte()
    for (i in from..buf.size - 2) {
        if (buf[i] == a && buf[i + 1] == b) return i
    }
    return -1
}

/** Find a 2-char tag within [start, end) and return (value-start, value-end). */
private fun findTagWithin(buf: ByteArray, start: Int, end: Int, tag: String): Pair<Int, Int>? {
    val a = tag[0].code.toByte()
    val b = tag[1].code.toByte()
    var i = start
    while (i + 4 <= end) {
        if (buf[i] == a && buf[i + 1] == b) {
            val len = u16LeAt(buf, i + 2)
            val valStart = i + 4
            val valEnd = valStart + len
            if (valEnd <= end) return valStart to valEnd
        }
        i++
    }
    return null
}
