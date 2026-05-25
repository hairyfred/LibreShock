"""LibreShock Remote API — Python server.

A small aiohttp HTTP server that exposes a subset of the in-app instant
actions over the network, mirroring the Android app's Remote API. Lets
external devices (Home Assistant, custom scripts, etc.) trigger vibrate /
beep / zap / alarm-stop on a Pavlok via BLE.

Endpoints (all require `Authorization: Bearer <token>`):

    GET  /api/v1/status      → {connected, battery, watch_model, ...}
    POST /api/v1/vibrate     {"intensity": 0-100}
    POST /api/v1/beep        {"intensity": 0-100}
    POST /api/v1/zap         {"intensity": 0-100}
    POST /api/v1/alarm/stop  (no body)
    POST /api/v1/burst       {"action": vibrate|beep|zap, "intensity": 0-100,
                              "count": 1-50, "gap_ms": 50-5000}

Each call to /vibrate /beep /zap fires ONE action. The watch's
instant-action characteristics ignore the count/on_time/off_time bytes
the protocol claims to expose (verified empirically May 2026 — only the
intensity byte affects output). For multi-pulse, use /burst (which loops
server-side) or call the single endpoint N times yourself.

Run with:

    pip install bleak aiohttp
    python libreshock_server.py

On first launch a token is generated and stored at
`~/.libreshock/server-token.txt`. Pass `--token` to override, `--port`
to change the bind port (default 8765), `--bind` for the listen address
(default `0.0.0.0` — reachable on the LAN).

Security: the token is the only line of defence. Anyone on the same
network with the token can fire stims. Don't run this on public wifi.
"""

import argparse
import asyncio
import hmac
import logging
import os
import secrets
import signal
import sys
from pathlib import Path

from aiohttp import web

# Reuse the protocol library — server is a thin BLE-bridging shell.
import libreshock as L

DEFAULT_PORT = 8765
TOKEN_PATH = Path.home() / ".libreshock" / "server-token.txt"
log = logging.getLogger("libreshock_server")


# ----- Auth -----

def load_or_create_token(override: str | None) -> str:
    """Return the user-supplied --token, or read/create the persistent one."""
    if override:
        return override
    if TOKEN_PATH.exists():
        existing = TOKEN_PATH.read_text(encoding="utf-8").strip()
        if existing:
            return existing
    fresh = secrets.token_hex(16)
    TOKEN_PATH.parent.mkdir(parents=True, exist_ok=True)
    TOKEN_PATH.write_text(fresh, encoding="utf-8")
    try:
        os.chmod(TOKEN_PATH, 0o600)  # restrict to user on Unix; no-op on Windows
    except OSError:
        pass
    return fresh


def check_auth(request: web.Request, token: str) -> bool:
    """Constant-time compare of the Bearer token in the request."""
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        return False
    supplied = header[len("Bearer "):].strip()
    return hmac.compare_digest(supplied, token)


# ----- Validation -----

def _parse_int(body: dict, field: str, lo: int, hi: int,
               default: int | None = None) -> tuple[int | None, str | None]:
    val = body.get(field, default)
    if val is None:
        return None, f"missing required field '{field}'"
    if not isinstance(val, (int, float)) or isinstance(val, bool):
        return None, f"'{field}' must be a number"
    val = int(val)
    if not (lo <= val <= hi):
        return None, f"'{field}' must be {lo}-{hi}"
    return val, None


# ----- Handlers -----

async def handle_vibrate(request: web.Request) -> web.Response:
    return await _handle_intensity_action(request, request.app["device"].vibrate)


async def handle_beep(request: web.Request) -> web.Response:
    return await _handle_intensity_action(request, request.app["device"].beep)


async def handle_zap(request: web.Request) -> web.Response:
    return await _handle_intensity_action(request, request.app["device"].zap)


async def handle_stop_alarm(request: web.Request) -> web.Response:
    if not check_auth(request, request.app["token"]):
        return _json(401, error="auth")
    if not _connected(request.app["device"]):
        return _json(503, error="not connected")
    await request.app["device"].stop_alarm()
    return _json(200, ok=True)


async def handle_status(request: web.Request) -> web.Response:
    """Lightweight read of connection state + last-known battery + watch
    identity. Auth still required so we don't leak the server's existence
    to passers-by on the LAN."""
    if not check_auth(request, request.app["token"]):
        return _json(401, error="auth")
    device: L.ShockDevice = request.app["device"]
    connected = _connected(device)
    out: dict = {"connected": connected}
    if connected:
        # Cache the device-info reads inside app state so /status stays
        # cheap (a few BLE reads on the first call, dict lookup after).
        cache: dict = request.app.setdefault("status_cache", {})
        if "model" not in cache:
            try:
                info = await device.read_device_info()
                cache["model"] = info.get("model")
                cache["ble_name"] = info.get("name")
                cache["firmware"] = info.get("firmware_revision")
            except Exception:
                pass
        out["battery"] = await device.read_battery()
        out["watch_model"] = cache.get("model")
        out["ble_name"] = cache.get("ble_name")
        out["firmware"] = cache.get("firmware")
    return _json(200, **out)


