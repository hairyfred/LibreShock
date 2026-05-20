package uk.hairyfred.libreshock.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.hairyfred.libreshock.ble.HandRaiseConfig
import uk.hairyfred.libreshock.ble.HandRaiseStim
import uk.hairyfred.libreshock.ble.ShockDevice

@Composable
fun HandRaiseScreen(
    device: ShockDevice,
    padding: PaddingValues,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<HandRaiseConfig?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val raw = try { device.readHandRaise() } catch (_: Exception) { null }
        config = HandRaiseConfig.parse(raw)
    }

    val c = config ?: HandRaiseConfig()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (config == null) {
            Text("Loading current settings...", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        // Enable toggle
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable detection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Trigger a stimulus when you raise your hand",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = c.enabled,
                    onCheckedChange = { config = c.copy(enabled = it) },
                )
            }
        }

        // Hand selector
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Which hand is the watch on?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    options = listOf("Left" to true, "Right" to false),
                    selected = c.leftHand,
                    onSelect = { config = c.copy(leftHand = it) },
                )
            }
        }

        // Wrist position selector
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Strapped inside or outside the wrist?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    options = listOf("Outside" to false, "Inside" to true),
                    selected = c.insideWrist,
                    onSelect = { config = c.copy(insideWrist = it) },
                )
            }
        }

        // Stimulus selector
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Stimulus", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StimRow(c, HandRaiseStim.VIBRATE) { config = c.copy(stimulus = it) }
                    StimRow(c, HandRaiseStim.BEEP)    { config = c.copy(stimulus = it) }
                    StimRow(c, HandRaiseStim.ZAP)     { config = c.copy(stimulus = it) }
                    StimRow(c, HandRaiseStim.COUNTDOWN) { config = c.copy(stimulus = it) }
                }

                // Only Zap exposes an intensity slider (the vendor app same).
                if (c.stimulus == HandRaiseStim.ZAP) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Zap intensity — ${c.intensity}%",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Slider(
                        value = c.intensity.toFloat(),
                        onValueChange = { config = c.copy(intensity = it.toInt()) },
                        valueRange = 0f..100f,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    when (c.stimulus) {
                        HandRaiseStim.VIBRATE -> "Watch will vibrate when hand-raise is detected."
                        HandRaiseStim.BEEP -> "Watch will beep when hand-raise is detected."
                        HandRaiseStim.ZAP -> "Watch will zap when hand-raise is detected."
                        HandRaiseStim.COUNTDOWN -> "Watch counts down then zaps. Lower your hand before time's up to cancel."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val current = config ?: return@Button
                    scope.launch {
                        saving = true
                        error = null
                        val ok = try { device.setHandRaise(current) } catch (_: Exception) { false }
                        saving = false
                        if (ok) onSaved() else error = "Failed to save"
                    }
                },
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) { Text(if (saving) "Saving..." else "Save") }
            OutlinedButton(
                onClick = onSaved,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun ChoiceRow(
    options: List<Pair<String, Boolean>>,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (label, value) ->
            if (value == selected) {
                Button(onClick = { onSelect(value) }, modifier = Modifier.weight(1f)) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(value) }, modifier = Modifier.weight(1f)) { Text(label) }
            }
        }
    }
}

@Composable
private fun StimRow(
    cfg: HandRaiseConfig,
    stim: HandRaiseStim,
    onSelect: (HandRaiseStim) -> Unit,
) {
    val selected = cfg.stimulus == stim
    if (selected) {
        Button(onClick = { onSelect(stim) }, modifier = Modifier.fillMaxWidth()) { Text(stim.displayName) }
    } else {
        FilledTonalButton(onClick = { onSelect(stim) }, modifier = Modifier.fillMaxWidth()) { Text(stim.displayName) }
    }
}
