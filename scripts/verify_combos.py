"""Byte-exact verification of libreshock against the 5 captured stim combos."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from libreshock import AlarmConfig, AlarmAction, Guarantor, _build_alarm_hac_block

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
    # Alarm guarantors (May 19 2026 captures). Captured 8am alarm with
    # weekdays + vibe(50)+zap(5x70%) and a guarantor task selected.
    # Stim block bytes vary by guarantor: JJ adds JL TLV (HAH=72), QR/Puzzle
    # have no extra TLV (HAC=67).
    (
        "08:00 Jumping Jacks=1 rep (HAH, AO=0x02 + JL)",
        "48414800414e0500616c61726d544d040000000880574401001e574902000500534e010001414f0100024a4c0100014d4809004d430500850c32fafa5a4806005a4302008546494402000200",
        dict(hour=8, minute=0, name="alarm", weekdays=0, snooze=True, stimulus_interval=5,
             vibration=AlarmAction(enabled=True, count=5, intensity=50),
             beep=AlarmAction(enabled=False),
             zap=AlarmAction(enabled=True, count=5, intensity=70),
             guarantor=Guarantor.JUMPING_JACKS, jumping_jacks_count=1),
        2,
    ),
    (
        "08:00 Jumping Jacks=2 reps (HAH, AO=0x02 + JL)",
        "48414800414e0500616c61726d544d040000000880574401001e574902000500534e010001414f0100024a4c0100024d4809004d430500850c32fafa5a4806005a4302008546494402000200",
        dict(hour=8, minute=0, name="alarm", weekdays=0, snooze=True, stimulus_interval=5,
             vibration=AlarmAction(enabled=True, count=5, intensity=50),
             beep=AlarmAction(enabled=False),
             zap=AlarmAction(enabled=True, count=5, intensity=70),
             guarantor=Guarantor.JUMPING_JACKS, jumping_jacks_count=2),
        2,
    ),
    (
        "08:00 Jumping Jacks=3 reps (HAH, AO=0x02 + JL)",
        "48414800414e0500616c61726d544d040000000880574401001e574902000500534e010001414f0100024a4c0100034d4809004d430500850c32fafa5a4806005a4302008546494402000200",
        dict(hour=8, minute=0, name="alarm", weekdays=0, snooze=True, stimulus_interval=5,
             vibration=AlarmAction(enabled=True, count=5, intensity=50),
             beep=AlarmAction(enabled=False),
             zap=AlarmAction(enabled=True, count=5, intensity=70),
             guarantor=Guarantor.JUMPING_JACKS, jumping_jacks_count=3),
        2,
    ),
    (
        "08:00 QR code guarantor (HAC, AO=0x04)",
        "48414300414e0500616c61726d544d040000000880574401001e574902000500534e010001414f0100044d4809004d430500850c32fafa5a4806005a4302008546494402000200",
        dict(hour=8, minute=0, name="alarm", weekdays=0, snooze=True, stimulus_interval=5,
             vibration=AlarmAction(enabled=True, count=5, intensity=50),
             beep=AlarmAction(enabled=False),
             zap=AlarmAction(enabled=True, count=5, intensity=70),
             guarantor=Guarantor.QR_CODE),
        2,
    ),
    (
        "08:00 Puzzle unlock guarantor (HAC, AO=0x80)",
        "48414300414e0500616c61726d544d040000000880574401001e574902000500534e010001414f0100804d4809004d430500850c32fafa5a4806005a4302008546494402000200",
        dict(hour=8, minute=0, name="alarm", weekdays=0, snooze=True, stimulus_interval=5,
             vibration=AlarmAction(enabled=True, count=5, intensity=50),
             beep=AlarmAction(enabled=False),
             zap=AlarmAction(enabled=True, count=5, intensity=70),
             guarantor=Guarantor.PUZZLE),
        2,
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
