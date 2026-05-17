@file:Suppress("DEPRECATION")

package uk.hairyfred.libreshock.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Minimal BLE wrapper around the shock device. Mirrors the Python ShockDevice.
 *
 * Permission handling lives in the calling Activity. This class assumes
 * BLUETOOTH_CONNECT/BLUETOOTH_SCAN (API 31+) or BLUETOOTH/ACCESS_FINE_LOCATION
 * (legacy) are already granted.
 */
class ShockDevice(private val context: Context) {

    companion object {
        private const val TAG = "ShockDevice"
        const val DEVICE_NAME_PATTERN = "Pavlok-3"

        // Action characteristics (service 156e1000)
        val CHAR_VIBE: UUID = UUID.fromString("00001001-0000-1000-8000-00805f9b34fb")
        val CHAR_BEEP: UUID = UUID.fromString("00001002-0000-1000-8000-00805f9b34fb")
        val CHAR_ZAP: UUID = UUID.fromString("00001003-0000-1000-8000-00805f9b34fb")
        val CHAR_LED: UUID = UUID.fromString("00001004-0000-1000-8000-00805f9b34fb")

        // Alarm control (service 156e5000)
        val CHAR_CTRL: UUID = UUID.fromString("00005001-0000-1000-8000-00805f9b34fb")
        val CHAR_DATA: UUID = UUID.fromString("00005002-0000-1000-8000-00805f9b34fb")
        val CHAR_NOTIFY: UUID = UUID.fromString("00005003-0000-1000-8000-00805f9b34fb")

        private const val TRIGGER_ENABLED: Byte = 0x81.toByte()
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gatt: BluetoothGatt? = null
    private var connectCont: Continuation<Boolean>? = null
    private var writeCont: Continuation<Boolean>? = null

    /** Emits ScanResults for devices whose name matches the shock device pattern. */
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

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, cb)
        awaitClose { scanner.stopScan(cb) }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = suspendCoroutine { cont ->
        connectCont = cont
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    val isConnected: Boolean
        get() = gatt != null

    suspend fun vibrate(intensity: Int = 100, count: Int = 1, onTime: Int = 22, offTime: Int = 22): Boolean =
        writeChar(CHAR_VIBE, byteArrayOf(TRIGGER_ENABLED, count.toByte(), intensity.toByte(), onTime.toByte(), offTime.toByte()))

    suspend fun beep(intensity: Int = 80, count: Int = 1, onTime: Int = 22, offTime: Int = 22): Boolean =
        writeChar(CHAR_BEEP, byteArrayOf(TRIGGER_ENABLED, count.toByte(), intensity.toByte(), onTime.toByte(), offTime.toByte()))

    suspend fun zap(intensity: Int = 50): Boolean =
        writeChar(CHAR_ZAP, byteArrayOf(TRIGGER_ENABLED, intensity.toByte()))

    @SuppressLint("MissingPermission")
    private suspend fun writeChar(uuid: UUID, data: ByteArray): Boolean = suspendCoroutine { cont ->
        val g = gatt ?: run {
            cont.resume(false)
            return@suspendCoroutine
        }
        val char = findCharacteristic(g, uuid) ?: run {
            Log.e(TAG, "Characteristic $uuid not found")
            cont.resume(false)
            return@suspendCoroutine
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

    private fun findCharacteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (service in g.services) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    connectCont?.resume(false)
                    connectCont = null
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            Log.d(TAG, "Services discovered: ok=$ok, ${g.services.size} services")
            connectCont?.resume(ok)
            connectCont = null
        }

        // Pre-Tiramisu callback
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            writeCont?.resume(status == BluetoothGatt.GATT_SUCCESS)
            writeCont = null
        }
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
