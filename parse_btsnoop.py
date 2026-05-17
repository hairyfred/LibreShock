"""
Parse btsnoop_hci.log to extract BLE ATT Write commands
Looking for commands sent to Pavlok device
"""

import struct
import sys
from datetime import datetime

# Known handles from our device scan
KNOWN_HANDLES = {
    0x000F: "vibe (1001)",
    0x0012: "beep (1002)",
    0x0015: "zap (1003)",
    0x0019: "led (1004)",
    0x0059: "a_ctrl (5001)",
    0x005D: "a_write (5002)",
    0x0061: "a_ntfy (5003)",
    0x0088: "sys_7 (0007)",
    0x008B: "sys_8 (0008)",
    0x002D: "events (2002)",
    0x008F: "7001",
    0x0092: "7999",
}

# ATT opcodes we care about
ATT_WRITE_REQ = 0x12
ATT_WRITE_CMD = 0x52
ATT_PREP_WRITE_REQ = 0x16
ATT_EXEC_WRITE_REQ = 0x18
ATT_READ_REQ = 0x0A
ATT_READ_RSP = 0x0B
ATT_HANDLE_VALUE_NTF = 0x1B

ATT_OPCODES = {
    0x01: "ERROR_RSP",
    0x02: "EXCHANGE_MTU_REQ",
    0x03: "EXCHANGE_MTU_RSP",
    0x04: "FIND_INFO_REQ",
    0x05: "FIND_INFO_RSP",
    0x06: "FIND_BY_TYPE_REQ",
    0x07: "FIND_BY_TYPE_RSP",
    0x08: "READ_BY_TYPE_REQ",
    0x09: "READ_BY_TYPE_RSP",
    0x0A: "READ_REQ",
    0x0B: "READ_RSP",
    0x0C: "READ_BLOB_REQ",
    0x0D: "READ_BLOB_RSP",
    0x10: "READ_BY_GROUP_REQ",
    0x11: "READ_BY_GROUP_RSP",
    0x12: "WRITE_REQ",
    0x13: "WRITE_RSP",
    0x16: "PREP_WRITE_REQ",
    0x17: "PREP_WRITE_RSP",
    0x18: "EXEC_WRITE_REQ",
    0x19: "EXEC_WRITE_RSP",
    0x1B: "HANDLE_VALUE_NTF",
    0x1D: "HANDLE_VALUE_IND",
    0x1E: "HANDLE_VALUE_CFM",
    0x52: "WRITE_CMD",
}


def parse_btsnoop(filename):
    """Parse btsnoop_hci.log file"""
    with open(filename, 'rb') as f:
        # Read file header (16 bytes)
        header = f.read(16)
        magic = header[:8]
        if magic != b'btsnoop\x00':
            print(f"Invalid btsnoop file (magic: {magic})")
            return []

        version = struct.unpack('>I', header[8:12])[0]
        datalink = struct.unpack('>I', header[12:16])[0]
        print(f"btsnoop version: {version}, datalink: {datalink}")

        packets = []
        packet_num = 0

        while True:
            # Read packet header (24 bytes)
            pkt_header = f.read(24)
            if len(pkt_header) < 24:
                break

            orig_len, incl_len, flags, drops, ts = struct.unpack('>IIIIQ', pkt_header)

            # Read packet data
            data = f.read(incl_len)
            if len(data) < incl_len:
                break

            packet_num += 1

            # Parse HCI packet
            # flags: bit 0 = direction (0=sent, 1=received), bit 1 = command/event
            direction = "TX" if (flags & 1) == 0 else "RX"

            packets.append({
                'num': packet_num,
                'direction': direction,
                'flags': flags,
                'timestamp': ts,
                'data': data
            })

        return packets


