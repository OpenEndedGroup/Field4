# OSC and TUIO: interop measurements

The evidence behind `public/docs/osc.md`, measured 2026-09-27 on macOS (Darwin 25.5, Apple
silicon) against branch `nightly/2026-09-27/3-osc-tuio-docs`. The public page's figures are
Field-to-Field loopback; this page is the independent half: the same exchanges with
[python-osc](https://pypi.org/project/python-osc/) 1.10.2 (no code shared with JavaOSC) and with
SuperCollider 3.12.2's `sclang` run headless, plus the defects those exchanges found.

Before this work nothing in the repository called `osc(...)`, `oscClient(...)`, `OscServer`,
`OscClient` or `TuioReceiver`: no test, no example, no template. `TuioReceiver.start()` could not
have been run by anybody, because it threw on every call.

## Method

A Field started on a dedicated port (`PORT=8473 AFIELD_SKIP_WORKSPACE_PERSIST=1 ./gradlew runJvm
--args="--workspace <tmp>"`), and boxes run into it with
`tools/field_backdoor.py --json --port 8473 --attach-existing`. A box registers its handlers and
returns; the backdoor invocation then `sleep`s while the peer sends, and `output @last` collects
what the handlers printed. Timing matters: the peer has to send inside the `sleep` window, or the
handler's `println` reaches a box whose session has already detached (the handler still runs — see
"Handlers outlive the box" below).

UDP ports 57300–57399 only. The peer is one script, run with
`uv run --with python-osc python peer.py <command> <port>`:

