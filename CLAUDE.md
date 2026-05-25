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

## Project rule: keep README.md and docs/CLI.md current

When a feature is added, removed, or changed in a user-facing way, update
`README.md` and `docs/CLI.md` in the same commit. README.md stays short — a
quick-start with `# comments` explaining each example plus a link to detailed
docs. `docs/CLI.md` is the authoritative per-command reference for the Python
CLI; add/remove rows in the commands table and example blocks as the surface
changes. Don't let docs and code drift.

---

## Target Device
- **BLE Name Pattern**: two vendor naming schemes seen in the wild —
  `Pavlok-<model>-<id>` (e.g. `Pavlok-3-XXXX`, the Pavlok 3) and a shorter
  `Pav<model>-<id>` (e.g. `Pav4-8cbf`, the Pavlok 4). Both start with `Pav`
  followed by either `lok` or the model digit. We match that shape (see
  `is_device_name` in libreshock.py / `ShockDevice.isDeviceName` in Kotlin)
  so new models pick up automatically without matching unrelated devices
  that merely contain `Pav`. Protocol was reverse-engineered against a
  Pavlok-3; other models likely share it but are untested.
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
| `SN` | 1 | bitfield | Snooze flags. bit 0 (`0x01`) = snooze enabled. bit 1 (`0x02`) = "Snooze Zap" (zap when the user snoozes). |
| `AO` | 1 | bitfield | Alarm on/enabled + guarantor task + additional wake-up features (see below) |
| `JL` | 1 | 1-20 | Jumping-Jacks rep count (present only when AO has the JJ bit set) |
| `ES` | 1 | `0x05` (vendor const) | Escalating Alarm config. Vendor app exposes no slider — always emits the same byte. Present only when AO bit `0x20` is set. |
| `SM` | 3 | `0f 05 06` (vendor const) | Smart Alarm config. Vendor app exposes no sliders — always emits the same 3 bytes (likely `[expiry?, recheck_min, ?]` matching "5 min recheck / 30 min total" defaults). Present only when AO bit `0x40` is set. |
| `MH` | 9 | contains MC | Vibration Habit (omitted when vibe disabled) |
| `MC` | 5 | `[flags, count, intensity, 0xfa, 0xfa]` | Vibration config |
| `PH` | 9 | contains PC | Beep Habit (omitted when beep disabled) |
| `PC` | 5 | `[flags, count, intensity, 0xfa, 0xfa]` | Beep config |
| `ZH` | 6 | contains ZC | Zap Habit (omitted when zap disabled) |
| `ZC` | 2 | `[flags, intensity]` | Zap config |
| `ID` | 2 | uint16_le | Alarm slot ID |

### AO byte — armed + guarantor task

The vendor app's "Wake up on time" screen offers one of three guarantor
tasks. Each gets its own bit in the AO byte:

The AO byte is a bitfield. Bits 0-2 and bit 7 are mutually-exclusive
guarantor tasks (only one set at a time); bits 3, 5, 6 are independent
"additional wake-up feature" flags that can be combined freely.

| AO bit | Meaning |
|--------|---------|
| `0x00` | Alarm disabled (whole byte = 0) |
| `0x01` | Armed, no guarantor task |
| `0x02` | Armed + **Jumping Jacks** guarantor (also adds `JL` TLV, rep count 1-20) |
| `0x04` | Armed + **QR code scan** guarantor (no extra TLV) |
| `0x08` | **Light Sleep** — watch monitors actigraphy and may fire up to 20 min early during light sleep |
| `0x10` | unobserved (no vendor toggle maps to this bit) |
| `0x20` | **Escalating Alarm** — stim intensity ramps up over time; also adds `ES` TLV |
| `0x40` | **Smart Alarm** — re-arms after dismiss if no motion detected; also adds `SM` TLV |
| `0x80` | Armed + **Puzzle unlock** guarantor (no extra TLV) |

Guarantor tasks are presented as a radio choice in the UI; the additional
feature bits are separate toggles. Decoded May 19 2026 from:
- 3 jumping-jacks captures (1/2/3 reps), 1 QR, 1 puzzle (guarantors)
- 1 isolated capture each for Snooze Zap, Disable Snooze, Light Sleep,
  Escalating Alarm, Smart Alarm (additional features)

All verified byte-exact in `scripts/verify_combos.py` and `AlarmProtocolTest.kt`.

