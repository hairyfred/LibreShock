package uk.hairyfred.libreshock.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import uk.hairyfred.libreshock.ble.SleepNight
import uk.hairyfred.libreshock.ble.SleepStage
import uk.hairyfred.libreshock.ble.SleepTimelineEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val COLOUR_AWAKE = Color(0xFFFFA94D)
private val COLOUR_REM = Color(0xFFFF6B6B)
private val COLOUR_LIGHT = Color(0xFFEC7CC1)
private val COLOUR_SLEEP = Color(0xFFEC7CC1)   // same as Light when 3-stage
private val COLOUR_DEEP = Color(0xFF7950F2)

@Composable
fun SleepNightDetailScreen(
    night: SleepNight,
    sessionId: Int,
    rawBody: ByteArray?,
    padding: PaddingValues,
) {
    val context = LocalContext.current
    // System "Save file" picker — user picks where to save the .bin. More
    // reliable than FileProvider+ACTION_SEND and gives a real local file the
    // user can open in any analysis tool.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null && rawBody != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(rawBody) }
                Toast.makeText(context, "Sleep session saved.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val bedFmt = remember {
        SimpleDateFormat("EEE dd MMM yyyy", Locale.UK).apply {
            timeZone = TimeZone.getDefault()
        }
    }
    val timeFmt = remember {
        SimpleDateFormat("HH:mm", Locale.UK).apply {
            timeZone = TimeZone.getDefault()
        }
    }
    val dateStr = bedFmt.format(Date(night.bedtimeSeconds * 1000L))
    val bedStr = timeFmt.format(Date(night.bedtimeSeconds * 1000L))
    val wakeStr = timeFmt.format(Date(night.wakeTimeSeconds * 1000L))
    val totalMin = (night.durationSeconds / 60).toInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Date header
        Text(dateStr, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)

        // Big "sleep time / bedtime / wake" card
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Sleep time", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${totalMin / 60}h ${totalMin % 60}m",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Bedtime", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(bedStr, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Wake up", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(wakeStr, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                }
            }
        }

        // Sleep chart
        if (night.timeline.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sleep chart", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    SleepChart(
                        night = night,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                    if (night.approximateSplit) {
                        Text(
                            "REM and Light are approximate — the vendor's classifier is " +
                                "proprietary so LibreShock applies its own heuristic.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = TextUnit(18f, TextUnitType.Sp),
                        )
                    }
                }
            }
        }

        // Stage breakdown card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Stage breakdown", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                if (night.approximateSplit) {
                    StageRow("Awake", night.awakeSeconds, night.durationSeconds, COLOUR_AWAKE)
                    StageRow("≈REM", night.remSeconds, night.durationSeconds, COLOUR_REM)
                    StageRow("≈Light", night.lightSeconds, night.durationSeconds, COLOUR_LIGHT)
                    StageRow("Deep", night.deepSeconds, night.durationSeconds, COLOUR_DEEP)
                } else {
                    StageRow("Awake", night.awakeSeconds, night.durationSeconds, COLOUR_AWAKE)
                    StageRow("Sleep", night.sleepSeconds, night.durationSeconds, COLOUR_SLEEP)
                    StageRow("Deep", night.deepSeconds, night.durationSeconds, COLOUR_DEEP)
                }
            }
        }

        // Save raw bytes via the system file picker. Lets the user pick
        // Downloads / Drive / etc. — useful for further analysis.
        if (rawBody != null && rawBody.isNotEmpty()) {
            OutlinedButton(
                onClick = { saveLauncher.launch("sleep-session-$sessionId.bin") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save raw session bytes…")
            }
        }
    }
}

