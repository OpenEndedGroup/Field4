"""Independent decoders for the Art-Net / sACN packets Field's afield.dmx sends.

Written from the protocol documents, not from the Kotlin encoder, so it can disagree with it.
Three modes, all listening on 127.0.0.1:

  artnet      hand-rolled struct parse of ArtDmx / ArtSync / ArtPoll / ArtPollReply
  sacn        hand-rolled struct parse of E1.31 data / sync / discovery packets
  sacn-lib    the third-party `sacn` package (pip: sacn) as the receiver

Usage (see dev_docs/dmx_lighting.md for the recorded runs):

  uv run --no-project python dev_docs/dmx/independent_decode.py artnet --port 16454 --seconds 5
  uv run --no-project python dev_docs/dmx/independent_decode.py sacn --port 15568 --seconds 5
  uv run --no-project --with sacn python dev_docs/dmx/independent_decode.py sacn-lib --port 15568 --universe 1 --seconds 5
"""
import argparse
import socket
import struct
import sys
import time


def first(data, n=12):
    return list(data[:n])


def decode_artnet(pkt):
    if len(pkt) < 10 or pkt[:8] != b"Art-Net\x00":
        return "not Art-Net"
    (op,) = struct.unpack_from("<H", pkt, 8)  # OpCode: low byte first
    if op == 0x5000:
        protver, seq, phys, subuni, net, length = struct.unpack_from(">HBBBBH", pkt, 10)
        data = pkt[18:18 + length]
        assert len(data) == length, "length field disagrees with datagram"
        assert length % 2 == 0 and 2 <= length <= 512, "ArtDmx length must be even, 2..512"
        port_address = (net << 8) | subuni
        return (f"ArtDmx protver={protver} seq={seq} phys={phys} net={net} subnet={subuni >> 4} "
                f"universe={subuni & 15} port-address={port_address} length={length} first={first(data)}")
    if op == 0x5200:
        return f"ArtSync len={len(pkt)}"
    if op == 0x2000:
        return f"ArtPoll len={len(pkt)} flags=0x{pkt[12]:02x}"
    if op == 0x2100:
        short = pkt[26:44].split(b"\x00")[0].decode()
        (port,) = struct.unpack_from("<H", pkt, 14)
        return f"ArtPollReply len={len(pkt)} ip={'.'.join(map(str, pkt[10:14]))} port=0x{port:04x} short={short!r}"
    return f"Art-Net opcode 0x{op:04x}"


ACN_ID = b"ASC-E1.17\x00\x00\x00"


def decode_sacn(pkt):
    pre, post = struct.unpack_from(">HH", pkt, 0)
    if pre != 0x10 or post != 0 or pkt[4:16] != ACN_ID:
        return "not ACN"
    fl_root, root_vec = struct.unpack_from(">HI", pkt, 16)
    assert fl_root >> 12 == 0x7 and fl_root & 0xFFF == len(pkt) - 16, "root flags/length"
    cid = pkt[22:38].hex()
    fl_frame, frame_vec = struct.unpack_from(">HI", pkt, 38)
    assert fl_frame >> 12 == 0x7 and fl_frame & 0xFFF == len(pkt) - 38, "framing flags/length"
    if root_vec == 4 and frame_vec == 2:
        name = pkt[44:108].split(b"\x00")[0].decode()
        prio, sync, seq, opts, universe = struct.unpack_from(">BHBBH", pkt, 108)
        fl_dmp, vec, adt, first_addr, incr, count = struct.unpack_from(">HBBHHH", pkt, 115)
        assert fl_dmp >> 12 == 0x7 and fl_dmp & 0xFFF == len(pkt) - 115, "DMP flags/length"
        assert vec == 0x02 and adt == 0xA1 and first_addr == 0 and incr == 1, "DMP header"
        start = pkt[125]
        data = pkt[126:126 + count - 1]
        assert len(data) == count - 1
        return (f"E1.31 data cid={cid[:8]}.. name={name!r} prio={prio} sync={sync} seq={seq} "
                f"opts=0x{opts:02x} universe={universe} start=0x{start:02x} slots={len(data)} first={first(data)}")
    if root_vec == 8 and frame_vec == 1:
        seq, sync = struct.unpack_from(">BH", pkt, 44)
        return f"E1.31 sync seq={seq} sync-address={sync} len={len(pkt)}"
    if root_vec == 8 and frame_vec == 2:
        name = pkt[44:108].split(b"\x00")[0].decode()
        page, last = pkt[118], pkt[119]
        universes = list(struct.unpack_from(f">{(len(pkt) - 120) // 2}H", pkt, 120))
        return f"E1.31 discovery name={name!r} page={page}/{last} universes={universes}"
    return f"E1.31 root=0x{root_vec:x} framing=0x{frame_vec:x}"


def listen(port, seconds, decode, limit):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", port))
    s.settimeout(0.2)
    end = time.time() + seconds
    n = 0
    t0 = None
    print(f"listening on 127.0.0.1:{port} for {seconds}s", flush=True)
    while time.time() < end and n < limit:
        try:
            pkt, addr = s.recvfrom(2000)
        except socket.timeout:
            continue
        n += 1
        now = time.time()
        t0 = t0 or now
        try:
            print(f"{n:3d} t=+{now - t0:6.3f}s {len(pkt):4d}B  {decode(pkt)}", flush=True)
        except AssertionError as e:
            print(f"{n:3d} MALFORMED: {e}", flush=True)
    print(f"received {n} packets", flush=True)


def sacn_lib(port, universe, seconds):
    import sacn  # third-party
    receiver = sacn.sACNreceiver(bind_address="127.0.0.1", bind_port=port)
    got = []

    @receiver.listen_on("universe", universe=universe)
    def on_packet(packet):
        got.append(packet)
        print(f"sacn-lib universe={packet.universe} source={packet.sourceName!r} prio={packet.priority} "
              f"seq={packet.sequence} slots={len(packet.dmxData)} first={list(packet.dmxData[:12])}", flush=True)

    @receiver.listen_on("availability")
    def on_availability(universe, changed):
        print(f"sacn-lib availability universe={universe} {changed}", flush=True)

    receiver.start()
    print(f"sacn library receiving on 127.0.0.1:{port} universe {universe} for {seconds}s", flush=True)
    time.sleep(seconds)
    receiver.stop()
    print(f"sacn-lib received {len(got)} packets", flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["artnet", "sacn", "sacn-lib"])
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--seconds", type=float, default=5)
    ap.add_argument("--universe", type=int, default=1)
    ap.add_argument("--limit", type=int, default=40)
    a = ap.parse_args()
    if a.mode == "artnet":
        listen(a.port, a.seconds, decode_artnet, a.limit)
    elif a.mode == "sacn":
        listen(a.port, a.seconds, decode_sacn, a.limit)
    else:
        sacn_lib(a.port, a.universe, a.seconds)
    sys.exit(0)
