# LibreShock - BLE Shock Device Control

## Project Goal
Control BLE-based shock/vibration wearables without vendor apps.

## Status: WORKING

All core actions (vibrate, beep, zap) have been successfully reverse-engineered and tested.

## Project rule: Python first, full feature parity

New protocol features are implemented in `libreshock.py` **first**, then ported
to the Android app under `android/`. Both must stay at feature parity so users
can pick either tool with full functionality — some prefer scripting, some
prefer the GUI. If something exists in one, it must exist in the other.

---

## Target Device
- **BLE Name Pattern**: anything starting with `Pavlok` (e.g., `Pavlok-3-XXXX`)
  - Today's format is `Pavlok-<model>-<id>` where the number is the hardware
    generation. Protocol was reverse-engineered against a Pavlok-3. Earlier
    and later models likely share the same protocol but are untested. We
    match the `Pavlok` prefix so future name formats still pick up.
- **Manufacturer**: Behavioral Technology Group, Inc.
- **Firmware (tested)**: 6.10.0
- **Hardware (tested)**: 6.0.0

## Important Notes
- **Avoid vendor brand names in code** - use generic terms like "ShockDevice", "device", etc.
- Device must be **paired in Windows Bluetooth** before scripts can connect
- Device goes to sleep quickly - wake it before connecting

---

## BLE Protocol (Reverse Engineered - COMPLETE)

### Key Discovery
The **trigger mechanism** is the `0x80` bit in the first byte:
- `0x01` = enabled (config only, no action)
- `0x81` = enabled + trigger (`0x80 | 0x01`) - **EXECUTES THE ACTION**

### Service UUID
`156e1000-a300-4fea-897b-86f698d74461` - Action Settings

### Action Commands

| Action | Characteristic UUID | Command Format | Example |
|--------|---------------------|----------------|---------|
| **Vibrate** | `00001001-0000-1000-8000-00805f9b34fb` | `[0x81, count, intensity, on_time, off_time]` | `[0x81, 3, 100, 22, 22]` |
| **Beep** | `00001002-0000-1000-8000-00805f9b34fb` | `[0x81, count, intensity, on_time, off_time]` | `[0x81, 3, 80, 22, 22]` |
| **Zap** | `00001003-0000-1000-8000-00805f9b34fb` | `[0x81, intensity]` | `[0x81, 70]` |
| **LED** | `00001004-0000-1000-8000-00805f9b34fb` | `[0x81, ?, intensity, ?, ?]` | TBD |

### Parameter Ranges
- **intensity**: 0-100 (percent)
- **count**: 1-255 (number of pulses)
- **on_time**: duration of action (default 22)
- **off_time**: pause between pulses (default 22)

### Reading Config (without triggering)
Reading characteristics returns current settings with `0x01` prefix (enabled, not triggered):
```
vibe: [1, 12, 100, 22, 22]  # enabled, count=12, intensity=100, timing
beep: [1, 12, 75, 22, 22]   # enabled, count=12, intensity=75, timing
zap:  [1, 70]               # enabled, intensity=70
```

---

## Usage

### CLI
```bash
# Install
pip install bleak

# Show help
python libreshock.py help

# Instant Actions
python libreshock.py vibe -i 100 -c 3    # Vibrate 100% intensity, 3 pulses
python libreshock.py beep -i 80 -c 2     # Beep 80% intensity, 2 beeps
python libreshock.py zap -i 50           # Zap 50% intensity
python libreshock.py status              # Read device config

# Alarm Management
python libreshock.py alarm --list        # List all alarms with full details

# Set alarm at 7:30 AM, weekdays only
python libreshock.py alarm -t 7:30 --days weekdays

# Full alarm config: 8AM daily, vibe+zap enabled, beep disabled
python libreshock.py alarm -t 8:00 --name "Wake up" \
    --days daily --vibe 80 --beep off --zap 50 --interval 15

# Add alarm to existing ones
python libreshock.py alarm --add 9:00 --days weekends
```

### CLI Options Reference

| Option | Description | Default |
|--------|-------------|---------|
| `-a, --address` | BLE device address | Auto-detect |
| `-i, --intensity` | Action intensity 0-100 | 50 |
| `-c, --count` | Pulse count for vibe/beep | 1 |

