# A RectSL scene on a light rig

A RectSL scene is a picture that changes over time, and an LED matrix is a screen with very few
pixels. This page connects the two, using four parts of Field that were not written with each other
in mind. `RectSLImager` draws the scene on the CPU into an `Img`. `Patch.mapImage` colours 128 RGB
pixels and four RGBWA washes from that image. `DmxOutput` sends the resulting universes as sACN to
this machine, and `DmxInput` receives them. Then `Fixture.decode` turns the received bytes back into
colours, laid out as the rig is, so they can be checked against the pixels the rig was given.

Two links in that chain were missing and are new: `RectSLImager` (a scene at time *t* as an image)
and `Fixture.decode` with `showReceived` (a received universe drawn in the room rather than in wire
order). Everything else was already in Field.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_scene_to_light/chain.png" wait="10" capture="box" open-outputs="true" w="1180" h="620" padding="18" timeout="180" name="chain-scene-to-light-chain" embed=true alt="Three panels: the RectSL frame at one second, an orange square tilted on a navy wall with a thin white line and a faint blue band; the 16 by 8 rig of discs lit from it, showing a blocky orange square, two columns of pale discs where the line straddles them, and four washes along the bottom; and the same rig lit by the universes that came back over sACN, indistinguishable from the one sent"}
:::static
import afield.dmx.*
import afield.rectsl.*

// The scene, drawn on the CPU: 256 x 128 pixels of a 16 x 8 wall, at one second in.
val scene = RectSLImager(SceneOnARig.SCENE)
val frame = scene.frame(1.0, 256, 128)

// A 16 x 8 grid of RGB pixels in universe 1 and four RGBWA washes in universe 2, coloured from
// the frame shrunk to one pixel per fixture.
val rig = SceneOnARig.Rig()
rig.show(frame)

:::fold send it as sACN to this machine, receive it, and draw both
// Plain objects rather than the box helpers, so every socket is closed before the box ends.
val out = DmxOutput(DmxProtocol.SACN, host = "127.0.0.1", port = 35692, autoStart = false, discovery = false)
val node = DmxInput(DmxProtocol.SACN, port = 35692, bindAddress = "127.0.0.1")
try {
    rig.patch.render(out)
    out.flush()
    val until = System.currentTimeMillis() + 2000
    while ((node.version(1) == 0L || node.version(2) == 0L) && System.currentTimeMillis() < until) Thread.sleep(5)

    showImg(frame.upscaleNearest(2), channel = "scene", maxSide = 540, caption = "the scene at t = 1 s, as RectSLImager draws it")
    showPatch(rig.patch, channel = "sent", w = 540.0, h = 330.0)
    showReceived(rig.patch, node, channel = "received", w = 540.0, h = 330.0)
} finally {
    out.close()
    node.close()
}
:::endfold
```

The first panel is the scene: an orange square that crosses the wall and turns, a line a quarter of
a unit wide sweeping from left to right, and a blue band along the bottom that fades in and out.

```diffstatic {data-lang=rectsl}
#rectsl
->wall 16 8 abstract
->background 0x0c1024
->duration 4
->fps 30

"sun" rect
  4 4 ->wh
  0xff7a1a solid ->fill
  '( 0=-5  2=5  4=-5 ) ->x
  '( 0=0  4=3.14159 ) ->rot
;

"band" rect
  16 1.5 ->wh
  0 -3.25 ->xy
  0x2a9df4 solid ->fill
  '( 0=0.15  2=1  4=0.15 ) ->alpha
;

"sweep" rect
  0.25 8 ->wh
  0xffffff solid ->fill
  '( 0=-8  4=8 ) linear ->x
