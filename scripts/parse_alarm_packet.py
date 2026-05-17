"""Reassemble and decode an alarm packet captured from btsnoop.

Concatenates the 20-byte chunks the vendor app writes to the alarm DATA
characteristic and parses the AH/AP/HAC|HAP/TLV structure.
"""
import struct

CAPTURED_CHUNKS_4ALARM_WRITE = [
    "41483701574c4150080053696e676c6520314841",
    "4300414e0500616c61726d544d04000012070057",
    "4401001e574902000900534e010001414f010001",
    "4d4809004d430500850c32fafa5a4806005a4302",
    "00833249440200020048414300414e0500616c61",
    "726d544d040000200700574401001e5749020009",
    "00534e010001414f0100014d4809004d43050085",
    "0c32fafa5a4806005a4302008332494402000100",
    "48414300414e0500616c61726d544d0400000009",
    "00574401001e574902000900534e010001414f01",
    "00014d4809004d430500850c32fafa5a4806005a",
    "430200833249440200030048415000414e050061",
    "6c61726d544d040000281080574401001e574902",
    "000f00534e010001414f0100014d4809004d4305",
    "00850c32fafa5048090050430500850c32fafa5a",
    "4806005a4302008132494402000400",
]


def _bcd_to_int(v: int) -> int:
    return ((v >> 4) * 10) + (v & 0x0F)


def parse_alarm_block(content: bytes, pos: int) -> tuple[dict, int]:
    """Parse a single alarm block (HAC or HAP header) starting at pos.
    Returns (parsed dict, position of next block start)."""
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
        tag_str = tag.decode("ascii")
        block["fields"][tag_str] = value
        pos += 4 + length

        if tag_str == "ID":
            return block, pos

    return block, pos


def decode_tm(tm: bytes) -> str:
    if len(tm) != 4:
        return f"len={len(tm)}!=4 raw={tm.hex()}"
    b0, b1, b2, b3 = tm
    return (
        f"raw={tm.hex()} | "
        f"minute_bcd=0x{b1:02x}({_bcd_to_int(b1)}) "
        f"hour_bcd=0x{b2:02x}({_bcd_to_int(b2)}) "
        f"flag=0x{b3:02x}"
    )


def main():
    raw = bytes.fromhex("".join(CAPTURED_CHUNKS_4ALARM_WRITE))
    print(f"Total bytes: {len(raw)}")
    print(f"Hex: {raw.hex()}\n")

    assert raw[:2] == b"AH", "Missing AH header"
    length = struct.unpack("<H", raw[2:4])[0]
    crc = struct.unpack("<H", raw[4:6])[0]
    print(f"AH length field: 0x{length:04x} ({length})")
    print(f"AH CRC field:    0x{crc:04x}")
    print(f"Content bytes after AH header: {len(raw) - 6} (expected {length - 2})\n")

    pos = 6
    assert raw[pos:pos+2] == b"AP", "Missing AP block"
    ap_len = struct.unpack("<H", raw[pos+2:pos+4])[0]
    profile = raw[pos+4:pos+4+ap_len]
    print(f"AP profile: len={ap_len} value={profile!r}")
    pos += 4 + ap_len

    alarm_num = 1
    while pos < len(raw):
        marker = raw[pos:pos+2]
        if marker != b"HA":
            print(f"\n[stop] non-HA marker at pos {pos}: {marker.hex()}")
            break
        block, pos = parse_alarm_block(raw, pos)
        print(f"\n--- Alarm {alarm_num} ({block['header']}) ---")
        for tag, val in block["fields"].items():
            if tag == "TM":
                print(f"  {tag} ({len(val)}): {decode_tm(val)}")
            elif tag == "WD":
                mask = val[0] if val else 0
                day_names = [n for bit, n in [
                    (0x01, "Sun"), (0x02, "Mon"), (0x04, "Tue"), (0x08, "Wed"),
                    (0x10, "Thu"), (0x20, "Fri"), (0x40, "Sat"),
                ] if mask & bit]
                print(f"  {tag} ({len(val)}): 0x{mask:02x} = {','.join(day_names)}")
            elif tag in ("MH", "PH", "ZH"):
                inner = val
                inner_tag = inner[:2].decode("ascii", errors="replace") if len(inner) >= 2 else ""
                inner_len = struct.unpack("<H", inner[2:4])[0] if len(inner) >= 4 else 0
                inner_val = inner[4:4+inner_len]
                print(f"  {tag} ({len(val)}): contains {inner_tag} len={inner_len} value={inner_val.hex()}")
            elif tag in ("AN",):
                print(f"  {tag} ({len(val)}): {val.decode('utf-8', errors='replace')!r}")
            else:
                print(f"  {tag} ({len(val)}): {val.hex()}")
        alarm_num += 1


if __name__ == "__main__":
    main()
