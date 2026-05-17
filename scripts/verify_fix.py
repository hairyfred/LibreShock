"""Generate alarms with the fixed libreshock and compare against captured vendor packet.

We can't byte-match the multi-alarm capture exactly (different IDs/WI/counts), but we
can confirm structural alignment: HAC vs HAP headers, TM byte order, presence of PH.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from libreshock import (
    AlarmConfig, AlarmAction, Weekday, _build_alarm_hac_block, _build_alarm_packet,
)


def hex_bytes(b: bytes) -> str:
    return b.hex()


def show(name: str, block: bytes):
    print(f"\n=== {name} ===")
    print(f"len={len(block)} header={block[:3]!r} hex={block.hex()}")


# Vibe + zap only (no beep) — should generate HAC header, no PH block
config_vibe_zap = AlarmConfig(
    hour=7, minute=30, name="alarm",
    weekdays=0x1E, snooze=True, stimulus_interval=9,
    vibration=AlarmAction(enabled=True, count=5, intensity=50),
    beep=AlarmAction(enabled=False),
    zap=AlarmAction(enabled=True, count=3, intensity=50),
)

# All 3 stims — should generate HAP header with PH block
config_all_three = AlarmConfig(
    hour=10, minute=28, name="alarm",
    weekdays=0x1E, snooze=True, stimulus_interval=15,
    vibration=AlarmAction(enabled=True, count=5, intensity=50),
    beep=AlarmAction(enabled=True, count=5, intensity=50),
    zap=AlarmAction(enabled=True, count=1, intensity=50),
)

block_hac = _build_alarm_hac_block(config_vibe_zap, alarm_id=2)
block_hap = _build_alarm_hac_block(config_all_three, alarm_id=4)

show("Vibe+Zap (expect HAC header)", block_hac)
show("All 3 stims (expect HAP header)", block_hap)

# Compare against captured alarm 2 (vibe+zap @ 7:20, id=1, WI=9, WD=0x1E, zap_count=3, intensity=50)
captured_alarm2 = bytes.fromhex(
    "48414300"                                  # HAC\x00
    "414e0500616c61726d"                        # AN "alarm"
    "544d040000200700"                          # TM 00 20 07 00 (7:20, flag=0x00 for default)
    "574401001e"                                # WD 0x1E
    "574902000900"                              # WI 9
    "534e010001"                                # SN 1
    "414f010001"                                # AO 1
    "4d4809004d430500850c32fafa"                # MH/MC
    "5a4806005a4302008332"                      # ZH/ZC (count=3, intensity=50)
    "494402000100"                              # ID 1
)
print(f"\n=== Captured alarm 2 (vendor app) ===")
print(f"len={len(captured_alarm2)} hex={captured_alarm2.hex()}")

# Now build ours with same params: 7:20, id=1, weekdays=0x1E, WI=9, zap count=3
config_match_alarm2 = AlarmConfig(
    hour=7, minute=20, name="alarm",
    weekdays=0x1E, snooze=True, stimulus_interval=9,
    vibration=AlarmAction(enabled=True, count=5, intensity=50),
    beep=AlarmAction(enabled=False),
    zap=AlarmAction(enabled=True, count=3, intensity=50),
)
our_alarm2 = _build_alarm_hac_block(config_match_alarm2, alarm_id=1)
print(f"\n=== Our alarm matching vendor alarm 2 ===")
print(f"len={len(our_alarm2)} hex={our_alarm2.hex()}")

# The capture's alarm 2 has TM flag byte = 0x00 (it's a stored default alarm), but
# our code emits 0x80 (newly armed). Both produced by vendor app in different contexts;
# we'll set 0x80 since that's what the vendor uses for fresh/edited alarms.
# Compare with vendor TM byte at position +7 forced to 0x80 to match our convention:
fixed = bytearray(captured_alarm2)
tm_pos = fixed.find(b'TM')
fixed[tm_pos + 7] = 0x80
print(f"\n=== Captured alarm 2 with TM flag forced to 0x80 (to match our convention) ===")
print(f"len={len(fixed)} hex={bytes(fixed).hex()}")

if our_alarm2 == bytes(fixed):
    print("\n[OK] HAC packet byte-matches captured (TM flag normalized to 0x80)")
else:
    print("\n[DIFF] differences in HAC:")
    for i in range(min(len(our_alarm2), len(fixed))):
        if our_alarm2[i] != fixed[i]:
            print(f"  offset {i}: ours=0x{our_alarm2[i]:02x} captured=0x{fixed[i]:02x}")
    if len(our_alarm2) != len(fixed):
        print(f"  length mismatch: ours={len(our_alarm2)} captured={len(fixed)}")

# Compare against captured alarm 4 (HAP, all 3 stims @ 10:28, id=4, WI=15, zap_count=1)
captured_alarm4 = bytes.fromhex(
    "48415000"                                  # HAP\x00
    "414e0500616c61726d"                        # AN "alarm"
    "544d040000281080"                          # TM 10:28 with 0x80 flag
    "574401001e"                                # WD 0x1E
    "574902000f00"                              # WI 15
    "534e010001"                                # SN 1
    "414f010001"                                # AO 1
    "4d4809004d430500850c32fafa"                # MH/MC
    "504809005043050085" "0c32fafa"             # PH/PC (flags=0x85)
    "5a4806005a4302008132"                      # ZH/ZC (count=1, intensity=50)
    "494402000400"                              # ID 4
)
config_match_alarm4 = AlarmConfig(
    hour=10, minute=28, name="alarm",
    weekdays=0x1E, snooze=True, stimulus_interval=15,
    vibration=AlarmAction(enabled=True, count=5, intensity=50),
    beep=AlarmAction(enabled=True, count=5, intensity=50),
    zap=AlarmAction(enabled=True, count=1, intensity=50),
)
our_alarm4 = _build_alarm_hac_block(config_match_alarm4, alarm_id=4)
print(f"\n=== Captured alarm 4 (HAP, vendor app) ===")
print(f"len={len(captured_alarm4)} hex={captured_alarm4.hex()}")
print(f"\n=== Our HAP for same config ===")
print(f"len={len(our_alarm4)} hex={our_alarm4.hex()}")

if our_alarm4 == captured_alarm4:
    print("\n[OK] HAP packet BYTE-EXACT match with captured vendor packet")
else:
    print("\n[DIFF] differences in HAP:")
    for i in range(min(len(our_alarm4), len(captured_alarm4))):
        if our_alarm4[i] != captured_alarm4[i]:
            print(f"  offset {i}: ours=0x{our_alarm4[i]:02x} captured=0x{captured_alarm4[i]:02x}")
    if len(our_alarm4) != len(captured_alarm4):
        print(f"  length mismatch: ours={len(our_alarm4)} captured={len(captured_alarm4)}")
