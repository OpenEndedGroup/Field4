# A RectSL scene on a light rig

An LED matrix is a screen with very few pixels. Let's play a RectSL animation on one, then read
the signal back to see what the lights received.

`RectSLImager` draws the scene into an `Img`, and `Patch.mapImage` uses it to colour 128 RGB pixels
and four RGBWA washes. `DmxOutput` sends those colours over sACN to this machine, where `DmxInput`
receives them. `Fixture.decode` turns the received bytes back into colours so we can compare them
with what was sent.

The box below draws one frame, lights the rig from it, sends it over sACN, and draws what came back.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_scene_to_light/chain.png" wait="10" capture="box" open-outputs="true" w="1180" h="620" padding="18" timeout="180" name="chain-scene-to-light-chain" embed=true alt="Three panels: the RectSL frame at one second, an orange square tilted on a navy wall with a thin white line and a blue band along the bottom; the 16 by 8 rig of discs lit from it, showing a blocky orange square, two columns of pale discs where the line straddles them, and four washes along the bottom; and the same rig lit by the universes that came back over sACN, indistinguishable from the one sent"}
:::static
import afield.io.dmx.*
import afield.lang.rectsl.*

// The scene, drawn on the CPU: 256 x 128 pixels of a 16 x 8 wall, at one second in.
val scene = RectSLImager(SceneOnARig.SCENE)
val frame = scene.frame(1.0, 256, 128)
showImg(frame, channel = "scene")

// A 16 x 8 grid of RGB pixels in universe 1 and four RGBWA washes in universe 2, coloured from
// the frame shrunk to one pixel per fixture.
val rig = SceneOnARig.Rig()
rig.show(frame)

:::fold send it as sACN to this machine, receive it, and draw both
// Nested scopes close the sender even if receiver construction fails.
val port = 35692
DmxOutput(DmxProtocol.SACN, host = "127.0.0.1", port = port, autoStart = false, discovery = false).use { out ->
    DmxInput(DmxProtocol.SACN, port = port, bindAddress = "127.0.0.1").use { node ->
        rig.patch.render(out)
        out.flush()
        val deadline = System.nanoTime() + 2_000_000_000L
        while ((node.version(1) == 0L || node.version(2) == 0L) && System.nanoTime() < deadline) Thread.sleep(5)
        check(node.version(1) > 0L && node.version(2) > 0L) {
            "Incomplete sACN frame on port $port after 2 seconds: universe 1=${node.version(1)}, universe 2=${node.version(2)}. Check sender address/port."
        }
        showPatch(rig.patch, channel = "sent", w = 540.0, h = 330.0)
        showReceived(rig.patch, node, channel = "received", w = 540.0, h = 330.0)
    }
}
:::endfold
```

The first panel is the scene: an orange square that crosses the wall and turns, a line a quarter of
a unit wide sweeping from left to right, and a blue band along the bottom that fades in and out.
In RectSL it reads:

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
context is involved, so the lights keep running on a machine whose display is asleep.

```diffstatic {data-lang=kotlin}
val scene = RectSLImager(source)
val img = scene.frame(1.5, 256, 128) // colours as typed: 0x808080 is 0.502
val light = scene.frame(1.5, 256, 128, linear = true) // the light that stands for: 0.216
```

There are two choices of value. A frame holds colours *as typed* by default, which is what the
RectSL pictures show and what an 8-bit target stores. `linear = true` gives the light those values
stand for. The difference matters in two places. Averaging pixels is only right on light, and an
LED's PWM output is linear in light. So a rig driven with linear frames at gamma 1 and a rig driven
with as-typed frames through `FixtureProfile.withGamma(2.2)` come out at roughly the same
brightness, but only the first gives a thin line the correct total energy when it is averaged into
a fixture, as the section on thin lines shows.

## What the wire does

Both panels above come from one frame. The readout below covers nine frames of the scene, sent and
received the same way on a port of their own. It compares every slot byte and every fixture's
colour, then runs the scene on the sender's own 44 Hz clock for three seconds and times it.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_scene_to_light/readout.png" wait="14" capture="box" open-outputs="true" w="1120" h="480" padding="18" timeout="240" name="chain-scene-to-light-readout" embed=true alt="Three tables: the round trip of nine frames of 132 fixtures, with no slot byte changed and pixels and washes both within half a level; the frame cost and the receiver's frame spacing over three seconds at 44 Hz; and the worst decode error of six fixture profiles over random colours, largest for RGBWA"}
:::static
import afield.io.dmx.*

val trip = SceneOnARig.roundTrip(port = 35693)
val timing = SceneOnARig.timing(port = 35694, seconds = 3.0)
val floor = SceneOnARig.decodeErrors()

:::fold the readout
fun f(v: Double, d: Int = 2) = "%.${d}f".format(v)
markdown(
    "roundtrip",
    """
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
""",
    width = 1000,
)
:::endfold
```

