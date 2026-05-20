package uk.hairyfred.libreshock.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 *  Sleep tracking history protocol.
 *
 *  The watch stores past sleep sessions in flash and the phone reads them
 *  on demand via char 2002 (the "events" char in service 156e2000). Two
 *  query types observed:
 *    0x03 = sleep-stage records (~3-4KB each) — watch's own classifier output
 *    0x82 = general event log — alarm sets / BLE traffic / etc, not actigraphy
 *
 *  Request format (write to char 2002, 9 bytes; send twice for start+end):
 *      <query_type:1> <session_id:u24-LE> 00 <range:u32-LE>
 *  range=00000000 marks start, range=ffffffff marks end of fetch. session_id=0
 *  returns ALL records of that type concatenated; otherwise one session.
 *
 *  Response (delivered as notifications on the same characteristic):
 *      14-byte header: <op:1> 00 <id_echo:5> <count:u32-LE> <byte_count:u32-LE>
 *      then byte_count bytes of body.
 *
 *  For the "all sessions" body, each per-session record begins with:
 *      <sub_op:1> 03 <body_len:u16-LE> <session_id:u32-LE> <timestamp:u32-LE>
 *  sub_op is 0x3f for the first session and 0x7f for the rest. Timestamps
 *  are Unix epoch seconds UTC, anchored near each tracked sleep window.
 *
 *  Per-session body has TWO formats (mirrors libreshock.py):
 *  1) Older summary: top-level `0x21 <len:1> 0000 <bedtime-u32> <activity-bytes>`
 *     wrappers. Each activity byte = one 5-min window's motion intensity
 *     (0..255). Phone-side thresholding produces Awake/Sleep/Deep.
 *  2) Current-night rich: `0x10 0x03 <dur:u16-LE> <stage:1>` segments with
 *     stage codes 0x11 (Sleep), 0x21 (Deep), 0x41/0x51 (Awake).
 *
 *  Light/REM split is a phone-side ESTIMATE — Pavlok uses a proprietary
 *  algorithm we don't have access to. The toggle in the UI lets users
 *  opt into the approximation.
 */

/** One stored sleep session on the watch. The body is opaque (raw bytes)
 *  until the inner TLV format is cracked. */
data class SleepSession(
    val sid: Int,
    /** Unix epoch seconds (UTC) — a checkpoint timestamp during the session,
     *  not necessarily bedtime or wake. Monotonic across stored sessions. */
    val timestampSeconds: Long,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is SleepSession
        && sid == other.sid && timestampSeconds == other.timestampSeconds
    override fun hashCode(): Int = sid * 31 + timestampSeconds.toInt()
}

/** Parse the response from `ShockDevice.readSleepIndex()` into per-session
 *  metadata + raw bodies. Empty list on short / malformed input. */
