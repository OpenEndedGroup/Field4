# Driving Lights: Art-Net and sACN

`afield.dmx` lets a box drive DMX lighting over a network, and lets Field act as a lighting node
itself. It speaks the two protocols nearly every LED controller, pixel driver and console accepts:
**Art-Net 4** (UDP 6454) and **sACN**, ANSI E1.31 (UDP 5568, usually multicast). On top of the wire
formats sits a small patch model: fixtures with channel layouts and addresses, strips that overflow
from one universe into the next, and mappers that colour fixtures from a function, a drawn line or
an image.

Nothing here needs hardware to try. The [dmx_lighting_demo](agent_transcripts.md#dmx_lighting_demo)
starting point has four boxes that send over the network to this machine, receive the packets again
and draw what arrived. Here it is with its three senders and receivers launched:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1700x760" screenshot="assets/dmx_lighting/demo_document_running.png" transcript="dev_docs/agents/transcripts/dmx_lighting_demo.yaml" padding="0" wait="2" timeout="180" embed=true hidden=true name="dmx-demo-document-running" alt="The demo document running: an Art-Net receiver drawing two universes, a 200-pixel strip on a curve, and an sACN RGBW matrix with the universe it received"}
// dev_docs/agents/transcripts/dmx_lighting_demo.yaml, with its three loop boxes launched
```

On the left, Field is an Art-Net node drawing the two universes it receives. In the middle is the
sender: 200 pixels on a curve, 170 in universe 1 and the remaining 30 in universe 2, which is why the
second grid on the left is mostly empty. On the right, a matrix of RGBW fixtures is sent as sACN and
received back in the same box.

## A first light

This sets two channels on a node at a fixed address:

```diffstatic {data-lang=kotlin}
import afield.dmx.*

val out = artnetOut("192.168.1.50")      // a node's IP, or a broadcast address
out.universe(1)[1] = 255                 // channel 1 of universe 1, full
out.universe(1).setLevel(2, 0.5)         // channel 2, half
```

`artnetOut` returns a `DmxOutput` owned by the box. Its sender thread sends a universe when its
bytes change (at most 44 times a second, DMX512's full frame rate), repeats it twice more, and after
that re-sends it about once a second so receivers know the source is still there. Re-running the box
hands back the same output, so the socket and the lights carry on; stopping the box closes it.

`sacnOut()` is the same thing for E1.31. With no host it sends each universe to its multicast group,
`239.255.hi.lo`; with a host it sends unicast. Closing an sACN output sends the protocol's
Stream_Terminated packets, so receivers let go at once rather than holding the last look.

Channels are **1-based**, as on a console and in every fixture manual. Art-Net numbers universes
from 0 and sACN from 1; `afield.dmx` passes the number you give straight through, so universe 1 is
Art-Net port-address `0:0:1` and sACN universe 1.

## Fixtures and a patch

Addresses by hand get old quickly. A `Patch` holds fixtures, each a `FixtureProfile` (which channel
does what) at a universe and start address, placed at a position in the room:

```diffstatic {data-lang=kotlin}
import afield.dmx.*

val patch = Patch(startUniverse = 1)
val strip = patch.strip(200, vec(0.0, 0.0), vec(400.0, 0.0))            // 200 RGB pixels
val pars = patch.grid(4, 2, spacing = 100.0, profile = FixtureProfile.RGBWA)
patch.add(FixtureProfile.DIMMER16, universe = 5, address = 1, position = vec(0.0, 300.0))

val out = artnetOut("192.168.1.50")
strip.forEach { it.rgb(1.0, 0.3, 0.0) }
patch.render(out)
```

The run builders (`strip`, `grid`, `stripAlong`) allocate addresses one after another and move to
the next universe whenever a fixture would straddle slot 512. That is how pixel controllers expect
long runs: 170 RGB pixels to a universe (the last two slots unused), 128 RGBW. The 200-pixel strip
above takes all of universe 1 and 30 pixels of universe 2, and the pars start after it.

Profiles provided: `RGB`, `GRB` (the WS2812 order many pixel controllers expose), `RGBW`, `RGBWA`,
`DIMMER`, `DIMMER16`, `RGB16`, `DRGB` (master dimmer, then colour). Anything else is a list of
slots:

```diffstatic {data-lang=kotlin}
val myPar = FixtureProfile("my par", listOf(Slot.DIMMER, Slot.RED, Slot.GREEN, Slot.BLUE, Slot.ZERO, Slot.ZERO))
```

A fixture's colour is always set as RGB, 0..1. Profiles with a white channel take the common part of
red, green and blue out as white; an amber channel then takes what it can of the remaining red and
green. A 16-bit channel (`RED` followed by `RED_FINE`, `DIMMER` followed by `DIMMER_FINE`) gets
coarse then fine byte. `withGamma(2.2)` makes a ramp look even on LEDs, whose PWM is linear.

!!! note "White and amber are a default, not a calibration"
    The white and amber split assumes ideal emitters (amber as 1 : 0.5 red : green). A real
    fixture's primaries differ, and a rig that needs matched colour needs measured values.

## Mapping: colour from a function, a line, an image

Three mappers set the colour of every fixture (or of a list you pass as `only`):

```diffstatic {data-lang=kotlin}
patch.map { x, y, t -> vec(0.5 + 0.5 * sin(x * 0.03 - t * 2), 0.2, 0.5 + 0.5 * cos(y * 0.05)) }
patch.mapAlong { u, t -> vec(u, 1 - u, 0.0) }        // u runs 0..1 from the first fixture to the last
patch.mapImage(imgFromFile("sky.png"))               // the rig's bounding box stretched over the image
```

`map` hands the function each fixture's position, so the same function drives a strip, a grid or a
scattered rig. `mapImage` samples an `Img` bilinearly at each fixture's position, which turns a matrix of fixtures into a very low
resolution screen.

!!! note "`mapImage` takes point samples"

    Each fixture reads the image at one point, not the area around it. On an image much larger
    than the rig, anything thinner than the gap between sample points is missed most of the time
    and strobes when it crosses one. For a grid, shrink the image to one pixel per fixture first,
    `patch.mapImage(img.resize(cols, rows))`, which area-averages and lands each fixture on its
    own pixel. [A RectSL scene on a light rig](chain_scene_to_light.md) measures the difference.

For a white of a given warmth, `colorTemperature(3200.0)` returns the colour of a black body at that
temperature (a curve fit, fine for looks; not a fixture calibration):

```diffstatic {data-lang=kotlin}
patch.map { _, _, _ -> colorTemperature(3200.0) }   // tungsten; 5600.0 is daylight
```

`stripAlong` places a strip on a drawn curve instead of a straight line: LED tape stuck to a shape.
Here 200 pixels go on a curve, are coloured once by position, and are drawn with `showPatch`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/dmx_lighting/patch_strip_along_curve.png" alt="200 RGB pixels along an S-shaped curve, their colours cycling through the hues along its length" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="lights" capture="box" embed=true trim=true name="dmx-strip-along-curve" w="760" h="300"}
import afield.dmx.*

val tape = FLine().moveTo(0, 0).cubicTo(x0 = 150, y0 = -160, x1 = 250, y1 = 160, x = 400, y = 0)
val patch = Patch(startUniverse = 1)
patch.stripAlong(tape, 200)                          // evenly spaced by arc length, ends on the ends
patch.map(0.0) { x, y, _ ->
    vec(0.5 + 0.5 * sin(x * 0.03), 0.5 + 0.5 * sin(x * 0.03 + 2.1), 0.5 + 0.5 * sin(y * 0.03))
}
showPatch(patch)
```

## Animating

To animate, compute the next look on the output's own clock:

```diffstatic {data-lang=kotlin}
val out = artnetOut("127.0.0.1")
patch.animate(out) { t ->
    map(t) { x, y, time -> vec(0.5 + 0.5 * sin(x * 0.03 - time * 2.0), 0.3, 0.6) }
}
```

The block runs before every send tick on the sender thread, then the patch is rendered into the
output. The lights therefore keep time whether or not anything else in Field is ticking, which is
what an installation that runs without a window wants. There is one animation per output, so
re-running the box replaces it, and it ends when the box is stopped.

## Seeing the rig without the rig

`showPatch(patch)` draws the patched fixtures in their current colours into the box's output;
`patch.toFLines()` is the same drawing as `FLine`s for your own composition, and a `Patch` or a
`DmxUniverse` left as the last value of a box draws itself inline. `showUniverse(input, n)`
draws what a receiver has for universe `n`, decoded as the fixtures that drive it:

```diffstatic {data-lang=kotlin}
showPatch(patch, channel = "matrix")
showUniverse(node, 10, profile = FixtureProfile.RGBW, cols = 16)   // or profile = null for 512 gray slots
```

This box sends an image to a 16 × 8 matrix of RGBW fixtures as sACN, receives it again on this
machine, and draws both. Launch it:

```diffstatic_runner {data-lang=kotlin screenshot="assets/dmx_lighting/rgbw_matrix.png" alt="two output panels: a 16 by 8 matrix of RGBW fixtures coloured by a gradient with a band of brightness across it, and below it universe 10 as received over sACN, the same fixtures drawn in wire order with every other row reversed" capture="box" execution="launch" wait="3" wait-output=true wait-output-kind=display wait-output-channel="universe 10" embed=true trim=true name="dmx-rgbw-matrix" w="760" h="640"}
import afield.dmx.*

// 128 RGBW fixtures x 4 slots fill universe 10 exactly; the matrix is wired serpentine, as most are.
val patch = Patch(startUniverse = 10)
patch.grid(16, 8, spacing = 10.0, profile = FixtureProfile.RGBW, serpentine = true)

// Any Img will do (imgFromFile("...") for a file); here a small gradient made in place.
val img = Img(64, 32, 3, FloatArray(64 * 32 * 3) { i ->
    val x = (i / 3) % 64 / 63f
    val y = (i / 3) / 64 / 31f
    when (i % 3) {
        0 -> x
        1 -> y
        else -> 1f - x
    }
})

val out = sacnOut("127.0.0.1")   // host = null sends to multicast 239.255.0.10 instead
val node = sacnIn()              // Field as an sACN receiver on UDP 5568

// On the sender's clock: sample the image, then sweep a wave of brightness across it.
patch.animate(out) { t ->
    mapImage(img)
    fixtures.forEach { it.dimmer = 0.55 + 0.45 * sin(t * 3.0 - it.position.x * 0.05) }
}

loop {
    if (frameNumber % 6 == 0) {
        showPatch(patch, channel = "matrix", w = 480.0, h = 260.0)
        showUniverse(node, 10, profile = FixtureProfile.RGBW, cols = 16, w = 480.0, h = 260.0)
    }
}
```

The lower picture is universe 10 as it came back over the network. Every other row runs
backwards, because the matrix was patched with `serpentine = true`, the way most LED matrices are
wired, and the receiver draws slots in wire order.

## Field as a node

`artnetIn()` and `sacnIn()` open a `DmxInput`: Field receives lighting data like any node would, and
other software (a console, a media server, another Field) can drive it.

```diffstatic {data-lang=kotlin}
val input = artnetIn()                  // UDP 6454; sacnIn(universes = listOf(1, 2)) joins multicast groups
input.channel(1, 1)                     // universe 1, channel 1, 0..255
input.universe(2)                       // all 512 slots, merged
input.version(1)                        // bumps when universe 1 may have changed; cheap to poll
```

Several sources on one universe are merged. sACN first keeps only the sources at the highest
priority (0..200, default 100), as E1.31 requires; then `MergeMode.HTP` (the default) takes the
highest value per slot and `MergeMode.LTP` the most recent packet. A source that goes silent is
dropped after sACN's 2.5 s data-loss timeout or Art-Net's 10 s merge timeout, and at once when an
sACN source sends Stream_Terminated.

This box is a node and its own sender: it listens for Art-Net, sends the 200-pixel strip to itself,
and draws the two universes it receives. 170 pixels fill universe 1; the other 30 roll over into
universe 2, which is why its grid is mostly empty. Launch it:

```diffstatic_runner {data-lang=kotlin screenshot="assets/dmx_lighting/artnet_received.png" alt="two output panels of received universes: universe 1 full with 170 coloured RGB pixels, universe 2 with 30 coloured pixels and the rest of the grid empty" capture="box" execution="launch" wait="3" wait-output=true wait-output-kind=display wait-output-channel="universe 2" embed=true name="dmx-artnet-received" w="760" h="560"}
import afield.dmx.*

val input = artnetIn()                    // Field as a node on UDP 6454

val tape = FLine().moveTo(0, 0).cubicTo(x0 = 150, y0 = -160, x1 = 250, y1 = 160, x = 400, y = 0)
val patch = Patch(startUniverse = 1)
patch.stripAlong(tape, 200)
val out = artnetOut("127.0.0.1")          // to this machine, so to `input`
patch.animate(out) { t ->
    map(t) { x, _, time -> vec(0.5 + 0.5 * sin(x * 0.03 - time * 2.0), 0.3, 0.6) }
}

loop {
    if (frameNumber % 4 == 0) {
        showUniverse(input, 1)
        showUniverse(input, 2)
    }
}
```

An Art-Net input answers ArtPoll, so discovery tools list Field as a node. `artPoll(host)` is the
other side: it sends one ArtPoll and returns every `ArtPollReply` that comes back within a second.

```diffstatic {data-lang=kotlin}
artPoll("255.255.255.255").forEach { println(it) }   // every node on the network
```

## Synchronised output

A long strip spans universes, and universes are separate packets; without help, a fast animation can
tear where one universe ends. `artnetOut(host, sync = true)` follows every frame with an ArtSync, and
`sacnOut(sync = true)` sends E1.31 synchronization packets on universe 7962 and marks its data
packets to wait for them. Receivers that support sync apply the frame's universes together. Field's
`DmxInput` does for both protocols: sACN data carrying a sync address waits for that sync packet,
and once an Art-Net input has seen an ArtSync it holds each ArtDmx for the next one, dropping back to
immediate output after 4 s without.

## Without a box

`DmxOutput`, `DmxInput`, `Patch` and the packet codecs (`ArtNet.encodeDmx`, `Sacn.encodeData`, and
their decoders) work outside a box too. Then nothing closes the sockets for you: call `close()`.
`DmxSockets.closeAll()` closes everything the box helpers opened, for a panic button.

## Not supported

RDM (remote device management), Art-Net's ArtAddress/ArtIpProg node programming, and sACN
per-channel priority (start code `0xDD`) are not implemented; an sACN packet with a non-zero start
code is ignored on receive.

See also: [A RectSL scene on a light rig](chain_scene_to_light.md), which drives a fixture grid
from a rendered scene, and [OSC and TUIO](osc.md) for show-control messages.

??? aside "How the wire formats were checked"
    The protocol claims were checked against a decoder written separately from the encoder, in
    Python, and against the third-party `sacn` package; the record is `dev_docs/dmx_lighting.md`.
