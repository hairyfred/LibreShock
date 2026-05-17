"""Chronological dump of ATT writes and notifications, filtered to interesting handles.

Useful for narrating what happened around an event like an alarm firing.
"""
import sys
import os
import struct

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from parse_btsnoop import parse_btsnoop, extract_att_commands

INTERESTING = {
    0x005A: "CTRL    (a_ctrl)",
    0x005E: "DATA    (a_data)",
    0x005F: "DATA_CCCD",
    0x0062: "NOTIFY  (a_ntfy)",
    0x002E: "EVENTS  (2002)",
    0x0030: "EVENTS_CCCD",
    0x001D: "events_1d?",
}


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else 'captures/alarm_fire_stop.btsnoop'
    pkt_min = int(sys.argv[2]) if len(sys.argv) > 2 else 1000
    pkt_max = int(sys.argv[3]) if len(sys.argv) > 3 else 1600
    packets = parse_btsnoop(path)
    att = extract_att_commands(packets)
    for p in att:
        if p['num'] < pkt_min or p['num'] > pkt_max:
            continue
        if len(p['data']) < 3:
            continue
        handle = struct.unpack('<H', p['data'][1:3])[0]
        if handle not in INTERESTING:
            continue
        value = p['data'][3:]
        arrow = '<-' if p['direction'] == 'RX' else '->'
        label = INTERESTING.get(handle, f"0x{handle:04X}")
        print(f"[{p['num']:5d}] {arrow} {label:20s} {p['opcode_name']:18s} {value.hex()}")


if __name__ == "__main__":
    main()