;
```

The second panel is the rig in its sent colours. The third is the same rig lit only by what the
receiver decoded from the two universes that came back. The wall is 16 units by 8, which gives one
fixture per unit, and the frame is drawn at 16 pixels per unit, so each fixture stands for a
16 × 16 block of pixels.

## From a scene to an image

`RectSLImager(source)` compiles the scene once, including any `{{ … }}` Kotlin escapes, and
`frame(t, width, height)` draws it with the CPU reference rasterizer that the RectSL study tools
use. That rasterizer draws the background, blends the rectangles the way the GPU blends them into
an 8-bit target, and runs StackSL materials through the StackSL interpreter. No window or graphics
context is involved, so the lights keep running on a machine whose display is asleep, and this
page can be built on one.

```diffstatic {data-lang=kotlin}
val scene = RectSLImager(source)
val img = scene.frame(1.5, 256, 128)          // colours as typed: 0x808080 is 0.502
val light = scene.frame(1.5, 256, 128, linear = true)   // the light that stands for: 0.216
```

There are two choices of value. A frame holds colours *as typed* by default, which is what the
RectSL pictures show and what an 8-bit target stores. `linear = true` gives the light those values
stand for. The difference matters in two places. Averaging pixels is only right on light, and an
LED's PWM output is linear in light. So a rig driven with linear frames at gamma 1 and a rig driven
with as-typed frames through `FixtureProfile.withGamma(2.2)` come out at roughly the same
brightness, but only the first gives a thin line the correct total energy when it is averaged into
a fixture. The next sections show why that matters.

## What the wire does

Both panels above come from one frame. The readout below covers nine frames of the scene, sent and
received the same way on a port of their own. It compares every slot byte and every fixture's
colour, then runs the scene on the sender's own 44 Hz clock for three seconds and times it.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_scene_to_light/readout.png" wait="14" capture="box" open-outputs="true" w="1120" h="480" padding="18" timeout="240" name="chain-scene-to-light-readout" embed=true alt="Three tables: the round trip of nine frames of 132 fixtures, with no slot byte changed and pixels and washes both within half a level; the frame cost and the receiver's frame spacing over three seconds at 44 Hz; and the worst decode error of six fixture profiles over random colours, largest for RGBWA"}
:::static
import afield.dmx.*

val trip = SceneOnARig.roundTrip(port = 35693)
val timing = SceneOnARig.timing(port = 35694, seconds = 3.0)
val floor = SceneOnARig.decodeErrors()

:::fold the readout
fun f(v: Double, d: Int = 2) = "%.${d}f".format(v)
markdown("roundtrip", """
#### ${trip.frames} frames, ${trip.fixtures} fixtures, two universes, sACN to 127.0.0.1 and back

| | |
|---|---|
| slot bytes compared | ${trip.slotsCompared} |
| slot bytes that changed on the way | **${trip.levelErrors}** |
| worst RGB pixel, asked for vs decoded | ${f(trip.worstGridLevels, 3)} of 255 levels |
| worst RGBWA wash, asked for vs decoded | ${f(trip.worstWashLevels, 3)} of 255 levels |
| packets sent / received | ${trip.packetsSent} / ${trip.packetsReceived} |
| median send-to-merged latency | ${f(trip.medianLatencyMicros / 1000.0, 2)} ms |

#### ${f(timing.seconds, 0)} s animated on the sender's clock

| | median | 95th percentile |
|---|---|---|
| one look: draw 256 × 128, shrink, map (ms) | ${f(timing.frameMedian)} | ${f(timing.frameP95)} |
| gap between universe-1 frames at the receiver (ms) | ${f(timing.arrivalMedian)} | ${f(timing.arrivalP95)} |

${timing.ticks} looks computed; ${timing.arrivalMillis.size + 1} frames of universe 1 received, ${f(timing.receivedHz, 1)} per second.

#### What each profile's slots cost, with no network: 2000 random colours each

| profile | ${floor.keys.joinToString(" | ") { it.name }} |
|---|${floor.keys.joinToString("") { "---|" }}
| worst error, levels | ${floor.values.joinToString(" | ") { f(it, 3) }} |
""", width = 1000)
:::endfold
```

