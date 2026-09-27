# `afield.dmx` — Art-Net 4 and sACN: design, verification, traps

Added 2026-09-27 (nightly branch `nightly/2026-09-27/1-artnet-sacn-lighting`). The user-facing page
is `public/docs/dmx_lighting.md`; the loadable demo is `public/docs/assets/dmx_lighting_demo/`.

## Files

| file | what |
| --- | --- |
| `src/jvmMain/kotlin/afield/dmx/ArtNet.kt` | ArtDmx / ArtSync / ArtPoll / ArtPollReply codecs, `PortAddress` |
| `src/jvmMain/kotlin/afield/dmx/Sacn.kt` | E1.31 data / sync / discovery codecs, `SacnSequence` (§6.7.2) |
| `src/jvmMain/kotlin/afield/dmx/DmxOutput.kt` | frame buffers + sender thread (send-on-change, repeats, keep-alive, sync, terminate) |
| `src/jvmMain/kotlin/afield/dmx/DmxInput.kt` | receiver: merge (priority, HTP/LTP), timeouts, sync hold, ArtPoll replies |
| `src/jvmMain/kotlin/afield/dmx/Patch.kt` | `Slot`, `FixtureProfile`, `Fixture`, `Patch` (allocation, mappers, `animate`) |
| `src/jvmMain/kotlin/afield/dmx/DmxVisual.kt` | `toFLines`, `universeToFLines`, `showPatch`, `showUniverse`, `renderLinesPng` |
| `src/jvmMain/kotlin/afield/dmx/DmxBox.kt` | box ownership (`artnetOut`/`sacnOut`/`artnetIn`/`sacnIn`), `artPoll` |
| `src/jvmTest/kotlin/afield/dmx/*` | 11 byte-layout tests, 17 service tests, 2 demo-bundle tests |
| `dev_docs/dmx/independent_decode.py` | Python decoders/senders written from the protocol documents |

## Design decisions

- **Box lifecycle through `Box.registerStopHook`, not a per-port singleton.** `osc.kt` caches
  servers per port forever, so its sockets are never released. DMX outputs are keyed by
  box + endpoint: a re-run with the same arguments returns the same object (the lights do not blink),
  different arguments close the old one, stopping the box closes it. Inputs are keyed by
  protocol + port, since only one socket can own a UDP port, and stopping *any* box that asked for
  the input closes it.
- **Animation on the sender's clock (`DmxOutput.beforeTick`, `Patch.animate`).** The first demo
  version used `loop { }` to map and render. It worked in the backdoor Field, but lighting should not
  depend on Field's frame clock (a `frames {}` body gets no ticks without a renderer — see the
  `frames-needs-a-renderer` note), and installations run headless. The hook is a single slot, so a
  re-run replaces rather than stacks.
- **One integer universe number for both protocols**, passed through: 1 is Art-Net `0:0:1` and sACN
  universe 1. Art-Net universe 0 is reachable; sACN universe 0 is reserved.
- **Allocation-light hot path.** Encoders write into caller-owned buffers; each output universe owns
  one packet buffer and two 512-byte snapshots; the receiver decodes ArtDmx straight into the
  source's array (`decodeDmxInto`). sACN receive still allocates a `Sacn.Data` per packet (a copy of
  the slots) — fine at 44 Hz × a few universes; worth revisiting for a large rig.

## Spec numbers used (and where they are cited in KDoc)

