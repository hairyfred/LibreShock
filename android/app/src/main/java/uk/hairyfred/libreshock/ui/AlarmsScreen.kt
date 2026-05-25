package uk.hairyfred.libreshock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.hairyfred.libreshock.ble.AlarmAction
import uk.hairyfred.libreshock.ble.AlarmConfig
import uk.hairyfred.libreshock.ble.AlarmDiagnostic
import uk.hairyfred.libreshock.ble.AlarmValidationIssue
import uk.hairyfred.libreshock.ble.Guarantor
import uk.hairyfred.libreshock.ble.ShockDevice
import uk.hairyfred.libreshock.ble.Weekday
import uk.hairyfred.libreshock.ble.computeAlarmValidationIssues

private val DAY_LABELS = listOf("S", "M", "T", "W", "T", "F", "S")
private val DAY_BITS = listOf(
    Weekday.SUNDAY, Weekday.MONDAY, Weekday.TUESDAY, Weekday.WEDNESDAY,
    Weekday.THURSDAY, Weekday.FRIDAY, Weekday.SATURDAY,
)

@Composable
fun AlarmsScreen(
    alarms: List<AlarmConfig>?,
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onEdit: (alarm: AlarmConfig?, index: Int?) -> Unit,
    onToggleEnabled: (index: Int, enabled: Boolean) -> Unit,
    onClearAll: () -> Unit,
    onValidate: suspend () -> List<AlarmDiagnostic>?,
) {
    // alarms == null means "not loaded yet" (or read failed). Empty list means
    // we have a confirmed empty state. Trigger an initial refresh if we don't
    // have a list yet — but don't re-read every time the screen recomposes.
    LaunchedEffect(Unit) { if (alarms == null) onRefresh() }

    val list = alarms ?: emptyList()
    val statusText = when {
        alarms == null -> "Loading alarms..."
        alarms.isEmpty() -> "No alarms set"
        else -> null
    }

    var showClearDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var validating by remember { mutableStateOf(false) }
    var validationResult by remember {
        mutableStateOf<Pair<Int, List<AlarmValidationIssue>>?>(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { onEdit(null, null) }) { Text("+ Add alarm") }
            OutlinedButton(onClick = onRefresh, enabled = !validating) { Text("Refresh") }
            OutlinedButton(
                enabled = !validating,
                onClick = {
                    scope.launch {
                        validating = true
                        val fresh = try { onValidate() } catch (_: Exception) { null }
                        if (fresh == null) {
                            validationResult = -1 to listOf(AlarmValidationIssue(
                                0, "Couldn't read alarms from the watch."))
                        } else {
                            val issues = computeAlarmValidationIssues(fresh, alarms)
                            validationResult = fresh.size to issues
                        }
                        validating = false
                    }
                },
            ) { Text(if (validating) "Validating…" else "Validate") }
        }
        statusText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(list) { index, alarm ->
                AlarmCard(
                    alarm = alarm,
                    onClick = { onEdit(alarm, index) },
                    onToggleEnabled = { newEnabled -> onToggleEnabled(index, newEnabled) },
                )
            }
        }

        if (list.isNotEmpty()) {
            TextButton(
                onClick = { showClearDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Clear all alarms") }
        }
    }

    validationResult?.let { (count, issues) ->
        AlertDialog(
            onDismissRequest = { validationResult = null },
            title = { Text(if (issues.isEmpty()) "Alarms validated" else "Validation issues found") },
            text = {
                Column {
                    if (count >= 0) {
                        Text(
                            "Re-read $count alarm(s) from the watch and checked " +
                                "the raw armed-state bytes for internal consistency, " +
                                "plus each field against what the app shows.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (issues.isEmpty()) {
                        Text("Everything matches — no issues found.",
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("${issues.size} issue(s):",
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            issues.forEach { i ->
                                val prefix = if (i.alarmIndex == 0) "•"
                                             else "• Alarm ${i.alarmIndex}:"
                                Text("$prefix ${i.message}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { validationResult = null }) { Text("OK") }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all alarms?") },
            text = {
                Text(
                    "This removes every alarm from the watch.\n\n" +
                    "The vendor app may still show these alarms — it caches " +
                    "them locally and isn't aware they were removed. They are no longer " +
                    "on the watch itself."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearAll()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AlarmCard(
    alarm: AlarmConfig,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (alarm.enabled) 1f else 0.5f),
            ) {
                Text(
                    "%02d:%02d".format(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(daysSummary(alarm.weekdays), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(stimSummary(alarm), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggleEnabled)
        }
    }
}

private fun daysSummary(mask: Int): String {
    val m = mask and 0x7F
    return when (m) {
        0 -> "One-shot (today)"
        Weekday.EVERYDAY -> "Every day"
        Weekday.WEEKDAYS -> "Weekdays"
        Weekday.WEEKENDS -> "Weekends"
        else -> {
            val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            DAY_BITS.mapIndexedNotNull { i, bit -> if (m and bit != 0) days[i] else null }.joinToString(", ")
        }
    }
}

private fun stimSummary(alarm: AlarmConfig): String {
    val parts = buildList {
        if (alarm.vibration.enabled) add("vibe ${alarm.vibration.intensity}%")
        if (alarm.beep.enabled) add("beep ${alarm.beep.intensity}%")
        if (alarm.zap.enabled) add("zap ${alarm.zap.intensity}%×${alarm.zap.count}")
        when (alarm.guarantor) {
            Guarantor.JUMPING_JACKS -> add("JJ×${alarm.jumpingJacksCount}")
            Guarantor.QR_CODE -> add("QR")
            Guarantor.PUZZLE -> add("puzzle")
            Guarantor.NONE -> {}
        }
        if (alarm.snoozeZap) add("snooze-zap")
        if (alarm.lightSleep) add("light-sleep")
        if (alarm.escalating) add("escalating")
        if (alarm.smartAlarm) add("smart")
    }
    return if (parts.isEmpty()) "(no stims)" else parts.joinToString(" • ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    device: ShockDevice,
    initial: AlarmConfig?,
    index: Int?,
    padding: PaddingValues,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    val starting = initial ?: AlarmConfig(
        hour = 8, minute = 0, name = "alarm",
        weekdays = 0,  // default: one-shot today
        snooze = true, stimulusInterval = 15,
        vibration = AlarmAction(enabled = true, count = 5, intensity = 50),
        beep = AlarmAction(enabled = false),
        zap = AlarmAction(enabled = false),
    )

    val timePickerState = rememberTimePickerState(
        initialHour = starting.hour,
        initialMinute = starting.minute,
        is24Hour = true,
    )
    var dayMask by remember { mutableStateOf(starting.weekdays and 0x7F) }
    var snooze by remember { mutableStateOf(starting.snooze) }
    var enabled by remember { mutableStateOf(starting.enabled) }
    var interval by remember { mutableStateOf(starting.stimulusInterval.toFloat()) }

    var vibeOn by remember { mutableStateOf(starting.vibration.enabled) }
    var vibeIntensity by remember { mutableStateOf(starting.vibration.intensity.toFloat()) }
    var beepOn by remember { mutableStateOf(starting.beep.enabled) }
    var beepIntensity by remember { mutableStateOf(starting.beep.intensity.toFloat()) }
    var zapOn by remember { mutableStateOf(starting.zap.enabled) }
    var zapIntensity by remember { mutableStateOf(starting.zap.intensity.toFloat()) }
    var zapCount by remember { mutableStateOf(starting.zap.count.toFloat()) }

    var guarantor by remember { mutableStateOf(starting.guarantor) }
    var jjacksCount by remember {
        mutableStateOf(starting.jumpingJacksCount.coerceIn(1, 20).toFloat())
    }
    var guarantorExpanded by remember { mutableStateOf(starting.guarantor != Guarantor.NONE) }

    var snoozeZap by remember { mutableStateOf(starting.snoozeZap) }
    var lightSleep by remember { mutableStateOf(starting.lightSleep) }
    var escalating by remember { mutableStateOf(starting.escalating) }
    var smartAlarm by remember { mutableStateOf(starting.smartAlarm) }
    var wakeFeaturesExpanded by remember {
        mutableStateOf(starting.snoozeZap || starting.lightSleep ||
                       starting.escalating || starting.smartAlarm)
    }

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showTimeDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }

    fun build(): AlarmConfig = AlarmConfig(
        hour = timePickerState.hour, minute = timePickerState.minute, name = "alarm",
        weekdays = dayMask, snooze = snooze, enabled = enabled,
        stimulusInterval = interval.toInt().coerceAtLeast(1),
        vibration = AlarmAction(enabled = vibeOn, count = 5, intensity = vibeIntensity.toInt()),
        beep = AlarmAction(enabled = beepOn, count = 5, intensity = beepIntensity.toInt()),
        zap = AlarmAction(enabled = zapOn, count = zapCount.toInt().coerceIn(1, 15), intensity = zapIntensity.toInt()),
        guarantor = guarantor,
        jumpingJacksCount = jjacksCount.toInt().coerceIn(1, 20),
        // Snooze Zap only meaningful when Snooze is on; auto-disable otherwise
        // so the bit doesn't get stuck on after the user turns snooze off.
        snoozeZap = snooze && snoozeZap,
        lightSleep = lightSleep,
        escalating = escalating,
        smartAlarm = smartAlarm,
    )

    suspend fun saveAndExit() {
        saving = true
        error = null
        val current = device.listAlarms()?.toMutableList()
        if (current == null) {
            saving = false; error = "Could not read existing alarms"; return
        }
        val newAlarm = build()
        if (index != null && index in current.indices) current[index] = newAlarm
        else current.add(newAlarm)
        val ok = device.setAlarms(current)
        saving = false
        if (ok) onDone() else error = "Failed to save"
    }

    suspend fun deleteAndExit() {
        if (index == null) { onDone(); return }
        saving = true
        error = null
        val current = device.listAlarms()?.toMutableList()
        if (current == null) {
            saving = false; error = "Could not read existing alarms"; return
        }
        if (index in current.indices) current.removeAt(index)
        val ok = device.setAlarms(current)
        saving = false
        if (ok) onDone() else error = "Failed to delete"
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enabled", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (enabled) "Alarm will fire" else "Alarm is off",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showTimeDialog = true },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Time", style = MaterialTheme.typography.titleMedium)
                    Text("Tap to change", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "%02d:%02d".format(timePickerState.hour, timePickerState.minute),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (showTimeDialog) {
            AlertDialog(
                onDismissRequest = { showTimeDialog = false },
                confirmButton = {
                    TextButton(onClick = { showTimeDialog = false }) { Text("OK") }
                },
                title = { Text("Select time") },
                text = { TimePicker(state = timePickerState) },
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Repeat", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(daysSummary(dayMask), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                DayChips(mask = dayMask, onToggle = { bit ->
                    dayMask = dayMask xor bit
                })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { dayMask = 0 }) { Text("None") }
                    OutlinedButton(onClick = { dayMask = Weekday.WEEKDAYS }) { Text("Weekdays") }
                    OutlinedButton(onClick = { dayMask = Weekday.EVERYDAY }) { Text("Every day") }
                }
            }
        }

        StimSection(
            label = "Vibrate", enabled = vibeOn, onEnabledChange = { vibeOn = it },
            intensity = vibeIntensity, onIntensityChange = { vibeIntensity = it },
            countSlider = null,
        )
        StimSection(
            label = "Beep", enabled = beepOn, onEnabledChange = { beepOn = it },
            intensity = beepIntensity, onIntensityChange = { beepIntensity = it },
            countSlider = null,
        )
        StimSection(
            label = "Zap", enabled = zapOn, onEnabledChange = { zapOn = it },
            intensity = zapIntensity, onIntensityChange = { zapIntensity = it },
            countSlider = CountSlider(value = zapCount, range = 1f..15f, onChange = { zapCount = it }),
        )

        GuarantorCard(
            expanded = guarantorExpanded,
            onExpandToggle = { guarantorExpanded = !guarantorExpanded },
            selected = guarantor,
            onSelect = { guarantor = it },
            jjacksCount = jjacksCount,
            onJjacksCountChange = { jjacksCount = it },
            onViewQrCode = { showQrDialog = true },
        )

        WakeFeaturesCard(
            expanded = wakeFeaturesExpanded,
            onExpandToggle = { wakeFeaturesExpanded = !wakeFeaturesExpanded },
            snoozeZap = snoozeZap, onSnoozeZapChange = { snoozeZap = it },
            snoozeAllowed = snooze,
            lightSleep = lightSleep, onLightSleepChange = { lightSleep = it },
            escalating = escalating, onEscalatingChange = { escalating = it },
            smartAlarm = smartAlarm, onSmartAlarmChange = { smartAlarm = it },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Snooze", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = snooze, onCheckedChange = { snooze = it })
                }
                Spacer(Modifier.height(8.dp))
                Text("Interval between stimuli — ${interval.toInt()}s", style = MaterialTheme.typography.titleSmall)
                Slider(value = interval, onValueChange = { interval = it }, valueRange = 5f..60f)
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { scope.launch { saveAndExit() } },
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) { Text(if (saving) "Saving..." else "Save") }
            OutlinedButton(
                onClick = onDone,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) { Text("Cancel") }
        }
        if (index != null) {
            OutlinedButton(
                onClick = { scope.launch { deleteAndExit() } },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete alarm") }
        }
    }

    if (showQrDialog) {
        QrCodeDialog(onDismiss = { showQrDialog = false })
    }
}

private data class CountSlider(
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val onChange: (Float) -> Unit,
)

@Composable
private fun StimSection(
    label: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    intensity: Float,
    onIntensityChange: (Float) -> Unit,
    countSlider: CountSlider?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                Spacer(Modifier.height(8.dp))
                Text("Intensity — ${intensity.toInt()}%", style = MaterialTheme.typography.titleSmall)
                Slider(value = intensity, onValueChange = onIntensityChange, valueRange = 0f..100f)
                countSlider?.let {
                    Text("Count — ${it.value.toInt()}", style = MaterialTheme.typography.titleSmall)
                    Slider(value = it.value, onValueChange = it.onChange, valueRange = it.range)
                }
            }
        }
    }
}

@Composable
private fun GuarantorCard(
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    selected: Guarantor,
    onSelect: (Guarantor) -> Unit,
    jjacksCount: Float,
    onJjacksCountChange: (Float) -> Unit,
    onViewQrCode: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpandToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wake-up guarantor", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (selected) {
                            Guarantor.NONE -> "Stop with a single tap"
                            Guarantor.JUMPING_JACKS -> "Jumping Jacks (${jjacksCount.toInt()} reps)"
                            Guarantor.QR_CODE -> "QR code scan"
                            Guarantor.PUZZLE -> "Puzzle unlock"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Guarantor.values().forEach { g ->
                    GuarantorRow(
                        guarantor = g,
                        selected = selected == g,
                        onClick = { onSelect(g) },
                    )
                }
                if (selected == Guarantor.JUMPING_JACKS) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Required reps — ${jjacksCount.toInt()}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Slider(
                        value = jjacksCount,
                        onValueChange = onJjacksCountChange,
                        valueRange = 1f..20f,
                        steps = 18,
                    )
                }
                if (selected == Guarantor.QR_CODE) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onViewQrCode,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("View / print QR code") }
                }
            }
        }
    }
}