No slot byte changes on the way. sACN over loopback is lossless, and that result is only interesting
because it rules out the network code as a source of error. The remaining error is quantisation. An
RGB pixel's colour is rounded to 8 bits once, so it comes back within half a level. A wash is
further off because `writeInto` splits its colour into red, green, blue, white and amber, and
rounds each of those separately. `decode` adds white back into all three primaries and amber into
red and green, so up to three rounded values combine in the red channel. The washes in this scene
happen to come back as close as the pixels do, because the band and the background they sample
leave little for white and amber to carry. The last table gives the general case: random colours
through each profile's slots and straight back, with no network involved, which is the floor any
round trip sits on.

The timing table is the sender's budget. At 44 Hz there are 22.7 ms between ticks. The look is
computed inside the tick, on the sender thread, before the universes are copied out, so a scene
that takes longer than that to draw would slow the lights down rather than tear them.

## A thin line on sixteen columns

`mapImage` stretches the rig's bounding box over the image and samples it bilinearly at each
fixture's position. If the image is exactly one pixel per fixture, that lands every fixture on its
own pixel, and the result is correct. With a 256-pixel frame and 16 fixtures across, the same call
takes one point sample every seventeen pixels, and whatever falls between the sample points never
reaches the rig.

The line in the scene is four pixels wide, a quarter of a fixture. Below it crosses the wall alone
on black, drawn in linear light, and the rig is coloured from it by the two routes: point samples of
the full frame, and the frame shrunk to 16 × 8 by area averaging (`Img.resize`) first.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_scene_to_light/aliasing.png" wait="14" capture="box" open-outputs="true" w="1180" h="700" padding="18" timeout="240" name="chain-scene-to-light-aliasing" embed=true alt="A plot of the total light on the rig over three seconds: the point-sampled route is zero most of the time with tall spikes each time the line passes a column of sample points, and the area-averaged route is a flat line at two fixtures' worth, the light the line carries; below, two rigs at the same instant, the point-sampled one dark and the area-averaged one with a dim column"}
:::static
import afield.dmx.*
import afield.rectsl.*

val (point, area) = SceneOnARig.aliasing()

:::fold plot both routes, and show the rig at an instant the point samples miss
val times = (0 until point.total.size).map { 0.5 + 3.0 * it / (point.total.size - 1.0) }
showSignals(
    "point samples of the 256 × 128 frame" to point.total.toList(),
    "area average to 16 × 8 first" to area.total.toList(),
    x = times,
    channel = "light on the rig",
    // No third trace for the truth: it is 2.0, exactly under the area-averaged route.
    title = "Light on the grid; the line is worth ${SceneOnARig.LINE_TRUTH}",
    xTitle = "seconds",
    yTitle = "fully lit fixtures",
)

// The first instant at which the point-sampled rig is dark.
val k = point.peak.indexOfFirst { it < 0.01 }.coerceAtLeast(0)
val frame = RectSLImager(SceneOnARig.LINE).frame(times[k], 256, 128, linear = true)
val rig = SceneOnARig.Rig()
rig.show(frame, area = false)
showPatch(rig.patch, channel = "point samples at t = ${"%.3f".format(times[k])} s", w = 540.0, h = 300.0)
rig.show(frame, area = true)
showPatch(rig.patch, channel = "area average at t = ${"%.3f".format(times[k])} s", w = 540.0, h = 300.0)

