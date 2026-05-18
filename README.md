# LibreShock

> **WARNING: AI Slop Coded**
>
> This software was AI slop coded. While it has been tested, do not use
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
- Manage alarms: list, create, edit, enable/disable, delete, per-day repeats
- Stop or snooze a currently-firing alarm from the phone / desktop
- Configure hand-raise detection: enable, wrist hand/position, stimulus
  (vibrate / beep / zap / countdown), zap intensity
- Rebind the watch's 3 hardware buttons: short + long press for each can
  trigger a stim (with repetition + intensity), toggle the built-in
  stopwatch / timer apps, toggle sleep tracking, or be disabled
- Enable / disable automatic sleep tracking
- Read battery level
- Read device info: name, manufacturer, model, serial, firmware / hardware
  revisions, on-device clock, timezone
- *(Android only)* Battery history graph, live connection state and
  auto-reconnect, Bluetooth-off detection with one-tap re-enable, full
  alarm CRUD UI with Material 3 TimePicker, in-app alarm-fire dialog
  with Stop / Snooze

## Status

Tested against:

- **BLE Name Pattern**: `Pavlok-*` (e.g. `Pavlok-3-XXXX`)
- **Manufacturer**: Behavioral Technology Group, Inc.
- **Model**: Pavlok-S (Pavlok 3)
- **Hardware**: 6.0.0
- **Firmware**: 6.10.0

Other Pavlok models likely share the same protocol but are untested. (I do not own any other Pavlok device, if there are issues please create an issue with logs and I will try my best to support it)

If your watch doesn't work with LibreShock, please export a debug log and attach it to a [GitHub issue](https://github.com/hairyfred/Libreshock/issues):

- **Android app**: Settings → **Export debug log**. Leave "Censor sensitive info" ticked unless you're comfortable sharing your MAC / serial publicly, then use the share sheet to send the `.txt` file to yourself and attach it to the issue.
- **Python CLI**: `python libreshock.py debug --censor > debug.txt`, then drag-and-drop `debug.txt` onto the new GitHub issue.

The log contains the full GATT tree, characteristic values, battery level and firmware/hardware revisions — enough for us to extend protocol support to your model.

## Python CLI

```
pip install bleak

# Show the watch's name, model, serial, firmware, clock and timezone
python libreshock.py info

# Vibrate at 100% intensity, 3 pulses
python libreshock.py vibe -i 100 -c 3

# Set a recurring 07:30 Wednesday alarm with all three stimuli
#   --vibe 50      vibrate at 50%
#   --beep 70      beep at 70%
#   --zap 30       zap at 30%
#   --interval 15  15 seconds between stimulus rounds
python libreshock.py alarm -t 7:30 --days wed --vibe 50 --beep 70 --zap 30 --interval 15
```

Full command reference: **[docs/CLI.md](docs/CLI.md)**.

## Android app

| Main | Alarms | Edit alarm |
|------|--------|------------|
| ![Main screen](docs/images/main.png) | ![Alarms list](docs/images/alarms.png) | ![Edit alarm](docs/images/edit-alarm.png) |

Pre-built signed APKs are attached to each release on the
**[Releases page](https://github.com/hairyfred/Libreshock/releases)** — grab
the `libreshock-vX.Y.Z.apk` from the latest release, transfer to your phone,
and install (you'll need to allow installs from unknown sources).

To build it yourself: project lives under `android/`. Open in Android Studio,
build, and install on a connected phone with USB debugging on. Min SDK 26
(Android 8.0).

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

## Protocol

The BLE protocol — services, characteristics, alarm packet format, day-mask
encoding, CRC algorithm, fire/stop/snooze commands — is fully documented
in [CLAUDE.md](CLAUDE.md). That file is the canonical reference.

## Disclaimer

This is an **unofficial project**. LibreShock is not affiliated with,
endorsed by, sponsored by, or in any way officially connected to Behavioral
Technology Group, Inc. or Pavlok. "Pavlok" and any related product names are
trademarks of their respective owners and are used here solely to identify
the hardware this project targets. The protocol implemented here was
reverse-engineered from publicly observable Bluetooth LE traffic for personal
interoperability purposes.
