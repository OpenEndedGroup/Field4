#!/usr/bin/env python3
"""Assert the bytes `field-encode` actually puts on the media wire.

Drives the real helper over its control channel and reads real datagrams off a UDP socket, so this
covers the whole producer-side path — control-channel decode, threading the pose through the async
VideoToolbox callback, header assembly — not just the header builder. A unit test of the builder
alone would pass with the pose dropped in the encoder.

Two phases:

  tagged    alloc + open + one `frame` carrying a pose, one without. Asserts POSE_VALID is set with
            the exact big-endian values on the first and clear with a zeroed tail on the second.
            Frames are blank, so they arrive as a single fragment.
  selftest  the helper's own animated test pattern, which compresses to *many* fragments. Asserts
            every fragment of a multi-fragment access unit carries a full v2 header, which is the
            geometry claim in WIRE_FORMAT.md ("the tail rides every fragment").

Run after any change to the wire, and after rebuilding the helper — a stale binary silently ignores
newer control fields, so a pose you set would just never appear.

Usage: python3 tools/rtt_wire_check.py [port]
"""
import json, socket, struct, subprocess, sys, threading, time

HELPER = "native/field-encode/.build/release/field-encode"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9411

WIRE_V2, HEADER_V2, HEADER_V1 = 2, 36, 20
FLAG_KEYFRAME, FLAG_FRAME_END, FLAG_POSE = 1 << 0, 1 << 1, 1 << 4

POSE_ID = 0x0102030405060708          # every byte distinct: catches byte-order and offset slips
POSE_NANOS = 1234567890123456789


class Capture:
    def __init__(self, port, timeout=6.0):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20)
        self.sock.bind(("127.0.0.1", port))
        self.sock.settimeout(timeout)
        self.packets = []
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self):
        while True:
            try:
                self.packets.append(self.sock.recv(65535))
            except (socket.timeout, OSError):
                return

    def stop(self):
        self.sock.close()
        self.thread.join(timeout=8)


class Helper:
    def __init__(self, *args):
        self.proc = subprocess.Popen([HELPER, *args], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     text=True, bufsize=1)
        self.events = []
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.proc.stdout:
            line = line.strip()
            if line:
                self.events.append(line)

    def send(self, obj):
        self.proc.stdin.write(json.dumps(obj) + "\n")
        self.proc.stdin.flush()

    def wait_for(self, ev, timeout=12.0):
        end = time.time() + timeout
        while time.time() < end:
            for line in list(self.events):
                try:
                    if json.loads(line).get("ev") == ev:
                        return json.loads(line)
                except Exception:
                    pass
            if self.proc.poll() is not None:
                break
            time.sleep(0.05)
        return None

    def finish(self):
        try:
            self.proc.wait(timeout=6)
        except subprocess.TimeoutExpired:
            self.proc.kill()


def parse(datagram):
    """Parse a header the way the receiver does: version first, then the length it implies."""
    assert len(datagram) >= HEADER_V1, f"runt datagram of {len(datagram)} bytes"
    version, flags, codec, reserved = datagram[0], datagram[1], datagram[2], datagram[3]
    assert version in (1, WIRE_V2), f"unknown wire version {version}"
    assert reserved == 0, f"reserved byte not zero: {reserved}"
    header = HEADER_V2 if version == WIRE_V2 else HEADER_V1
    assert len(datagram) >= header, \
        f"v{version} datagram must carry a {header}-byte header, got {len(datagram)}"
    seq, frag_index, frag_count, pts = struct.unpack_from(">IHHQ", datagram, 4)
    pose_id = pose_nanos = None
    if version == WIRE_V2:
        pose_id, pose_nanos = struct.unpack_from(">QQ", datagram, 20)
    return dict(version=version, flags=flags, codec=codec, seq=seq, frag_index=frag_index,
                frag_count=frag_count, pts=pts, pose_id=pose_id, pose_nanos=pose_nanos,
                header=header, size=len(datagram))


def group(packets):
    out = {}
    for p in packets:
        h = parse(p)
        out.setdefault(h["seq"], []).append(h)
    return out