markdown("counts", """
| route | frames with the rig dark | light on the rig, min – max | mean over the sweep | (max − min) / mean |
|---|---|---|---|---|
| point samples | ${point.darkFrames} of ${point.total.size} | ${"%.2f".format(point.min)} – ${"%.2f".format(point.max)} | ${"%.3f".format(point.mean)} | ${"%.2f".format(point.flicker)} |
| area average | ${area.darkFrames} of ${area.total.size} | ${"%.3f".format(area.min)} – ${"%.3f".format(area.max)} | ${"%.3f".format(area.mean)} | ${"%.4f".format(area.flicker)} |
""", width = 1000)
:::endfold
```

Both rigs in the figure are coloured from linear light and drawn as it is, so the area-averaged
column is dim: each of those fixtures carries an eighth of a white one, the line's quarter split
between two columns.

Point sampling gives a strobe. The line lights a column of eight fixtures at full brightness while
it is over their sample points and disappears in between, so the rig flashes once per column. It
also overshoots: at a peak, eight fixtures are fully lit by a line that covers a quarter of one
column. Averaged over the whole sweep the strobe delivers about the right amount of light, so the
fault is not how much light arrives but when. Area averaging instead puts the line's light onto the
rig in the right amount wherever the line is. As the line crosses from one column to the next, the light moves between them without
flickering.

That is why `SceneOnARig.Rig.show` shrinks the frame first:

```diffstatic {data-lang=kotlin}
patch.mapImage(frame.resize(16, 8))      // one pixel per fixture, each the mean of its cell
```

This works exactly because `resize` averages whole 16 × 16 blocks, and the image size then matches
the grid, so `mapImage` puts each fixture on its own pixel. A rig that is not a grid (a strip on a
curve, scattered pars) has no such image size, and the fix there is a smaller or blurred frame, so
that each sample point averages what its fixture ought to show.

!!! note "Average light, not typed colour"

    The flat line above averages *linear* frames. Averaging as-typed values instead gives a dimmer
    line, because the sRGB encoding is concave: a quarter of a white pixel is 0.25 in light and
    0.54 as typed, and the mean of typed values sits below the typed value of the mean light.
    Shrink with `linear = true`, and send linear values to fixtures at gamma 1.

## Where each piece is documented

* [RectSL Examples](rectsl_examples.md) and the [RectSL Cheatsheet](rectsl_cheatsheet.md) for the
  language; [Talking a Scene into Existence](rectsl_conversation.md) for the study tools whose CPU
  rasterizer `RectSLImager` uses.
* [Driving Lights: Art-Net and sACN](dmx_lighting.md) for `Patch`, the profiles, `mapImage`, the
  outputs and inputs, and `showUniverse`.
* [A RectSL Animation as a Solid](chain_rectsl_spacetime.md) is the neighbouring chain: the same
  language read as a volume over *(x, y, t)* instead of a sequence of frames.

The demo document `public/docs/assets/chain_scene_to_light_demo/` runs the chain live: a sender box
that animates the scene onto the rig, a receiver box that draws the rig from what arrives, and a box
that repeats this page's measurements. Edit the scene in the first box and launch it again to see
the change reach the second.

??? aside "Why the line moves with `linear`"

    A quote in a channel, `'( 0=-8  4=8 ) ->x`, is a TCB track, so with two keys the line eases in
    and out. The first version of this page used that, and the area-averaged route showed four
    spikes of a quarter above the truth. They were not the rig: at four instants the eased line's
    edges landed exactly on pixel centres, and the CPU rasterizer, which takes one sample per pixel
    centre and counts a centre on an edge as inside, drew the four-pixel line five pixels wide.
    With `linear` and these 121 frames an edge never lands on a centre. The rasterizer's edge rule
    is a documented difference from the GPU's, and it was left alone.

??? aside "What holds these numbers"

    `SceneOnARigTest` runs the round trip, the per-profile decode floor, the aliasing sweep and two
    seconds of timing on loopback ports away from 5568 and 6454, and holds each claim on this page to
    a bound. It also sends the same rig over Art-Net with ArtSync and checks both universes arrive
    byte for byte. It also takes the
    datagrams off a bare socket and reads them at the E1.31 offsets by hand, rather than through
    `Sacn.decodeData`, so the round trip is not only Field's decoder agreeing with Field's encoder.
    The figures on this page are docs-runner captures, drawn by the code in their fences.