```python
"""Independent OSC peer for Field interop checks (python-osc)."""
import sys, time, threading
from pythonosc.udp_client import SimpleUDPClient
from pythonosc.osc_message_builder import OscMessageBuilder
from pythonosc.osc_bundle_builder import OscBundleBuilder, IMMEDIATELY
from pythonosc.dispatcher import Dispatcher
from pythonosc.osc_server import ThreadingOSCUDPServer
from pythonosc.parsing import osc_types

def send_typed(port, addr, typed):
    b = OscMessageBuilder(address=addr)
    for tag, v in typed:
        b.add_arg(v, tag)
    SimpleUDPClient("127.0.0.1", port).send(b.build())

cmd = sys.argv[1]
if cmd == "types":
    port = int(sys.argv[2])
    send_typed(port, "/types", [("i", 7), ("f", 0.25), ("s", "hello"), ("b", b"\x01\x02\x03"),
                                ("h", 2**40), ("d", 0.1), ("T", True), ("F", False), ("N", None), ("r", 0x11223344),
                                ("m", (0, 0x90, 60, 100))])
    print("sent /types i f s b h d T F N r m")
elif cmd == "send":
    port = int(sys.argv[2]); addr = sys.argv[3]
    args = [eval(a) for a in sys.argv[4:]]
    SimpleUDPClient("127.0.0.1", port).send_message(addr, args)
    print("sent", addr, args)
elif cmd == "listen":
    port = int(sys.argv[2]); secs = float(sys.argv[3])
    d = Dispatcher()
    def h(addr, *args):
        print("recv", addr, [(type(a).__name__, a) for a in args], flush=True)
    d.set_default_handler(h)
    srv = ThreadingOSCUDPServer(("127.0.0.1", port), d)
    # raw typetag view
    import socketserver
    orig = srv.finish_request
    def fr(request, client_address):
        data = request[0]
        try:
            i = data.index(b",")
            j = data.index(b"\0", i)
            print("typetag", data[:data.index(b'\0')].decode(), data[i:j].decode(), flush=True)
        except ValueError:
            print("raw", data[:64], flush=True)
        orig(request, client_address)
    srv.finish_request = fr
    t = threading.Thread(target=srv.serve_forever, daemon=True); t.start()
    print("listening", port, flush=True)
    time.sleep(secs); srv.shutdown()
elif cmd == "bundle":
    port = int(sys.argv[2]); delay = float(sys.argv[3])
    bb = OscBundleBuilder(IMMEDIATELY if delay == 0 else time.time() + delay)
    for i in range(3):
        m = OscMessageBuilder(address="/bundle/item"); m.add_arg(i); m.add_arg(time.time(), "d"); bb.add_content(m.build())
    SimpleUDPClient("127.0.0.1", port).send(bb.build())
    print("sent bundle of 3, timetag", "IMMEDIATELY" if delay == 0 else f"now+{delay}s", "at", time.time())
elif cmd == "variants":
    port = int(sys.argv[2])
    send_typed(port, "/types", [("h", 2**40), ("d", 0.1)])
    send_typed(port, "/types", [("i", 5), ("b", b"\x01\x02\x03")])
    send_typed(port, "/types", [("b", b"\x01\x02\x03"), ("i", 9)])
    send_typed(port, "/types", [("b", b"\x01\x02\x03\x04"), ("i", 9)])
    print("sent variants")
elif cmd == "patterns":
    port = int(sys.argv[2]); c = SimpleUDPClient("127.0.0.1", port)
    for a in ["/fader/1", "/fader/2", "/fader/10", "/a/b/c"]:
        c.send_message(a, [1]); time.sleep(0.2)
    print("sent patterns")
elif cmd == "tuio":
    port = int(sys.argv[2]); c = SimpleUDPClient("127.0.0.1", port)
    def frame(fseq, alive, sets, profile="/tuio/2Dcur", src=True):
        bb = OscBundleBuilder(IMMEDIATELY)
        def msg(*args):
            m = OscMessageBuilder(address=profile)
            for a in args: m.add_arg(a)
            bb.add_content(m.build())
        if src: msg("source", "peer.py@127.0.0.1")
        msg("alive", *alive)
        for s in sets: msg("set", *s)
        msg("fseq", fseq)
        c.send(bb.build())
    frame(1, [11], [(11, 0.25, 0.5, 0.0, 0.0, 0.0)])
    frame(2, [11, 12], [(11, 0.30, 0.5, 0.05, 0.0, 0.0), (12, 0.75, 0.25, 0.0, 0.0, 0.0)])
    frame(3, [12], [(12, 0.75, 0.30, 0.0, 0.05, 0.0)])
    frame(4, [], [])
    frame(5, [40], [(40, 3, 0.5, 0.5, 1.57, 0.0, 0.0, 0.0, 0.0, 0.0)], profile="/tuio/2Dobj")
    print("sent 5 TUIO 1.1 frames")
```

## Python to Field: argument types

Box on 57311 printing `tags[i]`, class and value for each argument
(`m.event.message.info.argumentTypeTags`). Peer: `peer.py types 57311`, which sends
`i f s b h d T F N r m`.

After the fix:

```text
types[0] tag=i Integer = 7
types[1] tag=f Float = 0.25
types[2] tag=s String = hello
types[3] tag=b HeapByteBufferR = java.nio.HeapByteBufferR[pos=0 lim=3 cap=3]
types[4] tag=h Long = 1099511627776
types[5] tag=d Double = 0.1
types[6] tag=T Boolean = true
types[7] tag=F Boolean = false
types[8] tag=N null = null
types[9] tag=r OSCColor = com.illposed.osc.argument.OSCColor@25e1a6f1
types[10] tag=m OSCMidiMessage = com.illposed.osc.argument.OSCMidiMessage@24dff537
```

**Before the fix** the same send gave `types[4] Long = 72623842526232832` and
`types[5] Double = 5.2821946E-315`. Isolating it (`peer.py variants`): `(b 01 02 03, i 9)` arrived as
`(blob, 16909056)` — `0x01020300`, the blob's own bytes — and `(b 01 02 03 04, i 9)` as
`(blob, 16909060)`. `(h, d)` without a blob decoded correctly. Cause: JavaOSC 0.9's
`BlobArgumentHandler.parse` slices the blob but never advances the buffer past it. Fixed in
`osc.kt` (`AdvancingBlobArgumentHandler`, installed by `openOscPortIn`).