@Composable
private fun WakeFeaturesCard(
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    snoozeZap: Boolean, onSnoozeZapChange: (Boolean) -> Unit,
    snoozeAllowed: Boolean,
    lightSleep: Boolean, onLightSleepChange: (Boolean) -> Unit,
    escalating: Boolean, onEscalatingChange: (Boolean) -> Unit,
    smartAlarm: Boolean, onSmartAlarmChange: (Boolean) -> Unit,
) {
    val enabledCount = listOf(
        snoozeZap && snoozeAllowed, lightSleep, escalating, smartAlarm,
    ).count { it }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpandToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Additional wake-up features", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (enabledCount == 0) "None enabled"
                        else "$enabledCount enabled",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                WakeFeatureRow(
                    label = "Snooze Zap",
                    description = if (snoozeAllowed)
                        "Zap when you snooze the alarm"
                    else "Requires Snooze to be enabled above",
                    checked = snoozeZap && snoozeAllowed,
                    onCheckedChange = onSnoozeZapChange,
                    enabled = snoozeAllowed,
                )
                WakeFeatureRow(
                    label = "Light Sleep",
                    description = "Watch may fire up to 20 min early if it detects light sleep",
                    checked = lightSleep,
                    onCheckedChange = onLightSleepChange,
                )
                WakeFeatureRow(
                    label = "Escalating alarm",
                    description = "Stim intensity ramps up until you wake or hit the cap",
                    checked = escalating,
                    onCheckedChange = onEscalatingChange,
                )
                WakeFeatureRow(
                    label = "Smart alarm",
                    description = "After dismiss, re-arm if you stop moving — 5 min default, 30 min total",
                    checked = smartAlarm,
                    onCheckedChange = onSmartAlarmChange,
                )
            }
        }
    }
}

@Composable
private fun WakeFeatureRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun GuarantorRow(guarantor: Guarantor, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(guarantor.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                when (guarantor) {
                    Guarantor.NONE -> "Stop the alarm with a single tap"
                    Guarantor.JUMPING_JACKS -> "Watch counts your jumps before the alarm stops"
                    Guarantor.QR_CODE -> "Scan a printed QR code in the LibreShock app to stop the alarm"
                    Guarantor.PUZZLE -> "Vendor-app only for now — stop via the watch or the vendor app"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DayChips(mask: Int, onToggle: (bit: Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DAY_LABELS.forEachIndexed { i, lbl ->
            val bit = DAY_BITS[i]
            val on = (mask and bit) != 0
            DayChip(label = lbl, selected = on, onClick = { onToggle(bit) })
        }
    }
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