def extract_att_commands(packets):
    """Extract ATT layer commands from HCI packets"""
    att_packets = []

    for pkt in packets:
        data = pkt['data']

        # HCI packet types
        if len(data) < 1:
            continue

        hci_type = data[0]

        # We're interested in ACL data (type 0x02)
        if hci_type != 0x02:
            continue

        if len(data) < 5:
            continue

        # ACL header: handle (2 bytes), length (2 bytes)
        acl_handle = struct.unpack('<H', data[1:3])[0] & 0x0FFF
        acl_len = struct.unpack('<H', data[3:5])[0]

        if len(data) < 5 + acl_len:
            continue

        acl_data = data[5:5+acl_len]

        # L2CAP header: length (2 bytes), CID (2 bytes)
        if len(acl_data) < 4:
            continue

        l2cap_len = struct.unpack('<H', acl_data[0:2])[0]
        l2cap_cid = struct.unpack('<H', acl_data[2:4])[0]

        # CID 0x0004 = ATT
        if l2cap_cid != 0x0004:
            continue

        if len(acl_data) < 4 + l2cap_len:
            continue

        att_data = acl_data[4:4+l2cap_len]

        if len(att_data) < 1:
            continue

        att_opcode = att_data[0]

        att_packets.append({
            'num': pkt['num'],
            'direction': pkt['direction'],
            'timestamp': pkt['timestamp'],
            'opcode': att_opcode,
            'opcode_name': ATT_OPCODES.get(att_opcode, f"0x{att_opcode:02X}"),
            'data': att_data
        })

    return att_packets


def analyze_writes(att_packets):
    """Analyze ATT Write commands"""
    print("\n" + "=" * 70)
    print("ATT WRITE COMMANDS (sent to device)")
    print("=" * 70)

    writes = []
    for pkt in att_packets:
        if pkt['direction'] != 'TX':
            continue

        opcode = pkt['opcode']
        if opcode not in [ATT_WRITE_REQ, ATT_WRITE_CMD, ATT_PREP_WRITE_REQ]:
            continue

        data = pkt['data']
        if len(data) < 3:
            continue

        handle = struct.unpack('<H', data[1:3])[0]
        value = data[3:]

        handle_name = KNOWN_HANDLES.get(handle, f"0x{handle:04X}")

        writes.append({
            'num': pkt['num'],
            'opcode': pkt['opcode_name'],
            'handle': handle,
            'handle_name': handle_name,
            'value': value
        })

        print(f"[{pkt['num']:5d}] {pkt['opcode_name']:12s} -> {handle_name:20s} : {value.hex()}")

    return writes


def analyze_notifications(att_packets):
    """Analyze ATT notifications received"""
    print("\n" + "=" * 70)
    print("ATT NOTIFICATIONS (received from device)")
    print("=" * 70)

    for pkt in att_packets:
        if pkt['direction'] != 'RX':
            continue

        if pkt['opcode'] != ATT_HANDLE_VALUE_NTF:
            continue

        data = pkt['data']
        if len(data) < 3:
            continue

        handle = struct.unpack('<H', data[1:3])[0]
        value = data[3:]

        handle_name = KNOWN_HANDLES.get(handle, f"0x{handle:04X}")
        print(f"[{pkt['num']:5d}] NOTIFY <- {handle_name:20s} : {value.hex()}")


def main():
    if len(sys.argv) < 2:
        # Default to the bug report location
        filename = "C:/Users/XD/Downloads/bugreport-komodo-BP2A.250805.005-2026-01-29-13-29-10/FS/data/misc/bluetooth/logs/btsnoop_hci.log"
    else:
        filename = sys.argv[1]

    print(f"Parsing: {filename}")
    print("-" * 70)

    packets = parse_btsnoop(filename)
    print(f"Total HCI packets: {len(packets)}")

    att_packets = extract_att_commands(packets)
    print(f"ATT packets: {len(att_packets)}")

    # Show all ATT activity summary
    print("\n" + "=" * 70)
    print("ATT ACTIVITY SUMMARY")
    print("=" * 70)
    opcode_counts = {}
    for pkt in att_packets:
        key = (pkt['direction'], pkt['opcode_name'])
        opcode_counts[key] = opcode_counts.get(key, 0) + 1

    for (direction, opcode), count in sorted(opcode_counts.items()):
        print(f"  {direction} {opcode}: {count}")

    # Analyze writes (the main thing we need)
    writes = analyze_writes(att_packets)

    # Also show notifications
    analyze_notifications(att_packets)

    # Summary of unique write commands
    print("\n" + "=" * 70)
    print("UNIQUE WRITE COMMANDS (potential trigger commands)")
    print("=" * 70)

    unique_writes = {}
    for w in writes:
        key = (w['handle_name'], w['value'].hex())
        if key not in unique_writes:
            unique_writes[key] = w

    for (handle, value), w in sorted(unique_writes.items()):
        print(f"  {handle:25s} <- {value}")


if __name__ == "__main__":
    main()