The "Disable Snooze" toggle in the vendor "Additional wake-up features"
screen is **equivalent to clearing SN bit 0** — same wire effect as
flipping the regular alarm-edit Snooze switch off. Two UI paths to the
same byte, not two separate features.

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

Guarantor tasks add 5 bytes to any of the above when JJ is selected (the
JL TLV); QR and Puzzle add nothing. E.g. vibe+zap + JJ = HAC + 5 = HAH (72).

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
repeat, TM byte 3 = `0x80` (no day bits set). **For a disabled alarm clear
bit 0x80** — the watch fires based on this bit, not the AO byte, so leaving
0x80 set makes the alarm trigger even when AO = 0. (Bug fixed v0.1.11; both
encoders now emit TM byte 3 = `day_mask` with bit 7 cleared when
`config.enabled` is false.)

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

### Service 156e7000 - Buttons / misc
| Char | Properties | Notes |
|------|------------|-------|
| 7001 | read, write, notify | **Hardware button rebinding** (see below) |
| 7999 | read, write | Unknown (8-byte struct) |

#### Button rebinding (char 7001)

Each of the watch's three physical buttons has 2 press modes (short + long)
— 6 slots total. Each slot can be bound to a stimulus or a built-in app
toggle. Payload sent to char 7001: `[0x02, slot, action_class, ...params]`.

| Slot ID | Slot |
|---------|------|
| `0x01` | top short |
| `0x02` | middle short |
| `0x03` | lower short |
| `0x04` | top long |
| `0x05` | middle long |
| `0x06` | lower long |

| Action class | Action | Params |
|--------------|--------|--------|
| `0x01` | Vibrate | `[0x40\|count, 0x0c, intensity, 0x16, 0x16]` |
| `0x02` | Beep | `[0x40\|count, 0x0c, intensity, 0x16, 0x16]` |
| `0x03` | Zap | `[0x40\|count, intensity]` |
| `0x11` | App toggle | `[0x02, 0x10, app_id]` — app_id 1=stopwatch, 2=timer |
| `0x13` | Sleep tracking toggle | `[0x01, 0x02]` |
| `0xff` | Disabled | (no params) |

Where `count` is 1-15 packed into the low nibble of the byte OR'd with `0x40`.

### Timer & Stopwatch (same char 7001, opcode 0x22)

The watch's "Timer & Stopwatch" screen reuses the button-config
characteristic with a different opcode. Decoded May 19 2026.

Packet:

```
22 <body_len:u16-LE> <body> 00
```

Body:

```
<mode:1> f5 02 01 <duration:1> f0 <indicator:1> <intervals_data>
```

| Field | Bytes | Notes |
|-------|-------|-------|
| mode | 1 | `0x12` = Timer, `0x13` = Stopwatch |
| sub-mode | 3 | constant `f5 02 01` |
| duration | 1 | Timer countdown in seconds (u8 max 255). Stopwatch always 0. |
| sentinel | 1 | `f0` |
| indicator | 1 | `1 + len(intervals_data)` — offset from itself to byte after last interval |
| intervals_data | var | 3-byte intervals joined with `0x00` separator |

Each interval is 3 bytes: `<stim:1> <intensity:1> <every_seconds:u8>`.
Stim classes match button rebinding (`0x01` vibe, `0x02` beep, `0x03` zap).

**Intensity scale (different from alarms!)**: timer/stopwatch intensity
is mapped onto the watch's internal 0x21-0x34 range, presumably as a
safety cap since intervals can fire every second.

```
byte = 0x21 + (percent * 19) // 100   (integer truncation, not round)
percent = ceil((byte - 0x21) * 100 / 19)
```

So 0% → 0x21 (33), 50% → 0x2a (42), 75% → 0x2f (47), 100% → 0x34 (52).
Anything outside 0x21-0x34 is rejected by the watch.

The vendor app constrains the picker so that no two stim intervals can
share the same `every_seconds` value — you can have a Zap-every-5s and
a Beep-every-10s together, but not Zap-every-5s + Beep-every-5s.

Verified byte-exact in `scripts/verify_combos.py` and
`TimerStopwatchTest.kt` against 5 isolated captures (0%/100% intensity
extremes, vibe/beep/zap stim classes, both modes).

### Hand-raise detection (service 156e1000, char 1006)

