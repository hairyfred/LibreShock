"""
LibreShock - Open Source BLE Shock Device Controller
Controls BLE shock/vibration wearables without vendor apps

Protocol reverse-engineered January 2026
https://github.com/XD/LibreShock
"""

import asyncio
import argparse
import struct
from dataclasses import dataclass, field
from typing import Optional, List
from bleak import BleakScanner, BleakClient


# Weekday constants for alarm repeat days
class Weekday:
    SUNDAY = 0x01
    MONDAY = 0x02
    TUESDAY = 0x04
    WEDNESDAY = 0x08
    THURSDAY = 0x10
    FRIDAY = 0x20
    SATURDAY = 0x40
    WEEKDAYS = MONDAY | TUESDAY | WEDNESDAY | THURSDAY | FRIDAY  # 0x3E
    WEEKENDS = SATURDAY | SUNDAY  # 0x41
    EVERYDAY = 0x7F


@dataclass
class AlarmAction:
    """Configuration for a single alarm action (vibe, beep, or zap)"""
    enabled: bool = True
    count: int = 5
    intensity: int = 50  # 0-100 (zap can go higher per screenshot)


@dataclass
class AlarmConfig:
    """Full alarm configuration matching vendor app features"""
    hour: int = 8
    minute: int = 0
    name: str = "alarm"
    weekdays: int = Weekday.EVERYDAY  # Bitmask of days to repeat
    snooze: bool = True
    enabled: bool = True
    stimulus_interval: int = 15  # Seconds between triggers

    # Action configurations
    vibration: AlarmAction = field(default_factory=lambda: AlarmAction(enabled=True, count=5, intensity=50))
    beep: AlarmAction = field(default_factory=lambda: AlarmAction(enabled=True, count=5, intensity=50))
    zap: AlarmAction = field(default_factory=lambda: AlarmAction(enabled=True, count=5, intensity=50))

    def weekday_names(self) -> List[str]:
        """Return list of day names this alarm repeats on"""
        days = []
        day_map = [
            (Weekday.SUNDAY, "Sun"), (Weekday.MONDAY, "Mon"), (Weekday.TUESDAY, "Tue"),
            (Weekday.WEDNESDAY, "Wed"), (Weekday.THURSDAY, "Thu"),
            (Weekday.FRIDAY, "Fri"), (Weekday.SATURDAY, "Sat")
        ]
        for mask, name in day_map:
            if self.weekdays & mask:
                days.append(name)
        return days

# Device identification. Vendor uses "Pavlok-<model>-<id>" today but we match
# anything starting with "Pavlok" so future name formats (other models or
# rebrands) still work. Protocol verified on Pavlok-3; other models untested.
DEVICE_NAME_PATTERN = "Pavlok"

# Characteristic UUIDs (Service 156e1000)
CHAR_VIBE = "00001001-0000-1000-8000-00805f9b34fb"
CHAR_BEEP = "00001002-0000-1000-8000-00805f9b34fb"
CHAR_ZAP  = "00001003-0000-1000-8000-00805f9b34fb"
CHAR_LED  = "00001004-0000-1000-8000-00805f9b34fb"

# Alarm control (Service 156e5000)
CHAR_CTRL = "00005001-0000-1000-8000-00805f9b34fb"
CHAR_DATA = "00005002-0000-1000-8000-00805f9b34fb"

# Standard Battery Service (0x180F)
CHAR_BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"

# Standard Device Information Service (0x180A)
CHAR_MANUFACTURER = "00002a29-0000-1000-8000-00805f9b34fb"
CHAR_MODEL        = "00002a24-0000-1000-8000-00805f9b34fb"
CHAR_SERIAL       = "00002a25-0000-1000-8000-00805f9b34fb"
CHAR_HARDWARE_REV = "00002a27-0000-1000-8000-00805f9b34fb"
CHAR_FIRMWARE_REV = "00002a26-0000-1000-8000-00805f9b34fb"
CHAR_SOFTWARE_REV = "00002a28-0000-1000-8000-00805f9b34fb"  # used by vendor as timezone string

# Custom device-time characteristic. Lives in the action-settings service
# (156e1000), char 1005 — NOT in 156e2000 as a casual reading of CLAUDE.md
# might suggest. Returns an 8-byte BCD-encoded timestamp.
CHAR_TIME = "00001005-0000-1000-8000-00805f9b34fb"

# Default profile name for alarms
DEFAULT_PROFILE = b"Single 1"

# Trigger flag - OR with enabled (0x01) to execute action
TRIGGER_FLAG = 0x80
ENABLED_FLAG = 0x01
TRIGGER_ENABLED = TRIGGER_FLAG | ENABLED_FLAG  # 0x81


