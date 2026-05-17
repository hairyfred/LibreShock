"""Byte-exact verification of libreshock against the 5 captured stim combos."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from libreshock import AlarmConfig, AlarmAction, _build_alarm_hac_block

# Each tuple: (description, captured_block_hex, AlarmConfig kwargs, alarm_id)
CASES = [
    (
        "06:01 vibe-only (HA9)",
        "48413900414e0500616c61726d544d040000010680574401001e574902000f00534e010001414f0100014d4809004d430500850c32fafa494402000100",
        dict(hour=6, minute=1, name="alarm", weekdays=0, snooze=True, stimulus_interval=15,
             vibration=AlarmAction(enabled=True, count=5, intensity=50),
             beep=AlarmAction(enabled=False),
             zap=AlarmAction(enabled=False)),
        1,
    ),
    (
        "06:02 beep-only (HA9)",
        "48413900414e0500616c61726d544d040000020680574401001e574902000f00534e010001414f0100015048090050430500850c32fafa494402000200",
        dict(hour=6, minute=2, name="alarm", weekdays=0, snooze=True, stimulus_interval=15,
             vibration=AlarmAction(enabled=False),
             beep=AlarmAction(enabled=True, count=5, intensity=50),
             zap=AlarmAction(enabled=False)),
        2,
    ),
    (
        "06:03 zap-only (HA6)",
        "48413600414e0500616c61726d544d040000030680574401001e574902000f00534e010001414f0100015a4806005a4302008132494402000300",
        dict(hour=6, minute=3, name="alarm", weekdays=0, snooze=True, stimulus_interval=15,
             vibration=AlarmAction(enabled=False),
             beep=AlarmAction(enabled=False),
             zap=AlarmAction(enabled=True, count=1, intensity=50)),
        3,
    ),
    (
        "06:04 vibe+beep (HAF)",
        "48414600414e0500616c61726d544d040000040680574401001e574902000f00534e010001414f0100014d4809004d430500850c32fafa5048090050430500850c32fafa494402000400",
        dict(hour=6, minute=4, name="alarm", weekdays=0, snooze=True, stimulus_interval=15,
             vibration=AlarmAction(enabled=True, count=5, intensity=50),
             beep=AlarmAction(enabled=True, count=5, intensity=50),
             zap=AlarmAction(enabled=False)),
        4,
    ),
    (
        "06:05 beep+zap (HAC)",
        "48414300414e0500616c61726d544d040000050680574401001e574902000f00534e010001414f0100015048090050430500850c32fafa5a4806005a4302008132494402000500",
        dict(hour=6, minute=5, name="alarm", weekdays=0, snooze=True, stimulus_interval=15,
             vibration=AlarmAction(enabled=False),
             beep=AlarmAction(enabled=True, count=5, intensity=50),
             zap=AlarmAction(enabled=True, count=1, intensity=50)),
        5,
    ),
    # Day-of-week captures (TM flag byte = 0x80 | day_bit)
    (
        "06:11 Wednesday only (TM flag 0x88)",
        "48415000414e0500616c61726d544d040000110688574401001e574902000f00534e010001414f0100014d4809004d430500850c32fafa5048090050430500850c32fafa5a4806005a4302008132494402000100",
        dict(hour=6, minute=11, name="alarm", weekdays=0x08, snooze=True, stimulus_interval=15,
             vibration=AlarmAction(enabled=True, count=5, intensity=50),
             beep=AlarmAction(enabled=True, count=5, intensity=50),
             zap=AlarmAction(enabled=True, count=1, intensity=50)),
        1,
    ),
    (
        "06:12 Monday only (TM flag 0x82)",
        "48415000414e0500616c61726d544d040000120682574401001e574902000f00534e010001414f0100014d4809004d430500850c32fafa5048090050430500850c32fafa5a4806005a4302008132494402000200",
        dict(hour=6, minute=12, name="alarm", weekdays=0x02, snooze=True, stimulus_interval=15,
             vibration=AlarmAction(enabled=True, count=5, intensity=50),
             beep=AlarmAction(enabled=True, count=5, intensity=50),
             zap=AlarmAction(enabled=True, count=1, intensity=50)),
        2,
    ),
    (
        "06:13 Saturday only (TM flag 0xC0)",
        "48415000414e0500616c61726d544d0400001306c0574401001e574902000f00534e010001414f0100014d4809004d430500850c32fafa5048090050430500850c32fafa5a4806005a4302008132494402000300",
        dict(hour=6, minute=13, name="alarm", weekdays=0x40, snooze=True, stimulus_interval=15,
             vibration=AlarmAction(enabled=True, count=5, intensity=50),
             beep=AlarmAction(enabled=True, count=5, intensity=50),
             zap=AlarmAction(enabled=True, count=1, intensity=50)),
        3,
    ),
]


def main():
    all_match = True
    for name, captured_hex, cfg_kwargs, alarm_id in CASES:
        captured = bytes.fromhex(captured_hex.replace(" ", ""))
        ours = _build_alarm_hac_block(AlarmConfig(**cfg_kwargs), alarm_id=alarm_id)
        match = ours == captured
        print(f"{name}: {'[OK]' if match else '[DIFF]'}")
        if not match:
            all_match = False
            print(f"  Captured ({len(captured)}): {captured.hex()}")
            print(f"  Ours     ({len(ours)}): {ours.hex()}")
            for i in range(min(len(captured), len(ours))):
                if captured[i] != ours[i]:
                    print(f"    offset {i}: cap=0x{captured[i]:02x} ours=0x{ours[i]:02x}")
            if len(captured) != len(ours):
                print(f"    length mismatch")
    print()
    print("ALL MATCH" if all_match else "SOME MISMATCHES")


if __name__ == "__main__":
    main()
