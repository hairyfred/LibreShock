package uk.hairyfred.libreshock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.hairyfred.libreshock.ble.BatteryHistory
import uk.hairyfred.libreshock.ble.BatterySample
import uk.hairyfred.libreshock.ble.ShockDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BatteryUsageScreen(
    device: ShockDevice,
    history: BatteryHistory,
    padding: PaddingValues,
) {
    var currentPct by remember { mutableStateOf<Int?>(null) }
    var samples by remember { mutableStateOf<List<BatterySample>>(emptyList()) }
    var lastChargedMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        val now = device.readBattery()
        if (now != null) {
            history.append(now)
            currentPct = now
        }
        samples = history.samples()
        // Find the most recent timestamp where percent was >= 95% as a "last charged" proxy.
        lastChargedMs = samples.lastOrNull { it.percent >= 95 }?.timestampMs
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Current", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            currentPct?.let { "$it%" } ?: "—",
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Last charged to 95%+", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            lastChargedMs?.let { formatRelative(it) } ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Battery Usage", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${samples.size} sample${if (samples.size == 1) "" else "s"} stored",
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(modifier = Modifier.fillMaxWidth().height(260.dp).padding(top = 12.dp)) {
                    BatteryChart(samples = samples)
                }
            }
        }

        Text(
            "Samples are recorded each time you open this screen and whenever the watch reports a battery change. The graph fills in over time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatteryChart(samples: List<BatterySample>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = TextStyle(fontSize = 10.sp, color = textColor)
    val dayFormatter = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val leftPadding = with(density) { 28.dp.toPx() }
        val bottomPadding = with(density) { 24.dp.toPx() }
        val rightPadding = with(density) { 8.dp.toPx() }
        val topPadding = with(density) { 6.dp.toPx() }

        val chartWidth = size.width - leftPadding - rightPadding
        val chartHeight = size.height - topPadding - bottomPadding

        // Y-axis gridlines at 0, 25, 50, 75, 100
        for (pct in listOf(0, 25, 50, 75, 100)) {
            val y = topPadding + chartHeight * (1f - pct / 100f)
            drawLine(
                color = gridColor,
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + chartWidth, y),
                strokeWidth = 1f,
            )
            val label = textMeasurer.measure(AnnotatedString("$pct"), labelStyle)
            drawText(
                textMeasurer = textMeasurer,
                text = "$pct",
                topLeft = Offset(0f, y - label.size.height / 2f),
                style = labelStyle,
            )
        }

        if (samples.size < 2) {
            val msg = if (samples.isEmpty()) "No samples yet — open this screen later to collect more"
                      else "Only one sample — open later for trend"
            drawText(
                textMeasurer = textMeasurer,
                text = msg,
                topLeft = Offset(leftPadding + 8f, topPadding + 8f),
                style = labelStyle,
            )
            return@Canvas
        }

        val tMin = samples.first().timestampMs
        val tMax = samples.last().timestampMs
        val tSpan = (tMax - tMin).coerceAtLeast(1L)

        fun x(t: Long): Float = leftPadding + chartWidth * (t - tMin).toFloat() / tSpan
        fun y(p: Int): Float = topPadding + chartHeight * (1f - p / 100f)

        // X-axis date labels (start / mid / end)
        for (frac in listOf(0f, 0.5f, 1f)) {
            val t = tMin + (tSpan * frac).toLong()
            val xpx = leftPadding + chartWidth * frac
            val label = dayFormatter.format(Date(t))
            val m = textMeasurer.measure(AnnotatedString(label), labelStyle)
            drawText(
                textMeasurer = textMeasurer,
                text = label,
                topLeft = Offset(
                    (xpx - m.size.width / 2f).coerceAtLeast(0f),
                    topPadding + chartHeight + 4f,
                ),
                style = labelStyle,
            )
        }

        val path = Path()
        path.moveTo(x(samples[0].timestampMs), y(samples[0].percent))
        for (i in 1 until samples.size) {
            path.lineTo(x(samples[i].timestampMs), y(samples[i].percent))
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round),
        )
    }
}

private fun formatRelative(ms: Long): String {
    val deltaSec = (System.currentTimeMillis() - ms) / 1000
    if (deltaSec < 60) return "${deltaSec}s ago"
    val minutes = deltaSec / 60
    if (minutes < 60) return "${minutes}m ago"
    val hours = minutes / 60
    if (hours < 48) return "${hours}h ago"
    val days = hours / 24
    return "${days}d ago"
}