`c` is not in the list because python-osc 1.10.2 cannot *build* a `c` argument
(`ValueError: arg_type must be one of ('f', 'd', 'i', 'h', 'b', 's', 'r', 'm', 'T', 'F', 'N')`).

## Field to Python: what each Kotlin type becomes on the wire

Peer `peer.py listen 57320 30` prints the raw type tag string of each datagram. Box:

```kotlin
val out = oscClient("127.0.0.1", 57320)
out.send("/kotlin/literals", 1, 0.5, 0.5f, 2L, true, false, "s", null)
out.send("/kotlin/bytes", byteArrayOf(1, 2, 3))
out.send("/kotlin/char", 'x')
out.send("/kotlin/list", listOf(1, 2))
```

```text
typetag /kotlin/literals ,idfhTFsN
recv /kotlin/literals [('int', 1), ('float', 0.5), ('float', 0.5), ('int', 2), ('bool', True), ('bool', False), ('str', 's'), ('NoneType', None)]
typetag /kotlin/bytes ,b
recv /kotlin/bytes [('bytes', b'\x01\x02\x03')]
typetag /kotlin/char ,c
WARNING:root:Unhandled parameter type: c
recv /kotlin/char []
typetag /kotlin/list ,ii
recv /kotlin/list [('int', 1), ('int', 2)]
```

So a bare Kotlin `0.5` goes out as `d`, a `Long` as `h`, a `Char` as `c` (which python-osc drops),
and a `List` passed to `send` is spread into the argument list rather than sent as an OSC array.

## SuperCollider, both directions

`sclang` 3.12.2 headless (`/Applications/SuperCollider.app/Contents/MacOS/sclang sc2.scd`, about
10 s to compile its class library), against a box listening on 57361:

```text
(
OSCdef(\fromField, { |msg|
    ("from Field: " ++ msg ++ " classes " ++ msg.collect(_.class)).postln;
}, '/field/hello');
fork {
    NetAddr("127.0.0.1", 57361).sendMsg("/sc/fader", 1, 0.5, "hi", 2.5e10);
    "sc sent /sc/fader".postln;
    40.wait;
    0.exit;
};
)
```

The Field box that received printed:

```text
from sclang: /sc/fader tags=ifsf [Integer:1, Float:0.5, String:hi, Float:2.5E10]
```

A second box sent `oscClient("127.0.0.1", 57120).send("/field/hello", 1, 0.5f, 0.5, 2L, "text", true)`
to sclang's default `langPort` (57120, printed by sclang as `sclang langPort: 57120`). sclang printed:

```text
from Field: [ /field/hello, 1, 0.5, 0.5, h, , true ] classes [ class Symbol, class Integer, class Float, class Float, class Char, class Symbol, class True ]
```

sclang reads `d` (the `0.5` Double arrives as a `Float`) and `T`. It does **not** read `h`: the
`Long` arrives as the character `h` and the `String` after it as an empty symbol — the argument
stream is misaligned from the `h` on. This is why the public page tells readers to send `Int`,
`Float` and `String` to other programs.

## Address patterns

Box on 57312 with handlers on `/fader/*`, `/fader/?`, `/fader/[1-2]`, `/fader/{1,10}`, `//c`;
`peer.py patterns 57312` sends `/fader/1`, `/fader/2`, `/fader/10`, `/a/b/c`:

```text
star <- /fader/1 [1]
qmark <- /fader/1 [1]
range <- /fader/1 [1]
alt <- /fader/1 [1]
star <- /fader/2 [1]
qmark <- /fader/2 [1]
range <- /fader/2 [1]
star <- /fader/10 [1]
deep <- /a/b/c [1]
```

