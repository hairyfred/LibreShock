# Remote API

Optional HTTP server that lets external devices (Home Assistant, scripts,
smart-home gear, etc.) trigger LibreShock actions over the network.

The API is implemented in two places and the wire format is identical:

- **Android** — `Settings → Remote API` in the LibreShock app. Hosts the
  server inside the app as a foreground service. Off by default.
- **Python** — `libreshock_server.py` script in the repo root. Imports
  `libreshock.py` as a library and runs an [aiohttp][1] server.

[1]: https://docs.aiohttp.org/

Pick whichever you can leave running close to the watch. Both connect to
the same watch over BLE, **but only one at a time** — a Pavlok accepts a
single BLE connection at any moment.

## Endpoints

All require `Authorization: Bearer <token>`. Token is generated on first
enable (Android stores it in app prefs, Python in
`~/.libreshock/server-token.txt`).

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `GET`  | `/api/v1/status` | (none) | Connection state + battery + watch identity |
| `POST` | `/api/v1/vibrate` | `{"intensity": 0-100}` | One buzz |
| `POST` | `/api/v1/beep` | `{"intensity": 0-100}` | One beep |
| `POST` | `/api/v1/zap` | `{"intensity": 0-100}` | One zap |
| `POST` | `/api/v1/alarm/stop` | (none) | Stop a firing alarm |
| `POST` | `/api/v1/burst` | `{"action": vibrate\|beep\|zap, "intensity": 0-100, "count": 1-50, "gap_ms": 50-5000}` | Fire one action N times with a fixed gap, server-side |

### `GET /api/v1/status`

Lightweight read. Useful for dashboards (Home Assistant entity, etc.).
First call after server start triggers a BLE read of the watch's Device
Info service; subsequent calls return the cached values. Battery is
sourced from the most recent BLE notification (or `null` if none seen
yet).

```json
{
  "connected": true,
  "battery": 67,
  "watch_model": "Pavlok-S",
  "ble_name": "Pavlok-3-XXXX",
  "firmware": "6.10.0"
}
```

When not connected, every field other than `connected` is `null`.

### `POST /api/v1/burst`

Convenience wrapper around looping `/vibrate`, `/beep`, or `/zap`
client-side. The server runs the loop with the configured `gap_ms`
between writes and holds the request open until the burst completes
(roughly `count × gap_ms` milliseconds + BLE latency). Returns the
number of actions actually fired:

```json
{"ok": true, "fired": 3}
```

Defaults: `count=1`, `gap_ms=200`. Limits: `count` ≤ 50, `gap_ms` ∈
[50, 5000] — the cap exists to avoid pinning the BLE connection for
minutes on a runaway request.

### Response shape

- **`200 OK`** with `{"ok": true}` on success.
- **`401 Unauthorized`** with `{"error": "auth"}` if the token is missing
  or wrong.
- **`503 Service Unavailable`** with `{"error": "not connected"}` if the
  watch isn't currently connected over BLE.
- **`400 Bad Request`** with `{"error": "..."}` for malformed JSON or
  out-of-range parameters.

### Why no `count` parameter?

The instant-action protocol's `count` byte (along with `on_time` /
`off_time`) is **ignored by Pavlok firmware** — the watch fires one
fixed-length action regardless of what's in those bytes. Verified
empirically May 2026. The API surface reflects what the watch actually
supports rather than what the protocol bytes claim. For multi-pulse,
call the endpoint N times in a row — see examples below.

## Example: curl (Linux / macOS / Git Bash)

```sh
TOKEN=<from-settings-or-server-token.txt>
HOST=<phone-or-pc-ip>:8765

# Status
curl -X GET "http://$HOST/api/v1/status" \
  -H "Authorization: Bearer $TOKEN"

# Single buzz
curl -X POST "http://$HOST/api/v1/vibrate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"intensity": 50}'

# Three buzzes server-side
curl -X POST "http://$HOST/api/v1/burst" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action": "vibrate", "intensity": 50, "count": 3, "gap_ms": 250}'
```

## Example: PowerShell

```powershell
$base = "http://127.0.0.1:8765/api/v1"
$h    = @{ Authorization = "Bearer <token>" }
$ct   = "application/json"

# Status
Invoke-RestMethod -Method Get -Uri "$base/status" -Headers $h

# Single buzz
Invoke-RestMethod -Method Post -Uri "$base/vibrate" -Headers $h `
  -ContentType $ct -Body '{"intensity": 50}'

# Three buzzes via /burst — server loops, single request
Invoke-RestMethod -Method Post -Uri "$base/burst" -Headers $h `
  -ContentType $ct `
  -Body '{"action": "vibrate", "intensity": 50, "count": 3, "gap_ms": 250}'

# Same three buzzes via client-side loop (equivalent)
1..3 | ForEach-Object {
    Invoke-RestMethod -Method Post -Uri "$base/vibrate" -Headers $h `
      -ContentType $ct -Body '{"intensity": 50}'
    Start-Sleep -Milliseconds 250
}
```

**Heads up for Windows users:** bare `curl` in PowerShell is an alias for
`Invoke-WebRequest`, not curl. Use `curl.exe` explicitly if you want curl,
or use the native `Invoke-RestMethod` (cleaner JSON quoting).

## Example: Python

```python
import requests

API = "http://127.0.0.1:8765/api/v1"
TOKEN = "..."
headers = {"Authorization": f"Bearer {TOKEN}"}

# Status
r = requests.get(f"{API}/status", headers=headers)
print(r.json())  # {'connected': True, 'battery': 67, ...}

# Single buzz
requests.post(f"{API}/vibrate", json={"intensity": 50}, headers=headers)

# Three buzzes server-side
requests.post(f"{API}/burst", json={
    "action": "vibrate", "intensity": 50, "count": 3, "gap_ms": 250,
}, headers=headers)
```

## Running the Python server

```sh
pip install bleak aiohttp
python libreshock_server.py
```

Useful flags:

- `--port 9000` — change the port (default 8765).
- `--bind 0.0.0.0` — make the API reachable from other devices on the
  LAN (default: 127.0.0.1, localhost-only).
- `--token <token>` — override the auto-generated token. Default reads
  and writes `~/.libreshock/server-token.txt`.
- `--address F1:1F:...` — connect to a specific BLE MAC instead of
  auto-scanning.

On startup the script prints the bind address and the auth token. Wake
the watch (tap any button on the face) right before launch — it sleeps
within seconds when idle and the scan needs to see it advertising.

## Running the Android server

`Settings → Remote API → Enable Remote API`. Default off. Turning it on:

- Starts a foreground service with a persistent notification (required
  by Android to keep the BLE connection alive in the background).
- Exposes the API on `<phone-lan-ip>:8765` by default (configurable).
- Shows the auth token in the sub-screen with a copy button.

## Security model

Token is the only authentication. There is no user-level access control,
rate-limiting, or replay protection. Anyone on the network with the token
can fire stims at the watch while the server is running.

**Recommended:**

- Only enable on networks you trust (home wifi). Never on public wifi.
- Keep the Python server's `--bind` on `127.0.0.1` unless you actively
  need LAN reach.
- Regenerate the token if it leaks (Android Settings has a button;
  Python: delete `~/.libreshock/server-token.txt` and restart).

The Android app binds to `0.0.0.0` by default because that's the typical
use case (a separate device calling in); the Python script binds to
`127.0.0.1` by default because most Python users are testing locally.
