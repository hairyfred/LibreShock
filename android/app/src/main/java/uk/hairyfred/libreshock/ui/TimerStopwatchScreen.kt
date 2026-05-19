package uk.hairyfred.libreshock.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.hairyfred.libreshock.ble.ShockDevice
import uk.hairyfred.libreshock.ble.TnsConfig
import uk.hairyfred.libreshock.ble.TnsInterval
import uk.hairyfred.libreshock.ble.TnsMode
import uk.hairyfred.libreshock.ble.TnsStim

@Composable
fun TimerStopwatchScreen(
    device: ShockDevice,
    padding: PaddingValues,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(TnsMode.TIMER) }
    var durationMinutes by remember { mutableStateOf(1f) }
    var intervals by remember {
        mutableStateOf(
            listOf(TnsInterval(stim = TnsStim.ZAP, intensity = 50, everySeconds = 5))
        )
    }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var addingInterval by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Mode picker — Timer or Stopwatch.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Mode", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == TnsMode.TIMER,
                        onClick = { mode = TnsMode.TIMER },
                        label = { Text("Timer") },
                    )
                    FilterChip(
                        selected = mode == TnsMode.STOPWATCH,
                        onClick = { mode = TnsMode.STOPWATCH },
                        label = { Text("Stopwatch") },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (mode == TnsMode.TIMER)
                        "Counts down from the configured duration. Fires intervals along the way."
                    else "Counts up from zero. Fires intervals at the configured cadence until you stop it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Timer-only: duration.
        if (mode == TnsMode.TIMER) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Duration", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        Text(
                            "${durationMinutes.toInt()}:${"%02d".format(((durationMinutes - durationMinutes.toInt()) * 60).toInt())}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Range: 5 sec (0.083 min) to 4:15 min (255 sec — the u8 cap).
                    Slider(
                        value = durationMinutes,
                        onValueChange = { durationMinutes = it },
                        valueRange = (5f / 60f)..(255f / 60f),
                    )
                    Text(
                        "Timer duration is capped at 4:15 — the watch stores it in a single byte.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Intervals list.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Intervals: ${intervals.size}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { addingInterval = true }) {
                        Text("+ Add interval")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (intervals.isEmpty()) {
                    Text(
                        "No intervals. Add at least one — the watch requires it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                intervals.forEachIndexed { idx, iv ->
                    IntervalRow(
                        interval = iv,
                        onClick = { editingIndex = idx },
                        onDelete = { intervals = intervals.toMutableList().also { it.removeAt(idx) } },
                    )
                }
            }
        }

        // Save button + status.
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = {
                if (intervals.isEmpty()) {
                    error = "Add at least one interval before saving"
                    return@Button
                }
                error = null
                scaling@ run {
                    val cfg = TnsConfig(
                        mode = mode,
                        durationSeconds = if (mode == TnsMode.TIMER)
                            (durationMinutes * 60f).toInt().coerceIn(1, 255)
                        else 0,
                        intervals = intervals,
                    )
                    scope.launch {
                        saving = true
                        val ok = try { device.setTnsConfig(cfg) } catch (_: Exception) { false }
                        saving = false
                        if (ok) onSaved(
                            if (mode == TnsMode.TIMER) "Timer set on watch"
                            else "Stopwatch set on watch"
                        )
                        else error = "Failed to write config to watch"
                    }
                }
            },
            enabled = !saving && intervals.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (saving) "Saving..."
                else "Save to watch — start from the watch buttons"
            )
        }
    }

    if (addingInterval || editingIndex != null) {
        val existing = editingIndex?.let { intervals[it] }
        IntervalEditDialog(
            initial = existing,
            onDismiss = {
                addingInterval = false
                editingIndex = null
            },
            onConfirm = { newInterval ->
                intervals = if (editingIndex != null) {
                    intervals.toMutableList().also { it[editingIndex!!] = newInterval }
                } else {
                    intervals + newInterval
                }
                addingInterval = false
                editingIndex = null
            },
        )
    }
}

@Composable
private fun IntervalRow(
    interval: TnsInterval,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (interval.stim) {
                        TnsStim.VIBE -> "Vibrate"
                        TnsStim.BEEP -> "Beep"
                        TnsStim.ZAP -> "Zap"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${interval.intensity}% every ${interval.everySeconds}s",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete interval")
            }
        }
    }
}

@Composable
private fun IntervalEditDialog(
    initial: TnsInterval?,
    onDismiss: () -> Unit,
    onConfirm: (TnsInterval) -> Unit,
) {
    var stim by remember { mutableStateOf(initial?.stim ?: TnsStim.ZAP) }
    var intensity by remember { mutableStateOf((initial?.intensity ?: 50).toFloat()) }
    var everySecs by remember { mutableStateOf((initial?.everySeconds ?: 5).toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add interval" else "Edit interval") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stimulus", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TnsStim.values().forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = stim == s, onClick = { stim = s })
                            Text(
                                when (s) {
                                    TnsStim.VIBE -> "Vibe"
                                    TnsStim.BEEP -> "Beep"
                                    TnsStim.ZAP -> "Zap"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Text("Intensity — ${intensity.toInt()}%",
                    style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = intensity,
                    onValueChange = { intensity = it },
                    valueRange = 0f..100f,
                )
                Text("Every ${everySecs.toInt()} seconds",
                    style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = everySecs,
                    onValueChange = { everySecs = it },
                    valueRange = 1f..60f,
                )
                Text(
                    "Watch caps interval intensity around 0x21-0x34 internally as a safety cap, " +
                        "regardless of slider position. Slider 0%-100% is mapped onto that range.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(TnsInterval(
                    stim = stim,
                    intensity = intensity.toInt().coerceIn(0, 100),
                    everySeconds = everySecs.toInt().coerceIn(1, 255),
                ))
            }) { Text(if (initial == null) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