In this run, no slot byte changes on its way over loopback. The remaining error comes from
quantisation. An RGB pixel's colour is rounded to 8 bits once, so it comes back within half a level. A wash is
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

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_scene_to_light/aliasing.png" wait="14" capture="box" open-outputs="true" w="1180" h="700" padding="18" timeout="240" name="chain-scene-to-light-aliasing" embed=true alt="A plot of the total light on the rig over three seconds: the point-sampled route is zero most of the time with tall spikes each time the line passes a column of sample points, and the area-averaged route is a flat line at two fixtures' worth, the light the line carries; below, two rigs at the same instant, the point-sampled one dark and the area-averaged one with two dim columns"}
:::static
import afield.io.dmx.*
import afield.lang.rectsl.*

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
showPatch(rig.patch, channel = "point samples at t = ${times[k].f3} s", w = 540.0, h = 300.0)
rig.show(frame, area = true)
showPatch(rig.patch, channel = "area average at t = ${times[k].f3} s", w = 540.0, h = 300.0)

markdown(
    "counts",
    """
| route | frames with the rig dark | light on the rig, min – max | mean over the sweep | (max − min) / mean |
|---|---|---|---|---|
| point samples | ${point.darkFrames} of ${point.total.size} | ${point.min.f2} – ${point.max.f2} | ${point.mean.f3} | ${point.flicker.f2} |
| area average | ${area.darkFrames} of ${area.total.size} | ${area.min.f3} – ${area.max.f3} | ${area.mean.f3} | ${area.flicker.f4} |
""",
    width = 1000,
)
:::endfold
```

Both rigs in the figure are coloured from linear light and drawn as it is, so the area-averaged
column is dim: each of those fixtures carries an eighth of a white one, the line's quarter split
between two columns.

Point sampling makes the rig strobe. The line lights a column of eight fixtures at full brightness while
it is over their sample points and disappears in between, so the rig flashes once per column. It
also overshoots: at a peak, eight fixtures are fully lit by a line that covers a quarter of one
column. Averaged over the whole sweep the strobe delivers about the right amount of light, but at
the wrong times. Area averaging puts the right amount of the line's light onto the rig wherever the
line is, and as the line crosses from one column to the next, the light moves between them without
flickering.

That is why `SceneOnARig.Rig.show` shrinks the frame first:

```diffstatic {data-lang=kotlin}
patch.mapImage(frame.resize(16, 8)) // one pixel per fixture, each the mean of its cell
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

## Running it live

Open [chain_scene_to_light](agent_transcripts.md#chain_scene_to_light) from the welcome screen's
starting points. No lights are needed: the
universes go to this machine on port 35690 and come back. Launch `1. Scene on the rig`: it draws
the scene from this page, shrinks it to one pixel per fixture, and sends it on the 44 Hz clock,
showing the look it sends. Launch `2. Receiver`: it listens on the same port and draws the rig from
what arrives. Run `3. Measure` to repeat this page's numbers on your machine. Edit the scene in the
first box and launch it again to see the change reach the second; `area = false` in that box brings
back the flickering line.

To drive real lights, give `sacnOut` a node's address (`sacnOut("10.0.0.20")`), or no host at all
for multicast.

## Where each piece is documented

* [RectSL Examples](rectsl_examples.md) and the [RectSL Cheatsheet](rectsl_cheatsheet.md) for the
  language; [Talking a Scene into Existence](rectsl_conversation.md) for the study tools whose CPU
  rasterizer `RectSLImager` uses.
* [Driving Lights: Art-Net and sACN](dmx_lighting.md) for `Patch`, the profiles, `mapImage`, the
  outputs and inputs, and `showUniverse`.
* [A RectSL Animation as a Solid](chain_rectsl_spacetime.md) is the neighbouring chain: the same
  language read as a volume over *(x, y, t)* instead of a sequence of frames.
