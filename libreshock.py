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


# Alarm guarantor task ("wake-up task" in the vendor UI). Encoded as a bit
# in the AO byte of the alarm block:
#   AO=0x01: armed, no task
#   AO=0x02: Jumping Jacks (also adds a JL TLV holding the rep count)
#   AO=0x04: QR code scan
#   AO=0x80: Puzzle unlock
# These appear to be bit flags, but the vendor UI presents them as a single
# radio-button choice — only one can be active at a time.
class Guarantor:
    NONE = 0x01
    JUMPING_JACKS = 0x02
    QR_CODE = 0x04
    PUZZLE = 0x80


# Additional wake-up features. Each is an independent bit in either the SN
# byte or the AO byte. Two of them (Escalating + Smart Alarm) also emit a
# new TLV with the watch's hardcoded default config block (no UI exposes
# the byte values, so they're always the same).
SN_FLAG_SNOOZE_ZAP = 0x02
AO_FLAG_LIGHT_SLEEP = 0x08
AO_FLAG_ESCALATING = 0x20
AO_FLAG_SMART_ALARM = 0x40

# Hardcoded default ES / SM TLV bodies the vendor app writes — same bytes
# regardless of UI state because neither feature exposes any sliders.
ES_DEFAULT_BODY = bytes([0x05])
SM_DEFAULT_BODY = bytes([0x0f, 0x05, 0x06])


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

    # Wake-up "guarantor" task that must be completed to stop the alarm.
    # See Guarantor enum for values.
    guarantor: int = Guarantor.NONE
    # Required jumps for the Jumping Jacks guarantor (1-N). Ignored for
    # other guarantor types.
    jumping_jacks_count: int = 1

    # "Additional wake-up features" from the vendor app — independent
    # flags that can each be on or off alongside any guarantor.
    snooze_zap: bool = False   # zap when user snoozes
    light_sleep: bool = False  # fire up to 20 min early when actigraphy detects light sleep
    escalating: bool = False   # ramp stim intensity until the user wakes
    smart_alarm: bool = False  # re-arm if no motion 5 min after dismiss (30 min total)

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

# Timer & Stopwatch config. The watch's "Timer & Stopwatch" screen lets the
# user pick a mode (countdown timer with fixed duration, or open-ended
# stopwatch), then attach one or more recurring stim intervals. The config
# is written to the same characteristic as button rebinding (char 7001) but
# with opcode 0x22.
class TnsMode:
    TIMER = 0x12      # countdown for a fixed duration
    STOPWATCH = 0x13  # open-ended counter

# Timer/Stopwatch stim classes — same numbering as the per-button action
# classes (1=vibrate, 2=beep, 3=zap).
class TnsStim:
    VIBE = 0x01
    BEEP = 0x02
    ZAP = 0x03


@dataclass
class TnsInterval:
    """A single recurring stim during a Timer/Stopwatch run.

    The watch's per-interval intensity is clamped to a much narrower
    internal range (0x21–0x34) than the 0-100 used elsewhere, presumably
    as a safety cap since intervals can fire every second. The
    [intensity] field on this class is the user-facing 0-100; the
    encoder maps it onto the watch's range.
    """
    stim: int = TnsStim.ZAP
    intensity: int = 50  # 0-100, mapped onto 0x21..0x34 on the wire
    every_seconds: int = 5  # repeat interval, max 65535 (~18 hours)


@dataclass
class TnsConfig:
    """Timer & Stopwatch configuration. Has a single shared list of
    intervals; the vendor app only lets you have one stim per `every_seconds`
    value (i.e. you can have a Zap every 5s and a Beep every 10s, but not
    two stims both on the every-5s slot)."""
    mode: int = TnsMode.STOPWATCH
    duration_seconds: int = 0  # Timer countdown duration; ignored when mode=STOPWATCH
    intervals: List[TnsInterval] = field(default_factory=list)