| number | source | used in |
| --- | --- | --- |
| UDP 6454, OpCode low byte first, ProtVer 14, ArtDmx length even 2..512 | Art-Net 4 | `ArtNet` |
| ArtPollReply 239 bytes, Port field 0x1936 low byte first | Art-Net 4 | `ArtNet.encodePollReply` |
| unchanged-data refresh at least every 4 s; 10 s merge timeout | Art-Net 4 | `DmxOutput`, `DmxInput` |
| UDP 5568; multicast 239.255.hi.lo; universes 1..63999; priority 0..200 default 100 | E1.31-2016 | `Sacn` |
| three repeats then keep-alive every 800–1000 ms | E1.31-2016 §6.6.2 | `DmxOutput` |
| sequence: discard when signed(new − last) ∈ (−20, 0] | E1.31-2016 §6.7.2 | `SacnSequence` |
| Stream_Terminated sent 3×; option bits 0x80 preview / 0x40 terminated / 0x20 force-sync | E1.31-2016 §6.2.6 | `DmxOutput.close` |
| E131_NETWORK_DATA_LOSS_TIMEOUT 2.5 s; discovery every 10 s on universe 64214 | E1.31-2016 App. A, §8 | `Sacn`, `DmxInput` |

These were written from knowledge of the specifications, not checked against the PDF text tonight
(no network). The byte layouts are cross-checked by an independent decoder *and* by the third-party
`sacn` package (below), which is strong evidence for the E1.31 layouts; the Art-Net timing numbers
(4 s, 10 s) are the least verified claims here.

## Verification record (2026-09-27)

**JVM tests.** `./gradlew jvmTest --tests 'afield.dmx.*'`: `DmxProtocolTest` 11/11,
`DmxServicesTest` 17/17, `DmxLightingDemoBundleTest` 2/2. The byte tests spell out expected bytes
from the spec tables rather than round-tripping, so a field in the wrong place fails.

**Independent decoder, Field → Python.** A box sent a static 200-pixel strip (universes 1–2) as
Art-Net with ArtSync to 127.0.0.1:16454 and as sACN with sync to 127.0.0.1:15568, then was stopped;
`dev_docs/dmx/independent_decode.py` (hand-written `struct` parsing) listened. Excerpt, after the
keep-alive fix below:

```text
  1 t=+ 0.000s  530B  ArtDmx protver=14 seq=1 net=0 subnet=0 universe=1 length=512 first=[255, 0, 64, 254, 1, 64, ...]
  3 t=+ 0.000s   14B  ArtSync len=14
  7 t=+ 0.044s  530B  ArtDmx ... seq=3 ...          <- change + two repeats, one 44 Hz tick apart
 10 t=+ 0.953s  530B  ArtDmx ... seq=4 ...          <- keep-alive
 13 t=+ 1.862s  530B  ArtDmx ... seq=5 ...          <- 0.909 s later

  1 t=+ 0.000s  638B  E1.31 data name='Field4 demo' prio=100 sync=7962 seq=1 opts=0x00 universe=1 slots=512
  3 t=+ 0.000s   49B  E1.31 sync seq=1 sync-address=7962 len=49
  4 t=+ 0.000s  124B  E1.31 discovery name='Field4 demo' page=0/0 universes=[1, 2]
 11 t=+ 0.955s  638B  E1.31 data ... seq=4 ...
 20 t=+ 3.511s  638B  E1.31 data ... seq=7 opts=0x40 universe=1   <- box stopped: Stream_Terminated ×3 per universe
```

Every flags-and-length word, vector and DMP header field is asserted by the decoder; none failed.
The third-party `sacn` package (`uv run --no-project --with sacn …  sacn-lib`), receiving an
animated universe at 5 Hz, reported 17 successive frames with correct values and saw the terminate
as the universe going away.

**Independent sender, Python → Field.** A hand-packed Python ArtDmx sender (universe 3) and the `sacn`
package's own `sACNsender` (universe 4, priority 120) fed a box holding `artnetIn(port = 16471)` and
`sacnIn()`. Field decoded slots 1–8 as `[109, 112, 115, …]` and `[108, 111, 114, …]` — the sender's
`(3i + k) mod 256` ramp — with the lib's source name and priority 120, zero discards.