#### Alarm Options
| Option | Description | Default |
|--------|-------------|---------|
| `-t, --time HH:MM` | Alarm time (can repeat for multiple) | - |
| `--add HH:MM` | Add to existing alarms | - |
| `-l, --list` | List current alarms | - |
| `--clear` | Clear all alarms | - |
| `-n, --name` | Alarm name | "alarm" |
| `-d, --days` | Repeat: daily, weekdays, weekends, or mon,tue,wed | daily |
| `--vibe 0-100\|off` | Vibration intensity or disable | 50 |
| `--beep 0-100\|off` | Beep volume or disable | 50 |
| `--zap 0-100\|off` | Zap intensity or disable | 50 |
| `--vibe-count` | Number of vibrations | 5 |
| `--beep-count` | Number of beeps | 5 |
| `--interval` | Seconds between stimuli | 15 |
| `--snooze/--no-snooze` | Enable/disable snooze | enabled |

### Python API
```python
from libreshock import ShockDevice, AlarmConfig, AlarmAction, Weekday

async def main():
    device = ShockDevice()
    await device.connect()

    # Instant actions
    await device.vibrate(intensity=100, count=3)
    await device.beep(intensity=80, count=2)
    await device.zap(intensity=50)

    # List alarms (returns List[AlarmConfig])
    alarms = await device.list_alarms()
    for alarm in alarms:
        print(f"{alarm.hour}:{alarm.minute} - {alarm.name}")
        print(f"  Days: {alarm.weekday_names()}")
        print(f"  Vibe: {alarm.vibration.enabled}, {alarm.vibration.intensity}%")

    # Set alarm with full config
    alarm = AlarmConfig(
        hour=7, minute=30,
        name="Morning",
        weekdays=Weekday.WEEKDAYS,  # Mon-Fri
        snooze=True,
        stimulus_interval=15,
        vibration=AlarmAction(enabled=True, count=5, intensity=80),
        beep=AlarmAction(enabled=False, count=5, intensity=50),
        zap=AlarmAction(enabled=True, count=1, intensity=50)
    )
    await device.set_alarms([alarm])

    # Simple alarm (uses defaults)
    await device.set_alarm(8, 0, name="Backup", weekdays=Weekday.EVERYDAY)

    await device.disconnect()
```

---

## Alarm Protocol (Reverse Engineered)

### Service 156e5000 - Alarm Control

| Characteristic | UUID | Purpose |
|----------------|------|---------|
| CTRL (5001) | `00005001-...` | Control commands |
| DATA (5002) | `00005002-...` | Alarm data packets |

### Commands
- `[0x01, 0x00] + profile` - Enter write mode
- `[0x00, 0x00] + profile` - Exit write mode
- `[0x06, 0x00] + profile` - Query alarms
- `[0x02, 0x00]` (no profile) - **Stop a currently-firing alarm**
- `[0x03, 0x01]` (no profile) - **Snooze a currently-firing alarm**

### Alarm-fire / Stop / Snooze notifications (on char 5003)
- `[0x54, 0x00, alarm_id, 0x00]` - Watch reports alarm `alarm_id` is firing
- `[0x55, 0x00, alarm_id, 0x00]` - Watch confirms snooze handled for `alarm_id`
- `[0x56, 0x00, alarm_id, 0x00]` - Watch confirms stop handled for `alarm_id`
- `[0x56, 0x00, 0x00, 0x00]` - Generic "write OK" response (alarm_id 0)

### Alarm Packet Structure

```
AH [length:2] [crc:2] AP [profile_len:2] [profile] (HA [length:2] [tags...])*
```

The outer wrapper is `AH + length(2) + crc(2)`, followed by an `AP` profile
TLV, followed by zero or more alarm TLV blocks. Each alarm block is
`HA + length(2 LE) + content`. The third byte of the alarm header therefore
varies with content size and frequently lands on a printable ASCII character
('9', '6', 'F', 'C', 'P', etc.) — those letters are NOT a tag, just the low
byte of the length field.

MH (vibration), PH (beep), and ZH (zap) containers are independently included
only when the corresponding stim is enabled. The vendor app does NOT emit a
disabled-stim block. Verified byte-exact against captured packets for all 7
non-empty stim combinations (May 17 2026).

### Alarm Block Tags (TLV Format: Tag + Length:2 LE + Data)

Tags appear in this order: AN, TM, WD, WI, SN, AO, then any of MH/PH/ZH
(only for enabled stims), then ID.

