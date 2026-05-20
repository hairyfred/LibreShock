"""Decode the watch's sleep-history response out of a btsnoop capture.

Reassembles fragmented notifications on the EVENTS char into the full
response stream, then walks the per-session TLV body to extract:
  - bedtime / wake-up plausible u32 timestamps
  - the chronological stage-segment timeline
  - per-stage totals

The point of this tool is to crack the Light vs REM split: the watch only
records 3 stages but the vendor app shows 4 (Awake/REM/Light/Deep). With
ground-truth screenshots we can hand-correlate stage bytes / segment
ordering against the published per-night percentages.
"""
import os
import struct
import sys
from datetime import datetime, timezone, timedelta

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from parse_btsnoop import parse_btsnoop, extract_att_commands

ATT_WRITE_REQ = 0x12
ATT_WRITE_CMD = 0x52
ATT_HANDLE_VALUE_NTF = 0x1B


def find_events_handle(att_packets):
    """Find the handle of the events char by looking for the 9-byte
    sleep-query write pattern: <0x03|0x82> <id:3> <00> <range:4>."""
    for p in att_packets:
        if p['direction'] != 'TX' or p['opcode'] not in (ATT_WRITE_REQ, ATT_WRITE_CMD):
            continue
        data = p['data']
        if len(data) != 12:
            continue
        handle = struct.unpack('<H', data[1:3])[0]
        value = data[3:]
        if len(value) == 9 and value[0] in (0x03, 0x82) and value[4] == 0x00:
            return handle
    return None


def find_sleep_query_pairs(att_packets, events_handle):
    """Find pairs of TX writes on events handle: start-range then end-range.

    Returns list of (start_pkt_num, end_pkt_num, query_byte, session_id).
    """
    queries = []
    pending_start = None
    for p in att_packets:
        if p['direction'] != 'TX' or p['opcode'] not in (ATT_WRITE_REQ, ATT_WRITE_CMD):
            continue
        data = p['data']
        if len(data) != 12:
            continue
        handle = struct.unpack('<H', data[1:3])[0]
        if handle != events_handle:
            continue
        value = data[3:]
        if len(value) != 9 or value[0] not in (0x03, 0x82) or value[4] != 0x00:
            continue
        rng = struct.unpack('<I', value[5:9])[0]
        sid = value[1] | (value[2] << 8) | (value[3] << 16)
        if rng == 0x00000000:
            pending_start = (p['num'], value[0], sid)
        elif rng == 0xFFFFFFFF and pending_start is not None:
            queries.append((pending_start[0], p['num'], pending_start[1], pending_start[2]))
            pending_start = None
    return queries


def reassemble_responses(att_packets, events_handle, queries):
    """For each query pair, reassemble notifications arriving AFTER the
    end-range write into a single response stream."""
    # Build an ordered list of (pkt_num, value) RX notifications on events.
    rx = []
    for p in att_packets:
        if p['direction'] != 'RX' or p['opcode'] != ATT_HANDLE_VALUE_NTF:
            continue
        data = p['data']
        if len(data) < 3:
            continue
        handle = struct.unpack('<H', data[1:3])[0]
        if handle != events_handle:
            continue
        rx.append((p['num'], data[3:]))

    responses = []
    for qi, (start_pkt, end_pkt, qtype, qsid) in enumerate(queries):
        # next query's end_pkt defines our upper bound
        next_bound = queries[qi + 1][0] if qi + 1 < len(queries) else float('inf')
        frags = [(n, v) for n, v in rx if end_pkt < n < next_bound]
        if not frags:
            continue
        # First fragment is the 14-byte header (sometimes inline with body
        # if short response, but usually exactly 14 bytes).
        first = frags[0][1]
        if len(first) < 14:
            continue
        op = first[0]
        if op == 0x14:
            # 14-op = response with no body (start-range ack). Skip.
            # We may instead want the NEXT 14-byte block as the real header.
            # Find it.
            header_frag = None
            body_frags = []
            for n, v in frags[1:]:
                if header_frag is None and len(v) >= 14 and v[1] == 0x00:
                    header_frag = (n, v)
                else:
                    if header_frag is not None:
                        body_frags.append((n, v))
            if header_frag is None:
                continue
            header_pkt, hv = header_frag
            byte_count = struct.unpack('<I', hv[10:14])[0]
            body = hv[14:]
            for _, v in body_frags:
                body += v
                if len(body) >= byte_count:
                    break
            body = body[:byte_count]
            responses.append({
                'query_type': qtype,
                'query_sid': qsid,
                'header': hv[:14],
                'byte_count': byte_count,
                'body': body,
                'header_pkt': header_pkt,
            })
        else:
            byte_count = struct.unpack('<I', first[10:14])[0]
            body = first[14:]
            for _, v in frags[1:]:
                body += v
                if len(body) >= byte_count:
                    break
            body = body[:byte_count]
            responses.append({
                'query_type': qtype,
                'query_sid': qsid,
                'header': first[:14],
                'byte_count': byte_count,
                'body': body,
                'header_pkt': frags[0][0],
            })
    return responses


def parse_sessions(body):
    """Split the all-sessions body into per-session records.

    Each record: <sub_op:1> 03 <length:u16-LE> <session_id:u32-LE>
                 <timestamp:u32-LE> <body bytes>
    sub_op = 0x3f for first, 0x7f for subsequent. timestamp is Unix UTC.
    """
    sessions = []
    pos = 0
    while pos + 12 <= len(body):
        op = body[pos]
        type_byte = body[pos + 1]
        if op not in (0x3f, 0x7f) or type_byte != 0x03:
            pos += 1
            continue
        length = struct.unpack('<H', body[pos + 2:pos + 4])[0]
        sid = struct.unpack('<I', body[pos + 4:pos + 8])[0]
        ts = struct.unpack('<I', body[pos + 8:pos + 12])[0]
        body_start = pos + 12
        body_end = body_start + length
        if body_end > len(body):
            break
        sessions.append({
            'sid': sid,
            'timestamp': ts,
            'body': body[body_start:body_end],
        })
        pos = body_end
    return sessions