fun parseSleepSessions(raw: ByteArray): List<SleepSession> {
    if (raw.size < 14) return emptyList()
    val sessions = mutableListOf<SleepSession>()
    var pos = 14  // skip the response header
    while (pos + 12 <= raw.size) {
        val op = raw[pos].toInt() and 0xFF
        val typeByte = raw[pos + 1].toInt() and 0xFF
        if ((op != 0x3f && op != 0x7f) || typeByte != 0x03) {
            pos += 1
            continue
        }
        val length = ((raw[pos + 2].toInt() and 0xFF) or
                     ((raw[pos + 3].toInt() and 0xFF) shl 8))
        val sid = ByteBuffer.wrap(raw, pos + 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        val ts = ByteBuffer.wrap(raw, pos + 8, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val bodyStart = pos + 12
        val bodyEnd = bodyStart + length
        if (bodyEnd > raw.size) break
        sessions.add(SleepSession(
            sid = sid,
            timestampSeconds = ts,
            body = raw.sliceArray(bodyStart until bodyEnd),
        ))
        pos = bodyEnd
    }
    return sessions
}

/** Build the 9-byte request payload for the sleep-history protocol. */
internal fun buildSleepRequest(queryType: Int, sessionId: Int, rangeEnd: Boolean): ByteArray {
    val out = ByteArray(9)
    out[0] = queryType.toByte()
    // session_id is a 3-byte little-endian field starting at offset 1
    out[1] = (sessionId and 0xFF).toByte()
    out[2] = ((sessionId shr 8) and 0xFF).toByte()
    out[3] = ((sessionId shr 16) and 0xFF).toByte()
    out[4] = 0x00
    val fill: Byte = if (rangeEnd) 0xff.toByte() else 0x00
    for (i in 5..8) out[i] = fill
    return out
}

// ---------- Per-night decoder ----------

private const val STAGE_DEEP = 0x21
private val STAGE_AWAKE_CODES = setOf(0x41, 0x51)
private val STAGE_SLEEP_CODES = setOf(0x11, 0x13)

/** Activity-byte thresholds for the older summary format.
 *  Derived from grid search vs ground-truth screenshots — approximate fit. */
private const val ACTIVITY_DEEP_MAX = 2     // < 2 → Deep
private const val ACTIVITY_AWAKE_MIN = 60   // >= 60 → Awake
private const val REM_FRACTION_OF_SLEEP = 0.40
private const val WINDOW_SECONDS = 300L

/** A discrete stage label on the sleep chart. SLEEP is the combined
 *  Light+REM bucket emitted when the approximate split is off. */
enum class SleepStage { AWAKE, REM, LIGHT, SLEEP, DEEP }

/** One run of the same stage in the chronological timeline of a night. */
data class SleepTimelineEntry(
    /** Offset from bedtime in seconds (start of this run). */
    val offsetSeconds: Int,
    val durationSeconds: Int,
    val stage: SleepStage,
)

/** One night of sleep extracted from a session body. */
data class SleepNight(
    /** Unix epoch seconds (UTC). */
    val bedtimeSeconds: Long,
    val wakeTimeSeconds: Long,
    val awakeSeconds: Int,
    /** Combined Light + REM. Always populated. */
    val sleepSeconds: Int,
    val deepSeconds: Int,
    /** 0 unless [approximateSplit] is true. */
    val lightSeconds: Int = 0,
    val remSeconds: Int = 0,
    /** True if light/rem were estimated via a phone-side heuristic. */
    val approximateSplit: Boolean = false,
    /** Per-run stage timeline in chronological order. Empty if the
     *  decoder couldn't synthesise one. */
    val timeline: List<SleepTimelineEntry> = emptyList(),
) {
    val durationSeconds: Long get() = wakeTimeSeconds - bedtimeSeconds
}

/** Read a u32-LE at [offset] in [body]. */
private fun u32(body: ByteArray, offset: Int): Long =
    ((body[offset].toLong() and 0xFFL)) or
        ((body[offset + 1].toLong() and 0xFFL) shl 8) or
        ((body[offset + 2].toLong() and 0xFFL) shl 16) or
        ((body[offset + 3].toLong() and 0xFFL) shl 24)

/** Compress consecutive same-stage entries into one run. */
private fun compressRuns(entries: List<SleepTimelineEntry>): List<SleepTimelineEntry> {
    if (entries.isEmpty()) return entries
    val out = mutableListOf<SleepTimelineEntry>()
    var cur = entries[0]
    for (i in 1 until entries.size) {
        val e = entries[i]
        if (e.stage == cur.stage &&
            e.offsetSeconds == cur.offsetSeconds + cur.durationSeconds) {
            cur = cur.copy(durationSeconds = cur.durationSeconds + e.durationSeconds)
        } else {
            out.add(cur)
            cur = e
        }
    }
    out.add(cur)
    return out
}

private fun classifyActivityNight(
    bedtime: Long,
    activityBytes: ByteArray,
    estimateRemSplit: Boolean,
): SleepNight {
    // Per-window classification (chronological order preserved).
    val raw = IntArray(activityBytes.size)
    var awakeCount = 0; var sleepCount = 0; var deepCount = 0
    for ((i, b) in activityBytes.withIndex()) {
        val v = b.toInt() and 0xFF
        raw[i] = when {
            v < ACTIVITY_DEEP_MAX -> { deepCount++; 0 }   // 0=Deep
            v >= ACTIVITY_AWAKE_MIN -> { awakeCount++; 2 } // 2=Awake
            else -> { sleepCount++; 1 }                    // 1=Sleep
        }
    }
    val windowSec = WINDOW_SECONDS.toInt()
    val deepS = deepCount * windowSec
    val awakeS = awakeCount * windowSec
    val sleepS = sleepCount * windowSec
    val wakeTs = bedtime + activityBytes.size * WINDOW_SECONDS

    // Build the timeline. When approximate-split is on, mark the
    // chronologically-last 40% of Sleep windows as REM, the rest as Light.
    val splitOn = estimateRemSplit && sleepCount > 0
    val nRem = if (splitOn) (sleepCount * REM_FRACTION_OF_SLEEP).toInt() else 0
    val remStartSleepIdx = sleepCount - nRem  // sleep-index from which REM begins
    var sleepIdx = 0
    val entries = mutableListOf<SleepTimelineEntry>()
    for (i in raw.indices) {
        val stage = when (raw[i]) {
            0 -> SleepStage.DEEP
            2 -> SleepStage.AWAKE
            else -> {
                val s = if (splitOn) {
                    if (sleepIdx >= remStartSleepIdx) SleepStage.REM else SleepStage.LIGHT
                } else SleepStage.SLEEP
                sleepIdx++
                s
            }
        }
        entries.add(SleepTimelineEntry(
            offsetSeconds = i * windowSec,
            durationSeconds = windowSec,
            stage = stage,
        ))
    }
    val timeline = compressRuns(entries)

    val remS = nRem * windowSec
    val lightS = if (splitOn) (sleepCount - nRem) * windowSec else 0
    return SleepNight(
        bedtimeSeconds = bedtime,
        wakeTimeSeconds = wakeTs,
        awakeSeconds = awakeS,
        sleepSeconds = sleepS,
        deepSeconds = deepS,
        lightSeconds = lightS,
        remSeconds = remS,
        approximateSplit = splitOn,
        timeline = timeline,
    )
}

/** Decode a session body into per-night summaries.
 *
 *  When [estimateRemSplit] is true, the Sleep band is further split into
 *  Light + REM via a phone-side approximation (the vendor's classifier is
 *  proprietary). Resulting nights are marked `approximateSplit = true` so
 *  the UI can render an "≈" qualifier.
 */
fun decodeSleepNights(
    body: ByteArray,
    sessionTimestampSeconds: Long,
    estimateRemSplit: Boolean = false,
): List<SleepNight> {
    val nights = mutableListOf<SleepNight>()
    val consumed = BooleanArray(body.size)
    val window = 14L * 86400L
    val lo = sessionTimestampSeconds - window
    val hi = sessionTimestampSeconds + window

    // Pass 1: top-level 0x21 wrappers with embedded bedtime u32.
    var pos = 0
    while (pos + 2 <= body.size) {
        val op = body[pos].toInt() and 0xFF
        val ln = body[pos + 1].toInt() and 0xFF
        val end = pos + 2 + ln
        if (end > body.size) break
        if (op == 0x21 && ln >= 10) {
            val ts = u32(body, pos + 4)
            if (ts in lo..hi) {
                val activity = body.sliceArray((pos + 8) until end)
                if (activity.isNotEmpty()) {
                    nights.add(classifyActivityNight(ts, activity, estimateRemSplit))
                    for (i in pos until end) consumed[i] = true
                }
            }
        }
        pos = end
    }

    // Pass 2: recursive walk for 0x10/3 leaf events outside summary records.
    // Collect segments in chronological order so we can build a timeline.
    val segments = mutableListOf<Pair<SleepStage, Int>>()  // (stage, duration)

    fun walk(start: Int, endIdx: Int) {
        var p = start
        while (p + 2 <= endIdx) {
            val o = body[p].toInt() and 0xFF
            val l = body[p + 1].toInt() and 0xFF
            if (p + 2 + l > endIdx) break
            var insideConsumed = false
            for (i in p until (p + 2 + l)) {
                if (i < consumed.size && consumed[i]) {
                    insideConsumed = true
                    break
                }
            }
            if (insideConsumed) {
                p += 2 + l
                continue
            }
            if (o == 0x10 && l == 3) {
                val dur = (body[p + 2].toInt() and 0xFF) or
                    ((body[p + 3].toInt() and 0xFF) shl 8)
                val stageByte = body[p + 4].toInt() and 0xFF
                val stage = when {
                    stageByte == STAGE_DEEP -> SleepStage.DEEP
                    stageByte in STAGE_AWAKE_CODES -> SleepStage.AWAKE
                    stageByte in STAGE_SLEEP_CODES -> SleepStage.SLEEP
                    else -> null
                }
                if (stage != null && dur > 0) {
                    segments.add(stage to dur)
                }
            } else if (l >= 5) {
                walk(p + 2, p + 2 + l)
            }
            p += 2 + l
        }
    }
    walk(0, body.size)

    val awake = segments.filter { it.first == SleepStage.AWAKE }.sumOf { it.second }
    val sleepTot = segments.filter { it.first == SleepStage.SLEEP }.sumOf { it.second }
    val deep = segments.filter { it.first == SleepStage.DEEP }.sumOf { it.second }

    if (segments.isNotEmpty() && (awake + sleepTot + deep) > 0) {
        val tsList = mutableListOf<Long>()
        for (i in 0 until body.size - 4) {
            val v = u32(body, i)
            if (v in lo..hi && v != sessionTimestampSeconds) tsList.add(v)
        }
        tsList.sort()
        var bedtime = 0L
        var wake = 0L
        outer@ for (i in tsList.indices) {
            for (j in (i + 1) until tsList.size) {
                val delta = tsList[j] - tsList[i]
                if (delta in (3L * 3600 + 1800)..(14L * 3600)) {
                    bedtime = tsList[i]
                    wake = tsList[j]
                    break@outer
                }
            }
        }
        if (bedtime == 0L) {
            bedtime = sessionTimestampSeconds
            wake = sessionTimestampSeconds + awake + sleepTot + deep
        }

        // Mark the chronologically-last 40% of Sleep time as REM.
        val splitOn = estimateRemSplit && sleepTot > 0
        val remTarget = if (splitOn) (sleepTot * REM_FRACTION_OF_SLEEP).toInt() else 0
        // Walk in REVERSE, marking sleep segments as REM until quota hit.
        val newSegments = segments.toMutableList()
        if (splitOn) {
            var remRemaining = remTarget
            for (i in newSegments.indices.reversed()) {
                if (remRemaining <= 0) break
                val (st, dur) = newSegments[i]
                if (st != SleepStage.SLEEP) continue
                if (dur <= remRemaining) {
                    newSegments[i] = SleepStage.REM to dur
                    remRemaining -= dur
                } else {
                    // Split: tail becomes REM, head stays as LIGHT.
                    newSegments[i] = SleepStage.LIGHT to (dur - remRemaining)
                    newSegments.add(i + 1, SleepStage.REM to remRemaining)
                    remRemaining = 0
                }
            }
            // Remaining unmarked SLEEP segments are LIGHT.
            for (i in newSegments.indices) {
                if (newSegments[i].first == SleepStage.SLEEP) {
                    newSegments[i] = SleepStage.LIGHT to newSegments[i].second
                }
            }
        }

        // Build timeline with cumulative offsets.
        val entries = mutableListOf<SleepTimelineEntry>()
        var off = 0
        for ((stage, dur) in newSegments) {
            entries.add(SleepTimelineEntry(off, dur, stage))
            off += dur
        }
        val timeline = compressRuns(entries)

        val remS = if (splitOn) remTarget else 0
        val lightS = if (splitOn) sleepTot - remTarget else 0
        nights.add(SleepNight(
            bedtimeSeconds = bedtime,
            wakeTimeSeconds = wake,
            awakeSeconds = awake,
            sleepSeconds = sleepTot,
            deepSeconds = deep,
            lightSeconds = lightS,
            remSeconds = remS,
            approximateSplit = splitOn,
            timeline = timeline,
        ))
    }

    return nights.sortedBy { it.bedtimeSeconds }
}
