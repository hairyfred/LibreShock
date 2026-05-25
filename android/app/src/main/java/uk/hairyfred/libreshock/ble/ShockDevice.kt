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

        // Vendor BLE advertisement names seen in the wild:
        //   "Pavlok-3-XXXX"  — Pavlok 3 (Device Info model reads "Pavlok-S")
        //   "Pav4-8cbf"      — Pavlok 4 (shorter "Pav<model>-<id>" scheme)
        // Both start with "Pav" followed by either "lok" or the model digit.
        // [isDeviceName] matches that shape so new models pick up
        // automatically without matching unrelated devices that merely
        // contain "Pav". Protocol verified on Pavlok-3 only.
        fun isDeviceName(name: String?): Boolean {
            if (name == null) return false
            val low = name.lowercase()
            if (!low.startsWith("pav")) return false
            val rest = low.substring(3)
            return rest.startsWith("lok") || (rest.isNotEmpty() && rest[0].isDigit())
        }

        // The Pavlok-3 "Action Settings" service. Used as a feature-probe at
        // connect time: if a device exposes this service, we know it speaks
        // the protocol LibreShock implements. Pavlok-4 ships with a different
        // service scheme (66651000-39f4-11ed-..., etc.) and won't accept any
        // of our writes — we detect that case and surface a clear error
        // instead of silently failing on the first read.
        val SERVICE_ACTIONS: UUID = UUID.fromString("156e1000-a300-4fea-897b-86f698d74461")

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

        // Sleep tracking control (service 156e0000, char 0008). The vendor app
        // writes [0x02, 0x01/0x00] to a vendor descriptor at handle+1, but Pavlok
        // firmware also accepts the same payload at the characteristic value.
        val CHAR_SLEEP_TRACKING: UUID = UUID.fromString("00000008-0000-1000-8000-00805f9b34fb")

        // Hand-raise detection (service 156e1000, char 1006, user-desc "HD").
        // 4-byte payload: [flags, 0x70, stim_type, intensity]
        // flags: bit0=enabled, bits1+2 always set, bit3=inside-wrist, bit4=left-hand
        // stim_type: 0=vibrate, 1=beep, 2=zap, 3=countdown
        val CHAR_HAND_RAISE: UUID = UUID.fromString("00001006-0000-1000-8000-00805f9b34fb")

        // Hardware button rebinding (service 156e7000, char 7001).
        // Payload: [0x02, slot, action_class, ...params]
        val CHAR_BUTTON_CONFIG: UUID = UUID.fromString("00007001-0000-1000-8000-00805f9b34fb")

        // Sleep-history request/response endpoint (service 156e2000, char 2009).
        // Confirmed via handle 0x004A in the watch's GATT tree — char 2002 at
        // handle 0x002E is a *different* events characteristic. See
        // SleepHistory.kt for the request/response protocol.
        val CHAR_EVENTS: UUID = UUID.fromString("00002009-0000-1000-8000-00805f9b34fb")

        // Standard Device Information Service (0x180A)
        val CHAR_MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        val CHAR_MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val CHAR_SERIAL: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
        val CHAR_HARDWARE_REV: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
        val CHAR_FIRMWARE_REV: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val CHAR_SOFTWARE_REV: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")  // timezone

        // Device clock: 8-byte BCD timestamp in the action-settings service (char 1005).
        val CHAR_TIME: UUID = UUID.fromString("00001005-0000-1000-8000-00805f9b34fb")

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
    private val eventsNotifs = Channel<ByteArray>(Channel.UNLIMITED)

    private val _alarmEvents = MutableSharedFlow<NotifyEvent>(extraBufferCapacity = 16)
    /** Persistent stream of alarm-fire / stop / snooze events from the watch. */
    val alarmEvents: SharedFlow<NotifyEvent> = _alarmEvents.asSharedFlow()

    private val _batteryUpdates = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 8)
    /** Battery percentage emitted whenever the watch sends a battery notification
     *  or [readBattery] succeeds. Replays the most recent value to new subscribers. */
    val batteryUpdates: SharedFlow<Int> = _batteryUpdates.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScanResult> = callbackFlow {
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth not enabled or LE scanner unavailable")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (isDeviceName(result.device.name)) trySend(result)
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
        // Pavlok-3 protocol probe: the Action Settings service must exist.
        // Pavlok-4 (and possibly other future models) use a different service
        // UUID scheme and would silently fail every subsequent operation —
        // surface that as Unsupported so the UI can show a clear message.
        val g = gatt
        if (g != null && g.getService(SERVICE_ACTIONS) == null) {
            val modelName = runCatching {
                readChar(CHAR_MODEL)?.toString(Charsets.UTF_8)
            }.getOrNull()
            _connectionState.value = ConnectionState.Unsupported(modelName)
            g.disconnect()
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

    /** Bind one of the 6 hardware-button slots to an action. */
    suspend fun setButton(slot: ButtonSlot, binding: ButtonBinding): Boolean =
        gattMutex.withLock {
            writeChar(CHAR_BUTTON_CONFIG, binding.toPayload(slot))
        }

    /** Push a Timer or Stopwatch configuration to the watch. Same
     *  characteristic as button rebinding but with the 0x22 opcode that
     *  [buildTnsConfig] emits. Watch switches to Timer/Stopwatch mode and
     *  arms the intervals; start/stop is done from the watch buttons. */
    suspend fun setTnsConfig(config: TnsConfig): Boolean =
        gattMutex.withLock {
            writeChar(CHAR_BUTTON_CONFIG, buildTnsConfig(config))
        }

    /** Pull stored sleep-session data from the watch via char 2002.
     *
     *  Defaults fetch the full index of type-0x03 records (the watch's own
     *  sleep-stage classifications). Pass a specific [sessionId] to fetch
     *  one session only, or [queryType] = 0x82 for the general event log.
     *
     *  Returns the assembled raw response (14-byte header + body) or null
     *  on timeout. Use [parseSleepSessions] to extract per-session metadata.
     *  The per-session TLV body isn't fully decoded yet — see SleepHistory.kt. */
    suspend fun readSleepIndex(
        queryType: Int = 0x03,
        sessionId: Int = 0,
        timeoutMs: Long = 30_000L,
    ): ByteArray? = gattMutex.withLock {
        drain(eventsNotifs)
        if (!writeChar(CHAR_EVENTS, buildSleepRequest(queryType, sessionId, rangeEnd = false))) {
            return@withLock null
        }
        // The first write produces a 14-byte ack notification we don't need.
        // Drain it (with a short timeout) before sending the end-of-range
        // write that actually triggers the data stream.
        withTimeoutOrNull(2000) { eventsNotifs.receive() }
        drain(eventsNotifs)

        if (!writeChar(CHAR_EVENTS, buildSleepRequest(queryType, sessionId, rangeEnd = true))) {
            return@withLock null
        }
        val buffer = ArrayList<Byte>(4096)
        var expectedTotal: Int? = null
        var firstChunk = true
        while (true) {
            val gap = if (firstChunk) timeoutMs else 1500L
            val chunk = withTimeoutOrNull(gap) { eventsNotifs.receive() } ?: break
            for (b in chunk) buffer.add(b)
            firstChunk = false
            if (expectedTotal == null && buffer.size >= 14) {
                expectedTotal = 14 +
                    ((buffer[10].toInt() and 0xFF)) +
                    ((buffer[11].toInt() and 0xFF) shl 8) +
                    ((buffer[12].toInt() and 0xFF) shl 16) +
                    ((buffer[13].toInt() and 0xFF) shl 24)
            }
            if (expectedTotal != null && buffer.size >= expectedTotal!!) break
        }
        if (buffer.isEmpty()) null else buffer.toByteArray()
    }

    /** Convenience wrapper: fetch the index and parse session metadata. */
    suspend fun listSleepSessions(): List<SleepSession> {
        val raw = readSleepIndex(queryType = 0x03, sessionId = 0) ?: return emptyList()
        return parseSleepSessions(raw)
    }

    /** Build a human-readable debug report describing this device — BLE name,
     *  manufacturer/model/serial/fw/hw, every service, char and descriptor
     *  with read values. Designed to be exported and pasted into bug reports
     *  for users with non-Pavlok-3 devices that we'd like to support.
     *
     *  When [censor] is true, redacts unique device identifiers (BLE name
     *  suffix, MAC, serial-number string). */
    @SuppressLint("MissingPermission")
    suspend fun generateDebugReport(
        deviceName: String?,
        appVersion: String,
        censor: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# LibreShock debug report")
        sb.appendLine("Generated: ${java.time.OffsetDateTime.now()}")
        sb.appendLine("App: LibreShock for Android ($appVersion)")
        if (censor) sb.appendLine("Censored: yes (BLE MAC, name suffix, serial redacted)")
        sb.appendLine()

        // Device-level metadata
        sb.appendLine("## Device")
        val g = gatt
        val addr = g?.device?.address
        val name = deviceName ?: g?.device?.name
        sb.appendLine("  ble_name: ${if (censor) redactName(name) else name}")
        sb.appendLine("  address:  ${if (censor) redactAddress(addr) else addr}")
        val info = try { readDeviceInfo(deviceName) } catch (_: Exception) { null }
        if (info != null) {
            fun emit(label: String, raw: String?) {
                if (raw == null) return
                val redacted = if (censor && (label == "serial" || label == "name"))
                    "X".repeat(raw.length)
                else raw
                sb.appendLine("  $label: $redacted")
            }
            emit("manufacturer", info.manufacturer)
            emit("model", info.model)
            emit("serial", info.serial)
            emit("hardware_revision", info.hardwareRevision)
            emit("firmware_revision", info.firmwareRevision)
            emit("timezone", info.timezone)
            emit("date", info.date)
            emit("time", info.time)
        }
        sb.appendLine()

        // Battery
        sb.appendLine("## Battery")
        val pct = try { readBattery() } catch (_: Exception) { null }
        sb.appendLine(if (pct != null) "  level: $pct%" else "  level: (read failed)")
        sb.appendLine()

        // Full GATT tree
        sb.appendLine("## GATT services")
        if (g == null) {
            sb.appendLine("(no GATT connection)")
        } else {
            for (service in g.services) {
                sb.appendLine("SERVICE ${service.uuid}")
                for (char in service.characteristics) {
                    val props = listOf(
                        "read" to BluetoothGattCharacteristic.PROPERTY_READ,
                        "write" to BluetoothGattCharacteristic.PROPERTY_WRITE,
                        "write-no-resp" to BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                        "notify" to BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        "indicate" to BluetoothGattCharacteristic.PROPERTY_INDICATE,
                    ).filter { (_, bit) -> (char.properties and bit) != 0 }
                        .joinToString(",") { it.first }
                    val canRead = (char.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                    var line = "  CHAR ${char.uuid}  handle=0x%04x  [$props]".format(char.instanceId)
                    if (canRead) {
                        val bytes = try { readChar(char.uuid) } catch (_: Exception) { null }
                        if (bytes != null) {
                            val redacted =
                                if (censor && char.uuid in REDACT_UUIDS)
                                    ByteArray(bytes.size) { 'X'.code.toByte() }
                                else bytes
                            val hex = redacted.joinToString("") { "%02x".format(it) }
                            val ascii = redacted.map {
                                val v = it.toInt() and 0xFF
                                if (v in 32..126) v.toChar() else '.'
                            }.joinToString("")
                            line += "  value=$hex ascii='$ascii'"
                        } else {
                            line += "  read_error"
                        }
                    }
                    sb.appendLine(line)
                    for (desc in char.descriptors) {
                        sb.appendLine("      DESC ${desc.uuid}")
                    }
                }
            }
        }
        sb.appendLine()
        sb.appendLine("# end of report")
        return sb.toString()
    }

    private fun redactName(name: String?): String {
        if (name == null) return "(unknown)"
        // Drop the last hyphen-segment (the unique device id). Handles both
        // "Pavlok-3-XXXX" -> "Pavlok-3-XXXX" and "Pav4-8cbf" -> "Pav4-XXXX".
        val parts = name.split("-")
        return if (parts.size >= 2) parts.dropLast(1).joinToString("-") + "-XXXX" else name
    }

    private fun redactAddress(addr: String?): String {
        if (addr == null) return "XX:XX:XX:XX:XX:XX"
        val parts = addr.split(":")
        return if (parts.size == 6)
            (parts.take(3) + listOf("XX", "XX", "XX")).joinToString(":")
        else "XX:XX:XX:XX:XX:XX"
    }


    /** Read the watch's current hand-raise configuration. Returns the raw
     *  4-byte payload (see [HandRaiseConfig.parse]) or null on read failure. */
    suspend fun readHandRaise(): ByteArray? = readChar(CHAR_HAND_RAISE)

    /** Write a new hand-raise configuration to the watch. */
    suspend fun setHandRaise(config: HandRaiseConfig): Boolean = gattMutex.withLock {
        writeChar(CHAR_HAND_RAISE, config.toBytes())
    }

    /** Enable or disable automatic sleep tracking on the watch.
     *
     *  Mirrors libreshock.py's set_sleep_tracking — writes `[0x02, on/off]` to the
     *  sleep-tracking characteristic. Vendor app uses a vendor descriptor at
     *  handle+1 but Pavlok firmware also accepts the same payload at the char
     *  value, which is what we use here for compatibility with both BLE stacks. */
    suspend fun setSleepTracking(enabled: Boolean): Boolean = gattMutex.withLock {
        writeChar(CHAR_SLEEP_TRACKING, byteArrayOf(0x02, if (enabled) 0x01 else 0x00))
    }

    /** Read the watch's battery level (0-100). Returns null if the read fails. */
    suspend fun readBattery(): Int? {
        val pct = readChar(CHAR_BATTERY)?.firstOrNull()?.toInt()?.and(0xFF)
        if (pct != null) _batteryUpdates.tryEmit(pct)
        return pct
    }

    /** Read all the standard DIS strings, timezone, and the BCD device clock.
     *  Mirrors libreshock.py's read_device_info. */
    suspend fun readDeviceInfo(deviceName: String?): DeviceInfo {
        val manufacturer = readAscii(CHAR_MANUFACTURER)
        val model = readAscii(CHAR_MODEL)
        val serial = readAscii(CHAR_SERIAL)
        val hardware = readAscii(CHAR_HARDWARE_REV)
        val firmware = readAscii(CHAR_FIRMWARE_REV)
        val timezoneRaw = readAscii(CHAR_SOFTWARE_REV)
        val timezone = timezoneRaw?.let { formatTimezone(it) }
        val timeRaw = readChar(CHAR_TIME)
        val time = timeRaw?.let { decodeDeviceTime(it) }
        return DeviceInfo(
            name = deviceName,
            manufacturer = manufacturer,
            model = model,
            serial = serial,
            hardwareRevision = hardware,
            firmwareRevision = firmware,
            timezone = timezone,
            date = time?.first,
            time = time?.second,
        )
    }

    private suspend fun readAscii(uuid: UUID): String? =
        readChar(uuid)?.toString(Charsets.UTF_8)?.trim(' ', ' ', '\t', '\n', '\r')

    @SuppressLint("MissingPermission")
    private suspend fun readChar(uuid: UUID): ByteArray? = gattMutex.withLock {
        val g = gatt
        if (g == null) {
            Log.w(TAG, "readChar($uuid): gatt is null"); return@withLock null
        }
        val char = findCharacteristic(g, uuid)
        if (char == null) {
            Log.w(TAG, "readChar($uuid): characteristic not found"); return@withLock null
        }
        val result = suspendCoroutine<ByteArray?> { cont ->
            readCont = cont
            val started = g.readCharacteristic(char)
            DebugLog.d(TAG, "readChar($uuid): readCharacteristic returned $started")
            if (!started) {
                readCont = null
                cont.resume(null)
            }
        }
        DebugLog.d(TAG, "readChar($uuid): got ${result?.size ?: -1} bytes")
        result
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

    /** Read the alarms currently stored on the device.
     *
     * Returns null on read failure (no response received), an empty list when
     * the device confirms it has no alarms, or the alarm list otherwise. The
     * null/empty distinction lets callers preserve a cached list across
     * transient read failures rather than silently wiping it.
     */
    suspend fun listAlarms(
        profile: ByteArray = "Single 1".toByteArray(Charsets.UTF_8),
    ): List<AlarmConfig>? = gattMutex.withLock {
        drain(ctrlNotifs)
        drain(dataNotifs)
        if (!writeChar(CHAR_CTRL, CtrlCommand.queryAlarms(profile))) return@withLock null

        // The device echoes the alarm packet back via CTRL notifications.
        // First chunk can take a moment if the device is busy; once data starts
        // flowing, gaps between chunks are short.
        val buffer = ArrayList<Byte>(512)
        var firstChunk = true
        while (true) {
            val timeout = if (firstChunk) 3000L else 500L
            val chunk = withTimeoutOrNull(timeout) { ctrlNotifs.receive() } ?: break
            for (b in chunk) buffer.add(b)
            firstChunk = false
        }
        if (buffer.isEmpty()) return@withLock null  // no response at all
        parseAlarms(buffer.toByteArray())  // may legitimately return empty list
    }

    /** Like [listAlarms] but returns the raw armed-state diagnostic per
     *  alarm, so the Validate UI can flag internal-consistency bugs
     *  (e.g. an alarm with TM-byte-3 bit 0x80 set but AO = 0 — the watch
     *  will fire it despite the parser/UI saying it's disabled). */
    suspend fun validateAlarms(
        profile: ByteArray = "Single 1".toByteArray(Charsets.UTF_8),
    ): List<AlarmDiagnostic>? = gattMutex.withLock {
        drain(ctrlNotifs)
        drain(dataNotifs)
        if (!writeChar(CHAR_CTRL, CtrlCommand.queryAlarms(profile))) return@withLock null
        val buffer = ArrayList<Byte>(512)
        var firstChunk = true
        while (true) {
            val timeout = if (firstChunk) 3000L else 500L
            val chunk = withTimeoutOrNull(timeout) { ctrlNotifs.receive() } ?: break
            for (b in chunk) buffer.add(b)
            firstChunk = false
        }
        if (buffer.isEmpty()) return@withLock null
        parseAlarmDiagnostics(buffer.toByteArray())
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
        for (uuid in listOf(CHAR_DATA, CHAR_CTRL, CHAR_NOTIFY, CHAR_BATTERY, CHAR_EVENTS)) {
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
                    DebugLog.d(TAG, "Disconnected (status=$status, intentional=$intentionalDisconnect)")
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
            DebugLog.d(TAG, "Services discovered: ok=$ok, ${g.services.size} services")
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
            DebugLog.d(TAG, "onCharacteristicRead(legacy) ${char.uuid} status=$status size=${value?.size ?: -1}")
            readCont?.resume(if (status == BluetoothGatt.GATT_SUCCESS) value else null); readCont = null
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            DebugLog.d(TAG, "onCharacteristicRead ${char.uuid} status=$status size=${value.size}")
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
            CHAR_EVENTS -> eventsNotifs.trySend(value)
            CHAR_NOTIFY -> parseNotifyEvent(value)?.let { _alarmEvents.tryEmit(it) }
            CHAR_BATTERY -> value.firstOrNull()?.let {
                _batteryUpdates.tryEmit(it.toInt() and 0xFF)
            }
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
    /** Connect succeeded at the BLE layer but the device doesn't expose the
     *  Pavlok-3 protocol services — likely a Pavlok-4 or similar generation
     *  using a totally different service UUID scheme. The watch is reachable
     *  but LibreShock has nothing to say to it. [model] is whatever the
     *  Device Info service reports, if anything. */
    data class Unsupported(val model: String?) : ConnectionState()
}

/** One of the 6 hardware-button slots (3 buttons x 2 press modes). */
enum class ButtonSlot(val id: Int, val displayName: String) {
    TOP_SHORT(1, "Top short press"),
    MID_SHORT(2, "Middle short press"),
    LOWER_SHORT(3, "Lower short press"),
    TOP_LONG(4, "Top long press"),
    MID_LONG(5, "Middle long press"),
    LOWER_LONG(6, "Lower long press"),
}

/** Action that can be bound to a button slot. */
enum class ButtonAction(val displayName: String) {
    VIBRATE("Vibrate"),
    BEEP("Beep"),
    ZAP("Zap"),
    STOPWATCH("Stopwatch on/off"),
    TIMER("Timer on/off"),
    SLEEP_TRACKING("Sleep tracking on/off"),
    DISABLED("Disabled");

    val isStim: Boolean
        get() = this == VIBRATE || this == BEEP || this == ZAP
}

/** A single button slot's binding. */
data class ButtonBinding(
    val action: ButtonAction = ButtonAction.DISABLED,
    val count: Int = 1,        // 1-15, vibrate/beep/zap only
    val intensity: Int = 50,   // 0-100, vibrate/beep/zap only
) {
    /** Render the binding into the 4+ byte payload for char 7001, prefixed by the slot id. */
    fun toPayload(slot: ButtonSlot): ByteArray {
        val header = byteArrayOf(0x02, slot.id.toByte())
        val countByte = (0x40 or count.coerceIn(1, 15)).toByte()
        val i = intensity.coerceIn(0, 100).toByte()
        val params: ByteArray = when (action) {
            ButtonAction.VIBRATE -> byteArrayOf(0x01, countByte, 0x0c, i, 0x16, 0x16)
            ButtonAction.BEEP    -> byteArrayOf(0x02, countByte, 0x0c, i, 0x16, 0x16)
            ButtonAction.ZAP     -> byteArrayOf(0x03, countByte, i)
            ButtonAction.STOPWATCH -> byteArrayOf(0x11, 0x02, 0x10, 0x01)
            ButtonAction.TIMER     -> byteArrayOf(0x11, 0x02, 0x10, 0x02)
            ButtonAction.SLEEP_TRACKING -> byteArrayOf(0x13, 0x01, 0x02)
            ButtonAction.DISABLED -> byteArrayOf(0xff.toByte())
        }
        return header + params
    }

    fun summary(): String = when (action) {
        ButtonAction.VIBRATE -> "Vibrate · ${count}x @ $intensity%"
        ButtonAction.BEEP -> "Beep · ${count}x @ $intensity%"
        ButtonAction.ZAP -> "Zap · ${count}x @ $intensity%"
        else -> action.displayName
    }
}

/** Hand-raise detection settings. Mirrors libreshock.py's set_hand_raise. */
enum class HandRaiseStim(val byte: Int, val displayName: String) {
    VIBRATE(0x00, "Vibrate"),
    BEEP(0x01, "Beep"),
    ZAP(0x02, "Zap"),
    COUNTDOWN(0x03, "Countdown");

    companion object {
        fun fromByte(b: Int): HandRaiseStim =
            entries.firstOrNull { it.byte == b } ?: VIBRATE
    }
}

data class HandRaiseConfig(
    val enabled: Boolean = false,
    val leftHand: Boolean = false,    // false = right
    val insideWrist: Boolean = false, // false = outside
    val stimulus: HandRaiseStim = HandRaiseStim.VIBRATE,
    val intensity: Int = 30,          // 0-100; only meaningful for ZAP
) {
    fun toBytes(): ByteArray {
        var flags = 0x06  // bits 1+2 always set per observed protocol
        if (enabled) flags = flags or 0x01
        if (insideWrist) flags = flags or 0x08
        if (leftHand) flags = flags or 0x10
        return byteArrayOf(
            flags.toByte(),
            0x70.toByte(),
            stimulus.byte.toByte(),
            intensity.coerceIn(0, 100).toByte(),
        )
    }

    companion object {
        fun parse(bytes: ByteArray?): HandRaiseConfig {
            if (bytes == null || bytes.size < 4) return HandRaiseConfig()
            val flags = bytes[0].toInt() and 0xFF
            return HandRaiseConfig(
                enabled = (flags and 0x01) != 0,
                insideWrist = (flags and 0x08) != 0,
                leftHand = (flags and 0x10) != 0,
                stimulus = HandRaiseStim.fromByte(bytes[2].toInt() and 0xFF),
                intensity = bytes[3].toInt() and 0xFF,
            )
        }
    }
}

/** Snapshot of everything ShockDevice.readDeviceInfo() returns. */
data class DeviceInfo(
    val name: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val serial: String? = null,
    val hardwareRevision: String? = null,
    val firmwareRevision: String? = null,
    val timezone: String? = null,
    val date: String? = null,
    val time: String? = null,
)

private val MONTH_NAMES = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private fun bcd(value: Byte): Int = ((value.toInt() and 0xF0) shr 4) * 10 + (value.toInt() and 0x0F)

/** Decode the 8-byte BCD device clock to (date, time) strings, mirroring libreshock.py. */
internal fun decodeDeviceTime(buf: ByteArray): Pair<String, String>? {
    if (buf.size < 8) return null
    val sec = bcd(buf[0])
    val minute = bcd(buf[1])
    val hour = bcd(buf[2])
    val day = bcd(buf[3])
    val month = bcd(buf[5])
    val year = 2000 + bcd(buf[6])
    val monthName = MONTH_NAMES.getOrNull(month - 1) ?: "M$month"
    return "$monthName $day $year" to "%02d:%02d:%02d".format(hour, minute, sec)
}

/** "+100" → "UTC +1:00", "-0530" → "UTC -5:30". */
internal fun formatTimezone(raw: String): String {
    if (raw.isEmpty()) return raw
    val sign = if (raw.startsWith("-")) "-" else "+"
    val digits = raw.trimStart('+', '-')
    if (!digits.all { it.isDigit() }) return raw
    val padded = digits.padStart(3, '0')
    val minutes = padded.takeLast(2)
    val hours = padded.dropLast(2).trimStart('0').ifEmpty { "0" }
    return "UTC $sign$hours:$minutes"
}

private val SUCCESS_RESP_A = byteArrayOf(0, 0, 0, 0)
private val SUCCESS_RESP_B = byteArrayOf(4, 0, 0, 0)

/** Char UUIDs whose values uniquely identify the device — redacted in censored
 *  debug reports. GAP Device Name (0x2A00) + Serial Number String (0x2A25). */
private val REDACT_UUIDS = setOf(
    UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"),
)

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
