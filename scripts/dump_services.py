"""One-off: dump every service/char on the connected device and try reading
each readable char. Use to find the right UUID for the Time char.

The capture showed handle 0x001D returning an 8-byte BCD timestamp like
13 50 12 17 00 05 26 04 — find the UUID in the dump whose value looks
similar (first byte ~ seconds_bcd, third byte ~ hour_bcd).
"""
import asyncio
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from libreshock import ShockDevice


async def main():
    device = ShockDevice()
    if not await device.connect():
        return
    try:
        client = device.client
        for service in client.services:
            print(f"\nSERVICE {service.uuid}")
            for char in service.characteristics:
                props = ",".join(char.properties)
                line = f"  CHAR {char.uuid}  handle=0x{char.handle:04x}  [{props}]"
                if "read" in char.properties:
                    try:
                        val = await client.read_gatt_char(char.uuid)
                        ascii_part = "".join(chr(b) if 32 <= b < 127 else "." for b in val)
                        line += f"  value={val.hex()} ascii={ascii_part!r}"
                    except Exception as e:
                        line += f"  read_error={e}"
                print(line)
    finally:
        await device.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