_MONTH_NAMES = ["Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def _format_timezone(raw: str) -> str:
    """Vendor stores timezone as e.g. '+100' meaning UTC+1:00, or '-0530' meaning UTC-5:30.
    Reformat for display as 'UTC +1:00'."""
    if not raw:
        return raw
    sign = "+" if raw[0] != "-" else "-"
    digits = raw.lstrip("+-")
    if not digits.isdigit():
        return raw  # unknown shape
    digits = digits.zfill(3)
    minutes = digits[-2:]
    hours = digits[:-2].lstrip("0") or "0"
    return f"UTC {sign}{hours}:{minutes}"


def _decode_device_time(buf: bytes) -> Optional[dict]:
    """Decode the 8-byte BCD date/time blob from char 1005.

    Format: [sec_bcd, min_bcd, hour_bcd, day_bcd, 0x00, month_bcd, year_bcd, ??]
    The 8th byte doesn't match any standard day-of-week encoding — ignored.
    """
    if len(buf) < 8:
        return None
    sec = _bcd_to_int(buf[0])
    minute = _bcd_to_int(buf[1])
    hour = _bcd_to_int(buf[2])
    day = _bcd_to_int(buf[3])
    month = _bcd_to_int(buf[5])
    year = 2000 + _bcd_to_int(buf[6])
    month_name = _MONTH_NAMES[month - 1] if 1 <= month <= 12 else f"M{month}"
    return {
        "date": f"{month_name} {day} {year}",
        "time": f"{hour:02d}:{minute:02d}:{sec:02d}",
    }


def _crc16_ccitt(data: bytes) -> int:
    """CRC-16/CCITT-FALSE: poly=0x1021, init=0xFFFF, no reflect"""
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = (crc << 1) ^ 0x1021
            else:
                crc <<= 1
            crc &= 0xFFFF
    return crc


# TLV tag builder helper
def _tlv(tag: bytes, data: bytes) -> bytes:
    """Build a TLV (Tag-Length-Value) block"""
    return tag + struct.pack('<H', len(data)) + data


# MC block flags (Mode Config - vibration)
MC_FLAG_ENABLED = 0x80  # Always set when active
MC_FLAG_VIBE = 0x01     # Vibration action
MC_FLAG_VIBE2 = 0x04    # Vibration type marker (same as beep)

# PC block flags (Pulse Config - beep). Identical bit layout to MC.
PC_FLAG_ENABLED = 0x80
PC_FLAG_BEEP = 0x01
PC_FLAG_BEEP2 = 0x04

# ZC block flags (Zap Config)
ZC_FLAG_ENABLED = 0x80  # Always set when active
ZC_FLAG_ZAP = 0x01      # Zap enabled


def _int_to_bcd(val: int) -> int:
    """Convert integer to BCD (Binary-Coded Decimal). E.g., 30 -> 0x30, 45 -> 0x45"""
    return ((val // 10) << 4) | (val % 10)


def _bcd_to_int(val: int) -> int:
    """Convert BCD to integer. E.g., 0x30 -> 30, 0x45 -> 45"""
    return ((val >> 4) * 10) + (val & 0x0F)


def _build_mc_block(config: AlarmConfig) -> bytes:
    """Build MC block for vibration. Only called when vibration is enabled —
    MH/MC is omitted entirely when vibration is off."""
    flags = MC_FLAG_ENABLED | MC_FLAG_VIBE | MC_FLAG_VIBE2
    count = 0x0c  # Internal timing constant (vendor app always sends 0x0c)
    intensity = config.vibration.intensity
    return _tlv(b'MC', bytes([flags, count, intensity, 0xfa, 0xfa]))


def _build_pc_block(config: AlarmConfig) -> bytes:
    """Build PC block for beep. Only called when beep is enabled — PH/PC is
    omitted entirely when beep is off."""
    flags = PC_FLAG_ENABLED | PC_FLAG_BEEP | PC_FLAG_BEEP2
    count = 0x0c
    intensity = config.beep.intensity
    return _tlv(b'PC', bytes([flags, count, intensity, 0xfa, 0xfa]))


def _build_zc_block(config: AlarmConfig) -> bytes:
    """Build ZC block for zap. Only called when zap is enabled — ZH/ZC is
    omitted entirely when zap is off."""
    count = min(max(config.zap.count, 1), 15)  # 1-15, encoded in low nibble of flags
    flags = ZC_FLAG_ENABLED | count
    return _tlv(b'ZC', bytes([flags, config.zap.intensity]))


def _build_alarm_hac_block(config: AlarmConfig, alarm_id: int) -> bytes:
    """Build a complete alarm block from AlarmConfig.

    Block layout: HA + length(2 bytes LE) + content. The "HAC"/"HAP"/"HA9"
    etc. that show in hex dumps are coincidental ASCII renderings of the
    1-byte length (with high byte always 0 for typical alarms).

    MH/PH/ZH containers are independently included only when the corresponding
    stim is enabled. The vendor app does not write disabled-stim blocks.
    """
    name_bytes = config.name.encode('utf-8')[:20]
    an_block = _tlv(b'AN', name_bytes)

    # TM: [0x00, minute_bcd, hour_bcd, 0x80|day_mask]. The high bit (0x80) marks
    # the alarm as armed; bits 0-6 are the weekday repeat mask using the same
    # values as Weekday.* constants. Templates from device default have flag=0x00.
    minute_bcd = _int_to_bcd(config.minute)
    hour_bcd = _int_to_bcd(config.hour)
    day_mask = config.weekdays & 0x7F
    tm_block = _tlv(b'TM', bytes([0x00, minute_bcd, hour_bcd, 0x80 | day_mask]))

    # WD is a device constant in all observed vendor-app packets (0x1E).
    # The actual repeat-day mask lives in TM byte 3.
    wd_block = _tlv(b'WD', bytes([0x1E]))
    wi_block = _tlv(b'WI', struct.pack('<H', config.stimulus_interval))
    sn_block = _tlv(b'SN', bytes([0x01 if config.snooze else 0x00]))
    ao_block = _tlv(b'AO', bytes([0x01 if config.enabled else 0x00]))
    id_block = _tlv(b'ID', struct.pack('<H', alarm_id))

    content = an_block + tm_block + wd_block + wi_block + sn_block + ao_block
    if config.vibration.enabled:
        content += _tlv(b'MH', _build_mc_block(config))
    if config.beep.enabled:
        content += _tlv(b'PH', _build_pc_block(config))
    if config.zap.enabled:
        content += _tlv(b'ZH', _build_zc_block(config))
    content += id_block

    return b'HA' + struct.pack('<H', len(content)) + content


def _build_alarm_packet(alarms: List[AlarmConfig], profile: bytes = DEFAULT_PROFILE) -> bytes:
    """
    Build an alarm packet with one or more alarms.

    Args:
        alarms: List of AlarmConfig objects
        profile: Profile name bytes

    Returns:
        Complete packet ready to send to device
    """
    # Build AP header + profile name
    ap_block = b'AP' + struct.pack('<H', len(profile)) + profile

    # Build HAC blocks for each alarm
    hac_blocks = b''
    for i, config in enumerate(alarms, start=1):
        hac_blocks += _build_alarm_hac_block(config, i)

    # Combine content
    content = ap_block + hac_blocks

    # Build packet with CRC
    header = b'AH'
    length = len(content) + 2

    pkt_for_crc = header + struct.pack('<H', length) + b'\x00\x00' + content
    crc = _crc16_ccitt(pkt_for_crc)

    return header + struct.pack('<H', length) + struct.pack('<H', crc) + content


class ShockDevice:
    """Controller for BLE shock devices"""

    def __init__(self, address=None):
        self.address = address
        self.client = None
        self.device = None

    async def find_device(self, timeout=10.0):
        """Scan for device"""
        print(f"Scanning for {DEVICE_NAME_PATTERN}...")
        self.device = await BleakScanner.find_device_by_filter(
            lambda d, adv: d.name and DEVICE_NAME_PATTERN in d.name,
            timeout=timeout
        )
        if self.device:
            self.address = self.device.address
            print(f"Found: {self.device.name} ({self.address})")
            return True
        print("Device not found!")
        return False

    async def connect(self):
        """Connect to device"""
        if not self.address and not await self.find_device():
            return False

        self.client = BleakClient(self.address, timeout=20.0)
        await self.client.connect()
        print(f"Connected: {self.client.is_connected}")
        return self.client.is_connected

    async def disconnect(self):
        """Disconnect from device"""
        if self.client and self.client.is_connected:
            await self.client.disconnect()
            print("Disconnected")

    async def vibrate(self, intensity=100, count=1, on_time=22, off_time=22):
        """
        Trigger vibration

        Args:
            intensity: 0-100 (percent)
            count: number of pulses (1-255)
            on_time: vibration on duration
            off_time: pause between pulses
        """
        data = bytes([TRIGGER_ENABLED, count, intensity, on_time, off_time])
        await self.client.write_gatt_char(CHAR_VIBE, data)
        print(f"Vibrate: intensity={intensity}%, count={count}")

    async def beep(self, intensity=80, count=1, on_time=22, off_time=22):
        """
        Trigger beep/buzzer

        Args:
            intensity: 0-100 (percent)
            count: number of beeps (1-255)
            on_time: beep duration
            off_time: pause between beeps
        """
        data = bytes([TRIGGER_ENABLED, count, intensity, on_time, off_time])
        await self.client.write_gatt_char(CHAR_BEEP, data)
        print(f"Beep: intensity={intensity}%, count={count}")

    async def zap(self, intensity=50):
        """
        Trigger electric shock

        Args:
            intensity: 0-100 (percent) - recommend starting low!
        """
        data = bytes([TRIGGER_ENABLED, intensity])
        await self.client.write_gatt_char(CHAR_ZAP, data)
        print(f"Zap: intensity={intensity}%")

    async def led(self, enabled=True, intensity=50):
        """
        Control LED

        Args:
            enabled: True/False
            intensity: 0-100 (percent)
        """
        flag = TRIGGER_ENABLED if enabled else TRIGGER_FLAG
        data = bytes([flag, 0x11, intensity, 0x12, 0x29])  # Pattern from device
        await self.client.write_gatt_char(CHAR_LED, data)
        print(f"LED: {'on' if enabled else 'off'}, intensity={intensity}%")

    async def read_config(self):
        """Read current device configuration"""
        configs = {}
        for name, char in [("vibe", CHAR_VIBE), ("beep", CHAR_BEEP),
                           ("zap", CHAR_ZAP), ("led", CHAR_LED)]:
            try:
                val = await self.client.read_gatt_char(char)
                configs[name] = list(val)
            except:
                configs[name] = None
        return configs

    async def stop_alarm(self) -> bool:
        """Stop a currently-firing alarm on the device.

        Vendor app sends `[0x02, 0x00]` (no profile) to CTRL when Stop is
        pressed. Device confirms with `[0x56, 0x00, alarm_id, 0x00]` on the
        alarm-notify characteristic.
        """
        await self.client.write_gatt_char(CHAR_CTRL, bytes([0x02, 0x00]), response=True)
        print("Stop sent")
        return True

    async def snooze_alarm(self) -> bool:
        """Snooze a currently-firing alarm on the device.

        Vendor app sends `[0x03, 0x01]` (no profile) to CTRL when Snooze is
        pressed. Device confirms with `[0x55, 0x00, alarm_id, 0x00]` on the
        alarm-notify characteristic.
        """
        await self.client.write_gatt_char(CHAR_CTRL, bytes([0x03, 0x01]), response=True)
        print("Snooze sent")
        return True

    async def read_battery(self) -> Optional[int]:
        """Read the watch's battery percentage (0-100). Returns None on error."""
        try:
            val = await self.client.read_gatt_char(CHAR_BATTERY)
            return val[0] if val else None
        except Exception as e:
            print(f"Battery read failed: {e}")
            return None

    async def read_device_info(self) -> dict:
        """Read everything shown on the vendor app's Device Info screen.

        Returns a dict with keys: name (BLE adv name), manufacturer, model,
        serial, hardware_revision, firmware_revision, timezone, date, time,
        weekday. Missing fields are omitted.
        """
        out: dict = {}

        # BLE advertisement name (used by the vendor app as "Device name")
        if self.device is not None and self.device.name:
            out["name"] = self.device.name

        # Standard Device Information Service strings
        str_fields = {
            "manufacturer": CHAR_MANUFACTURER,
            "model": CHAR_MODEL,
            "serial": CHAR_SERIAL,
            "hardware_revision": CHAR_HARDWARE_REV,
            "firmware_revision": CHAR_FIRMWARE_REV,
        }
        for key, char in str_fields.items():
            try:
                val = await self.client.read_gatt_char(char)
                out[key] = val.decode("utf-8", errors="ignore").rstrip("\x00").strip()
            except Exception:
                pass

        # Vendor repurposes Software Revision String as timezone ("+100" = UTC+1:00)
        try:
            val = await self.client.read_gatt_char(CHAR_SOFTWARE_REV)
            raw = val.decode("utf-8", errors="ignore").rstrip("\x00").strip()
            out["timezone"] = _format_timezone(raw)
        except Exception:
            pass

        # Date/time on device (char 00001005)
        try:
            val = await self.client.read_gatt_char(CHAR_TIME)
            decoded = _decode_device_time(val)
            if decoded:
                out.update(decoded)
        except Exception:
            pass

        return out

    async def set_alarms(self, alarms: List[AlarmConfig], profile: bytes = DEFAULT_PROFILE) -> bool:
        """
        Set one or more alarms on the device. Pass empty list to clear all alarms.

        Args:
            alarms: List of AlarmConfig objects (empty list clears alarms)
            profile: Profile name (default: "Single 1")

        Returns:
            True if successful, False otherwise
        """
        # Build the alarm packet (empty list = clear alarms)
        packet = _build_alarm_packet(alarms, profile)

        # Set up notification handler to capture response
        response = []

        def on_notify(sender, data):
            response.append(data)

        await self.client.start_notify(CHAR_DATA, on_notify)
        await asyncio.sleep(0.2)

        try:
            # Enter write mode
            cmd = bytes([0x01, 0x00]) + profile
            await self.client.write_gatt_char(CHAR_CTRL, cmd, response=True)
            await asyncio.sleep(0.2)

            # Send packet in 20-byte chunks
            for i in range(0, len(packet), 20):
                chunk = packet[i:i+20]
                await self.client.write_gatt_char(CHAR_DATA, chunk, response=True)
                await asyncio.sleep(0.05)

            # Finalize with empty write
            await self.client.write_gatt_char(CHAR_DATA, b'', response=True)
            await asyncio.sleep(0.2)

            # Exit write mode
            cmd = bytes([0x00, 0x00]) + profile
            await self.client.write_gatt_char(CHAR_CTRL, cmd, response=True)
            await asyncio.sleep(0.3)

        finally:
            await self.client.stop_notify(CHAR_DATA)

        # Check response - 0x00000000 and 0x04000000 are both success
        success_responses = [b'\x00\x00\x00\x00', b'\x04\x00\x00\x00']
        if response and response[-1] in success_responses:
            for i, cfg in enumerate(alarms, start=1):
                days = ','.join(cfg.weekday_names()) if cfg.weekdays != Weekday.EVERYDAY else "Daily"
                actions = []
                if cfg.vibration.enabled:
                    actions.append(f"vibe:{cfg.vibration.intensity}%")
                if cfg.beep.enabled:
                    actions.append(f"beep:{cfg.beep.intensity}%")
                if cfg.zap.enabled:
                    actions.append(f"zap:{cfg.zap.intensity}%")
                print(f"Alarm {i}: {cfg.hour:02d}:{cfg.minute:02d} [{days}] - {', '.join(actions)}")
            return True
        else:
            error_code = response[-1].hex() if response else "no response"
            print(f"Failed to set alarms: {error_code}")
            return False

    async def set_alarm(self, hour: int, minute: int = 0, **kwargs) -> bool:
        """
        Set a single alarm (convenience wrapper).

        Args:
            hour: Hour (0-23)
            minute: Minute (0-59)
            **kwargs: Additional AlarmConfig fields (weekdays, snooze, vibration, beep, zap, etc.)
        """
        config = AlarmConfig(hour=hour, minute=minute, **kwargs)
        return await self.set_alarms([config])

    async def list_alarms(self, profile: bytes = DEFAULT_PROFILE) -> List[AlarmConfig]:
        """
        List current alarms on the device.

        Returns:
            List of AlarmConfig objects with all alarm settings
        """
        received = []

        def on_notify(sender, data):
            received.append(data)

        await self.client.start_notify(CHAR_DATA, on_notify)
        await self.client.start_notify(CHAR_CTRL, on_notify)
        await asyncio.sleep(0.3)

        try:
            # Query alarms (mode 0x06)
            cmd = bytes([0x06, 0x00]) + profile
            await self.client.write_gatt_char(CHAR_CTRL, cmd, response=True)
            await asyncio.sleep(2.0)  # Wait for all notifications
        finally:
            await self.client.stop_notify(CHAR_DATA)
            await self.client.stop_notify(CHAR_CTRL)

        if not received:
            return []

        # Combine all received data
        data = b''.join(received)

        # Skip 6-byte header if present (AH + length + checksum)
        if data[:2] == b'AH' and len(data) > 6:
            data = data[6:]

        # Parse alarms from response. Each alarm block is HA + len(2 LE) + content.
        # The third byte (commonly printable as 'C', 'P', '9', '6', 'F') is just
        # the low byte of the 2-byte content length and varies by stim combo.
        # Skip past the AP profile header first so we don't accidentally land on
        # "HA" inside the profile bytes.
        ap_pos = data.find(b'AP')
        scan_start = 0
        if ap_pos >= 0 and ap_pos + 4 <= len(data):
            ap_len = data[ap_pos + 2] | (data[ap_pos + 3] << 8)
            scan_start = ap_pos + 4 + ap_len

        alarms = []
        pos = scan_start
        while pos + 4 <= len(data):
            if data[pos:pos+2] != b'HA':
                pos += 1
                continue
            content_len = data[pos + 2] | (data[pos + 3] << 8)
            block_start = pos + 4
            block_end = block_start + content_len
            if block_end > len(data):
                break
            block = data[block_start:block_end]
            pos = block_end

            config = AlarmConfig()

            # Parse AN (Alarm Name)
            an_pos = block.find(b'AN')
            if an_pos >= 0 and an_pos + 4 <= len(block):
                an_len = block[an_pos + 2] | (block[an_pos + 3] << 8)
                if an_pos + 4 + an_len <= len(block):
                    config.name = block[an_pos + 4:an_pos + 4 + an_len].decode('utf-8', errors='ignore')

            # Parse TM (Time). Layout: T M [len:2] [00, min_bcd, hour_bcd, 0x80|day_mask]
            tm_pos = block.find(b'TM')
            if tm_pos >= 0 and tm_pos + 8 <= len(block):
                minute_bcd = block[tm_pos + 5]
                hour_bcd = block[tm_pos + 6]
                config.minute = _bcd_to_int(minute_bcd)
                config.hour = _bcd_to_int(hour_bcd)
                # Day mask is the low 7 bits of TM byte 3 (0x80 is the armed flag).
                config.weekdays = block[tm_pos + 7] & 0x7F

            # Parse WI (Stimulus Interval)
            wi_pos = block.find(b'WI')
            if wi_pos >= 0 and wi_pos + 6 <= len(block):
                config.stimulus_interval = block[wi_pos + 4] | (block[wi_pos + 5] << 8)

            # Parse SN (Snooze)
            sn_pos = block.find(b'SN')
            if sn_pos >= 0 and sn_pos + 5 <= len(block):
                config.snooze = block[sn_pos + 4] != 0

            # Parse AO (Alarm On/Enabled)
            ao_pos = block.find(b'AO')
            if ao_pos >= 0 and ao_pos + 5 <= len(block):
                config.enabled = block[ao_pos + 4] != 0

            # Each stim block is omitted entirely when the stim is disabled.
            # Default to disabled, then enable if its block is present.
            config.vibration = AlarmAction(enabled=False)
            config.beep = AlarmAction(enabled=False)
            config.zap = AlarmAction(enabled=False)

            mc_pos = block.find(b'MC')
            if mc_pos >= 0 and mc_pos + 7 <= len(block):
                config.vibration = AlarmAction(
                    enabled=True,
                    count=block[mc_pos + 5],
                    intensity=block[mc_pos + 6],
                )

            pc_pos = block.find(b'PC')
            if pc_pos >= 0 and pc_pos + 7 <= len(block):
                config.beep = AlarmAction(
                    enabled=True,
                    count=block[pc_pos + 5],
                    intensity=block[pc_pos + 6],
                )

            zc_pos = block.find(b'ZC')
            if zc_pos >= 0 and zc_pos + 6 <= len(block):
                zc_flags = block[zc_pos + 4]
                zc_count = zc_flags & 0x0F
                config.zap = AlarmAction(
                    enabled=True,
                    count=zc_count if zc_count > 0 else 1,
                    intensity=block[zc_pos + 5],
                )

            alarms.append(config)

        return alarms


def _parse_weekdays(days_str: str) -> int:
    """Parse weekday string to bitmask.
    Accepts: 'daily'/'everyday'/'all', 'weekdays'/'workdays', 'weekends'/'weekend',
    'none' (one-shot, no repeat), or a comma list like 'mon,wed,fri'."""
    days_str = days_str.lower().strip()

    if days_str in ('daily', 'everyday', 'all'):
        return Weekday.EVERYDAY
    if days_str in ('weekdays', 'workdays'):
        return Weekday.WEEKDAYS
    if days_str in ('weekends', 'weekend'):
        return Weekday.WEEKENDS
    if days_str in ('none', 'once', 'one-shot', 'oneshot'):
        return 0  # one-shot today; TM byte 3 = 0x80 with no day bits

    day_map = {
        'sun': Weekday.SUNDAY, 'sunday': Weekday.SUNDAY,
        'mon': Weekday.MONDAY, 'monday': Weekday.MONDAY,
        'tue': Weekday.TUESDAY, 'tuesday': Weekday.TUESDAY,
        'wed': Weekday.WEDNESDAY, 'wednesday': Weekday.WEDNESDAY,
        'thu': Weekday.THURSDAY, 'thursday': Weekday.THURSDAY,
        'fri': Weekday.FRIDAY, 'friday': Weekday.FRIDAY,
        'sat': Weekday.SATURDAY, 'saturday': Weekday.SATURDAY,
    }

    mask = 0
    for day in days_str.replace(' ', '').split(','):
        if day in day_map:
            mask |= day_map[day]
        else:
            raise ValueError(f"Unknown day: {day}")
    return mask if mask else Weekday.EVERYDAY


def _print_alarm(idx: int, cfg: AlarmConfig):
    """Pretty print an alarm configuration"""
    days = ','.join(cfg.weekday_names()) if cfg.weekdays != Weekday.EVERYDAY else "Daily"
    status = "ON" if cfg.enabled else "OFF"
    print(f"\n  Alarm {idx}: {cfg.hour:02d}:{cfg.minute:02d} [{days}] - {status}")
    print(f"    Name: {cfg.name}")
    print(f"    Snooze: {'Yes' if cfg.snooze else 'No'}")
    print(f"    Interval: {cfg.stimulus_interval}s between triggers")
    print(f"    Vibration: {'ON' if cfg.vibration.enabled else 'OFF'} - {cfg.vibration.count}x @ {cfg.vibration.intensity}%")
    print(f"    Beep: {'ON' if cfg.beep.enabled else 'OFF'} - {cfg.beep.count}x @ {cfg.beep.intensity}%")
    print(f"    Zap: {'ON' if cfg.zap.enabled else 'OFF'} - @ {cfg.zap.intensity}%")


def print_help():
    """Print comprehensive help message"""
    help_text = """
LibreShock - BLE Shock Device Controller

USAGE:
    python libreshock.py <action> [options]

ACTIONS:
    vibe      Trigger vibration
    beep      Trigger beep/buzzer
    zap       Trigger electric shock
    led       Control LED
    alarm     Manage alarms
    stop      Stop a currently-firing alarm
    snooze    Snooze a currently-firing alarm
    battery   Show watch battery percentage
    info      Show device info (manufacturer, model, serial, fw/hw versions)
    status    Show device configuration
    help      Show this help message

COMMON OPTIONS:
    -a, --address <addr>    BLE device address (auto-detect if omitted)
    -i, --intensity <0-100> Action intensity (default: 50)
    -c, --count <n>         Pulse count for vibe/beep (default: 1)

EXAMPLES:

  Instant Actions:
    python libreshock.py vibe -i 100 -c 3    # Vibrate 100%, 3 pulses
    python libreshock.py beep -i 80 -c 2     # Beep 80%, 2 times
    python libreshock.py zap -i 50           # Zap at 50% (start low!)
    python libreshock.py status              # Show device config

  Alarm Management:
    python libreshock.py alarm --list        # List all alarms

    # Set alarm at 7:30 AM, weekdays only
    python libreshock.py alarm -t 7:30 --days weekdays

    # Full alarm config: 8AM daily, vibe+zap, no beep
    python libreshock.py alarm -t 8:00 --name "Wake up" \\
        --days daily --vibe 80 --beep off --zap 50 --interval 15

    # Add alarm to existing ones
    python libreshock.py alarm --add 9:00

ALARM OPTIONS:
    -t, --time <HH:MM>      Alarm time (24h format, can repeat)
    --add <HH:MM>           Add to existing alarms
    -l, --list              List current alarms
    --clear                 Clear all alarms
    -n, --name <name>       Alarm name (default: "alarm")
    -d, --days <days>       Repeat days: daily, weekdays, weekends,
                            or comma-separated: mon,tue,wed,thu,fri
    --vibe <0-100|off>      Vibration intensity or 'off'
    --beep <0-100|off>      Beep volume or 'off'
    --zap <0-100|off>       Zap intensity or 'off'
    --vibe-count <n>        Vibration count (default: 5)
    --beep-count <n>        Beep count (default: 5)
    --interval <sec>        Seconds between stimuli (default: 15)
    --snooze/--no-snooze    Enable/disable snooze (default: on)
"""
    print(help_text)


async def main():
    parser = argparse.ArgumentParser(
        description="LibreShock - BLE Shock Device Controller",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("action", nargs='?', default="help",
                        choices=["vibe", "beep", "zap", "led", "alarm", "stop", "snooze",
                                 "battery", "info", "status", "help"],
                        help="Action to perform")

    # Common options
    parser.add_argument("-a", "--address", type=str, default=None,
                        help="Device BLE address (optional)")
    parser.add_argument("-i", "--intensity", type=int, default=50,
                        help="Intensity 0-100 (default: 50)")
    parser.add_argument("-c", "--count", type=int, default=1,
                        help="Pulse count for vibe/beep (default: 1)")

    # Alarm options
    parser.add_argument("-t", "--time", type=str, action="append",
                        help="Alarm time HH:MM (can use multiple times)")
    parser.add_argument("--add", type=str, action="append",
                        help="Add alarm to existing ones HH:MM")
    parser.add_argument("-l", "--list", action="store_true",
                        help="List current alarms")
    parser.add_argument("--clear", action="store_true",
                        help="Clear all alarms")
    parser.add_argument("--enable", type=int, metavar="INDEX",
                        help="Enable the alarm at the given 1-based index (see --list)")
    parser.add_argument("--disable", type=int, metavar="INDEX",
                        help="Disable the alarm at the given 1-based index (see --list)")
    parser.add_argument("-n", "--name", type=str, default="alarm",
                        help="Alarm name (default: alarm)")
    parser.add_argument("-d", "--days", type=str, default="daily",
                        help="Repeat days: daily, weekdays, weekends, or mon,tue,wed...")
    parser.add_argument("--vibe", type=str, default="50",
                        help="Vibration intensity 0-100 or 'off'")
    parser.add_argument("--beep", type=str, default="50",
                        help="Beep intensity 0-100 or 'off'")
    parser.add_argument("--zap", type=str, default="50",
                        help="Zap intensity 0-100 or 'off'")
    parser.add_argument("--vibe-count", type=int, default=5,
                        help="Vibration count (default: 5)")
    parser.add_argument("--beep-count", type=int, default=5,
                        help="Beep count (default: 5)")
    parser.add_argument("--interval", type=int, default=15,
                        help="Seconds between stimuli (default: 15)")
    parser.add_argument("--snooze", dest="snooze", action="store_true", default=True,
                        help="Enable snooze (default)")
    parser.add_argument("--no-snooze", dest="snooze", action="store_false",
                        help="Disable snooze")

    args = parser.parse_args()

    # Handle help
    if args.action == "help":
        print_help()
        return

    device = ShockDevice(args.address)

    if not await device.connect():
        return

    try:
        if args.action == "vibe":
            await device.vibrate(intensity=args.intensity, count=args.count)
        elif args.action == "beep":
            await device.beep(intensity=args.intensity, count=args.count)
        elif args.action == "zap":
            await device.zap(intensity=args.intensity)
        elif args.action == "led":
            await device.led(enabled=True, intensity=args.intensity)
        elif args.action == "stop":
            await device.stop_alarm()
        elif args.action == "snooze":
            await device.snooze_alarm()
        elif args.action == "battery":
            level = await device.read_battery()
            if level is None:
                print("Could not read battery")
            else:
                print(f"Battery: {level}%")
        elif args.action == "info":
            info = await device.read_device_info()
            if not info:
                print("No device info available")
            else:
                print("Device Info:")
                for key, value in info.items():
                    print(f"  {key.replace('_', ' ').title():20s} {value}")
        elif args.action == "alarm":
            if args.list:
                alarms = await device.list_alarms()
                if alarms:
                    print("\nCurrent Alarms:")
                    for i, cfg in enumerate(alarms, 1):
                        _print_alarm(i, cfg)
                else:
                    print("No alarms set")

            elif args.clear:
                # Clear by sending empty alarm packet
                await device.set_alarms([])
                print("Alarms cleared")

            elif args.enable is not None or args.disable is not None:
                idx = args.enable if args.enable is not None else args.disable
                want_enabled = args.enable is not None
                existing = await device.list_alarms()
                if not existing:
                    print("No alarms set")
                elif idx < 1 or idx > len(existing):
                    print(f"Index {idx} out of range; have {len(existing)} alarm(s). Use --list.")
                else:
                    target = existing[idx - 1]
                    if target.enabled == want_enabled:
                        action = "already" + (" enabled" if want_enabled else " disabled")
                        print(f"Alarm {idx} is {action}")
                    else:
                        target.enabled = want_enabled
                        ok = await device.set_alarms(existing)
                        verb = "Enabled" if want_enabled else "Disabled"
                        print(f"{verb} alarm {idx}" if ok else f"Failed to update alarm {idx}")

            elif args.add or args.time:
                # Parse action settings
                def parse_action(val: str, default_count: int) -> AlarmAction:
                    if val.lower() == 'off':
                        return AlarmAction(enabled=False, count=default_count, intensity=0)
                    return AlarmAction(enabled=True, count=default_count, intensity=int(val))

                try:
                    weekdays = _parse_weekdays(args.days)
                except ValueError as e:
                    print(f"Error: {e}")
                    return

                vibe_action = parse_action(args.vibe, args.vibe_count)
                beep_action = parse_action(args.beep, args.beep_count)
                zap_action = parse_action(args.zap, 1)

                if args.add:
                    # Add to existing alarms
                    existing = await device.list_alarms()
                    alarm_list = list(existing)

                    for time_str in args.add:
                        try:
                            h, m = map(int, time_str.split(':'))
                            if not (0 <= h <= 23 and 0 <= m <= 59):
                                raise ValueError()
                            new_alarm = AlarmConfig(
                                hour=h, minute=m, name=args.name,
                                weekdays=weekdays, snooze=args.snooze,
                                stimulus_interval=args.interval,
                                vibration=vibe_action, beep=beep_action, zap=zap_action
                            )
                            alarm_list.append(new_alarm)
                        except ValueError:
                            print(f"Error: Invalid time format '{time_str}'. Use HH:MM")
                            return

                    if len(alarm_list) > 3:
                        print(f"Error: Maximum 3 alarms supported (would have {len(alarm_list)})")
                        return
                    await device.set_alarms(alarm_list)

                elif args.time:
                    # Set new alarm(s), replacing existing
                    alarm_list = []
                    for time_str in args.time:
                        try:
                            h, m = map(int, time_str.split(':'))
                            if not (0 <= h <= 23 and 0 <= m <= 59):
                                raise ValueError()
                            new_alarm = AlarmConfig(
                                hour=h, minute=m, name=args.name,
                                weekdays=weekdays, snooze=args.snooze,
                                stimulus_interval=args.interval,
                                vibration=vibe_action, beep=beep_action, zap=zap_action
                            )
                            alarm_list.append(new_alarm)
                        except ValueError:
                            print(f"Error: Invalid time format '{time_str}'. Use HH:MM")
                            return

                    if len(alarm_list) > 3:
                        print(f"Error: Maximum 3 alarms supported")
                        return
                    await device.set_alarms(alarm_list)
            else:
                print("Usage: alarm --time HH:MM [options]")
                print("       alarm --add HH:MM [options]")
                print("       alarm --list")
                print("\nRun 'python libreshock.py help' for full options")

        elif args.action == "status":
            config = await device.read_config()
            print("\nDevice Configuration:")
            for name, val in config.items():
                print(f"  {name}: {val}")
    finally:
        await device.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
