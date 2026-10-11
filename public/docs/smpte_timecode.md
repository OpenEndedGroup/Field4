# SMPTE Timecode: LTC and MIDI Time Code

SMPTE timecode is the `HH:MM:SS:FF` clock that lighting desks, video servers, DAWs and show
controllers share. It travels two ways. LTC (linear timecode) is an audio signal, a square-ish
buzz on a spare audio track or cable. MTC (MIDI Time Code) is the same time carried in MIDI
messages. Field reads and writes both, and turns either into a clock a box can ask "what time is
it?" from its draw loop.

Everything is in the package `afield.timecode`, which box code imports:

```diffstatic {data-lang=kotlin}
import afield.timecode.*
```

## Timecode values and drop-frame

A `Timecode` is a frame count at a `TimecodeRate`: `FPS_24`, `FPS_25`, `FPS_30`, or `FPS_2997_DF`,
29.97 frames a second with drop-frame labels. Adding and subtracting frames is integer arithmetic on
the count; the hours, minutes, seconds and frame fields are worked out from it.

Counting frames this way is what makes drop-frame simple. NTSC video runs at 30000/1001 frames a second, slightly slower than 30,
so a clock that labelled 30 frames a second would fall 3.6 seconds behind the wall each hour.
Drop-frame keeps up by skipping the labels `;00` and `;01` at the start of every minute except every
tenth minute. No frames are dropped, only labels. A `;` before the frames marks a drop-frame
timecode, and `parse` reads it that way:

<!--
FIELD_TEST
id: docs.smpte-timecode.arithmetic
runner: backdoor-box
tags: [docs, timecode]
timeoutSec: 60
expect:
  outputContains:
    - "00:01:00;02"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/smpte_timecode/arithmetic.png" alt="the box's output: 00:00:59;29 plus one frame is 00:01:00;02, 00:09:59;29 plus one frame is 00:10:00;00, and an hour of drop-frame labels is 3599.996 seconds of wall time" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="smpte-arithmetic" w="760" h="140"}
:::static
import afield.timecode.*

val t = Timecode.parse("00:00:59;29")
val tenth = Timecode.parse("00:09:59;29")
val hour = Timecode.parse("01:00:00;00").totalSeconds
markdown("main", "`$t` + 1 frame = `${t + 1}`  \n`$tenth` + 1 frame = `${tenth + 1}`  \n" +
    "an hour of drop-frame labels is ${"%.3f".format(hour)} s of wall time")
```

At minute 1 the label jumps from `;29` to `;02`. At minute 10 it does not skip. `totalSeconds` is
real time, so an hour of drop-frame labels comes to an hour of wall time within a few
milliseconds. `Timecode.of(h, m, s, f, rate)` builds one from fields and refuses a label that does
not exist, such as `00:01:00;00`. `Timecode.fromSeconds(seconds, rate)` goes the other way. A
timecode wraps at 24 hours.

## LTC: timecode as audio

Each LTC frame is 80 bits: the time in binary-coded decimal, 32 user bits, and a fixed 16-bit sync
word that marks where a frame ends and which way it is playing. The bits are written biphase-mark:
the signal flips at the start of every bit, and flips again half-way through a 1. A reader therefore
only needs the time between flips, not the signal level, and the level can be inverted.

`LtcEncoder.render(start, seconds)` returns the samples as a `FloatArray`, and `TimecodeWav.write`
saves them as a mono `.wav` to play out of an interface into a desk or a video server:

```diffstatic {data-lang=kotlin}
import afield.timecode.*