def collect_stage_segments(body):
    """Recursive walk, return ordered list of (stage_byte, duration_secs).

    Descend into wrapper-shaped containers (any opcode with len >= 5 whose
    payload itself contains valid TLV).
    """
    segments = []

    def walk(buf, start, end):
        pos = start
        while pos + 2 <= end:
            op = buf[pos]
            ln = buf[pos + 1] if pos + 1 < end else 0
            if pos + 2 + ln > end:
                break
            if op == 0x10 and ln == 3:
                dur = buf[pos + 2] | (buf[pos + 3] << 8)
                stage = buf[pos + 4]
                segments.append((stage, dur))
            elif ln >= 5:
                walk(buf, pos + 2, pos + 2 + ln)
            pos += 2 + ln

    walk(body, 0, len(body))
    return segments


def find_timestamps_near(body, anchor, window_days=14):
    """Return all u32-LE values in `body` within ±window_days of anchor."""
    window = window_days * 86400
    lo = anchor - window
    hi = anchor + window
    out = []
    for i in range(len(body) - 4):
        val = struct.unpack('<I', body[i:i+4])[0]
        if lo <= val <= hi:
            out.append((i, val))
    return out


def fmt_ts(ts, tz_offset_hours=1):
    tz = timezone(timedelta(hours=tz_offset_hours))
    return datetime.fromtimestamp(ts, tz).strftime('%Y-%m-%d %H:%M:%S %Z')


def fmt_dur(secs):
    m, s = divmod(secs, 60)
    h, m = divmod(m, 60)
    if h:
        return f"{h}h{m:02d}m{s:02d}s"
    if m:
        return f"{m}m{s:02d}s"
    return f"{s}s"


STAGE_LABEL = {
    0x11: "0x11",
    0x13: "0x13",
    0x21: "0x21 (Deep?)",
    0x41: "0x41 (Awake?)",
    0x51: "0x51",
}


def stage_short(s):
    return STAGE_LABEL.get(s, f"0x{s:02x}")


def print_session(session, tz_offset_hours=1, verbose=False, focus_window=None):
    sid = session['sid']
    ts = session['timestamp']
    body = session['body']
    print(f"\n=== Session {sid} — anchor {fmt_ts(ts, tz_offset_hours)} "
          f"(ts={ts}, body={len(body)}B) ===")

    timestamps = find_timestamps_near(body, ts)
    pruned = [(i, t) for i, t in timestamps if t != ts]
    if pruned:
        print(f"  Embedded timestamps (BST):")
        for off, t in pruned[:20]:
            print(f"    @0x{off:04x}: {fmt_ts(t, tz_offset_hours)}")
    segments = collect_stage_segments(body)
    counts = {}
    sums = {}
    for s, d in segments:
        counts[s] = counts.get(s, 0) + 1
        sums[s] = sums.get(s, 0) + d
    print(f"  Stage segments: {len(segments)} total")
    for s in sorted(counts.keys()):
        print(f"    {stage_short(s)}: {counts[s]} segs, "
              f"{fmt_dur(sums[s])} ({sums[s]}s)")
    total = sum(sums.values())
    print(f"    TOTAL classified: {fmt_dur(total)} ({total}s)")
    if verbose:
        # Print the chronological timeline of segments (compact)
        print(f"  Chronological segments (stage : duration):")
        for i, (s, d) in enumerate(segments):
            print(f"    [{i:3d}] {stage_short(s):14s} {fmt_dur(d)}")


def main():
    if len(sys.argv) < 2:
        print("Usage: decode_sleep_capture.py <btsnoop> [--verbose] [--sid N]")
        sys.exit(1)
    path = sys.argv[1]
    verbose = '--verbose' in sys.argv
    focus_sid = None
    if '--sid' in sys.argv:
        focus_sid = int(sys.argv[sys.argv.index('--sid') + 1])

    print(f"Parsing {path}")
    pkts = parse_btsnoop(path)
    att = extract_att_commands(pkts)
    events_handle = find_events_handle(att)
    if events_handle is None:
        print("Could not find events char handle in capture.")
        sys.exit(1)
    print(f"Events handle: 0x{events_handle:04x}")

    queries = find_sleep_query_pairs(att, events_handle)
    print(f"Found {len(queries)} sleep-query pair(s):")
    for (s, e, q, sid) in queries:
        print(f"  start_pkt={s} end_pkt={e} qtype=0x{q:02x} sid={sid}")
    responses = reassemble_responses(att, events_handle, queries)
    print(f"Reassembled {len(responses)} response stream(s).")
    for r in responses:
        print(f"  - header_pkt={r['header_pkt']}, qtype=0x{r['query_type']:02x}, "
              f"qsid={r['query_sid']}, header={r['header'].hex()}, "
              f"body={len(r['body'])}B (declared {r['byte_count']})")

    for ri, r in enumerate(responses):
        sessions = parse_sessions(r['body'])
        if not sessions:
            continue
        print(f"\n--- Response #{ri}: {len(sessions)} session(s) ---")
        for s in sessions:
            if focus_sid is not None and s['sid'] != focus_sid:
                continue
            print_session(s, verbose=verbose)


if __name__ == '__main__':
    main()
