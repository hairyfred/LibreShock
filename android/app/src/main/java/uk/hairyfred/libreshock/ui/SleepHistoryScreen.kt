package uk.hairyfred.libreshock.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.hairyfred.libreshock.ble.ShockDevice
import uk.hairyfred.libreshock.ble.SleepNight
import uk.hairyfred.libreshock.ble.SleepSession
import uk.hairyfred.libreshock.ble.decodeSleepNights
import uk.hairyfred.libreshock.ble.parseSleepSessions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 *  Lists the sleep sessions stored on the watch and renders per-night
 *  Awake/Sleep/Deep summaries decoded from each session body. An optional
 *  toggle estimates the Light/REM split — the watch only stores 3 stages,
 *  so 4-stage output is approximate (the vendor's classifier is proprietary).
 */
/** Compact metadata-only representation of a stored session, persisted to
 *  prefs as a CSV row so we can show the list instantly on screen open
 *  without re-fetching the 40KB body data. */
private data class SessionMeta(val sid: Int, val timestampSeconds: Long, val bodySize: Int)

private const val PREFS_KEY_INDEX = "sleep_sessions_index"
private const val PREFS_KEY_ESTIMATE_REM = "sleep_estimate_rem"

private fun loadCachedIndex(prefs: SharedPreferences): List<SessionMeta> {
    val raw = prefs.getString(PREFS_KEY_INDEX, null) ?: return emptyList()
    return raw.split(",").mapNotNull { row ->
        val parts = row.split(":")
        if (parts.size != 3) return@mapNotNull null
        try {
            SessionMeta(parts[0].toInt(), parts[1].toLong(), parts[2].toInt())
        } catch (_: NumberFormatException) {
            null
        }
    }
}

private fun saveCachedIndex(prefs: SharedPreferences, sessions: List<SleepSession>) {
    val rows = sessions.joinToString(",") { "${it.sid}:${it.timestampSeconds}:${it.body.size}" }
    prefs.edit().putString(PREFS_KEY_INDEX, rows).apply()
}

@Composable
fun SleepHistoryScreen(
    device: ShockDevice,
    padding: PaddingValues,
    sleepTrackingEnabled: Boolean,
    onSleepTrackingToggle: (Boolean) -> Unit,
    onViewNight: (SleepNight, Int, ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("libreshock", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    // Show cached index immediately for "instant" perceived load. Refresh
    // hits the watch over BLE which is slow (~10-30s for the 40KB index).
    var sessions by remember {
        mutableStateOf(loadCachedIndex(prefs).map {
            SleepSession(it.sid, it.timestampSeconds, ByteArray(it.bodySize))
        })
    }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Per-session body bytes are loaded on demand; null means "not yet fetched".
    var fetchedBodies by remember { mutableStateOf<Map<Int, ByteArray>>(emptyMap()) }
    var estimateRem by remember {
        mutableStateOf(prefs.getBoolean(PREFS_KEY_ESTIMATE_REM, false))
    }

    suspend fun refresh() {
        refreshing = true
        error = null
        try {
            val fresh = device.listSleepSessions()
            if (fresh.isEmpty()) {
                error = "No sleep sessions found on the watch."
            } else {
                sessions = fresh
                fetchedBodies = fresh.associate { it.sid to it.body }
                saveCachedIndex(prefs, fresh)
            }
        } catch (e: Exception) {
            error = "Read failed: ${e.message}"
        }
        refreshing = false
    }

    // Always refresh on screen open. Cached metadata is shown instantly
    // while the BLE pull runs in the background (~10-30s on a 40KB index),
    // so we get fresh per-session bodies without the user tapping Refresh.
    LaunchedEffect(Unit) {
        refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // All sleep-related controls live on this screen.
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Automatic sleep tracking", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tell the watch to start/stop logging actigraphy. The vendor " +
                            "app stores the time-range locally — toggling here may blank " +
                            "its '-- and --' time range until you re-set it there.",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = androidx.compose.ui.unit.TextUnit(18f,
                            androidx.compose.ui.unit.TextUnitType.Sp),
                    )
                }
                Switch(checked = sleepTrackingEnabled, onCheckedChange = onSleepTrackingToggle)
            }
        }

        // REM / Light estimation toggle. Default OFF — when ON, results are
        // approximate (the vendor's split algorithm is proprietary).
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Estimate REM / Light split",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "The watch only records 3 stages (Awake / Sleep / Deep). " +
                            "The vendor app uses a proprietary algorithm to split Sleep " +
                            "into Light and REM. When this is on, LibreShock applies " +
                            "its own approximation — values are prefixed with ≈ to " +
                            "make clear they aren't byte-exact to the vendor app.",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = androidx.compose.ui.unit.TextUnit(18f,
                            androidx.compose.ui.unit.TextUnitType.Sp),
                    )
                }
                Switch(
                    checked = estimateRem,
                    onCheckedChange = {
                        estimateRem = it
                        prefs.edit().putBoolean(PREFS_KEY_ESTIMATE_REM, it).apply()
                    },
                )
            }
        }

        Text(
            "Stored sleep sessions",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Each session covers ~1 week of recordings (older nights compressed; " +
                "the current night is full-resolution). Tap Load on a session to " +
                "decode its per-night breakdown. Export saves raw bytes for analysis.",
            style = MaterialTheme.typography.bodySmall,
            lineHeight = androidx.compose.ui.unit.TextUnit(18f,
                androidx.compose.ui.unit.TextUnitType.Sp),
        )

        when {
            sessions.isEmpty() && refreshing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.height(8.dp))
                Text("Reading sleep index from watch — this can take 10-30s…")
            }
            sessions.isEmpty() && error != null -> {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { scope.launch { refresh() } }) {
                    Text("Try again")
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${sessions.size} session(s)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                // Newest session at the top.
                sessions.sortedByDescending { it.timestampSeconds }.forEach { s ->
                    val body = fetchedBodies[s.sid]
                    SessionCard(
                        session = s,
                        body = body,
                        estimateRem = estimateRem,
                        onFetch = {
                            scope.launch {
                                val raw = try {
                                    device.readSleepIndex(queryType = 0x03, sessionId = s.sid)
                                } catch (_: Exception) { null }
                                if (raw != null && raw.size > 14) {
                                    val parsed = parseSleepSessions(raw)
                                    val matched = parsed.firstOrNull { it.sid == s.sid }
                                    if (matched != null) {
                                        fetchedBodies = fetchedBodies + (s.sid to matched.body)
                                    }
                                }
                            }
                        },
                        onViewNight = { night ->
                            body?.let { onViewNight(night, s.sid, it) }
                        },
                    )
                }
                OutlinedButton(
                    onClick = { scope.launch { refresh() } },
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (refreshing) "Refreshing from watch…" else "Refresh from watch")
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SleepSession,
    body: ByteArray?,
    estimateRem: Boolean,
    onFetch: () -> Unit,
    onViewNight: (SleepNight) -> Unit,
) {
    val fmt = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm zzz", Locale.UK).apply {
            timeZone = TimeZone.getDefault()
        }
    }
    val sessionDateStr = remember(session.timestampSeconds) {
        fmt.format(Date(session.timestampSeconds * 1000L))
    }
    val hasBody = body != null && body.isNotEmpty()
    val nights: List<SleepNight> = remember(body, estimateRem) {
        if (body == null || body.isEmpty()) emptyList()
        else decodeSleepNights(body, session.timestampSeconds, estimateRem)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Session ${session.sid}", style = MaterialTheme.typography.titleSmall)
                    Text(sessionDateStr, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (hasBody) "${body!!.size} bytes" else "metadata only — tap Load",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!hasBody) {
                    Button(onClick = onFetch) { Text("Load") }
                }
            }
            if (hasBody) {
                if (nights.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No recognisable per-night data (likely an empty / heartbeat-only " +
                            "session).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    // Newest night first within a session.
                    nights.sortedByDescending { it.bedtimeSeconds }.forEach { n ->
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(14.dp))
                        NightSummary(n, onView = { onViewNight(n) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NightSummary(night: SleepNight, onView: () -> Unit) {
    val bedFmt = remember {
        SimpleDateFormat("EEE dd MMM HH:mm", Locale.UK).apply {
            timeZone = TimeZone.getDefault()
        }
    }
    val wakeFmt = remember {
        SimpleDateFormat("HH:mm", Locale.UK).apply {
            timeZone = TimeZone.getDefault()
        }
    }
    val bedStr = bedFmt.format(Date(night.bedtimeSeconds * 1000L))
    val wakeStr = wakeFmt.format(Date(night.wakeTimeSeconds * 1000L))
    val totalMin = (night.durationSeconds / 60).toInt()
    val totalLabel = "${totalMin / 60}h ${totalMin % 60}m"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onView),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Bed $bedStr → Wake $wakeStr  ($totalLabel tracked)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "View details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (night.approximateSplit) {
            StagesRow4(
                awakeMin = night.awakeSeconds / 60,
                remMin = night.remSeconds / 60,
                lightMin = night.lightSeconds / 60,
                deepMin = night.deepSeconds / 60,
            )
        } else {
            StagesRow3(
                awakeMin = night.awakeSeconds / 60,
                sleepMin = night.sleepSeconds / 60,
                deepMin = night.deepSeconds / 60,
            )
        }
    }
}

@Composable
private fun StagesRow3(awakeMin: Int, sleepMin: Int, deepMin: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StageChip("Awake", awakeMin, Color(0xFFFFA94D), Modifier.weight(1f))
        StageChip("Sleep", sleepMin, Color(0xFFEC7CC1), Modifier.weight(1f))
        StageChip("Deep", deepMin, Color(0xFF7950F2), Modifier.weight(1f))
    }
}

@Composable
private fun StagesRow4(awakeMin: Int, remMin: Int, lightMin: Int, deepMin: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StageChip("Awake", awakeMin, Color(0xFFFFA94D), Modifier.weight(1f))
        StageChip("≈REM", remMin, Color(0xFFFF6B6B), Modifier.weight(1f))
        StageChip("≈Light", lightMin, Color(0xFFEC7CC1), Modifier.weight(1f))
        StageChip("Deep", deepMin, Color(0xFF7950F2), Modifier.weight(1f))
    }
}

@Composable
private fun StageChip(label: String, minutes: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        val hh = minutes / 60
        val mm = minutes % 60
        val text = when {
            hh > 0 && mm > 0 -> "${hh}h ${mm}m"
            hh > 0 -> "${hh}h"
            else -> "${mm}m"
        }
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

