package uk.hairyfred.libreshock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.hairyfred.libreshock.ble.DeviceInfo
import uk.hairyfred.libreshock.ble.ShockDevice

@Composable
fun DeviceInfoScreen(device: ShockDevice, deviceName: String?, padding: PaddingValues) {
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            info = device.readDeviceInfo(deviceName)
        } catch (e: Exception) {
            error = "Failed to read: ${e.message}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            info == null -> Row { CircularProgressIndicator(); Text("  Reading...") }
            else -> InfoCard(info!!)
        }
    }
}

@Composable
private fun InfoCard(info: DeviceInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoRow("Device name", info.name)
            InfoRow("Model", info.model)
            InfoRow("Serial number", info.serial)
            InfoRow("Firmware version", info.firmwareRevision)
            InfoRow("Hardware version", info.hardwareRevision)
            InfoRow("Manufacturer", info.manufacturer)
            InfoRow("Date on device", info.date)
            InfoRow("Time on device", info.time)
            InfoRow("Timezone on device", info.timezone)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