The watch can fire a stimulus when it detects you raising your hand. 4-byte
payload to char 1006: `[flags, 0x70, stim_type, intensity]`.

Flags byte:
- bit 0 (`0x01`): enabled
- bits 1, 2 (`0x06`): always set (sentinel)
- bit 3 (`0x08`): wrist position — `0`=outside, `1`=inside
- bit 4 (`0x10`): hand — `0`=right, `1`=left

Stim type:
- `0x00` = Vibrate
- `0x01` = Beep
- `0x02` = Zap
- `0x03` = Countdown (watch counts down via haptic cues then zaps; lowering
  the hand cancels)

Intensity is 0-100; only Zap exposes a slider in the vendor app — the others
use a fixed `0x1e` (30).

### Sleep tracking history (service 156e2000, char 2002)

The watch stores past sleep sessions in flash. The phone fetches them via
a request/response protocol on the "events" characteristic (handle ~0x004A,
UUID `00002002-0000-1000-8000-00805f9b34fb`).

Two query types observed:
- `0x03` — sleep-stage records (~3-4KB per session). Watch's own classifier output.
- `0x82` — general event log (alarm sets, BLE traffic, etc.). **Not** actigraphy.

Request format (write 9 bytes to char 2002, twice for start+end):

```
<query_type:1> <session_id:u24-LE> 00 <range:u32-LE>
range = 00000000 marks fetch start; range = ffffffff marks fetch end.
session_id = 0 returns ALL records of that type concatenated.
```

Response (delivered as notifications on the same char):

```
14-byte header: <op:1> 00 <id_echo:5> <count:u32-LE> <byte_count:u32-LE>
Then byte_count bytes of body.
```

For the "all sessions" body, each per-session record begins with:

```
<sub_op:1> 03 <body_len:u16-LE> <session_id:u32-LE> <timestamp:u32-LE>
sub_op = 0x3f for the first session, 0x7f for subsequent ones.
timestamp = Unix epoch seconds UTC, monotonic across sessions.
```

Each session covers about a week of sleep tracking (a single session can
contain multiple nights). The watch uses **two different per-night formats**
inside the body — both yield clean Awake / Sleep / Deep totals, but **not**
a real Light vs REM split:

1. **Older "summary" format** (sessions covering past nights): top-level
   `0x21 <len:1> 00 00 <bedtime:u32-LE> <activity-bytes>` wrappers, one
   per tracked night. Each activity byte represents one **5-minute window**'s
   motion intensity (0..255). Length of the activity-byte array = night's
   tracked duration ÷ 5 min. Phone-side thresholds yield 3-stage:
   `< 2 = Deep`, `≥ 60 = Awake`, otherwise Sleep.

2. **Current-night "rich" format** (the actively recording session, with
   the largest sid): recursive `0x10 0x03 <dur:u16-LE> <stage:1>` segments
   with stage codes:
   - `0x11` → Sleep (combined Light + REM)
   - `0x13` → rare transient (treated as Sleep)
   - `0x21` → Deep
   - `0x41` → Awake
   - `0x51` → rare transient (treated as Awake)

The watch does NOT separately store Light vs REM. The vendor app applies
a **proprietary post-classifier** to split Sleep into Light + REM —
re-implementing that exactly is not feasible without firmware access.
LibreShock can OPTIONALLY apply its own approximation (lowest-activity
40% of Sleep band → REM; for segment-based data, last 40% of Sleep time
chronologically → REM). Results are flagged `approximate_split=True` so
the UI / CLI shows them with a `≈` qualifier.

Verified May 20 2026 against the user's vendor-app screenshots for two nights:
- May 14 2026: 7h50m total (Awake 15 / REM 165 / Light 165 / Deep 135 min)
- May 15 2026: 7h06m total (Awake 30 / REM 105 / Light 255 / Deep 45 min)

3-stage totals match approximately (within ~25% per stage on the worse
night); 4-stage with the LibreShock approximation is close but not
byte-exact to vendor-app output. The setting is OFF by default.

### Sleep tracking (service 156e0000, char 0008)

