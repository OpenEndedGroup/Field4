# Csound

[Csound](https://csound.com) is a language for sound: an *orchestra* of instruments, written as
lines of opcodes, and a *score* that says when each instrument plays. Field runs Csound in-process,
through its C library. An orchestra plays through the same audio engine as Faust and Pure Data.
Kotlin writes its control channels and sends it score lines, and what Csound prints goes to the
box's output.

## Building libcsound

Field does not ship a Csound binary. Build one once:

```diffstatic {data-lang=bash}
./gradlew buildCsoundNative
```

This needs `cmake`, `flex`, `bison` and libsndfile (`brew install libsndfile`, or
`apt install libsndfile1-dev`), and the network the first time. It downloads the Csound 6.18.1
source, checks it against a pinned checksum, and builds just the library into `build/field-csound/`.
The command-line programs, audio drivers and plugin opcodes are left out. The download is cached per
user, so other checkouts and worktrees reuse it. An installed Csound
(`/Library/Frameworks/CsoundLib64.framework`, or `libcsound64.so.6.0` on Linux) works too, and
`-Dafield.csound.library=<path>` or `AFIELD_CSOUND_LIBRARY` points Field at any other one. Until
one is found, the calls on this page fail with an error that says how to build it.

## An orchestra in a box

```diffstatic {data-lang=kotlin}
val drone = csound("""
chn_k "pitch", 1, 2, 110, 55, 440
chn_k "cutoff", 1, 2, 1200, 200, 6000
instr 1
  kf chnget "pitch"
  kc chnget "cutoff"
  kf port kf, 0.05
  kc port kc, 0.05
  a1 vco2 0.12, kf
  a1 moogladder a1, kc, 0.3
  outs a1, a1
endin
""", "i1 0 z")
csoundControls(drone)
drone
```

`csound(orchestra, score)` compiles both and returns a `CsoundLaunch`. Return it as the box's last
expression to hear it. Stopping the box stops the sound and frees the Csound. `i1 0 z` plays
instrument 1 from time 0 forever (`z` is Csound's "infinite"). A score that ends goes silent.

Leave out the orchestra header (`sr`, `ksmps`, `nchnls`, `0dbfs`). Field supplies it: the sample
rate is the audio engine's, two outputs, and `0dbfs = 1`, so a Csound sample of 1.0 is full scale
in Field too.

## Control channels

`chn_k "pitch", 1, 2, 110, 55, 440` declares an input channel called `pitch` with default 110 and
range 55 to 440. `csoundControls(launch)` reads those declarations and makes one slider per input
channel, with Csound's own default and range. The range is written once, in the orchestra.

From Kotlin, `launch.set("pitch", 220)` writes a channel and `launch.get("level")` reads one that the
orchestra writes with `chnset`. Both are safe from any thread. The orchestra sees a new value on its
next control cycle.

## Writing the score from Kotlin

```diffstatic {data-lang=kotlin}
val cs = csound("""
instr 1
  aenv expseg 0.001, 0.005, 0.2, p3, 0.001
  a1 oscili aenv, cpsmidinn(p4)
  outs a1, a1
endin
""")
val notes = listOf(57, 60, 64, 67, 69, 67, 64, 60)
var i = 0
cs.every(0.25) { event('i', 1, 0, 0.4, notes[i++ % notes.size]) }
cs
```

With no score, `csound` uses `f0 z`, which keeps Csound running with nothing playing.
`event('i', 1, 0, 0.4, 60)` sends the score line `i 1 0 0.4 60`, and `event("i1 0 1 440")` sends one
as text. `every(seconds) { }` runs its block on Field's main loop while the box plays. That timing
is good to a frame or so. For tight rhythm, put the timing in the score and let Kotlin choose the
notes. `compile("instr 2 … endin")` adds or replaces instruments in the running Csound.

## Rendering without an audio device

```diffstatic {data-lang=kotlin}
val (left, right) = csoundOffline("""
instr 1
  aenv linen 0.25, 0.01, p3, 0.05
  a1 oscili aenv, 440
  outs a1, a1
endin
""", "i1 0 1", wav = java.io.File("/tmp/sine.wav"))
val crossings = (1 until 48000).count { left[it - 1] < 0f && left[it] >= 0f }
println("$crossings upward zero crossings in a second")
```

`csoundOffline` renders until the score ends (or for `seconds = …`), puts a player and a waveform in
the box's output, and returns the samples. `wav =` writes a 16-bit stereo `.wav` as well. The
example prints 439 crossings: the envelope starts at zero, so the first cycle's crossing is not
counted.

For more control, use `afield.csound.CsoundInstance` directly. It has `renderOffline`,
`renderToWav`, `table(n)` to read a function table, and `console`, the lines Csound has printed.

## Errors

Csound's compile errors go to the box's output and are marked on the line they belong to:

```diffstatic {data-lang=kotlin}
csoundOffline("""
instr 1
  a1 oscilx 0.2, 440
  outs a1, a1
endin
""", "i1 0 1")
```

This marks the `oscilx` line with "Unable to find opcode entry for 'oscilx'", and the call throws
`CsoundCompileError`, whose `problems` list carries each message with its orchestra line.

## The demo

`dev_docs/agents/transcripts/csound_demo.yaml` builds five boxes in a fresh document: where libcsound
was found, the offline sine, the drone with its sliders, the Kotlin-written score, and the error.
Replay it with the backdoor's `macro write csound_demo --file …` and `macro run csound_demo`.

??? aside "What this page does not capture"
    The fences here are static. The docs runner has no `requires="csound"` gate yet, so a capture
    would fail on any machine that has not built libcsound. The numbers quoted (439 crossings, the
    marked line) were observed by running the demo boxes through the backdoor on 2026-10-02, and
    `CsoundTest` pins the measurements: a 440 Hz sine's crossings, peak and RMS, channel round trips,
    live events and error lines.
