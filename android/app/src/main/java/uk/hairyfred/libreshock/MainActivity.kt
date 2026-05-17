package uk.hairyfred.libreshock

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import uk.hairyfred.libreshock.ble.ShockDevice
import uk.hairyfred.libreshock.ui.theme.LibreShockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LibreShockTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("LibreShock") }) },
    ) { padding ->
        ConnectionFlow(padding)
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectionFlow(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val device = remember { ShockDevice(context) }

    var permissionsGranted by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var connectingName by remember { mutableStateOf<String?>(null) }
    val foundDevices = remember { mutableStateListOf<ScanResult>() }
    var status by remember { mutableStateOf("Tap Scan to find your device") }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        status = if (permissionsGranted) "Permissions granted. Tap Scan." else "Permissions denied — cannot scan."
    }

    LaunchedEffect(Unit) {
        val required = ShockDevice.Permissions.required()
        permissionsGranted = required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permissionsGranted) permLauncher.launch(required)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(status, style = MaterialTheme.typography.bodyMedium)

        if (!isConnected) {
            Button(
                onClick = {
                    if (!permissionsGranted) {
                        permLauncher.launch(ShockDevice.Permissions.required())
                        return@Button
                    }
                    foundDevices.clear()
                    isScanning = true
                    status = "Scanning for Pavlok-3-*..."
                    scope.launch {
                        try {
                            device.scan().takeWhile { isScanning }.collect { result ->
                                if (foundDevices.none { it.device.address == result.device.address }) {
                                    foundDevices.add(result)
                                }
                            }
                        } catch (e: Exception) {
                            status = "Scan error: ${e.message}"
                            isScanning = false
                        }
                    }
                },
                enabled = !isScanning,
            ) { Text(if (isScanning) "Scanning..." else "Scan") }

            if (isScanning) {
                Button(onClick = { isScanning = false; status = "Scan stopped" }) {
                    Text("Stop scan")
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(foundDevices, key = { it.device.address }) { result ->
                    DeviceCard(
                        name = result.device.name ?: "(unknown)",
                        address = result.device.address,
                        rssi = result.rssi,
                        connecting = connectingName == result.device.address,
                        onClick = {
                            isScanning = false
                            connectingName = result.device.address
                            status = "Connecting to ${result.device.name}..."
                            scope.launch {
                                val ok = device.connect(result.device)
                                connectingName = null
                                if (ok) {
                                    isConnected = true
                                    status = "Connected to ${result.device.name}"
                                } else {
                                    status = "Failed to connect"
                                }
                            }
                        },
                    )
                }
            }
        } else {
            ActionButtons(
                onVibe = { intensity ->
                    scope.launch {
                        val ok = device.vibrate(intensity = intensity, count = 3)
                        status = if (ok) "Vibrate sent" else "Vibrate failed"
                    }
                },
                onBeep = { intensity ->
                    scope.launch {
                        val ok = device.beep(intensity = intensity, count = 2)
                        status = if (ok) "Beep sent" else "Beep failed"
                    }
                },
                onZap = { intensity ->
                    scope.launch {
                        val ok = device.zap(intensity = intensity)
                        status = if (ok) "Zap sent" else "Zap failed"
                    }
                },
                onDisconnect = {
                    device.disconnect()
                    isConnected = false
                    status = "Disconnected"
                },
            )
        }
    }
}

@Composable
private fun DeviceCard(
    name: String,
    address: String,
    rssi: Int,
    connecting: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text("$address  •  RSSI $rssi dBm", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Button(onClick = onClick, enabled = !connecting) {
                Text(if (connecting) "Connecting..." else "Connect")
            }
        }
    }
}

@Composable
private fun ActionButtons(
    onVibe: (Int) -> Unit,
    onBeep: (Int) -> Unit,
    onZap: (Int) -> Unit,
    onDisconnect: () -> Unit,
) {
    var vibeIntensity by remember { mutableStateOf(50f) }
    var beepIntensity by remember { mutableStateOf(50f) }
    var zapIntensity by remember { mutableStateOf(30f) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IntensityControl(
            label = "Vibrate",
            value = vibeIntensity,
            onValueChange = { vibeIntensity = it },
            onTrigger = { onVibe(vibeIntensity.toInt()) },
        )
        IntensityControl(
            label = "Beep",
            value = beepIntensity,
            onValueChange = { beepIntensity = it },
            onTrigger = { onBeep(beepIntensity.toInt()) },
        )
        IntensityControl(
            label = "Zap",
            value = zapIntensity,
            onValueChange = { zapIntensity = it },
            onTrigger = { onZap(zapIntensity.toInt()) },
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Disconnect") }
    }
}

@Composable
private fun IntensityControl(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onTrigger: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("$label — ${value.toInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..100f,
                steps = 99,
            )
            Button(onClick = onTrigger, modifier = Modifier.fillMaxWidth()) {
                Text("Trigger $label")
            }
        }
    }
}
