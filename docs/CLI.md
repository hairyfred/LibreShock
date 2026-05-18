# Python CLI reference

`libreshock.py` is a command-line interface to the same protocol the Android
app uses. It runs anywhere [bleak](https://github.com/hbldh/bleak) does
(Windows, Linux, macOS).

## Setup

```
pip install bleak
```

Pair the device in your OS Bluetooth settings first (Windows: Settings →
Bluetooth → Add device). The CLI auto-scans for any `Pavlok-*` device on
each run, so no address needs to be passed.

## Commands

| Command | What it does |
|---------|-------------|
| `vibe` | Trigger a vibration |
| `beep` | Trigger a beep |
| `zap` | Trigger an electric pulse |
| `led` | Pulse the device LED |
| `alarm` | Manage alarms (see sub-commands below) |
| `stop` | Stop a currently-firing alarm |
| `snooze` | Snooze a currently-firing alarm |
| `battery` | Show the watch's battery percentage |
| `info` | Show device info (name, model, serial, firmware, clock, timezone) |
| `sleep` | Enable/disable automatic sleep tracking (`--on` / `--off`) |
| `status` | Show the raw vibe / beep / zap / LED config currently stored on the device |
| `help` | Print full help with all options |

Run `python libreshock.py help` for a full option list at any time.

## Instant actions

```
python libreshock.py vibe -i 100 -c 3   # vibrate at 100% intensity, 3 pulses
python libreshock.py beep -i 80  -c 2   # beep at 80%, 2 beeps
python libreshock.py zap  -i 50         # one zap at 50% intensity
```

Common flags:

- `-i, --intensity 0-100` — power level for the action (default 50)
- `-c, --count N` — number of pulses for vibe / beep (default 1)
- `-a, --address F1:1F:...` — BLE MAC, if you want to skip the scan

## Alarms

```
python libreshock.py alarm --list                  # what's on the device
python libreshock.py alarm --clear                 # remove all alarms

# Wednesday 07:30 alarm with vibe + zap, no beep
python libreshock.py alarm -t 7:30 --days wed --vibe 50 --beep off --zap 30

# One-shot alarm (no repeat) for today at 18:00, all three stims
python libreshock.py alarm -t 18:00 --days none --vibe 60 --beep 60 --zap 40

# Add another alarm without overwriting existing ones
python libreshock.py alarm --add 8:00 --days weekdays

# Enable / disable an alarm without deleting it (1-based index from --list)
python libreshock.py alarm --disable 2
python libreshock.py alarm --enable 2
```

Alarm flags:

- `-t, --time HH:MM` — alarm time, 24-hour
- `--add HH:MM` — append instead of replace
- `--enable N`, `--disable N` — flip the "alarm on" flag for the alarm at
  1-based index N (see `--list` for indexes). Keeps the alarm in the list;
  only stops/starts it firing
- `-d, --days …` — repeat days. Accepts `daily`, `weekdays`, `weekends`,
  `none` (one-shot), or a comma list like `mon,wed,fri`
- `--vibe N|off`, `--beep N|off`, `--zap N|off` — per-stim intensity (0-100)
  or `off` to disable that stim
- `--vibe-count N`, `--beep-count N` — pulse counts
- `--interval S` — seconds between stimulus rounds (default 15)
- `--snooze` / `--no-snooze` — enable / disable snooze (default on)
- `-n, --name "Wake up"` — friendly name for the alarm

## Stop / snooze a firing alarm

When an alarm is going off on the device:

```
python libreshock.py stop      # silences and clears
python libreshock.py snooze    # silences and re-fires after the snooze interval
```

## Inspecting the device

```
python libreshock.py battery   # one-line battery %
python libreshock.py info      # name, manufacturer, model, serial,
                               # firmware/hardware, on-device clock, timezone
python libreshock.py status    # current vibe/beep/zap/LED raw config bytes
```

## Hand-raise detection

The watch can detect when you raise your hand toward your face (e.g.
when reaching for a phone or a snack) and trigger a stim. The vendor app
exposes 4 settings: enable, which hand the device is worn on, whether
it's strapped inside or outside the wrist, and the stimulus type.
Only zap has a user-configurable intensity; the others fire at 30%.

```
# Enable hand-raise on the right wrist, outside-strap, vibrate on detection
python libreshock.py handraise --on

# Right wrist, inside-strap, zap at 60%
python libreshock.py handraise --on --wrist inside --stim zap -i 60

# Left wrist, outside, beep
python libreshock.py handraise --on --hand left --stim beep

# Countdown — watch counts down (via vibration / beep cues) then zaps.
# Lowering your hand before time's up cancels the zap.
python libreshock.py handraise --on --stim countdown

# Disable
python libreshock.py handraise --off
```

Flags:

- `--hand left|right` — which wrist the watch is worn on (default `right`)
- `--wrist inside|outside` — strap orientation (default `outside`)
- `--stim vibrate|beep|zap|countdown` — what fires when a raise is detected
  (default `vibrate`)
- `-i, --intensity 0-100` — zap intensity (default 30, only applies to zap)

## Sleep tracking

The watch can stream actigraphy data (raw accelerometer-derived motion
samples) which the vendor app post-processes into Awake/REM/Light/Deep
sleep stages. Time-range scheduling (e.g. "track only between midnight
and 5 AM") is entirely phone-side — the BLE command is just on / off.

```
python libreshock.py sleep --on    # start streaming sleep data
python libreshock.py sleep --off   # stop streaming
```

This project doesn't yet parse the raw stream into sleep stages — that's
a separate signal-processing job. The watch itself only knows "track" or
"don't track"; the staging happens in software downstream.

**Heads up:** the Pavlok app stores its sleep-tracking time range locally
on the phone, not on the watch. If you toggle via libreshock the watch
will obey, but the Pavlok app may show a blank "-- and --" time range
afterwards because we bypassed its scheduling. Re-set the time range in
the Pavlok app to clear the blank state.
