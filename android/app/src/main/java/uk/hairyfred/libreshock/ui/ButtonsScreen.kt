package uk.hairyfred.libreshock.ui

import android.content.SharedPreferences
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.launch
import uk.hairyfred.libreshock.ble.ButtonAction
import uk.hairyfred.libreshock.ble.ButtonBinding
import uk.hairyfred.libreshock.ble.ButtonSlot
import uk.hairyfred.libreshock.ble.ShockDevice

private const val PREF_PREFIX = "button_binding_"

@Composable
fun ButtonsScreen(
    device: ShockDevice,
    prefs: SharedPreferences,
    padding: PaddingValues,
) {
    val scope = rememberCoroutineScope()

    // Load all 6 slot bindings from prefs into a live state map.
    val bindings = remember {
        val map = mutableStateMapOf<ButtonSlot, ButtonBinding>()
        ButtonSlot.entries.forEach { map[it] = loadBinding(prefs, it) }
        map
    }

    var editing by remember { mutableStateOf<ButtonSlot?>(null) }
    var savingError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "The watch has three buttons (top, middle, lower). Tap any row " +
            "to change what a short or long press does.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ButtonCard("Top Button", ButtonSlot.TOP_SHORT, ButtonSlot.TOP_LONG, bindings) { editing = it }
        ButtonCard("Middle Button", ButtonSlot.MID_SHORT, ButtonSlot.MID_LONG, bindings) { editing = it }
        ButtonCard("Lower Button", ButtonSlot.LOWER_SHORT, ButtonSlot.LOWER_LONG, bindings) { editing = it }
        savingError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }

    editing?.let { slot ->
        BindingPickerDialog(
            slot = slot,
            initial = bindings[slot] ?: ButtonBinding(),
            onDismiss = { editing = null },
            onConfirm = { newBinding ->
                editing = null
                bindings[slot] = newBinding
                saveBinding(prefs, slot, newBinding)
                scope.launch {
                    val ok = try { device.setButton(slot, newBinding) } catch (_: Exception) { false }
                    if (!ok) savingError = "Failed to update ${slot.displayName}"
                }
            },
        )
    }
}

@Composable
private fun ButtonCard(
    title: String,
    shortSlot: ButtonSlot,
    longSlot: ButtonSlot,
    bindings: Map<ButtonSlot, ButtonBinding>,
    onTap: (ButtonSlot) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            BindingRow("Quick press", bindings[shortSlot] ?: ButtonBinding()) { onTap(shortSlot) }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            BindingRow("Long press", bindings[longSlot] ?: ButtonBinding()) { onTap(longSlot) }
        }
    }
}

@Composable
private fun BindingRow(label: String, binding: ButtonBinding, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(binding.summary(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BindingPickerDialog(
    slot: ButtonSlot,
    initial: ButtonBinding,
    onDismiss: () -> Unit,
    onConfirm: (ButtonBinding) -> Unit,
) {
    var selected by remember { mutableStateOf(initial.action) }
    var count by remember { mutableStateOf(initial.count.toFloat()) }
    var intensity by remember { mutableStateOf(initial.intensity.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign ${slot.displayName}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ButtonAction.entries.forEach { action ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == action, onClick = { selected = action })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == action, onClick = { selected = action })
                        Spacer(Modifier.height(0.dp))
                        Text(action.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (selected.isStim) {
                    Spacer(Modifier.height(8.dp))
                    Text("Repetition — ${count.toInt()}", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = count,
                        onValueChange = { count = it },
                        valueRange = 1f..15f,
                    )
                    Text("Intensity — ${intensity.toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = intensity,
                        onValueChange = { intensity = it },
                        valueRange = 0f..100f,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    ButtonBinding(
                        action = selected,
                        count = count.toInt().coerceIn(1, 15),
                        intensity = intensity.toInt().coerceIn(0, 100),
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun loadBinding(prefs: SharedPreferences, slot: ButtonSlot): ButtonBinding {
    val key = PREF_PREFIX + slot.name
    val actionName = prefs.getString("${key}_action", ButtonAction.DISABLED.name) ?: ButtonAction.DISABLED.name
    val action = runCatching { ButtonAction.valueOf(actionName) }.getOrDefault(ButtonAction.DISABLED)
    return ButtonBinding(
        action = action,
        count = prefs.getInt("${key}_count", 1),
        intensity = prefs.getInt("${key}_intensity", 50),
    )
}

private fun saveBinding(prefs: SharedPreferences, slot: ButtonSlot, binding: ButtonBinding) {
    val key = PREF_PREFIX + slot.name
    prefs.edit {
        putString("${key}_action", binding.action.name)
        putInt("${key}_count", binding.count)
        putInt("${key}_intensity", binding.intensity)
    }
}