# Watch's intensity range for Timer/Stopwatch intervals (vendor-app-imposed
# safety cap; outside this range the watch ignores the byte).
TNS_INTENSITY_MIN = 0x21  # 33 — corresponds to 0% on the slider
TNS_INTENSITY_MAX = 0x34  # 52 — corresponds to 100%
TNS_INTENSITY_RANGE = TNS_INTENSITY_MAX - TNS_INTENSITY_MIN  # 19


def tns_intensity_to_byte(percent: int) -> int:
    """Map a 0-100 user-facing intensity onto the watch's 0x21-0x34 byte.

    Vendor app uses integer truncation, not rounding — verified
    byte-exact for 0/50/75/100% (the only values we've captured).
    """
    p = max(0, min(100, percent))
    return TNS_INTENSITY_MIN + (p * TNS_INTENSITY_RANGE) // 100


def tns_intensity_from_byte(byte_val: int) -> int:
    """Inverse of tns_intensity_to_byte. Returns 0-100."""
    clamped = max(TNS_INTENSITY_MIN, min(TNS_INTENSITY_MAX, byte_val))
    # Round up so the parsed percent re-encodes to the same byte.
    return ((clamped - TNS_INTENSITY_MIN) * 100 + TNS_INTENSITY_RANGE - 1) // TNS_INTENSITY_RANGE


def build_tns_config(config: TnsConfig) -> bytes:
    """Build the BLE write payload for a Timer/Stopwatch config.

    Layout (full packet sent to char 7001):
      22 <body_len:u16-LE> <body> 00

      body = <mode:1> f5 02 01 <duration:1> f0
             <indicator:1> <intervals_data>

    Each interval is 3 bytes: <stim_class> <intensity_byte> <every_seconds:u8>.
    When multiple intervals are present they're joined with a `0x00`
    separator (so the watch can scan stim by stim). The indicator byte
    equals 1 + len(intervals_data) — effectively the offset from itself
    to the byte right after the last interval.

    Interval seconds are u8 (1..255). The vendor app caps the picker
    accordingly.
    """
    chunks = []
    for iv in config.intervals:
        secs = max(1, min(iv.every_seconds, 0xFF))
        chunks.append(bytes([
            iv.stim & 0xFF,
            tns_intensity_to_byte(iv.intensity),
            secs,
        ]))
    intervals_data = b'\x00'.join(chunks)
    indicator = 1 + len(intervals_data)
    duration = max(0, min(config.duration_seconds, 0xFF))
    body = bytes([
        config.mode & 0xFF,
        0xf5, 0x02, 0x01,
        duration,
        0xf0,
        indicator & 0xFF,
    ]) + intervals_data
    return bytes([0x22]) + struct.pack('<H', len(body)) + body + bytes([0x00])


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

# Sleep tracking lives in service 156e0000 char 0008 (write,notify). The
# enable/disable command is a 2-byte write to the *descriptor* immediately
# following the char value (handle+1). Bleak doesn't enumerate this descriptor,
# so we compute its handle from the characteristic at runtime.
CHAR_SLEEP_TRACKING = "00000008-0000-1000-8000-00805f9b34fb"

# Watch hardware button rebinding (service 156e7000, char 7001).
# Each of the 3 physical buttons has 2 press modes (short + long) = 6 slots:
#   slot 1 = top short    slot 4 = top long
#   slot 2 = middle short slot 5 = middle long
#   slot 3 = lower short  slot 6 = lower long
# Payload format: [0x02, slot, action_class, ...action_params]
# Action classes seen so far:
#   0x01 = vibrate   params [0x40|count, 0x0c, intensity, 0x16, 0x16]
#   0x02 = beep      params [0x40|count, 0x0c, intensity, 0x16, 0x16]
#   0x03 = zap       params [0x40|count, intensity]
#   0x11 = app toggle  params [0x02, 0x10, app_id] (app_id 1=stopwatch, 2=timer)
#   0x13 = sleep tracking toggle  params [0x01, 0x02]
#   0xff = disabled (no params)
CHAR_BUTTON_CONFIG = "00007001-0000-1000-8000-00805f9b34fb"