`/fader/{1,10}` does not fire for `/fader/10`. Reading `OSCPatternAddressMessageSelector.matches`
in the JavaOSC 0.9 sources: after alternative `1` consumes the `1` of `10` and the remainder fails,
the comma branch backtracks the string but steps the pattern index one past the next alternative's
first character. Fixed in `osc.kt` without touching JavaOSC's matcher: `BraceExpandingSelector`
expands every `{…}` into the plain patterns it stands for and matches each with JavaOSC's own
selector, so `*`, `?`, `[…]` and `//` behave exactly as before.
`OscLoopbackTest.anAlternativeThatExtendsAnotherStillMatches` pins it; with the old selector swapped
back in it fails with `expected:<[/fader/1, /fader/10]> but was:<[/fader/1]>`. The public page's
patterns figure now shows `/fader/10` matching `/fader/{1,10}`.

## Bundles and timetags

Peer `peer.py bundle <port> <delay>` sends three messages in one bundle, each carrying python's
`time.time()` as a `d`; the box prints arrival minus that time. (A first attempt sent the time as
python's default `f`, float32, whose resolution at 1.79e9 is 128 s — the "lag" it produced was
noise.)

**Before the fix**, a bundle stamped 1.5 s ahead and one stamped 10 s ahead both arrived after
0.001 s. One stamped 3605 s ahead arrived after **4.992 s**. So JavaOSC 0.9 dispatches a future
bundle exactly 3600 s early: its `OSCTimeTag64.EPOCH_START_JAVA_TIME_0` is `-2208992400000`, where
1900-01-01T00:00Z is `-2208988800000`. Fixed in `osc.kt` (`TimetagDispatcher`, which schedules from
the raw NTP seconds). After the fix:

```text
bundle item 0 arrived 0.001 s after python built it     (IMMEDIATELY)
bundle item 1 arrived 0.002 s after python built it
bundle item 2 arrived 0.002 s after python built it
bundle item 0 arrived 1.500 s after python built it     (now + 1.5 s)
bundle item 1 arrived 1.500 s after python built it
bundle item 2 arrived 1.500 s after python built it
```

The same epoch error affects `OSCTimeTag64.valueOf(Date)` and a `t` argument converted to a `Date`;
the public page's bundle figure builds its timetag from raw NTP seconds for that reason.

## TUIO

Peer `peer.py tuio 57341` sends five TUIO 1.1 bundles (`source`, `alive`, `set`…, `fseq`): a cursor
11 alone, 11 and 12, 12 alone, nothing, then a `/tuio/2Dobj` frame with object 40, class 3.

**Before the fix** `TuioReceiver(57340) { … }.start()` failed at once with
`Cannot invoke "java.util.List.stream()" because "listeners" is null` — JavaOSC 0.9's
`OSCPortInBuilder.addMessageListener` looks for a dispatcher in a listener list that is still null
on a fresh builder. After the fix:

```text
tuio fseq=1 src=peer.py@127.0.0.1 +cur=[11] ~cur=[] -cur=[] +obj=[] alive=[11]
tuio fseq=2 src=peer.py@127.0.0.1 +cur=[12] ~cur=[11] -cur=[] +obj=[] alive=[11, 12]
tuio fseq=3 src=peer.py@127.0.0.1 +cur=[] ~cur=[12] -cur=[11] +obj=[] alive=[12]
tuio fseq=4 src=peer.py@127.0.0.1 +cur=[] ~cur=[] -cur=[12] +obj=[] alive=[]
tuio fseq=5 src=peer.py@127.0.0.1 +cur=[] ~cur=[] -cur=[] +obj=[40/class3/angle1.57] alive=[]
```

(The jvmTest first caught a smaller defect here: a session first seen in a frame was listed as both
added and updated. Fixed.)

**A second receiver on the same port got nothing.** With the receiver above still bound (its box's
session gone), a new box started `TuioReceiver(57341) { println("SECOND receiver …") }` and the peer
sent the five frames again. The new box printed nothing; the old receiver's listener received all of
them. Fixed: `start()` now closes the receiver previously started on its port.
`OscLoopbackTest.startingASecondTuioReceiverOnAPortClosesTheFirst` is the regression test, and with
the fix commented out it fails with `expected:<[7]> but was:<[]>` — the same behaviour, reproduced in
a jvmTest.

