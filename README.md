# LibreShock

> **WARNING: AI Slop Coded**
>
> This integration was AI slop coded. While it has been tested, do not use
> this in mission-critical situations. Use at your own risk. The code may
> contain bugs, security issues, or unexpected behavior.

---

LibreShock is a Python library + Android app that talks BLE to Pavlok
Devices without the vendor app. The BLE protocol was reverse-engineered
from adb btsnoop captures of the vendor app and is documented in
[CLAUDE.md](CLAUDE.md).

Two interfaces:

- **`libreshock.py`** — Python CLI (uses [bleak](https://github.com/hbldh/bleak))
- **`android/`** — Kotlin / Jetpack Compose Android app

## Features

- Trigger vibrate / beep / zap (with adjustable intensity)
- Manage alarms: list, create, edit, delete, per-day repeat schedules
- Stop or snooze a currently-firing alarm from the phone / desktop
- Read battery level
- Read device info: name, manufacturer, model, serial, firmware / hardware
  revisions, on-device clock, timezone
- *(Android only)* Battery history graph, live connection state and
  auto-reconnect, Bluetooth-off detection with one-tap re-enable, full
  alarm CRUD UI with Material 3 TimePicker

## Python CLI

Install:

```
pip install bleak
```

Make sure the device is paired in your OS Bluetooth settings first. Then:

```
python libreshock.py help        # show all commands
python libreshock.py vibe -i 100 -c 3
python libreshock.py beep -i 80  -c 2
python libreshock.py zap  -i 50
python libreshock.py status

python libreshock.py alarm --list
python libreshock.py alarm -t 7:30 --days wed --vibe 50 --beep off --zap 30
python libreshock.py alarm --clear

python libreshock.py stop        # stops a firing alarm
python libreshock.py snooze

python libreshock.py battery
python libreshock.py info
```

See `python libreshock.py help` for the full option list.

## Android app

Project lives under `android/`. Open in Android Studio, build, and install
on a connected phone with USB debugging on. Min SDK 26 (Android 8.0).

Permissions: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (API 31+); falls
back to `ACCESS_FINE_LOCATION` on older Android. Requested at runtime.

From the command line, with Android Studio's bundled JDK on `JAVA_HOME`:

```
cd android
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest --tests \
  "uk.hairyfred.libreshock.ble.AlarmProtocolTest"
```

The unit test verifies the Kotlin protocol output byte-matches captured
vendor-app packets — same validation as `scripts/verify_combos.py` for the
Python implementation.

## Repository layout

```
libreshock.py        Python CLI (reference implementation)
parse_btsnoop.py     btsnoop_hci.log parser used during RE
android/             Kotlin / Compose Android app
  app/src/main/.../ble/        Protocol + BLE wrapper
  app/src/main/.../ui/         Compose screens
  app/src/test/.../             JUnit tests (byte-exact protocol verification)
scripts/
  extract_alarm_writes.py      Decode alarm transactions from a btsnoop
  verify_combos.py             Byte-exact test vs captured vendor packets
  parse_alarm_packet.py        Decode a single packet
  timeline.py                  Chronological dump of writes/notifications
CLAUDE.md            Full reverse-engineered protocol documentation
```

## Status

Tested against:

- **BLE Name Pattern**: `Pavlok-*` (e.g. `Pavlok-3-XXXX`)
- **Manufacturer**: Behavioral Technology Group, Inc.
- **Model**: Pavlok-S (Pavlok 3)
- **Hardware**: 6.0.0
- **Firmware**: 6.10.0

Other Pavlok models likely share the same protocol but are untested.

## Protocol

The BLE protocol — services, characteristics, alarm packet format, day-mask
encoding, CRC algorithm, fire/stop/snooze commands — is fully documented
in [CLAUDE.md](CLAUDE.md). That file is the canonical reference.