BUTTON_SLOTS = {
    "top-short": 1, "top-long": 4,
    "mid-short": 2, "mid-long": 5,
    "lower-short": 3, "lower-long": 6,
    # aliases
    "middle-short": 2, "middle-long": 5,
    "bottom-short": 3, "bottom-long": 6,
}

BUTTON_ACTIONS = ("vibrate", "vibe", "beep", "zap", "shock",
                  "stopwatch", "timer", "sleep", "disabled", "off")

# Hand-raise detection (service 156e1000, char 1006, user description "HD").
# 4-byte payload: [flags, 0x70, stim_type, intensity].
#   flags bit 0 = enabled, bits 1+2 always set, bit 3 = inside wrist,
#         bit 4 = left hand. Bits 5-7 unobserved.
#   stim_type: 0=vibrate, 1=beep, 2=zap, 3=countdown.
#   intensity: 0-100; only zap surfaces a slider in the vendor app
#              (other stims default to 30).
CHAR_HAND_RAISE = "00001006-0000-1000-8000-00805f9b34fb"

HAND_RAISE_STIMULI = {
    "vibrate": 0x00, "vibe": 0x00,
    "beep": 0x01,
    "zap": 0x02, "shock": 0x02,
    "countdown": 0x03,
}

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
    # SN is a 2-bit field: bit 0 = snooze enabled, bit 1 = Snooze Zap.
    # Snooze Zap requires snooze to be enabled to actually fire — we mirror
    # the vendor app behavior and only set bit 1 when bit 0 is also set.
    sn_value = 0
    if config.snooze:
        sn_value |= 0x01
        if config.snooze_zap:
            sn_value |= SN_FLAG_SNOOZE_ZAP
    sn_block = _tlv(b'SN', bytes([sn_value]))

    # AO encodes the armed bit, the guarantor task, and the additional
    # wake-up feature flags. When disabled the whole byte is 0; otherwise
    # we OR the guarantor bit with the feature flags.
    if config.enabled:
        ao_value = config.guarantor
        if config.light_sleep:
            ao_value |= AO_FLAG_LIGHT_SLEEP
        if config.escalating:
            ao_value |= AO_FLAG_ESCALATING
        if config.smart_alarm:
            ao_value |= AO_FLAG_SMART_ALARM
    else:
        ao_value = 0x00
    ao_block = _tlv(b'AO', bytes([ao_value]))
    id_block = _tlv(b'ID', struct.pack('<H', alarm_id))

    content = an_block + tm_block + wd_block + wi_block + sn_block + ao_block
    # Jumping Jacks needs a rep-count TLV (JL) sandwiched between AO and MH.
    # Vendor app caps the user-visible slider at 20 reps.
    if config.enabled and config.guarantor == Guarantor.JUMPING_JACKS:
        jl_count = max(1, min(config.jumping_jacks_count, 20))
        content += _tlv(b'JL', bytes([jl_count]))
    # Escalating Alarm adds an ES TLV between AO/JL and MH. Vendor app
    # ships a hardcoded body — no UI sliders for it.
    if config.enabled and config.escalating:
        content += _tlv(b'ES', ES_DEFAULT_BODY)
    # Smart Alarm adds an SM TLV in the same position. Body is also
    # hardcoded — the vendor's "5 min recheck / 30 min expiry" defaults
    # appear to be baked in here without a UI override.
    if config.enabled and config.smart_alarm:
        content += _tlv(b'SM', SM_DEFAULT_BODY)
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

    async def set_button(
        self,
        slot: str,
        action: str,
        count: int = 1,
        intensity: int = 50,
    ) -> bool:
        """Bind one of the watch's 6 button slots (3 buttons x 2 press modes)
        to a stimulus or built-in app action.

        Slot is one of: top-short, top-long, mid-short, mid-long,
        lower-short, lower-long (with middle-/bottom- aliases).

        Action is one of: vibrate, beep, zap, stopwatch, timer, sleep, disabled.
        `count` (1-15) applies only to vibrate/beep/zap.
        `intensity` (0-100) applies only to vibrate/beep/zap.
        """
        slot_id = BUTTON_SLOTS.get(slot.lower())
        if slot_id is None:
            print(f"Unknown slot '{slot}'. Use one of: {', '.join(sorted(set(BUTTON_SLOTS)))}")
            return False
        action_l = action.lower()
        if action_l not in BUTTON_ACTIONS:
            print(f"Unknown action '{action}'. Use one of: {', '.join(BUTTON_ACTIONS)}")
            return False

        count_byte = 0x40 | max(1, min(15, int(count)))
        i = max(0, min(100, int(intensity)))
        params: bytes
        if action_l in ("vibrate", "vibe"):
            params = bytes([0x01, count_byte, 0x0c, i, 0x16, 0x16])
        elif action_l == "beep":
            params = bytes([0x02, count_byte, 0x0c, i, 0x16, 0x16])
        elif action_l in ("zap", "shock"):
            params = bytes([0x03, count_byte, i])
        elif action_l == "stopwatch":
            params = bytes([0x11, 0x02, 0x10, 0x01])
        elif action_l == "timer":
            params = bytes([0x11, 0x02, 0x10, 0x02])
        elif action_l == "sleep":
            params = bytes([0x13, 0x01, 0x02])
        elif action_l in ("disabled", "off"):
            params = bytes([0xff])
        else:
            print(f"Unhandled action '{action_l}'")
            return False

        payload = bytes([0x02, slot_id]) + params
        try:
            await self.client.write_gatt_char(CHAR_BUTTON_CONFIG, payload, response=True)
            print(f"Bound {slot} -> {action_l}"
                  + (f" x{count} @ {i}%" if action_l in ('vibrate', 'vibe', 'beep', 'zap', 'shock') else ""))
            return True
        except Exception as e:
            print(f"Button bind failed: {e}")
            return False

    async def set_tns_config(self, config: TnsConfig) -> bool:
        """Write a Timer/Stopwatch configuration to the watch.

        Goes through the same characteristic as button rebinding (char 7001)
        but with opcode 0x22. The watch parses the payload, switches its
        display to Timer or Stopwatch mode (per `config.mode`), and arms
        the recurring intervals. Start/stop is done from the watch buttons.
        """
        payload = build_tns_config(config)
        try:
            await self.client.write_gatt_char(CHAR_BUTTON_CONFIG, payload, response=True)
            mode_name = "Timer" if config.mode == TnsMode.TIMER else "Stopwatch"
            iv_summary = ", ".join(
                f"{['?', 'vibe', 'beep', 'zap'][iv.stim] if 0 <= iv.stim <= 3 else '?'}"
                f" {iv.intensity}% every {iv.every_seconds}s"
                for iv in config.intervals
            ) or "(no intervals)"
            duration_str = (
                f" for {config.duration_seconds}s" if config.mode == TnsMode.TIMER
                                                     and config.duration_seconds > 0
                else ""
            )
            print(f"{mode_name}{duration_str}: {iv_summary}")
            return True
        except Exception as e:
            print(f"Timer/Stopwatch config failed: {e}")
            return False

    async def read_hand_raise(self) -> Optional[bytes]:
        """Read the current 4-byte hand-raise config from the watch."""
        try:
            return bytes(await self.client.read_gatt_char(CHAR_HAND_RAISE))
        except Exception as e:
            print(f"Hand-raise read failed: {e}")
            return None

    async def set_hand_raise(
        self,
        enabled: bool,
        hand: str = "right",         # "left" or "right"
        wrist: str = "outside",      # "outside" or "inside"
        stimulus: str = "vibrate",   # vibrate / beep / zap / countdown
        intensity: int = 30,         # 0-100; only zap exposes a slider in vendor app
    ) -> bool:
        """Configure the watch's hand-raise detection feature.

        Mirrors the vendor app's Hand Raise Detection screen. Sends a single
        4-byte write to char 1006 (handle 0x0020 on observed devices).
        """
        if stimulus.lower() not in HAND_RAISE_STIMULI:
            print(f"Unknown stimulus '{stimulus}'. Use one of: vibrate, beep, zap, countdown")
            return False

        flags = 0x06  # bits 1+2 always set
        if enabled: flags |= 0x01
        if wrist.lower() == "inside": flags |= 0x08
        if hand.lower() == "left":   flags |= 0x10

        stim_byte = HAND_RAISE_STIMULI[stimulus.lower()]
        intensity_byte = max(0, min(100, int(intensity)))
        payload = bytes([flags, 0x70, stim_byte, intensity_byte])

        try:
            await self.client.write_gatt_char(CHAR_HAND_RAISE, payload, response=True)
            state = "enabled" if enabled else "disabled"
            print(f"Hand-raise {state} ({hand} hand, {wrist} wrist, {stimulus} @ {intensity_byte}%)")
            return True
        except Exception as e:
            print(f"Hand-raise write failed: {e}")
            return False

    async def set_sleep_tracking(self, enabled: bool) -> bool:
        """Enable or disable automatic sleep tracking on the watch.

        Vendor app writes `[0x02, enabled_flag]` to a vendor-specific
        descriptor at `(char 0x0008 value handle) + 1`. The Windows BLE
        stack and bleak don't expose that descriptor (UUID isn't a
        recognized standard one), so we first try writing the same payload
        to the char value itself — some Pavlok firmware accepts both —
        then fall back to the descriptor write if a UUID exposes it.
        """
        payload = bytes([0x02, 0x01 if enabled else 0x00])
        char = self.client.services.get_characteristic(CHAR_SLEEP_TRACKING)
        if char is None:
            print("Sleep tracking characteristic not found on device")
            return False

        # Attempt 1: write to the char value itself.
        try:
            await self.client.write_gatt_char(CHAR_SLEEP_TRACKING, payload, response=True)
            print(f"Sleep tracking {'enabled' if enabled else 'disabled'}.")
            print("Note: the Pavlok app schedules sleep tracking on the phone side"
                  " using a time range that's stored locally. Toggling directly via"
                  " libreshock bypasses that — the app may show a blank '-- and --'"
                  " time range until you re-set it in the app.")
            return True
        except Exception as char_err:
            char_error = char_err

        # Attempt 2: descriptor write (will fail on Windows if not enumerated).
        try:
            desc_handle = char.handle + 1
            await self.client.write_gatt_descriptor(desc_handle, payload)
            print(f"Sleep tracking {'enabled' if enabled else 'disabled'} (via descriptor write)")
            return True
        except Exception as desc_err:
            print(f"Sleep tracking write failed.")
            print(f"  char-value attempt: {char_error}")
            print(f"  descriptor attempt: {desc_err}")
            return False

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

            # Parse SN (Snooze + Snooze Zap). 2-bit field.
            sn_pos = block.find(b'SN')
            if sn_pos >= 0 and sn_pos + 5 <= len(block):
                sn_byte = block[sn_pos + 4]
                config.snooze = (sn_byte & 0x01) != 0
                config.snooze_zap = (sn_byte & SN_FLAG_SNOOZE_ZAP) != 0

            # Parse AO (Alarm On/Enabled + Guarantor task + feature flags).
            # Mask off the additional-feature bits before resolving the
            # guarantor, since those bits live in the same byte.
            ao_pos = block.find(b'AO')
            if ao_pos >= 0 and ao_pos + 5 <= len(block):
                ao_byte = block[ao_pos + 4]
                config.enabled = ao_byte != 0
                config.light_sleep = (ao_byte & AO_FLAG_LIGHT_SLEEP) != 0
                config.escalating = (ao_byte & AO_FLAG_ESCALATING) != 0
                config.smart_alarm = (ao_byte & AO_FLAG_SMART_ALARM) != 0
                guarantor_bits = ao_byte & ~(
                    AO_FLAG_LIGHT_SLEEP | AO_FLAG_ESCALATING | AO_FLAG_SMART_ALARM
                )
                config.guarantor = guarantor_bits if guarantor_bits != 0 else Guarantor.NONE

            # Parse JL (Jumping-Jacks count) — only present when guarantor=JJ.
            jl_pos = block.find(b'JL')
            if jl_pos >= 0 and jl_pos + 5 <= len(block):
                config.jumping_jacks_count = block[jl_pos + 4]

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
    guarantor_names = {
        Guarantor.NONE: "None",
        Guarantor.JUMPING_JACKS: f"Jumping Jacks ({cfg.jumping_jacks_count} reps)",
        Guarantor.QR_CODE: "QR code scan",
        Guarantor.PUZZLE: "Puzzle unlock",
    }
    if cfg.guarantor != Guarantor.NONE:
        print(f"    Guarantor: {guarantor_names.get(cfg.guarantor, f'unknown 0x{cfg.guarantor:02x}')}")
    extras = []
    if cfg.snooze_zap: extras.append("Snooze Zap")
    if cfg.light_sleep: extras.append("Light Sleep")
    if cfg.escalating: extras.append("Escalating")
    if cfg.smart_alarm: extras.append("Smart Alarm")
    if extras:
        print(f"    Wake-up features: {', '.join(extras)}")


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
    sleep     Enable/disable automatic sleep tracking (--on / --off)
    handraise Configure hand-raise detection (--on/--off, --hand, --wrist, --stim)
    button    Rebind one of the watch's hardware-button slots (--slot, --act)
    debug     Print a debug report (services, chars, descriptors, device info)
              for use in bug reports. Redirect to a file with `> debug.txt`.
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