The watch can stream actigraphy samples (accelerometer-derived motion data)
on the events characteristic, which the vendor app post-processes into
Awake/REM/Light/Deep stages. The toggle is a 2-byte write `[0x02, 0x01]`
(enable) or `[0x02, 0x00]` (disable) sent to the char value at handle
0x008B. The vendor app actually writes to a vendor descriptor at handle+1
(value `[0x02, 0x01]`); the watch accepts both. Time-range scheduling
("track only between midnight and 5 AM") is entirely phone-side.

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
- Fire/stop/snooze: when the watch fires an alarm (0x54 notification on char 5003), an in-app dialog pops up with Stop and Snooze buttons. Dialog auto-dismisses if the user stops/snoozes on the watch directly (0x55/0x56 confirmation). In addition to the in-app dialog, a system notification with Stop / Snooze actions is posted via [AlarmNotifier](app/src/main/java/uk/hairyfred/libreshock/ui/AlarmNotifier.kt) — works while the app process is alive (foreground or backgrounded), even if the dialog isn't on-screen. The notification is dismissed automatically when the watch confirms stop/snooze. Lightweight (no foreground service) — won't fire if the user swipes the app away because the BLE connection dies with the process.
- System back / swipe-back navigates back through screens (alarm-edit → alarms → main)
- **Device info** screen: reads BLE name, manufacturer, model, serial, firmware/hardware revisions, timezone, device clock (mirrors the vendor app's Device Info screen)
- **Battery usage** screen: current %, "last charged" approximation, line graph of historical samples. Samples are recorded each time the screen is opened; persisted to a private CSV file in app storage (`battery_history.csv`).
- **Live battery %** inline with the connection status, e.g. "Connected to Pavlok-3-XXXX  •  Battery 26%". Updates in real time via watch battery notifications (CCCD subscription on `0x2A19`).
- **Bluetooth-off detection**: a `BluetoothAdapter.ACTION_STATE_CHANGED` receiver surfaces a persistent "Bluetooth is off" snackbar with a "Turn on" action that opens the system enable-BT prompt. The snackbar auto-dismisses when BT comes back on.
- **Auto-reconnect**: when the watch disconnects unexpectedly (Out of range / BT toggled / etc.) the app attempts to reconnect to the last-known MAC. Distinguishes intentional disconnects (user tapped the Disconnect button) from lost connections via an `intentionalDisconnect` flag inside `ShockDevice`. Reconnect kicks in either immediately (BT still on) or when BT comes back on (BT-state receiver).
- **Debug logging** toggle in Settings: turns on verbose BLE read/write logcat traces via `DebugLog.d(...)` calls inside `ShockDevice.kt`. Off by default; persisted across launches. View via `adb logcat -s ShockDevice`.
- **Alarm wake-up guarantors** (Phases A-E, May 19 2026): the alarm-edit screen exposes the watch's four guarantor modes (None / Jumping Jacks / QR code / Puzzle). When such an alarm fires the in-app dialog and system notification swap their Stop action for the appropriate gate:
  - **Jumping Jacks** — counted on the watch, no app interaction.
  - **QR code** — opens the in-app camera (`zxing-android-embedded`); only stops if the scan matches the bundled `ALARM_QR_CONTENT`. The bundled QR PNG ships in both `res/drawable/libreshock_qr.png` and `docs/images/libreshock-qr.png` for printing. The alarm-edit guarantor row has a "View / print QR code" dialog with print/share + raw-GitHub-link download.
  - **Puzzle** — opens `AlarmPuzzleScreen`, which randomly picks between a 3×4 memory-grid puzzle (5 lit tiles, 5s show with progress bar countdown) and an arithmetic equation puzzle (`+/-` under 100, `×` under 10, `/` whole-number). Wrong answer rolls a fresh puzzle (toggleable in Settings to keep the same one). Snooze always available; back-button trapped so the user can't escape without solving or snoozing.
- **Confetti on alarm dismiss** (Konfetti, ISC license, off by default): a short popper-style burst on successful stop — origin (button vs center) varies by which path stopped the alarm. Not fired on snooze.
- **Export debug log** button in Settings: generates a text report (full GATT tree with hex + ASCII characteristic values, battery, device-info strings) into the app cache and fires `Intent.ACTION_SEND` via FileProvider so the user can share it to email / Drive / GitHub. "Censor sensitive info" checkbox (default on) redacts MAC, BLE name suffix, and serial. Equivalent to `python libreshock.py debug --censor` — both paths produce the same report so users with unsupported Pavlok models can submit one file for protocol extension.

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