**In Field (backdoor, port 8471).** All four demo boxes were run one at a time from a *fresh copy of
the committed bundle* (`open dmx_lighting_demo --must-exist`, then `launch dmx-receiver`,
`launch dmx-sender`, `run dmx-artpoll`, `launch dmx-sacn-matrix`): every verdict `success: true`;
the ArtPoll box printed Field's own `ArtPollReply(127.0.0.1 "Field4" …)`; the receiver displayed
both universes, the matrix box both its channels. A box assembled from every snippet on the public
page also ran. Pixels were looked at: the received universes rendered in-box with `renderLinesPng`,
and the displayed SVGs rasterised (these are the page's images). With `--with-playwright`,
`screenshot-window --uid dmx-sender --open-outputs` on the backdoor-managed Field returned a real
4400 × 5600 capture of the running document (not the 48 × 48 placeholder `screenshot-box` gives on
some headless paths); that capture is the page's first image. Multicast on this Mac: joining
239.255.0.77 and sending to it delivered the packet (`received=1 ch1=123`).

## Traps found tonight

- **Keep-alive at 1000 ms lands at 1.022 s.** The sender checks "≥ keepAliveMs since the last send"
  once per 22.7 ms tick, so the real interval is keepAliveMs plus up to one tick, which broke
  E1.31's 800–1000 ms window. Default is now 900 ms (measured 0.909 s). Only a timed external
  decoder showed this; the synthetic-clock unit test was satisfied either way.
- **First sACN discovery page was empty.** The sender thread's first tick runs inside the
  constructor, before any universe exists. Discovery now waits for a universe.
- **The `sacn` package's sender binds UDP 5568 by default**, so it silently steals unicast traffic
  from a Field receiver on the same port; pass `bind_port`.
- **The backdoor only collects events while a non-`sleep` command is in flight**, so counting
  `set-output` events across a `sleep` undercounts a running loop (it looked like the matrix box had
  drawn once). Use `wait-output` to sample a later frame.
- **A closed output channel is 0 × 0**: `showPatch`/`showUniverse` call `displayOpen`.
- **The first bundle layout overlapped in the real UI.** Output panels open beneath a box and are
  about 480 canvas px wide and 180 px tall per channel, and a box widens to fit a long name — so
  boxes 110 px apart vertically put one box's outputs under the next box. Only the Playwright
  window capture showed it; the bundle now lays the boxes out in three columns 520–620 px apart.
- **`FLine.cubicTo(x0, y0, z0 = 0, x1, …)`** has defaulted z parameters in the middle, so six
  positional numbers mean the wrong thing; use names (the demo does).

## Not done

RDM; ArtAddress / ArtIpProg; sACN per-channel priority (0xDD) — the receiver discards non-zero
start codes; no sequence-based reordering for Art-Net (optional in the spec). ArtSync on receive
treats any ArtSync as applying to every held source, where the spec narrows it to the controller
that sent the ArtDmx. No hardware was driven: every test was loopback on one Mac.

`afield.dmx` is not in `StdlibReferenceGenerator.SOURCE_ROOTS`, so it has no rows in
`public/docs/reference/`. Adding a root is treated in that file as its own change (a blurb, a
category page — this package's category is `Lighting`, which has no page — and corpus-global
evidence links), so it was left for one.

## Scale (measured 2026-09-27, one box, loopback, an aarch64 Mac that other agents were also building on)

64 universes of RGB pixels — 10,880 fixtures — per frame, mean over 200 frames after 20 warm-up:

| protocol | `map` | `render` | `tick` (64 sends) | sent | received | discarded |
| --- | --- | --- | --- | --- | --- | --- |
| Art-Net | 0.166 ms | 0.579 ms | 0.603 ms | 14080 | 14080 | 0 |
| sACN | 0.140 ms | 0.440 ms | 0.512 ms | 14080 | 14081 (+1 discovery) | 0 |

So a 44 Hz frame (22.7 ms) is about 6 % used at that size. The population is one machine, one run
per protocol; it says the design is not the bottleneck at installation scale, not what a slower host
or a real network would do.
