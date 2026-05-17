@file:Suppress("DEPRECATION")

package uk.hairyfred.libreshock.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * BLE wrapper around the shock device. Mirrors libreshock.py's ShockDevice.
 *
 * GATT operations are serialized with a mutex because Android only allows one
 * outstanding GATT op at a time per connection. Notifications are surfaced via
 * channels (for one-shot request/response flows like setAlarms) and via a
 * SharedFlow (for the persistent alarm-fire/stop/snooze stream).
 */
class ShockDevice(private val context: Context) {

    companion object {
        private const val TAG = "ShockDevice"
        // Match anything starting with "Pavlok" so other models and any future
        // name format are picked up. Protocol verified on Pavlok-3 only.
        const val DEVICE_NAME_PATTERN = "Pavlok"

        // Action characteristics (service 156e1000)
        val CHAR_VIBE: UUID = UUID.fromString("00001001-0000-1000-8000-00805f9b34fb")
        val CHAR_BEEP: UUID = UUID.fromString("00001002-0000-1000-8000-00805f9b34fb")
        val CHAR_ZAP: UUID = UUID.fromString("00001003-0000-1000-8000-00805f9b34fb")
        val CHAR_LED: UUID = UUID.fromString("00001004-0000-1000-8000-00805f9b34fb")

        // Alarm control (service 156e5000)
        val CHAR_CTRL: UUID = UUID.fromString("00005001-0000-1000-8000-00805f9b34fb")
        val CHAR_DATA: UUID = UUID.fromString("00005002-0000-1000-8000-00805f9b34fb")
        val CHAR_NOTIFY: UUID = UUID.fromString("00005003-0000-1000-8000-00805f9b34fb")

        // Standard battery service
        val CHAR_BATTERY: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        // Client Characteristic Configuration Descriptor — same for all chars
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TRIGGER_ENABLED: Byte = 0x81.toByte()
        private const val MTU_PAYLOAD = 20  // bytes per write chunk
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gatt: BluetoothGatt? = null
    private val gattMutex = Mutex()

    // Continuations for awaiting GATT callback completions
    private var connectCont: Continuation<Boolean>? = null
    private var writeCont: Continuation<Boolean>? = null
    private var descCont: Continuation<Boolean>? = null
    private var readCont: Continuation<ByteArray?>? = null

    /** Set to true before .disconnect() so the resulting STATE_DISCONNECTED is reported as intentional. */
    private var intentionalDisconnect = false

    // Channels for receiving notifications from specific characteristics
    private val dataNotifs = Channel<ByteArray>(Channel.UNLIMITED)
    private val ctrlNotifs = Channel<ByteArray>(Channel.UNLIMITED)

    private val _alarmEvents = MutableSharedFlow<NotifyEvent>(extraBufferCapacity = 16)
    /** Persistent stream of alarm-fire / stop / snooze events from the watch. */
    val alarmEvents: SharedFlow<NotifyEvent> = _alarmEvents.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScanResult> = callbackFlow {
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth not enabled or LE scanner unavailable")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (DEVICE_NAME_PATTERN in name) trySend(result)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        awaitClose { scanner.stopScan(cb) }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean {
        intentionalDisconnect = false
        _connectionState.value = ConnectionState.Connecting
        val ok = suspendCoroutine<Boolean> { cont ->
            connectCont = cont
            gatt = device.connectGatt(context, false, gattCallback)
        }
        if (!ok) {
            _connectionState.value = ConnectionState.Disconnected
            return false
        }
        val notifyOk = setupNotifications()
        if (notifyOk) _connectionState.value = ConnectionState.Connected
        return notifyOk
    }

    /** Connect by stored MAC address. Returns false if the device is unknown or out of range. */
    @SuppressLint("MissingPermission")
    suspend fun connectByAddress(mac: String): Boolean {
        val adapter = bluetoothManager.adapter ?: return false
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return connect(device)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        intentionalDisconnect = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    val isConnected: Boolean get() = gatt != null

    /** Read the watch's battery level (0-100). Returns null if the read fails. */
    @SuppressLint("MissingPermission")
    suspend fun readBattery(): Int? = gattMutex.withLock {
        val g = gatt ?: return@withLock null
        val char = findCharacteristic(g, CHAR_BATTERY) ?: return@withLock null
        val value = suspendCoroutine<ByteArray?> { cont ->
            readCont = cont
            if (!g.readCharacteristic(char)) {
                readCont = null
                cont.resume(null)
            }
        }
        value?.firstOrNull()?.toInt()?.and(0xFF)
    }

    // ---- Instant actions ----

    suspend fun vibrate(intensity: Int = 100, count: Int = 1, onTime: Int = 22, offTime: Int = 22): Boolean =
        gattMutex.withLock {
            writeChar(CHAR_VIBE, byteArrayOf(TRIGGER_ENABLED, count.toByte(), intensity.toByte(), onTime.toByte(), offTime.toByte()))
        }

    suspend fun beep(intensity: Int = 80, count: Int = 1, onTime: Int = 22, offTime: Int = 22): Boolean =
        gattMutex.withLock {
            writeChar(CHAR_BEEP, byteArrayOf(TRIGGER_ENABLED, count.toByte(), intensity.toByte(), onTime.toByte(), offTime.toByte()))
        }

    suspend fun zap(intensity: Int = 50): Boolean =
        gattMutex.withLock {
            writeChar(CHAR_ZAP, byteArrayOf(TRIGGER_ENABLED, intensity.toByte()))
        }

    // ---- Alarm operations ----

    /** Write a full set of alarms. Empty list clears all alarms on the device. */
    suspend fun setAlarms(
        alarms: List<AlarmConfig>,
        profile: ByteArray = "Single 1".toByteArray(Charsets.UTF_8),
    ): Boolean = gattMutex.withLock {
        drain(dataNotifs)
        val packet = buildAlarmPacket(alarms, profile)

        if (!writeChar(CHAR_CTRL, CtrlCommand.enterWriteMode(profile))) return@withLock false
        for (chunk in packet.chunkedBytes(MTU_PAYLOAD)) {
            if (!writeChar(CHAR_DATA, chunk)) return@withLock false
        }
        // Empty finalize write
        if (!writeChar(CHAR_DATA, ByteArray(0))) return@withLock false
        if (!writeChar(CHAR_CTRL, CtrlCommand.exitWriteMode(profile))) return@withLock false

        // Wait for device's success notification (00000000 or 04000000)
        val response = withTimeoutOrNull(2000) {
            var last: ByteArray? = null
            while (true) {
                val next = withTimeoutOrNull(500) { dataNotifs.receive() } ?: break
                last = next
            }
            last
        }
        val success = response?.let { it.contentEquals(SUCCESS_RESP_A) || it.contentEquals(SUCCESS_RESP_B) }
        success == true
    }

    /** Read the alarms currently stored on the device. */
    suspend fun listAlarms(
        profile: ByteArray = "Single 1".toByteArray(Charsets.UTF_8),
    ): List<AlarmConfig> = gattMutex.withLock {
        drain(ctrlNotifs)
        drain(dataNotifs)
        if (!writeChar(CHAR_CTRL, CtrlCommand.queryAlarms(profile))) return@withLock emptyList()

        // The device echoes the alarm packet back via CTRL (and sometimes DATA)
        // notifications. Collect until a quiet period.
        val buffer = ArrayList<Byte>(512)
        while (true) {
            val chunk = withTimeoutOrNull(800) { ctrlNotifs.receive() } ?: break
            for (b in chunk) buffer.add(b)
        }
        if (buffer.isEmpty()) return@withLock emptyList()
        parseAlarms(buffer.toByteArray())
    }

    suspend fun stopAlarm(): Boolean = gattMutex.withLock {
        writeChar(CHAR_CTRL, CtrlCommand.STOP_ALARM)
    }

    suspend fun snoozeAlarm(): Boolean = gattMutex.withLock {
        writeChar(CHAR_CTRL, CtrlCommand.SNOOZE_ALARM)
    }

    // ---- Internals ----

    private suspend fun setupNotifications(): Boolean {
        val g = gatt ?: return false
        for (uuid in listOf(CHAR_DATA, CHAR_CTRL, CHAR_NOTIFY)) {
            val char = findCharacteristic(g, uuid)
            if (char == null) {
                Log.e(TAG, "Char $uuid not found for notification setup")
                return false
            }
            @SuppressLint("MissingPermission")
            val localOk = g.setCharacteristicNotification(char, true)
            if (!localOk) {
                Log.e(TAG, "setCharacteristicNotification failed for $uuid")
                return false
            }
            val descriptor = char.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                Log.e(TAG, "CCCD missing for $uuid")
                return false
            }
            if (!writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                Log.e(TAG, "Failed to enable notify on $uuid")
                return false
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeChar(uuid: UUID, data: ByteArray): Boolean = suspendCoroutine { cont ->
        val g = gatt ?: run { cont.resume(false); return@suspendCoroutine }
        val char = findCharacteristic(g, uuid) ?: run {
            Log.e(TAG, "Characteristic $uuid not found"); cont.resume(false); return@suspendCoroutine
        }
        writeCont = cont
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(char)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeDescriptor(desc: BluetoothGattDescriptor, value: ByteArray): Boolean =
        suspendCoroutine { cont ->
            val g = gatt ?: run { cont.resume(false); return@suspendCoroutine }
            descCont = cont
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(desc, value)
            } else {
                desc.value = value
                g.writeDescriptor(desc)
            }
        }

    private fun findCharacteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (service in g.services) service.getCharacteristic(uuid)?.let { return it }
        return null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status, intentional=$intentionalDisconnect)")
                    val wasIntentional = intentionalDisconnect
                    intentionalDisconnect = false
                    connectCont?.resume(false); connectCont = null
                    g.close()
                    if (gatt === g) gatt = null
                    _connectionState.value =
                        if (wasIntentional) ConnectionState.Disconnected else ConnectionState.Lost
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            Log.d(TAG, "Services discovered: ok=$ok, ${g.services.size} services")
            connectCont?.resume(ok); connectCont = null
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            writeCont?.resume(status == BluetoothGatt.GATT_SUCCESS); writeCont = null
        }

        @Deprecated("Deprecated in Java")
        override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
            descCont?.resume(status == BluetoothGatt.GATT_SUCCESS); descCont = null
        }

        // Pre-Tiramisu read callback
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val value = char.value
            readCont?.resume(if (status == BluetoothGatt.GATT_SUCCESS) value else null); readCont = null
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            readCont?.resume(if (status == BluetoothGatt.GATT_SUCCESS) value else null); readCont = null
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = char.value ?: return
            dispatchNotification(char.uuid, value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatchNotification(char.uuid, value)
        }
    }

    private fun dispatchNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            CHAR_DATA -> dataNotifs.trySend(value)
            CHAR_CTRL -> ctrlNotifs.trySend(value)
            CHAR_NOTIFY -> parseNotifyEvent(value)?.let { _alarmEvents.tryEmit(it) }
        }
    }

    private fun drain(ch: Channel<ByteArray>) {
        while (ch.tryReceive().isSuccess) { /* drop */ }
    }

    /** Permission strings that need to be granted before scanning/connecting. */
    object Permissions {
        fun required(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}

/** High-level connection state, exposed as a StateFlow for UI to observe. */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    /** Connection dropped without a call to [ShockDevice.disconnect] — candidate for auto-reconnect. */
    object Lost : ConnectionState()
}

private val SUCCESS_RESP_A = byteArrayOf(0, 0, 0, 0)
private val SUCCESS_RESP_B = byteArrayOf(4, 0, 0, 0)

private fun ByteArray.chunkedBytes(size: Int): List<ByteArray> {
    val chunks = mutableListOf<ByteArray>()
    var pos = 0
    while (pos < this.size) {
        val end = minOf(pos + size, this.size)
        chunks.add(this.copyOfRange(pos, end))
        pos = end
    }
    return chunks
}