@Composable
private fun StageRow(label: String, seconds: Int, totalSeconds: Long, colour: Color) {
    val minutes = seconds / 60
    val pct = if (totalSeconds > 0) (seconds.toDouble() / totalSeconds * 100).toInt() else 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(colour, CircleShape),
            )
            Spacer(Modifier.size(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            Text(
                "${minutes / 60}h ${minutes % 60}m   $pct%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(colour.copy(alpha = 0.15f),
                    RoundedCornerShape(2.dp)),
        ) {
            val frac = if (totalSeconds > 0) seconds.toFloat() / totalSeconds else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .height(4.dp)
                    .background(colour, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun SleepChart(
    night: SleepNight,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val labelPx = with(density) { 11.sp.toPx() }
    val xAxisHeightDp = 28.dp
    val xAxisHeightPx = with(density) { xAxisHeightDp.toPx() }
    val leftPadDp = 48.dp
    val leftPadPx = with(density) { leftPadDp.toPx() }

    // Lane order top → bottom. Awake/Restless lives at the top, Deep at the
    // bottom — matches the vendor app's chart layout.
    val lanes: List<Pair<String, Color>> = if (night.approximateSplit) {
        listOf(
            "Restless" to COLOUR_AWAKE,   // a step ABOVE Awake on the chart so brief Awake
            "Awake"    to COLOUR_AWAKE,
            "REM"      to COLOUR_REM,
            "Light"    to COLOUR_LIGHT,
            "Deep"     to COLOUR_DEEP,
        )
    } else {
        listOf(
            "Awake" to COLOUR_AWAKE,
            "Sleep" to COLOUR_SLEEP,
            "Deep"  to COLOUR_DEEP,
        )
    }

    // Map each stage onto a lane index (0 = top).
    fun laneFor(s: SleepStage): Int = if (night.approximateSplit) {
        when (s) {
            SleepStage.AWAKE -> 1
            SleepStage.REM -> 2
            SleepStage.LIGHT -> 3
            SleepStage.SLEEP -> 3   // fallback, shouldn't happen with split
            SleepStage.DEEP -> 4
        }
    } else {
        when (s) {
            SleepStage.AWAKE -> 0
            SleepStage.REM -> 1   // fallback
            SleepStage.LIGHT -> 1
            SleepStage.SLEEP -> 1
            SleepStage.DEEP -> 2
        }
    }

    val timelineLocal = night.timeline
    val totalDur = timelineLocal.lastOrNull()?.let { it.offsetSeconds + it.durationSeconds }
        ?: night.durationSeconds.toInt()
    if (totalDur <= 0) return

    val gridColour = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val labelColour = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurfaceArgb = labelColour.toArgb()

    val timeFmt = remember {
        SimpleDateFormat("HH:mm", Locale.UK).apply {
            timeZone = TimeZone.getDefault()
        }
    }
    val bedLabel = timeFmt.format(Date(night.bedtimeSeconds * 1000L))
    val wakeLabel = timeFmt.format(Date(night.wakeTimeSeconds * 1000L))
    val midLabel = timeFmt.format(Date((night.bedtimeSeconds + night.durationSeconds / 2) * 1000L))

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val plotLeft = leftPadPx
        val plotRight = w
        val plotTop = 0f
        val plotBottom = h - xAxisHeightPx
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        val nLanes = lanes.size
        val laneHeight = plotH / nLanes

        // Lane labels + horizontal grid lines.
        val paint = android.graphics.Paint().apply {
            color = onSurfaceArgb
            textSize = labelPx
            isAntiAlias = true
        }
        drawIntoCanvas { canvas ->
            for ((idx, lane) in lanes.withIndex()) {
                val laneCentreY = plotTop + idx * laneHeight + laneHeight / 2
                canvas.nativeCanvas.drawText(
                    lane.first,
                    8f,
                    laneCentreY + labelPx / 3,
                    paint,
                )
            }
            // X-axis time labels
            val baselineY = plotBottom + xAxisHeightPx / 2 + labelPx / 3
            canvas.nativeCanvas.drawText(bedLabel, plotLeft, baselineY, paint)
            val midX = plotLeft + plotW / 2 - paint.measureText(midLabel) / 2
            canvas.nativeCanvas.drawText(midLabel, midX, baselineY, paint)
            val rightX = plotRight - paint.measureText(wakeLabel) - 4f
            canvas.nativeCanvas.drawText(wakeLabel, rightX, baselineY, paint)
        }
        // Faint horizontal grid lines between lanes.
        for (idx in 1 until nLanes) {
            val y = plotTop + idx * laneHeight
            drawLine(
                color = gridColour,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1f,
            )
        }
        // Top + bottom borders
        drawLine(gridColour, Offset(plotLeft, plotTop),
            Offset(plotRight, plotTop), 1f)
        drawLine(gridColour, Offset(plotLeft, plotBottom),
            Offset(plotRight, plotBottom), 1f)

        // Pure coloured line. Each entry gets a horizontal segment at its
        // stage's lane y; the vertical connector between two consecutive
        // entries takes the INCOMING stage's colour so the line visually
        // "leads into" the new stage.
        val lineWidth = 4f
        var prevY: Float? = null
        var prevX: Float? = null
        for (entry in timelineLocal) {
            val x0 = plotLeft + plotW * entry.offsetSeconds.toFloat() / totalDur
            val x1 = plotLeft + plotW * (entry.offsetSeconds + entry.durationSeconds).toFloat() / totalDur
            val laneIdx = laneFor(entry.stage)
            val y = plotTop + laneIdx * laneHeight + laneHeight * 0.5f
            val colour = when (entry.stage) {
                SleepStage.AWAKE -> COLOUR_AWAKE
                SleepStage.REM -> COLOUR_REM
                SleepStage.LIGHT -> COLOUR_LIGHT
                SleepStage.SLEEP -> COLOUR_SLEEP
                SleepStage.DEEP -> COLOUR_DEEP
            }
            // Vertical connector from previous y to this y at the shared x,
            // coloured by the INCOMING stage.
            if (prevY != null && prevX != null && prevY != y) {
                drawLine(
                    color = colour,
                    start = Offset(prevX, prevY),
                    end = Offset(prevX, y),
                    strokeWidth = lineWidth,
                )
            }
            // Horizontal segment for this entry.
            drawLine(
                color = colour,
                start = Offset(x0, y),
                end = Offset(x1, y),
                strokeWidth = lineWidth,
            )
            prevY = y
            prevX = x1
        }
    }
}

