# Capturing a btsnoop log for protocol RE

Every BLE feature in LibreShock was reverse-engineered from `btsnoop_hci.log`
files — Android's full record of every BLE packet sent and received by the
phone. If you want to help add support for a new device (e.g. **Pavlok 4**)
or a new feature, the most valuable contribution is a clean btsnoop covering
just the action you're documenting.

## What you need

- An Android phone (any version that runs the vendor app — the snoop log is
  built into Android, no root needed).
- The vendor app (e.g. Pavlok's official app) installed and paired with the
  watch.
- A Mac/Linux/Windows machine with `adb` (Android Debug Bridge) for pulling
  the log off the phone afterwards.

## Step-by-step

### 1. Enable Developer options

`Settings → About phone → Build number` — tap it 7 times until it confirms
"You are now a developer".

### 2. Enable Bluetooth HCI snoop log

`Settings → System → Developer options → Enable Bluetooth HCI snoop log` →
toggle ON.

### 3. Cycle Bluetooth

Toggle **Bluetooth off, then back on**. The snoop log only starts recording
new sessions — it won't retroactively capture a connection that was already
open when you flipped the toggle.

### 4. Reproduce the action

Open the vendor app, connect to the watch, then perform the *single* action
you want to document. Examples:

- *Set an 07:30 alarm with vibrate + zap, weekdays.*
- *Toggle hand-raise detection on.*
- *Trigger a zap manually.*

Try to keep it to one action per capture — interleaved actions make the log
much harder to read.

Make a note of the **exact time** you tapped the action — it helps narrow
the relevant packets when there are minutes of BLE chatter on either side.

### 5. Generate a bug report

On your computer with the phone plugged in:

```
adb bugreport bugreport-<short-name>.zip
```

This takes 1-2 minutes. The output is a ~30 MB zip containing the full
snoop log plus a bunch of unrelated system diagnostics.

### 6. Extract the snoop log

```
unzip bugreport-<short-name>.zip -d bugreport
ls bugreport/FS/data/misc/bluetooth/logs/
```

You want `btsnoop_hci.log` from that directory. Rename it to something
descriptive (`pavlok4-alarm-set.btsnoop`) before sharing.

### 7. Sanity check + redact

Open the file in [Wireshark](https://www.wireshark.org/) (it understands
btsnoop natively) to confirm you can see BLE packets. The vendor's device
MAC will be visible in plain hex — if you'd rather not share that, you can
either:

- Trim the capture in Wireshark to just the writes/notifications on the
  vendor service UUIDs, or
- Note in your report that the MAC is OK to redact and we'll work around
  it.

### 8. Share

Attach the `.btsnoop` to a GitHub issue
[on the LibreShock tracker](https://github.com/hairyfred/LibreShock/issues),
along with:

- Watch model + firmware version (Settings → About in the vendor app)
- The **exact** action you performed and at what wall-clock time
- Any user-visible parameters you set (intensity, time, etc.) — these are
  the "ground truth" we match decoded packet bytes against

The captures already in this repo (`captures/*.btsnoop`) are good examples
of the size and scope to aim for.

## What we do with it

Open the file with `parse_btsnoop.py` (in the repo root) or
`scripts/timeline.py` to extract a chronological list of ATT writes and
notifications. Cross-reference against the timestamps in your action notes
to isolate the bytes that mean *the thing you did*. From there it's a
question of comparing parameter values across multiple captures to figure
out what each byte field encodes.

[CLAUDE.md](../CLAUDE.md) shows the result of that process for the
Pavlok-3 protocol — each field decoded, its range, its meaning. A
Pavlok-4 / Shock Clock Max support effort would build the same document
for the `66651000-39f4-11ed-92bd-832abac11ab4` service that ships on
that watch.
