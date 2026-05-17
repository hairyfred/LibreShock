"""Extract every full alarm-write transaction from a btsnoop and reassemble each packet.

A transaction is delimited by:
  CTRL <- 01 00 + profile   (enter write mode)
  ... chunked writes to DATA handle ...
  CTRL <- 00 00 + profile   (exit write mode)

Outputs each reassembled packet plus a parse of its alarm blocks.
"""
import struct
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from parse_btsnoop import parse_btsnoop, extract_att_commands

ATT_WRITE_REQ = 0x12
ATT_WRITE_CMD = 0x52


def find_transactions(att_packets):
    """Find alarm-write transactions in the ATT stream.
    Returns list of (ctrl_handle, data_handle, profile, packet_bytes)."""
    # Identify the CTRL/DATA handles dynamically by looking for the enter-write-mode pattern.
    # The CTRL write payload is `01 00 <profile_bytes>`.
    transactions = []
    i = 0
    while i < len(att_packets):
        pkt = att_packets[i]
        if pkt['direction'] != 'TX' or pkt['opcode'] not in (ATT_WRITE_REQ, ATT_WRITE_CMD):
            i += 1
            continue

        data = pkt['data']
        if len(data) < 5:
            i += 1
            continue

        handle = struct.unpack('<H', data[1:3])[0]
        value = data[3:]

        # Enter-write-mode: starts with 01 00 followed by profile name (ASCII printable)
        if not (len(value) >= 4 and value[0] == 0x01 and value[1] == 0x00):
            i += 1
            continue
        profile = value[2:]
        if not all(32 <= b < 127 for b in profile):
            i += 1
            continue

        ctrl_handle = handle
        chunks = []
        data_handle = None
        j = i + 1
        while j < len(att_packets):
            p2 = att_packets[j]
            if p2['direction'] != 'TX' or p2['opcode'] not in (ATT_WRITE_REQ, ATT_WRITE_CMD):
                j += 1
                continue
            if len(p2['data']) < 3:
                j += 1
                continue
            h2 = struct.unpack('<H', p2['data'][1:3])[0]
            v2 = p2['data'][3:]

            if h2 == ctrl_handle and len(v2) >= 2 and v2[0] == 0x00 and v2[1] == 0x00:
                break
            if h2 == ctrl_handle:
                j += 1
                continue

            # Only start collecting once we've seen the AH-prefixed first chunk.
            # Earlier writes (e.g. enabling CCCD notify) are ignored.
            if data_handle is None:
                if len(v2) >= 2 and v2[:2] == b'AH':
                    data_handle = h2
                    chunks.append(v2)
            elif h2 == data_handle:
                if len(v2) > 0:
                    chunks.append(v2)
            j += 1

        packet = b''.join(chunks)
        if packet.startswith(b'AH'):
            transactions.append({
                'ctrl_handle': ctrl_handle,
                'data_handle': data_handle,
                'profile': profile,
                'packet': packet,
                'first_pkt_num': pkt['num'],
            })
        i = j + 1

    return transactions


def _bcd_to_int(v: int) -> int:
    return ((v >> 4) * 10) + (v & 0x0F)


def parse_alarm_block(content: bytes, pos: int):
    header = content[pos:pos+4]
    block = {"header": header[:3].decode("ascii", errors="replace"), "fields": {}}
    pos += 4
    while pos + 4 <= len(content):
        tag = content[pos:pos+2]
        if not (tag[0:1].isalpha() and tag[1:2].isalpha()):
            break
        if tag in (b"HA",):
            break
        length = struct.unpack("<H", content[pos+2:pos+4])[0]
        value = content[pos+4:pos+4+length]
        block["fields"][tag.decode("ascii")] = value
        pos += 4 + length
        if tag.decode("ascii") == "ID":
            return block, pos
    return block, pos


def summarize_alarm(block):
    f = block['fields']
    tm = f.get('TM', b'')
    if len(tm) == 4:
        time_str = f"{_bcd_to_int(tm[2]):02d}:{_bcd_to_int(tm[1]):02d}"
        tm_flag = f"flag=0x{tm[3]:02x}"
    else:
        time_str = "??:??"
        tm_flag = "tm=?"
    wd = f.get('WD', b'\x00')[0]
    wi = struct.unpack('<H', f.get('WI', b'\x00\x00'))[0]
    sn = f.get('SN', b'\x00')[0]
    ao = f.get('AO', b'\x00')[0]
    alarm_id = struct.unpack('<H', f.get('ID', b'\x00\x00'))[0]

    parts = [
        f"id={alarm_id}",
        f"time={time_str}",
        tm_flag,
        f"WD=0x{wd:02x}",
        f"WI={wi}s",
        f"SN={sn}",
        f"AO={ao}",
    ]
    if 'MH' in f:
        mc = f['MH'][4:]
        parts.append(f"MH(MC={mc.hex()})")
    if 'PH' in f:
        pc = f['PH'][4:]
        parts.append(f"PH(PC={pc.hex()})")
    if 'ZH' in f:
        zc = f['ZH'][4:]
        parts.append(f"ZH(ZC={zc.hex()})")

    return f"  [{block['header']}] " + " ".join(parts)


def parse_full_packet(packet: bytes):
    """Parse a full AH packet and yield each alarm block."""
    assert packet[:2] == b'AH'
    length = struct.unpack('<H', packet[2:4])[0]
    crc = struct.unpack('<H', packet[4:6])[0]
    pos = 6
    if packet[pos:pos+2] == b'AP':
        ap_len = struct.unpack('<H', packet[pos+2:pos+4])[0]
        profile = packet[pos+4:pos+4+ap_len]
        pos += 4 + ap_len
    else:
        profile = b'?'

    info = {'length': length, 'crc': crc, 'profile': profile, 'alarms': []}
    while pos < len(packet):
        marker = packet[pos:pos+2]
        if marker != b'HA':
            break
        block, pos = parse_alarm_block(packet, pos)
        info['alarms'].append(block)
    return info


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else 'captures/combos_5.btsnoop'
    print(f"Parsing: {path}")
    packets = parse_btsnoop(path)
    att = extract_att_commands(packets)
    transactions = find_transactions(att)
    print(f"Found {len(transactions)} alarm-write transactions\n")

    dump_raw = '--raw' in sys.argv
    for idx, tx in enumerate(transactions, 1):
        info = parse_full_packet(tx['packet'])
        print(f"=== Transaction {idx} (first packet #{tx['first_pkt_num']}) ===")
        print(f"  AH length=0x{info['length']:04x} crc=0x{info['crc']:04x} profile={info['profile']!r}")
        print(f"  Total packet bytes: {len(tx['packet'])}, alarms: {len(info['alarms'])}")
        for a in info['alarms']:
            print(summarize_alarm(a))
        if dump_raw:
            print(f"  Full packet hex: {tx['packet'].hex()}")
            # Split into raw alarm blocks
            buf = tx['packet']
            # Skip AH(6) + AP TLV
            ap_pos = buf.find(b'AP')
            ap_len = buf[ap_pos+2] | (buf[ap_pos+3] << 8)
            pos = ap_pos + 4 + ap_len
            i = 0
            while pos + 4 <= len(buf):
                if buf[pos:pos+2] != b'HA':
                    pos += 1
                    continue
                clen = buf[pos+2] | (buf[pos+3] << 8)
                full = buf[pos:pos+4+clen]
                i += 1
                print(f"  alarm #{i} block ({len(full)} bytes): {full.hex()}")
                pos += 4 + clen
        print()


if __name__ == '__main__':
    main()
