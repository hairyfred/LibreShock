package uk.hairyfred.libreshock

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import uk.hairyfred.libreshock.ble.AlarmConfig
import uk.hairyfred.libreshock.ble.BatteryHistory
import uk.hairyfred.libreshock.ble.ConnectionState
import uk.hairyfred.libreshock.ble.NotifyOpcode
import uk.hairyfred.libreshock.ble.ShockDevice
import uk.hairyfred.libreshock.ui.AlarmEditScreen
import uk.hairyfred.libreshock.ui.AlarmFiringDialog
import uk.hairyfred.libreshock.ui.AlarmsScreen
import uk.hairyfred.libreshock.ui.BatteryUsageScreen
import uk.hairyfred.libreshock.ui.DeviceInfoScreen
import uk.hairyfred.libreshock.ui.theme.LibreShockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LibreShockTheme {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf("main") }
    var editingAlarm by remember { mutableStateOf<AlarmConfig?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var firingAlarmId by remember { mutableStateOf<Int?>(null) }
    var batteryPercent by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("libreshock", Context.MODE_PRIVATE) }
    val device = remember { ShockDevice(context) }
    val batteryHistory = remember { BatteryHistory(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Apply persisted debug-logging preference at start.
    remember {
        uk.hairyfred.libreshock.ble.DebugLog.enabled = prefs.getBoolean("debug_logging", false)
        Unit
    }
    val rootScope = rememberCoroutineScope()

    // Listen for alarm-fire / stop / snooze notifications from the watch.
    LaunchedEffect(device) {
        device.alarmEvents.collect { event ->
            when (event.opcode) {
                NotifyOpcode.ALARM_FIRING -> firingAlarmId = event.alarmId
                NotifyOpcode.STOP_OK, NotifyOpcode.SNOOZE_OK -> firingAlarmId = null
            }
        }
    }

    // Battery notifications + on-connect read both feed batteryUpdates;
    // mirror to the top-bar state and append to local history.
    LaunchedEffect(device) {
        device.batteryUpdates.collect { pct ->
            batteryPercent = pct
            batteryHistory.append(pct)
        }
    }

    val btAdapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    val enableBtLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* outcome surfaced via BT state broadcast */ }

    // Tracks the currently-displayed "Bluetooth is off" snackbar so we can
    // dismiss it the moment BT comes back on.
    var btOffSnackbarJob by remember { mutableStateOf<Job?>(null) }

    suspend fun tryReconnect(reason: String): Boolean {
        val lastMac = prefs.getString("last_mac", null) ?: return false
        if (prefs.getBoolean("user_disconnected", false)) return false
        if (device.connectionState.value is ConnectionState.Connected) return true
        val lastName = prefs.getString("last_name", null) ?: "device"
        // Give the BLE stack a moment to settle after a state change.
        delay(800)
        val ok = try { device.connectByAddress(lastMac) } catch (_: Exception) { false }
        snackbarHostState.showSnackbar(
            if (ok) "Reconnected to $lastName" else "Could not reconnect — tap Scan",
            duration = SnackbarDuration.Short,
        )
        if (ok) try { device.readBattery() } catch (_: Exception) {}
        return ok
    }

    // The BroadcastReceiver is the single source of truth for BT-state messages.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        btOffSnackbarJob?.cancel()
                        btOffSnackbarJob = rootScope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Bluetooth is off",
                                actionLabel = "Turn on",
                                duration = SnackbarDuration.Indefinite,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            }
                        }
                    }
                    BluetoothAdapter.STATE_ON -> {
                        btOffSnackbarJob?.cancel()
                        btOffSnackbarJob = null
                        rootScope.launch { tryReconnect("BT enabled") }
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    // On launch, if BT is off, surface the enable-Bluetooth prompt up front.
    LaunchedEffect(Unit) {
        if (btAdapter?.isEnabled == false) {
            btOffSnackbarJob?.cancel()
            btOffSnackbarJob = rootScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Bluetooth is off",
                    actionLabel = "Turn on",
                    duration = SnackbarDuration.Indefinite,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }
        }
    }

    // When the watch drops out from under us while BT is on (e.g. out of range
    // or watch reboot), try to reconnect. If BT is off we let the broadcast
    // receiver above handle it once BT comes back.
    LaunchedEffect(device) {
        device.connectionState.collect { state ->
            when (state) {
                ConnectionState.Lost -> {
                    if (btAdapter?.isEnabled == true) {
                        snackbarHostState.showSnackbar(
                            "Lost connection — reconnecting...",
                            duration = SnackbarDuration.Short,
                        )
                        tryReconnect("lost while BT on")
                    }
                    // else: BroadcastReceiver will reconnect when BT returns
                }
                ConnectionState.Disconnected -> batteryPercent = null
                else -> {}
            }
        }
    }

    val title = when (screen) {
        "settings" -> "Settings"
        "alarms" -> "Alarms"
        "alarm_edit" -> if (editingIndex == null) "New alarm" else "Edit alarm"
        "device_info" -> "Device info"
        "battery_usage" -> "Battery usage"
        else -> "LibreShock"
    }

    // System back / swipe-back: alarm_edit → alarms, anything else → main
    BackHandler(enabled = screen != "main") {
        screen = when (screen) {
            "alarm_edit" -> "alarms"
            else -> "main"
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (screen != "main") {
                        TextButton(onClick = {
                            screen = when (screen) {
                                "alarm_edit" -> "alarms"
                                else -> "main"
                            }
                        }) { Text("Back") }
                    }
                },
                actions = {
                    if (screen == "main") {
                        TextButton(onClick = { screen = "settings" }) { Text("Settings") }
                    }
                },
            )
        },
    ) { padding ->
        when (screen) {
            "settings" -> SettingsScreen(prefs, padding)
            "alarms" -> AlarmsScreen(device, padding) { alarm, index ->
                editingAlarm = alarm
                editingIndex = index
                screen = "alarm_edit"
            }
            "alarm_edit" -> AlarmEditScreen(device, editingAlarm, editingIndex, padding) {
                screen = "alarms"
            }
            "device_info" -> DeviceInfoScreen(
                device = device,
                deviceName = prefs.getString("last_name", null),
                padding = padding,
            )
            "battery_usage" -> BatteryUsageScreen(device, batteryHistory, padding)
            else -> ConnectionFlow(
                prefs = prefs,
                device = device,
                padding = padding,
                batteryPercent = batteryPercent,
                onOpenAlarms = { screen = "alarms" },
                onOpenDeviceInfo = { screen = "device_info" },
                onOpenBatteryUsage = { screen = "battery_usage" },
            )
        }
    }

    firingAlarmId?.let { id ->
        AlarmFiringDialog(
            alarmId = id,
            onStop = {
                rootScope.launch {
                    device.stopAlarm()
                    firingAlarmId = null
                }
            },
            onSnooze = {
                rootScope.launch {
                    device.snoozeAlarm()
                    firingAlarmId = null
                }
            },
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectionFlow(
    prefs: SharedPreferences,
    device: ShockDevice,
    padding: PaddingValues,
    batteryPercent: Int?,
    onOpenAlarms: () -> Unit,
    onOpenDeviceInfo: () -> Unit,
    onOpenBatteryUsage: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var permissionsGranted by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var connectingName by remember { mutableStateOf<String?>(null) }
    val foundDevices = remember { mutableStateListOf<ScanResult>() }
    var status by remember { mutableStateOf("Starting...") }

    // Keep isConnected in sync with the device's StateFlow (e.g., when auto-reconnect
    // finishes from outside this composable, or the watch drops the connection).
    LaunchedEffect(device) {
        device.connectionState.collect { state ->
            isConnected = state is ConnectionState.Connected
        }
    }

    suspend fun runScan() {
        if (!permissionsGranted) return
        foundDevices.clear()
        isScanning = true
        status = "Scanning for Pavlok-3-*..."
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

    suspend fun runConnect(target: BluetoothDevice, displayName: String) {
        connectingName = target.address
        status = "Connecting to $displayName..."
        val ok = device.connect(target)
        connectingName = null
        if (ok) {
            isConnected = true
            isScanning = false
            status = "Connected to $displayName"
            prefs.edit {
                putString("last_mac", target.address)
                putString("last_name", displayName)
                putBoolean("user_disconnected", false)
            }
            // Read battery once after connecting; subsequent updates arrive via notifications.
            try { device.readBattery() } catch (_: Exception) {}
        } else {
            status = "Failed to connect to $displayName"
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        status = if (permissionsGranted) "Permissions granted" else "Permissions denied — cannot scan"
    }

    LaunchedEffect(Unit) {
        val required = ShockDevice.Permissions.required()
        permissionsGranted = required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permissionsGranted) {
            permLauncher.launch(required)
            return@LaunchedEffect
        }

        val autoConnect = prefs.getBoolean("auto_connect", true)
        val autoScan = prefs.getBoolean("auto_scan", true)
        val lastMac = prefs.getString("last_mac", null)
        val lastName = prefs.getString("last_name", null) ?: lastMac.orEmpty()
        val userDisconnected = prefs.getBoolean("user_disconnected", false)

        if (autoConnect && lastMac != null && !userDisconnected) {
            status = "Reconnecting to $lastName..."
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val target = try { bm.adapter?.getRemoteDevice(lastMac) } catch (_: Exception) { null }
            if (target != null) {
                runConnect(target, lastName)
                if (isConnected) return@LaunchedEffect
            }
        }
        if (autoScan) runScan()
        else status = "Tap Scan to find your device"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val displayStatus = if (isConnected && batteryPercent != null) {
            "$status  •  Battery $batteryPercent%"
        } else {
            status
        }
        Text(displayStatus, style = MaterialTheme.typography.bodyMedium)

        if (!isConnected) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!permissionsGranted) {
                            permLauncher.launch(ShockDevice.Permissions.required()); return@Button
                        }
                        // Manual scan clears the user-disconnected flag — they want to reconnect.
                        prefs.edit { putBoolean("user_disconnected", false) }
                        scope.launch { runScan() }
                    },
                    enabled = !isScanning,
                ) { Text(if (isScanning) "Scanning..." else "Scan") }

                if (isScanning) {
                    Button(onClick = { isScanning = false; status = "Scan stopped" }) {
                        Text("Stop")
                    }
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
                            scope.launch { runConnect(result.device, result.device.name ?: result.device.address) }
                        },
                    )
                }
            }
        } else {
            ActionButtons(
                onVibe = { i -> scope.launch {
                    val ok = device.vibrate(intensity = i, count = 3); status = if (ok) "Vibrate sent" else "Vibrate failed"
                } },
                onBeep = { i -> scope.launch {
                    val ok = device.beep(intensity = i, count = 2); status = if (ok) "Beep sent" else "Beep failed"
                } },
                onZap = { i -> scope.launch {
                    val ok = device.zap(intensity = i); status = if (ok) "Zap sent" else "Zap failed"
                } },
                onAlarms = onOpenAlarms,
                onDeviceInfo = onOpenDeviceInfo,
                onBatteryUsage = onOpenBatteryUsage,
                onDisconnect = {
                    device.disconnect()
                    isConnected = false
                    // Remember the user manually disconnected so we don't auto-reconnect next launch.
                    prefs.edit { putBoolean("user_disconnected", true) }
                    status = "Disconnected"
                },
            )
        }
    }
}