| Tag | Length | Data | Description |
|-----|--------|------|-------------|
| `AN` | var | UTF-8 string | Alarm name |
| `TM` | 4 | `[0x00, minute_bcd, hour_bcd, 0x80\|day_mask]` | Time (BCD) and repeat-day mask. flag=0x00 for stored templates that aren't actively armed. |
| `WD` | 1 | `0x1E` | Constant in all observed vendor packets; not the day mask |
| `WI` | 2 | uint16_le | Stimulus interval (seconds) |
| `SN` | 1 | 0/1 | Snooze enabled |
| `AO` | 1 | 0/1 | Alarm on/enabled |
| `MH` | 9 | contains MC | Vibration Habit (omitted when vibe disabled) |
| `MC` | 5 | `[flags, count, intensity, 0xfa, 0xfa]` | Vibration config |
| `PH` | 9 | contains PC | Beep Habit (omitted when beep disabled) |
| `PC` | 5 | `[flags, count, intensity, 0xfa, 0xfa]` | Beep config |
| `ZH` | 6 | contains ZC | Zap Habit (omitted when zap disabled) |
| `ZC` | 2 | `[flags, intensity]` | Zap config |
| `ID` | 2 | uint16_le | Alarm slot ID |

### Stim Combination → HA length byte (observed)

| Combo | Content len | Header bytes | Hex appearance |
|-------|------------|--------------|----------------|
| vibe only | 57 | `48 41 39 00` | "HA9" |
| beep only | 57 | `48 41 39 00` | "HA9" |
| zap only | 54 | `48 41 36 00` | "HA6" |
| vibe + beep | 70 | `48 41 46 00` | "HAF" |
| vibe + zap | 67 | `48 41 43 00` | "HAC" |
| beep + zap | 67 | `48 41 43 00` | "HAC" |
| vibe + beep + zap | 80 | `48 41 50 00` | "HAP" |

### MC / PC Flags (identical layout)
- `0x80` - Always set (enabled/active)
- `0x01` - Action enabled
- `0x04` - Secondary flag (always set with 0x01)
- Observed value when enabled: `0x85`. The `count` byte inside MC/PC is always
  `0x0c` regardless of user-facing pulse count (internal timing constant).

### ZC Flags (Zap)
- `0x80` - Always set
- Low nibble (`0x0F`) - Zap pulse count (1-15)
- Observed: `0x83` (3 zaps), `0x81` (1 zap)

All 7 non-empty stim combinations have been captured and verified byte-exact
against libreshock's output (May 17 2026 capture session, scripts/verify_combos.py).

### ZC Flags (Zap)
- `0x80` - Always set (enabled/active)
- `0x01` - Zap enabled

### Weekday Bitmask (TM byte 3, low 7 bits)

The day mask lives in the low 7 bits of TM byte 3. The high bit (0x80) marks
the alarm as armed; clear it to disable. Verified May 17 2026 by capturing
Wednesday-only (0x88), Monday-only (0x82), and Saturday-only (0xC0) alarms.

| Bit | Day |
|-----|-----|
| 0x01 | Sunday |
| 0x02 | Monday |
| 0x04 | Tuesday |
| 0x08 | Wednesday |
| 0x10 | Thursday |
| 0x20 | Friday |
| 0x40 | Saturday |
| 0x7F | Every day (all bits) |
| 0x3E | Weekdays (Mon-Fri) |
| 0x41 | Weekends (Sat-Sun) |

For an armed alarm, TM byte 3 = `0x80 | mask`. For a one-time alarm with no
repeat, TM byte 3 = `0x80` (no day bits set).

---

## Other Services (Reference)

### Service 156e1000 - Action Settings (extended)
The action service has more characteristics than the four used for instant
actions. Verified via service enumeration (`scripts/dump_services.py`):

| Char | Properties | Notes |
|------|------------|-------|
| 1001 | write, read | Vibrate (see Action Commands) |
| 1002 | write, read | Beep |
| 1003 | write, read, notify | Zap |
| 1004 | write, read | LED |
| 1005 | write, read | **Device clock** — 8-byte BCD: `[ss, mm, HH, DD, 00, MM, YY, dow]` |
| 1006 | write, read | Unknown (4 bytes) |
| 1007 | write, read | Unknown (4 bytes; alarm counter?) |
| 1008 | write, read | Unknown (8 bytes) |

### Service 156e0000 - Device State
| Char | Properties | Notes |
|------|------------|-------|
| 0007 | write, notify | Unknown |
| 0008 | write, notify | Unknown |