val ltc = LtcEncoder.render(Timecode.parse("10:00:00:00", TimecodeRate.FPS_25), seconds = 60.0)
TimecodeWav.write(java.io.File("/tmp/ltc_25fps.wav"), ltc, sampleRate = 48000)
```

`LtcDecoder` reads it back. It is streaming: hand it blocks of samples of any size with `feed`, and
it returns the frames completed in that block, each with the timecode, its user bits, the sample it
started at, whether it was read backwards, and the playback speed it measured. A box below renders
four seconds of drop-frame LTC across the minute boundary, plays it back badly (a tenth of the
level, a DC offset, noise, and a speed that ramps from 0.9× to 1.1×), and decodes it:

<!--
FIELD_TEST
id: docs.smpte-timecode.ltc-roundtrip
runner: backdoor-box
tags: [docs, timecode]
timeoutSec: 90
expect:
  outputContains:
    - "consecutive: true"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/smpte_timecode/ltc_roundtrip.png" alt="a plot: the decoded frame number rises steadily over four seconds of playback, while the measured speed climbs in a straight line from 0.9 to 1.1; below it, 118 frames decoded, consecutive, with 00:00:59;29 followed by 00:01:00;02" wait-output=true wait-output-kind=display wait-output-channel="info" capture="box" embed=true name="smpte-ltc-roundtrip" w="760" h="420"}
:::static
import afield.timecode.*

// Four seconds of 29.97 drop-frame LTC across the one-minute boundary.
val start = Timecode.parse("00:00:58;00")
val ltc = LtcEncoder.render(start, 4.0, sampleRate = 48000)

// Play it back badly: quiet, offset, noisy, and speeding up from 0.9x to 1.1x.
val rng = java.util.Random(1)
val played = ArrayList<Float>()
var p = 0.0
while (p < ltc.size - 1) {
    val k = p.toInt()
    val v = ltc[k] + (ltc[k + 1] - ltc[k]) * (p - k).toFloat()
    played += v * 0.1f + 0.2f + rng.nextGaussian().toFloat() * 0.01f
    p += 0.9 + 0.2 * p / ltc.size
}

val frames = LtcDecoder(48000, TimecodeRate.FPS_2997_DF).decodeAll(played.toFloatArray())

// Decoded frame number against playback time, and the speed the decoder measured.
val w = 640.0; val h = 220.0
val tMax = played.size / 48000.0
val fMax = frames.maxOf { it.timecode - start }.toDouble()
val pts = frames.joinToString(" ") { "%.1f,%.1f".format(it.sampleStart / 48000 / tMax * w, h - (it.timecode - start) / fMax * h) }
val spd = frames.joinToString(" ") { "%.1f,%.1f".format(it.sampleStart / 48000 / tMax * w, h - (it.speed - 0.8) / 0.4 * h) }
display("main", """<svg width="${w + 60}" height="${h + 40}" style="font: 12px sans-serif">
  <g transform="translate(40,10)">
    <rect width="$w" height="$h" fill="none" stroke="#999"/>
    <polyline points="$pts" fill="none" stroke="#2a7" stroke-width="2"/>
    <polyline points="$spd" fill="none" stroke="#c52" stroke-width="1.5"/>
    <text x="4" y="14" fill="#2a7">decoded frame (0 … ${fMax.toInt()})</text>
    <text x="4" y="${h - 6}" fill="#c52">measured speed (0.8 … 1.2)</text>
    <text x="${w / 2 - 40}" y="${h + 22}" fill="#999">playback time, s (0 … ${"%.2f".format(tMax)})</text>
  </g></svg>""")
markdown("info", "decoded **${frames.size}** frames, ${frames.first().timecode} … ${frames.last().timecode}; " +
    "consecutive: ${frames.zipWithNext().all { (a, b) -> a.timecode + 1 == b.timecode }}; " +
    "across the minute: ${frames.map { it.timecode.toString() }.filter { it.startsWith("00:00:59;2") || it.startsWith("00:01:00;0") }}")
```

Four seconds at 29.97 is 120 frames. The decoder reports 118: it spends the first frame learning
the bit length, and the last frame is only complete when the signal flips after it, which it never
does at the end of a buffer. In a running stream neither costs anything after the first frame.

The decoder follows the level (it tracks the signal's envelope), removes a DC offset, and tracks the
bit length rather than assuming it, so speed changes of ±10 % and a ramp between them decode
cleanly. Played backwards, frames come out in descending order with `reverse` set. Two checks keep
noise from producing a frame: the polarity bit makes every genuine frame's count of ones even, and a
frame's 80 bits must be evenly spaced.

!!! note "Tell the decoder the frame rate"
    The bits carry a drop-frame flag and nothing else about the rate. Pass the rate to
    `LtcDecoder(sampleRate, rate)` when you know it. Without one it guesses from the measured bit
    length, which is right only when the source plays at close to real time: 24 and 25 fps are 4 %
    apart.

## MIDI Time Code

While a MIDI clock source runs, it sends eight quarter-frame messages every two frames, each
carrying one 4-bit piece of the time. A receiver has the whole time only after the eighth, by which
point two more frames have passed, and `MtcDecoder` adds them back. When the time jumps, the source
sends one full-frame SysEx (`F0 7F 7F 01 01 hh mm ss ff F7`) instead. `Mtc.quarterFrames(tc)` and
`Mtc.fullFrame(tc)` build the bytes, and `MtcDecoder.accept` takes any MIDI message and ignores
what is not timecode.

Through a MIDI port opened with `MidiDevices`, `MidiOutput.sendMtcQuarterFrames(tc)` and
`sendMtcFullFrame(tc)` send, and `MidiInput.mtcDecoder()` listens:

```diffstatic {data-lang=kotlin}
import afield.timecode.*

val input = MidiDevices.openInput(MidiDevices.findInput("IAC")!!)
val clock = TimecodeClock(TimecodeRate.FPS_25).followMtc(input)
```

## A clock a box can read

`TimecodeClock` turns any of these into one answer. `now()` is the current `Timecode`, `seconds` the
same as a `Double` for an animation or a keyframe timeline, and `locked` says whether the source is
still arriving. It can generate time itself, `generate(start)`, or follow LTC (`followLtc`, then
`feedLtc(samples)` from whatever delivers the audio) or MTC (`followMtc`). Between frames it
extrapolates on the wall clock at the measured speed, so a draw loop reading it 60 times a second
sees smooth time even though LTC arrives 24 to 30 times a second. When a followed source stops, the
clock runs on for `freewheelSeconds` (half a second) and then holds.

```diffstatic {data-lang=kotlin}
import afield.timecode.*

val clock = TimecodeClock(TimecodeRate.FPS_30).generate(Timecode.parse("00:59:50:00", TimecodeRate.FPS_30))
println(clock.now())        // 00:59:50:00, counting from the moment generate was called
```

Feed `feedLtc` from the thread that owns the audio buffers. The decoder holds per-sample state and
is not thread-safe. `now()`, `seconds` and `locked` are safe to read from any thread.

The four boxes behind this page, including MTC into a clock and a clock following LTC in
512-sample blocks, are the [smpte_timecode](agent_transcripts.md#smpte_timecode) starting point.