@Composable
private fun SettingsScreen(prefs: SharedPreferences, padding: PaddingValues) {
    var autoScan by remember { mutableStateOf(prefs.getBoolean("auto_scan", true)) }
    var autoConnect by remember { mutableStateOf(prefs.getBoolean("auto_connect", true)) }
    var debugLogging by remember { mutableStateOf(prefs.getBoolean("debug_logging", false)) }
    val lastName = prefs.getString("last_name", null)
    val lastMac = prefs.getString("last_mac", null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingRow(
            label = "Auto-scan on launch",
            description = "Start scanning automatically when the app opens",
            checked = autoScan,
            onCheckedChange = {
                autoScan = it
                prefs.edit { putBoolean("auto_scan", it) }
            },
        )
        SettingRow(
            label = "Auto-reconnect to last device",
            description = "Reconnect to the most recently used device on launch (unless you tapped Disconnect)",
            checked = autoConnect,
            onCheckedChange = {
                autoConnect = it
                prefs.edit { putBoolean("auto_connect", it) }
            },
        )
        SettingRow(
            label = "Enable debug logging",
            description = "Write verbose BLE read/write traces to logcat. View via `adb logcat -s ShockDevice`. Off by default.",
            checked = debugLogging,
            onCheckedChange = {
                debugLogging = it
                prefs.edit { putBoolean("debug_logging", it) }
                uk.hairyfred.libreshock.ble.DebugLog.enabled = it
            },
        )

        if (lastName != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Last connected device", style = MaterialTheme.typography.titleSmall)
                    Text(lastName, style = MaterialTheme.typography.bodyMedium)
                    Text(lastMac ?: "", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        prefs.edit {
                            remove("last_mac"); remove("last_name"); remove("user_disconnected")
                        }
                    }) { Text("Forget device") }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
    onAlarms: () -> Unit,
    onDeviceInfo: () -> Unit,
    onBatteryUsage: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var vibeIntensity by remember { mutableStateOf(50f) }
    var beepIntensity by remember { mutableStateOf(50f) }
    var zapIntensity by remember { mutableStateOf(30f) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = onAlarms, modifier = Modifier.fillMaxWidth()) { Text("Manage alarms") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onDeviceInfo, modifier = Modifier.weight(1f)) { Text("Device info") }
            Button(onClick = onBatteryUsage, modifier = Modifier.weight(1f)) { Text("Battery usage") }
        }
        IntensityControl("Vibrate", vibeIntensity, { vibeIntensity = it }) { onVibe(vibeIntensity.toInt()) }
        IntensityControl("Beep", beepIntensity, { beepIntensity = it }) { onBeep(beepIntensity.toInt()) }
        IntensityControl("Zap", zapIntensity, { zapIntensity = it }) { onZap(zapIntensity.toInt()) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
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
            Slider(value = value, onValueChange = onValueChange, valueRange = 0f..100f, steps = 99)
            Button(onClick = onTrigger, modifier = Modifier.fillMaxWidth()) { Text("Trigger $label") }
        }
    }
}