def phase_tagged():
    print("== phase: tagged frames ==")
    cap = Capture(PORT)
    h = Helper()
    h.send({"cmd": "alloc", "width": 640, "height": 480, "count": 2})
    assert h.wait_for("allocated"), f"no 'allocated'; events={h.events}"
    h.send({"cmd": "open", "width": 640, "height": 480, "fps": 60, "bitrateKbps": 8000,
            "dest": f"127.0.0.1:{PORT}", "codec": "h264", "idrIntervalMs": 250})
    assert h.wait_for("opened"), f"no 'opened'; events={h.events}"

    h.send({"cmd": "frame", "surface": 0, "seq": 0, "ptsMicros": 0, "forceIdr": True,
            "poseFrameId": POSE_ID, "poseDisplayTimeNanos": POSE_NANOS})
    time.sleep(0.6)
    h.send({"cmd": "frame", "surface": 1, "seq": 1, "ptsMicros": 16666})
    time.sleep(1.0)
    h.send({"cmd": "close"})
    h.finish()
    cap.stop()

    frames = group(cap.packets)
    assert 0 in frames, f"the tagged frame never arrived; seqs={sorted(frames)}"
    for f in frames[0]:
        assert f["version"] == WIRE_V2, "the macOS sender must emit v2"
        assert f["flags"] & FLAG_POSE, f"POSE_VALID missing (flags=0x{f['flags']:02x})"
        assert f["pose_id"] == POSE_ID, f"poseFrameId mangled: {f['pose_id']:#x}"
        assert f["pose_nanos"] == POSE_NANOS, f"poseDisplayTimeNanos mangled: {f['pose_nanos']}"
        assert f["flags"] & FLAG_KEYFRAME, "forceIdr frame lost KEYFRAME"
    print(f"  seq 0: POSE_VALID set, values exact big-endian, on all {len(frames[0])} fragment(s)")

    if 1 in frames:
        for f in frames[1]:
            assert not f["flags"] & FLAG_POSE, "POSE_VALID set on an untagged frame"
            assert f["pose_id"] == 0 and f["pose_nanos"] == 0, "untagged tail must be zeroed"
        print(f"  seq 1: POSE_VALID clear, tail zeroed, on all {len(frames[1])} fragment(s)")
    else:
        print("  seq 1 not captured — untagged case not verified this run")


def phase_selftest():
    print("== phase: selftest (multi-fragment) ==")
    cap = Capture(PORT)
    h = Helper()
    h.send({"cmd": "selftest", "width": 1280, "height": 720, "fps": 60, "frames": 60,
            "bitrateKbps": 20000, "codec": "h264", "dest": f"127.0.0.1:{PORT}"})
    time.sleep(3.5)
    h.send({"cmd": "close"})
    h.finish()
    cap.stop()

    frames = group(cap.packets)
    assert frames, f"selftest produced no datagrams; events={h.events[:5]}"
    multi = {s: f for s, f in frames.items() if f[0]["frag_count"] > 1}
    assert multi, (f"no multi-fragment frame in {len(frames)} frame(s); "
                   f"max fragCount={max(f[0]['frag_count'] for f in frames.values())}")
    complete = 0
    for seq, f in multi.items():
        counts = {x["frag_count"] for x in f}
        assert len(counts) == 1, f"seq {seq}: fragCount disagrees across fragments: {counts}"
        for x in f:
            assert x["version"] == WIRE_V2, f"seq {seq} frag {x['frag_index']}: not v2"
            assert x["header"] == HEADER_V2, f"seq {seq} frag {x['frag_index']}: short header"
        if len(f) == f[0]["frag_count"]:
            complete += 1
            ends = [x for x in f if x["flags"] & FLAG_FRAME_END]
            assert len(ends) == 1, f"seq {seq}: expected one FRAME_END, got {len(ends)}"
            assert ends[0]["frag_index"] == f[0]["frag_count"] - 1, \
                f"seq {seq}: FRAME_END is not the last fragment"
    biggest = max(f[0]["frag_count"] for f in multi.values())
    print(f"  {len(multi)} multi-fragment frame(s), up to {biggest} fragments, "
          f"{complete} fully captured — every fragment carried a full v2 header")


def main():
    phase_tagged()
    phase_selftest()
    print("rtt-wire-check ok")


if __name__ == "__main__":
    main()