def _redact_address(addr: Optional[str]) -> str:
    """Replace the last 3 octets of a MAC with XX so the OUI (manufacturer) is
    still visible for debugging but the unique device id isn't."""
    if not addr or len(addr) < 8:
        return "XX:XX:XX:XX:XX:XX"
    parts = addr.split(":")
    if len(parts) != 6:
        return "XX:XX:XX:XX:XX:XX"
    return ":".join(parts[:3] + ["XX", "XX", "XX"])


def _redact_name(name: Optional[str]) -> str:
    """Strip the unique suffix from a `Pavlok-3-XXXX` style name."""
    if not name:
        return "(unknown)"
    parts = name.split("-")
    if len(parts) >= 3:
        return "-".join(parts[:-1]) + "-XXXX"
    return name


def _redact_serial(value: bytes) -> bytes:
    """Replace any printable hex-id payload with X's of the same length."""
    return b"X" * len(value)


# Standard GATT characteristics that contain unique device identifiers we
# want to redact in censored debug reports.
_REDACT_CHAR_UUIDS = {
    "00002a00-0000-1000-8000-00805f9b34fb",  # GAP Device Name
    "00002a25-0000-1000-8000-00805f9b34fb",  # Serial Number String
}


async def _print_debug_report(device: "ShockDevice", censor: bool = False) -> None:
    """Dump a human-readable report describing the connected device: BLE name,
    all DIS strings, every service / characteristic / descriptor with read
    values. Designed to be redirected to a file so users with non-Pavlok-3
    devices can paste it into a bug report and we can extend support.

    Usage:
        python libreshock.py debug > libreshock-debug.txt
        python libreshock.py debug --censor > debug.txt   # safer for sharing
    """
    import datetime
    print("# LibreShock debug report")
    print(f"Generated: {datetime.datetime.now().isoformat(timespec='seconds')}")
    print(f"Library:   libreshock.py")
    if censor:
        print(f"Censored:  yes (BLE MAC, name suffix, serial redacted)")
    print()

    # Advertisement name + DIS strings
    print("## Device")
    ble_name = device.device.name if device.device is not None else None
    print(f"  ble_name: {_redact_name(ble_name) if censor else ble_name}")
    print(f"  address:  {_redact_address(device.address) if censor else device.address}")
    info = await device.read_device_info()
    for key, value in info.items():
        if censor and key == "serial" and isinstance(value, str):
            value = "X" * len(value)
        if censor and key == "name" and isinstance(value, str):
            value = _redact_name(value)
        print(f"  {key}: {value}")
    print()

    # Battery
    print("## Battery")
    pct = await device.read_battery()
    print(f"  level: {pct}%" if pct is not None else "  level: (read failed)")
    print()

    # Service tree with descriptors
    print("## GATT services")
    for service in device.client.services:
        print(f"SERVICE {service.uuid}")
        for char in service.characteristics:
            props = ",".join(char.properties)
            line = f"  CHAR {char.uuid}  handle=0x{char.handle:04x}  [{props}]"
            if "read" in char.properties:
                try:
                    val = await device.client.read_gatt_char(char.uuid)
                    if censor and str(char.uuid).lower() in _REDACT_CHAR_UUIDS:
                        val = _redact_serial(val)
                    ascii_part = "".join(chr(b) if 32 <= b < 127 else "." for b in val)
                    line += f"  value={val.hex()} ascii={ascii_part!r}"
                except Exception as e:
                    line += f"  read_error={e}"
            print(line)
            for desc in char.descriptors:
                dline = f"      DESC {desc.uuid}  handle=0x{desc.handle:04x}"
                try:
                    dval = await device.client.read_gatt_descriptor(desc.handle)
                    dline += f"  value={bytes(dval).hex()}"
                except Exception as e:
                    dline += f"  read_error={e}"
                print(dline)
    print()
    print("# end of report")