async def handle_burst(request: web.Request) -> web.Response:
    """Convenience wrapper: fire one of vibrate/beep/zap N times with a
    fixed gap between, server-side. Avoids the caller having to loop +
    sleep in their own code. Holds the request open for the full burst
    duration."""
    if not check_auth(request, request.app["token"]):
        return _json(401, error="auth")
    if not _connected(request.app["device"]):
        return _json(503, error="not connected")
    try:
        body = await request.json()
    except Exception:
        return _json(400, error="invalid JSON")
    action_name = body.get("action")
    if action_name not in ("vibrate", "beep", "zap"):
        return _json(400, error="action must be one of vibrate / beep / zap")
    intensity, err = _parse_int(body, "intensity", 0, 100)
    if err:
        return _json(400, error=err)
    count, err = _parse_int(body, "count", 1, 50, default=1)
    if err:
        return _json(400, error=err)
    gap_ms, err = _parse_int(body, "gap_ms", 50, 5000, default=200)
    if err:
        return _json(400, error=err)
    device = request.app["device"]
    action = {"vibrate": device.vibrate, "beep": device.beep,
              "zap": device.zap}[action_name]
    log.info("→ burst %s intensity=%d count=%d gap=%dms",
             action_name, intensity, count, gap_ms)
    for i in range(count):
        if i > 0:
            await asyncio.sleep(gap_ms / 1000)
        await action(intensity=intensity)
    return _json(200, ok=True, fired=count)


async def _handle_intensity_action(request, action):
    """Shared body for /vibrate /beep /zap. Each call fires ONE action of
    the watch's default duration — the count/on_time/off_time bytes the
    protocol accepts are ignored by the watch firmware (verified May
    2026), so we don't bother surfacing them in the API. Callers wanting
    multiple pulses should loop client-side."""
    if not check_auth(request, request.app["token"]):
        return _json(401, error="auth")
    if not _connected(request.app["device"]):
        return _json(503, error="not connected")
    try:
        body = await request.json()
    except Exception:
        return _json(400, error="invalid JSON")
    intensity, err = _parse_int(body, "intensity", 0, 100)
    if err:
        return _json(400, error=err)
    log.info("→ %s intensity=%d", action.__name__, intensity)
    await action(intensity=intensity)
    return _json(200, ok=True)


def _connected(device: L.ShockDevice) -> bool:
    return device.client is not None and device.client.is_connected


def _json(status: int, **fields) -> web.Response:
    return web.json_response(fields, status=status)


# ----- BLE lifecycle -----

async def ensure_connected(device: L.ShockDevice, address: str | None) -> None:
    """Connect on startup. Pavlok watches sleep within seconds when idle,
    so we widen the scan window and prompt the user to wake the watch
    right before scanning starts. If the device drops out later, requests
    will return 503 — a future iteration could auto-reconnect in the
    background. For v0.2.0 the user just restarts the script."""
    if address:
        device.address = address
    log.info("WAKE THE WATCH NOW (tap any button on the watch face). "
             "Scanning for 30s...")
    # Bump the scan timeout from the library default (10s) so the user
    # has time to actually wake the watch.
    if not address:
        if not await device.find_device(timeout=30.0):
            log.error("watch not found during scan window; "
                      "endpoints will return 503 until a connection is made")
            return
    ok = await device.connect()
    if not ok:
        log.error("could not connect to watch; endpoints will return 503")
    else:
        log.info("connected")


# ----- Server -----

def build_app(token: str, device: L.ShockDevice) -> web.Application:
    app = web.Application()
    app["token"] = token
    app["device"] = device
    app.router.add_get("/api/v1/status", handle_status)
    app.router.add_post("/api/v1/vibrate", handle_vibrate)
    app.router.add_post("/api/v1/beep", handle_beep)
    app.router.add_post("/api/v1/zap", handle_zap)
    app.router.add_post("/api/v1/alarm/stop", handle_stop_alarm)
    app.router.add_post("/api/v1/burst", handle_burst)
    return app


async def run(args) -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    token = load_or_create_token(args.token)
    device = L.ShockDevice(args.address)
    await ensure_connected(device, args.address)

    app = build_app(token, device)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, host=args.bind, port=args.port)
    await site.start()

    log.info("LibreShock Remote API listening on http://%s:%d",
             args.bind, args.port)
    log.info("Auth token: %s", token)
    log.info("Tip: keep this token private — anyone on the network with it "
             "can fire stims while the server is running.")

    # Wait until interrupted.
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    if sys.platform != "win32":
        # Windows doesn't support add_signal_handler on the proactor loop;
        # there KeyboardInterrupt is enough.
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop_event.set)
        await stop_event.wait()
    else:
        try:
            while True:
                await asyncio.sleep(3600)
        except (KeyboardInterrupt, asyncio.CancelledError):
            pass

    log.info("shutting down...")
    await runner.cleanup()
    try:
        await device.disconnect()
    except Exception:
        pass


def main() -> None:
    parser = argparse.ArgumentParser(
        description="LibreShock Remote API server. Exposes vibrate/beep/zap/"
                    "alarm-stop over HTTP for external devices to call.")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help=f"TCP port to listen on (default {DEFAULT_PORT})")
    parser.add_argument("--bind", default="127.0.0.1",
                        help="Address to bind (default 127.0.0.1 — "
                             "localhost-only; pass 0.0.0.0 to make the API "
                             "reachable from other devices on the LAN)")
    parser.add_argument("--token", default=None,
                        help="Override the auth token. Default reads / writes "
                             f"{TOKEN_PATH}.")
    parser.add_argument("--address", default=None,
                        help="BLE device address. Default auto-scans.")
    args = parser.parse_args()

    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