### Service 156e2000 - Events/Timers
| Char | Properties | Notes |
|------|------------|-------|
| 2001 | read, notify | Time |
| 2002 | write, notify | Events |
| 2003 | read, write, notify | Timers |

### Service 156e5000 - Action Control
| Char | Properties | Notes |
|------|------------|-------|
| 5001 | read, write, notify | Mode setting ("Single 1") |
| 5002 | read, write, notify | Unknown |
| 5003 | read, notify | Unknown |

### Standard Services

**Battery Service** (`0000180f-0000-1000-8000-00805f9b34fb`)
- Char `0x2A19` Battery Level: uint8 0-100. Read + notify (level updates).

**Device Information Service** (`0000180a-0000-1000-8000-00805f9b34fb`)
- Char `0x2A29` Manufacturer Name: e.g. "Behavioral Technology Group, Inc."
- Char `0x2A24` Model Number: e.g. "Pavlok-S 03" (truncated read returns "Pavlok-S")
- Char `0x2A25` Serial Number: 12 hex chars matching the BLE MAC tail, e.g. "107C6224F11F"
- Char `0x2A26` Firmware Revision: e.g. "6.10.0"
- Char `0x2A27` Hardware Revision: e.g. "6.0.0"

All ASCII strings. Read once on connect; values don't change at runtime.

### Custom date/time and timezone (decode TBD)
The vendor app reads two more characteristics on the Device Info screen
that we haven't fully decoded:
- Timezone offset: 4-byte ASCII like `"+100"` (= UTC+1:00). Vendor handle 0x0079 (UUID TBD).
- Date/time on device: 8-byte binary blob like `13 50 12 17 00 05 26 04`. Pattern suggests `min sec hour day ?? month year-2000 ??` but offset/order not verified.

---

## Development

### Python (libreshock.py)
```bash
pip install bleak
```

### Files
| File | Purpose |
|------|---------|
| `libreshock.py` | Reference controller library + CLI (bleak-based) |
| `parse_btsnoop.py` | btsnoop_hci.log parser for reverse engineering |
| `scripts/extract_alarm_writes.py` | Decode alarm transactions from a btsnoop |
| `scripts/verify_combos.py` | Byte-exact verification of protocol against captured packets |
| `scripts/timeline.py` | Chronological dump of writes/notifications around an event |
| `android/` | Kotlin Android app (see below) |
| `CLAUDE.md` | Protocol documentation |

### Android app (android/)

Kotlin + Jetpack Compose app. Package `uk.hairyfred.libreshock`, min SDK 26.

```
android/app/src/main/java/uk/hairyfred/libreshock/
├── MainActivity.kt           # Compose UI, screen navigation
├── ble/
│   ├── AlarmProtocol.kt      # Pure-Kotlin protocol port (mirrors libreshock.py)
│   └── ShockDevice.kt        # BLE wrapper over BluetoothGatt + coroutines
└── ui/theme/                 # Default Compose theme
app/src/test/java/.../ble/AlarmProtocolTest.kt   # Byte-exact tests vs captures
```

**Current features:**
- Scan for `Pavlok-3-*` devices, connect, instant actions (vibe/beep/zap with intensity sliders)
- Auto-scan on app launch (toggleable in Settings)
- Auto-reconnect to last-used device on launch (toggleable; respects manual-disconnect)
- Settings screen: auto-scan toggle, auto-reconnect toggle, "Forget device" button
- Alarm CRUD: list device alarms, add/edit/delete with time, repeat days, per-stim toggles + intensity, zap count, snooze, stimulus interval
- Time entry uses Material 3 TimePicker (clock dial) in a dialog opened by tapping the time card
- Fire/stop/snooze: when the watch fires an alarm (0x54 notification on char 5003), an in-app dialog pops up with Stop and Snooze buttons. Dialog auto-dismisses if the user stops/snoozes on the watch directly (0x55/0x56 confirmation). In-app only — won't show if the app isn't running.
- System back / swipe-back navigates back through screens (alarm-edit → alarms → main)

**Build/install:**
```
cd android
./gradlew :app:installDebug    # builds APK and pushes to connected adb device
./gradlew :app:testDebugUnitTest --tests "uk.hairyfred.libreshock.ble.AlarmProtocolTest"
```

The unit tests verify the Kotlin protocol output byte-matches captured vendor-app packets — same validation as `scripts/verify_combos.py` for the Python implementation.

---

## Resources
- bleak library: https://github.com/hbldh/bleak
- BLE GATT specs: https://www.bluetooth.com/specifications/gatt/