async def main():
    parser = argparse.ArgumentParser(
        description="LibreShock - BLE Shock Device Controller",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("action", nargs='?', default="help",
                        choices=["vibe", "beep", "zap", "led", "alarm", "stop", "snooze",
                                 "battery", "info", "sleep", "handraise", "button",
                                 "timer", "stopwatch", "debug", "status", "help"],
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
    parser.add_argument("--guarantor", type=str, default="none",
                        choices=["none", "jjacks", "jumping-jacks", "qr", "qr-code", "puzzle"],
                        help="Wake-up guarantor task that must be completed to stop the alarm "
                             "(default: none). Note: the Python CLI sets the flag on the watch; "
                             "actually completing a scan / puzzle requires the Android app.")
    parser.add_argument("--jjacks", type=int, default=5,
                        help="Required Jumping-Jacks reps when --guarantor=jjacks (1-20, default: 5)")

    # Additional wake-up feature flags. Each is independent; can be combined
    # with any guarantor.
    parser.add_argument("--snooze-zap", dest="snooze_zap", action="store_true",
                        help="Give a zap when the alarm is snoozed (requires --snooze)")
    parser.add_argument("--light-sleep", dest="light_sleep", action="store_true",
                        help="Watch monitors actigraphy and fires up to 20 min early if you're in light sleep")
    parser.add_argument("--escalating", action="store_true",
                        help="Stim intensity ramps up over time until you wake or hit the cap")
    parser.add_argument("--smart-alarm", dest="smart_alarm", action="store_true",
                        help="After dismiss, watch re-arms if it detects no motion (~5 min default, ~30 min total)")

    # Sleep-tracking + hand-raise shared on/off flags
    parser.add_argument("--on", dest="sleep_on", action="store_true",
                        help="With 'sleep'/'handraise' action: turn the feature on")
    parser.add_argument("--off", dest="sleep_off", action="store_true",
                        help="With 'sleep'/'handraise' action: turn the feature off")

    # Hand-raise specifics
    parser.add_argument("--hand", type=str, default="right", choices=["left", "right"],
                        help="Hand-raise: which hand the watch is on (default: right)")
    parser.add_argument("--wrist", type=str, default="outside", choices=["inside", "outside"],
                        help="Hand-raise: wrist position (default: outside)")
    parser.add_argument("--stim", type=str, default="vibrate",
                        choices=["vibrate", "vibe", "beep", "zap", "shock", "countdown"],
                        help="Hand-raise stimulus type (default: vibrate)")

    # Button rebinding
    parser.add_argument("--slot", type=str,
                        choices=list(BUTTON_SLOTS.keys()),
                        help="Button slot for the 'button' action")
    parser.add_argument("--act", type=str,
                        choices=list(BUTTON_ACTIONS),
                        help="Action to bind to the slot")

    # Debug-report options
    parser.add_argument("--censor", action="store_true",
                        help="With 'debug': redact identifying info (BLE MAC, "
                             "BLE name suffix, serial number)")

    # Timer & Stopwatch options
    parser.add_argument("--every", type=int, default=5, metavar="SEC",
                        help="With 'timer'/'stopwatch': stim interval in seconds (1-255, default: 5)")
    parser.add_argument("--duration", type=int, default=60, metavar="SEC",
                        help="With 'timer': countdown duration in seconds (default: 60). Ignored for 'stopwatch'.")

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
        elif args.action == "sleep":
            requested = None
            if args.sleep_on: requested = True
            elif args.sleep_off: requested = False
            if requested is None:
                print("Usage: python libreshock.py sleep --on    (enable sleep tracking)")
                print("       python libreshock.py sleep --off   (disable sleep tracking)")
            else:
                await device.set_sleep_tracking(requested)
        elif args.action == "button":
            if not args.slot or not args.act:
                print("Usage: python libreshock.py button --slot <slot> --act <action> [-c N] [-i N]")
                print("  slot: " + ", ".join(sorted(set(BUTTON_SLOTS))))
                print("  action: " + ", ".join(BUTTON_ACTIONS))
                print("  -c, --count   for vibrate/beep/zap (default 1)")
                print("  -i, --intensity  for vibrate/beep/zap (default 50)")
            else:
                await device.set_button(
                    slot=args.slot,
                    action=args.act,
                    count=args.count,
                    intensity=args.intensity,
                )
        elif args.action == "handraise":
            requested = None
            if args.sleep_on: requested = True
            elif args.sleep_off: requested = False
            if requested is None:
                print("Usage: python libreshock.py handraise --on [--hand left|right]")
                print("                              [--wrist inside|outside]")
                print("                              [--stim vibrate|beep|zap|countdown]")
                print("                              [-i N]    (intensity, zap only)")
                print("       python libreshock.py handraise --off")
            else:
                await device.set_hand_raise(
                    enabled=requested,
                    hand=args.hand,
                    wrist=args.wrist,
                    stimulus=args.stim,
                    intensity=args.intensity,
                )
        elif args.action == "debug":
            await _print_debug_report(device, censor=args.censor)
        elif args.action in ("timer", "stopwatch"):
            stim_map = {
                "vibe": TnsStim.VIBE, "vibrate": TnsStim.VIBE,
                "beep": TnsStim.BEEP,
                "zap": TnsStim.ZAP, "shock": TnsStim.ZAP,
            }
            stim = stim_map.get((args.stim or "zap").lower(), TnsStim.ZAP)
            mode = TnsMode.TIMER if args.action == "timer" else TnsMode.STOPWATCH
            duration = args.duration if mode == TnsMode.TIMER else 0
            cfg = TnsConfig(
                mode=mode,
                duration_seconds=duration,
                intervals=[TnsInterval(
                    stim=stim,
                    intensity=args.intensity,
                    every_seconds=args.every,
                )],
            )
            await device.set_tns_config(cfg)
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
                print("Alarms cleared.")
                print("Note: the official Pavlok app may still show these alarms "
                      "cached locally — they're no longer on the watch.")

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

                guarantor_map = {
                    "none": Guarantor.NONE,
                    "jjacks": Guarantor.JUMPING_JACKS,
                    "jumping-jacks": Guarantor.JUMPING_JACKS,
                    "qr": Guarantor.QR_CODE,
                    "qr-code": Guarantor.QR_CODE,
                    "puzzle": Guarantor.PUZZLE,
                }
                guarantor = guarantor_map[args.guarantor]

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
                                vibration=vibe_action, beep=beep_action, zap=zap_action,
                                guarantor=guarantor, jumping_jacks_count=args.jjacks,
                                snooze_zap=args.snooze_zap,
                                light_sleep=args.light_sleep,
                                escalating=args.escalating,
                                smart_alarm=args.smart_alarm,
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
                                vibration=vibe_action, beep=beep_action, zap=zap_action,
                                guarantor=guarantor, jumping_jacks_count=args.jjacks,
                                snooze_zap=args.snooze_zap,
                                light_sleep=args.light_sleep,
                                escalating=args.escalating,
                                smart_alarm=args.smart_alarm,
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