## A port another program holds

python-osc listening on `127.0.0.1:57330` first, then a box: `osc(57330)` returned a server with
`isListening = true`, no error. `peer.py send 57330 /who "to 127.0.0.1"` was received by python,
not by Field. After python exited, the same send reached Field (`OSC: /who = after python left` on
Field's console). Cause: JavaOSC's `UDPTransport` sets `SO_REUSEADDR` before binding. python-osc
does not, so the reverse order (Field first) makes *python's* bind fail with `Errno 48`. Documented
as a warning; not changed, because refusing a shared bind would break programs that rely on it.

## Handlers outlive the box

- Run 1 of a box registered `/old`; the edited run 2 registered `/new`; a second box sent both. Both
  fired, the stale `/old` printing `run 1: /old fired` into the box.
- Handlers whose box's backdoor session had detached kept firing: the server log shows their
  output being released to a session that no longer exists (`send-failed-release`).
- `s.close()` then `osc(57313)` **before the fix** returned `same object=true listening=false` — a
  dead server, forever, for that port. Fixed: `OscServer.isClosed`, and `OscServers.get` opens a new
  server when the cached one is closed.

## Where handler code runs

A box printing `Thread.currentThread().name` from both halves of a handler: the lambda ran on
`Thread-17` (JavaOSC's receive thread), the generator on `field-runloop-main`, and the generator's
first `yield()` returned in the same service pass that started it. A generator yielding in a loop
for one second was resumed 238 and 270 times on two runs, while `Runloop.runLoop.frame` advanced
807 — service passes are not display frames.

An exception thrown inside a generator (`throw IllegalStateException(...)`) printed
`OSC generator threw on start: inside the generator` to Field's console and nothing to the box. A
send to `no-such-host.invalid` printed `OSC send failed: Cannot invoke "Object.getClass()" because
the return value of "java.net.InetSocketAddress.getAddress()" is null`, also console only. (Box
code's `error("...")` is not a throw — it is `HasOutput.error`, which writes an error to the box — so
it cannot be used to test this.)

## Debug printing

With `debug` on, a message matching a handler's pattern was printed twice on the console
(`OSC: /probe = 1, 2.5, three` ×2): once by the catch-all `//*` listener and once by the handler's
own. Fixed: only the catch-all logs.

## Not measured

- TouchOSC and Max are installed on the measuring machine but are GUI applications; neither was
  driven. The public page's settings for them are the applications' own and are marked as not
  exercised in an aside.
- Sending to another machine over Wi-Fi, and the macOS firewall prompt, were not exercised.
- TUIO 2.0 and the `/tuio/2Dblb` profile are unsupported by `TuioReceiver`; not tested.

## Traps met while measuring

- **The smoke runner does not see output that a run-mode box produces after it returns.** Three of
  the page's figures display from a handler's generator, which gets its first pass only after the
  box has finished running. The docs capture waits for the output channel and was fine; the
  `backdoor-box` FIELD_TEST collected only the `run` command's own events and failed with "No
  output-area events were emitted". Only the `launch` path flushes with `output @last` afterwards,
  so those three FIELD_TESTs say `execution: launch`. Changing the runner to flush after every
  run-mode test would be more general, but it would also newly expose late errors in every existing
  smoke, which wants a full catalog run to land.
- **A listener left over from an earlier command answers instead of the one you just started.** A
  python listener started with a 25 s lifetime was still bound when the next experiment started a
  second one on the same port; the second failed with `Errno 48`, and the first one's output landed
  in the same file. Give listeners short lifetimes and distinct ports per experiment.
- **Send times as `d`.** python-osc's default for a Python `float` is `f` (float32), useless for a
  Unix timestamp.
- **The backdoor's `sleep` is the only window in which a peer's messages reach the box's output.**
  Start the invocation in the background, send from the peer inside the window, and read with
  `output @last` at the end.
