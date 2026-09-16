# A pattern in space: `pat` → Dough → Steam Audio

A pattern says *what* and *when*. This path adds *where* — and it adds it with a tag, not an API.

```diffstatic {data-lang=kotlin}
audio {
    patSpatial(
        pat("bd ~ sd ~, hh*8").with(
            param("az", sine().range(-70.0, 70.0)),
            param("dist", "1 3 1 6"),
        ),
        cps = 2.0,
    )
}
```

**Put headphones on.** HRTF rendering assumes each ear hears only its own channel. Over speakers it
collapses to something close to ordinary panning, and everything below stops being true.

---

## Whether this is the path you want

| | |
| --- | --- |
| **Use it when** | you want a rhythm or a line to come from *somewhere* — a direction, a distance, a thing you can walk around — and you want the placement written in the pattern rather than driven from outside it |
| **Use it when** | you want dough's synth vocabulary: `s`, `note`, `lpf`, `room`, `crush`, `fmi` and the rest of superdough's parameters, which this path forwards untouched |
| **Don't** | if you need it in a file. This path is **realtime only**, and that is not a missing feature — see [why there is no clip of the output](#why-there-is-no-clip-of-the-output) |
| **Don't** | if you need sample-exact onsets. Notes quantise to a 128-frame dough block, 2.9 ms at 44.1 kHz. [`patSynth`](sound.md) is sample-exact and proves it |
| **Don't** | if you want dozens of independently placed voices. Each `spat` group costs a dough engine — about 200 MB of wasm and a few hundred milliseconds to boot — and the default ceiling is four |
| **Needs** | the dough wasm build (tracked here) **and** Steam Audio's `libphonon`, which is **not** committed: `./gradlew fetchSteamAudioNatives` |

The neighbours, so you can rule this out quickly: [`patSynth`](sound.md) is the sample-exact stand-in
voice with no spatialisation; [`faustPoly`](faust.md) is a Faust instrument played by a pattern, also
sample-exact, also unplaced; `spatial-openal` is Field's other spatial engine, which places *sources
you position from code* rather than a pattern, and which — unlike this path — has an offline loopback
mode. [Making a sound in Field: every path there is](sound_paths.md) puts all eight side by side.

---

## The tag is the spatial parameter

A pattern's named parameters ride on `PatEvent.annotations`, and `with` writes any key at all into
that map. So the pattern language needed no extension to describe where a sound is. Six keys are read
by this path; **every other key is forwarded to dough untouched.**

| tag | means | default |
| --- | --- | --- |
| `spat` | which spatial source — i.e. which dough engine — the event plays on | `"0"` |
| `az` | azimuth in degrees; 0 ahead, +90 to the right | 0 |
| `elev` | elevation in degrees; +90 overhead | 0 |
| `dist` | distance in metres | 1.5 |
| `occl` | occlusion, 0 clear to 1 fully blocked | 0 |
| `air` | 1 to apply air absorption over distance, 0 for none | 1 |

```diffstatic {data-lang=kotlin}
patSpatial(
    pat("bd ~ sd ~, hh*8").with(
        paramOf("spat", pat("<kit hats>")),
        param("az", sine().range(-70.0, 70.0)),
        param("dist", "1 3 1 6"),
    ),
    cps = 2.0,
)
```

Because `az` takes a *continuous* pattern, `sine()` sampled per event is a source that moves between
notes. And because the placement is held on the **source** rather than on the note, that group's next
event moves the whole group. The tags do not decorate the sound, they aim it.

**An atom is read as a note if it is a number and as a sound name if it is not.** `pat("36 48")`
plays MIDI notes through `s`'s default `saw`; `pat("bd sd")` asks dough for sounds *called* `bd` and
`sd`. Both work — dough synthesises its own drums, so `pat("bd ~ sd ~, hh*8")` transfers from
`patSynth` unchanged — but they reach dough as entirely different events, and they are not equally
bright: measured, the drum spelling of one rhythm comes out at a 678 Hz spectral centroid and the
note spelling at 4677 Hz. `synth.pat-spatial.sound` renders both and prints both.

Two more behaviours of the placement itself:

* A placement change is **approached**, not jumped to — one pole per block, about 5 ms. A note that
  jumps 140° of azimuth between two blocks would step the HRTF filter discontinuously, which is
  audible as a click on every note.
* `dist` is clamped at 0.4 m, so a source cannot land inside the head, and air absorption is a
  per-band exponential in distance using Steam Audio's own default coefficients.

---

## What Steam Audio is handed

Here is the signal at the point just before the part that has no figure. One `spat` group, one dough
engine, every event sent `pan/0` — this is the buffer `PatSpatialRoom.spatialiseSource` copies into
`source.mono` and gives to the binaural effect.

```diffstatic_runner {data-lang=kotlin audio="assets/pat_spatial/spatial-source-plain.wav" audio-spec="spatial-source-plain" audio-renderer="afield.four.synth.PatSpatialSoundDocs" embed=true hidden=true name="pat-spatial-source-plain"}
// PatSpatialSoundDocs.renderWav("spatial-source-plain", …)
// patSpatialEventsForArc(pat("36 ~ 48 ~, 72*4") with gain(0.9), az = -70, dist = 1.5)
// -> one DoughEngine, 128-frame blocks, left channel only
```

**The mono source, as Steam Audio is handed it.** Not the spatialised output — this is `source.mono`, one `spat` group's dough engine with every event sent `pan/0`, which is the buffer the binaural effect takes as its input.

```diffstatic_runner {data-lang=kotlin audio="assets/pat_spatial/spatial-source-forwarded.wav" audio-spec="spatial-source-forwarded" audio-renderer="afield.four.synth.PatSpatialSoundDocs" embed=true hidden=true name="pat-spatial-source-forwarded"}
// PatSpatialSoundDocs.renderWav("spatial-source-forwarded", …)
// the same pattern, plus param("lpf", 700) — not a spatial tag, so forwarded verbatim
```

**The same notes, with `lpf` added to the pattern.** `lpf` is not one of the six spatial tags, so it is forwarded to dough untouched and the engine's own filter answers for it. Everything you can hear between these two clips is that forwarding rule.

Three things about this path that the clips **cannot** show, because both of them are peak-normalised
like every other figure on this site, are measured on the un-normalised buffers instead and printed
by `run_field_tests.py --id synth.pat-spatial.sound`:

1. **`pan/0` is a lossless tap, not a downmix.** Steam Audio spatialises a *mono* signal, and
   `DoughEngine` mixes its 32 voices to stereo with `MAX_ORBITS = 1` — there is no way to pull two
   separable signals out of one engine. So every event goes out with `pan/0`, which puts the whole
   of that engine's energy in its left channel, and that channel *is* the mono source. Measured,
   the right channel dough produces peaks at **exactly 0.000e+00** on both figures: not a gain law
   landing near zero, but nothing at all. That is what lets the render thread `arraycopy` the left
   channel instead of computing `0.5*(L+R)` and halving the level.
2. **The six spatial tags never reach dough.** Asserted on the event maps rather than on the audio:
   across the 24 events of these four cycles, `spat`, `az`, `elev`, `dist`, `occl` and `air` are
   consumed here and `lpf` is not — it arrives at dough as `700`.
3. **`az` is not in the signal at all.** The same pattern rendered at `az = 0` and at `az = -70`
   produces a **bit-identical** mono source: 124928 samples, 0 of them differing. That is the
   sharpest way to say what the next section says at length — the direction lives entirely in the
   effect. It also happens to be the reason these two clips can be published under a contract that
   re-renders them on every build: two separately booted GraalWasm engines agreeing to the bit is
   the determinism that contract requires.

And the second figure's claim, as a number:

| figure | spectral centroid | vs the first | peak | RMS |
| --- | --- | --- | --- | --- |
| `spatial-source-plain` | 4677 Hz | — | 0.284 | 0.078 |
| `spatial-source-forwarded` | 544 Hz | 0.12× | 0.198 | 0.059 |

A saw at note 72 has harmonics well past 700 Hz, and dough's own filter takes them. The guard
requires that ratio to stay under 0.6×, so a `lpf` that stopped reaching dough would fail the build
rather than publish two clips that sound alike.

## Why there is no clip of the output

**There is no published clip of this path's actual output, and there cannot be one.** The site's own
audio contract rules it out, in three steps:

* **The docs build renders audio offline.** An `audio=` fence calls
  `<renderer>.renderWav(scene, path)` over the backdoor — no device, no clock, no browser. There is
  no offline half of `patSpatial` to call. `PatSpatialLaunch.advanceOffline` looks like one and is
  not: the render thread is running regardless, so that call only *consumes* what has already been
  produced, in real time. A caller asking for four seconds of audio waits four seconds, and no flag
  makes it faster: what it is waiting for is a render thread that runs on its own clock, decoupled
  from whoever consumes it.
* **The HRTF needs a binary this repository does not carry.** `libphonon` is fetched, checksum
  pinned, into `build/steamaudio` by `./gradlew fetchSteamAudioNatives`. On a clean clone, on CI,
  and in `check`, a render of this path does not merely come out different — it cannot start.
* **`DocsAudioProvenance` re-renders every fence on every build**, correlates the result against the
  committed bytes, and fails when a render fails. So a clip of this path behind a fence would not be
  a figure with a caveat; it would be a red build on every machine that has not run one optional
  Gradle task. See
  [how the captions on every clip are checked](sound.md#how-the-voice-captions-are-checked).

The two clips above make the smaller claim instead: they are rendered by the code that really ships,
which is what the provenance audit can check. Why an offline stand-in for the spatialised output was
rejected rather than written is in `dev_docs/PAT_SPATIAL_MEASUREMENTS.md`.

To hear the real thing takes two commands.

```diffstatic {data-lang=bash}
./gradlew fetchSteamAudioNatives
```

```diffstatic {data-lang=bash}
tools/run_field_tests.py --id synth.pat-spatial.engine
```

That opens an audio device and plays two sources at opposite azimuths, circling. On a machine with no
device it says so and stops rather than passing for the wrong reason.

---

## One dough engine per source

Steam Audio spatialises a mono buffer — a direction is a property of a whole buffer, not of a voice
inside it. That single fact decides the shape of this path: one `DoughEngine` per `spat` group, which
is also **why `spat` exists at all** rather than every event getting its own position.

That costs the pattern two things:

* **A pattern's `pan` is overridden.** Every event is sent `pan/0`. Panning is lossless this way, but
  `pan` as a musical parameter is gone. `az` replaces it, and it is a better parameter.
* **Note onsets quantise to a dough block** — 128 frames, 2.9 ms at 44.1 kHz. Dough takes an event
  through `evaluate()` and applies it at the next `dsp()`, so unlike `PatSynthBank`, which is
  sample-exact and proves it by rendering bit-identically at four buffer sizes, this path cannot
  place a note inside a block. That is a property of dough's event API, not of the scheduler.

Engines are booted **ahead of the audio**, not on the first event that names a tag: `prescanCycles`
(8 by default) opening cycles are searched for `spat` tags and their engines are built before a
sample is produced. A tag that first appears later than that is counted by
`PatSpatialRoom.lateSourceBoots`, and each one is a likely dropout — a boot of a few hundred
milliseconds inside a block that owes the audio thread 2.9 ms is longer than any reasonable ring
holds.

---

## Why dough is not on the audio thread

`patSynth` puts the pattern *query* on a feeder thread because querying allocates, and keeps
synthesis on the audio thread because Field's own voices are a fixed amount of float arithmetic.
Neither half of that argument survives here. `DoughEngine` is GraalWasm: every block enters a
polyglot context, and the JIT and the collector get a say in how long that takes.

`PatSpatialBudgetProbe` measures four sources against a block's 2.902 ms budget, idle and under load,
and the two runs differ only in the tail. The median is *flat* — four wasm engines and eight Steam
Audio effects are about 4% of a block either way, so nothing here needs optimising. The tail moves by
a factor of three, with load the audio thread has no say over. A path whose median fits twenty-five
times over and which still misses one block in a hundred is not a path to shave down; it is one to
**decouple**. Both runs' percentiles are in `dev_docs/PAT_SPATIAL_MEASUREMENTS.md`.

So `PatSpatialRoom` renders on its own thread into a `PatSpatialRing`, and the audio thread's entire
job is a bounded copy out of that ring — no synthesis, no spatialisation, no allocation, no locks.

Raising that thread's priority was tried and reverted. As a high-priority poller on a loaded machine
it preempted the audio engine's own 4 ms loop badly enough that OpenAL stopped reporting processed
buffers at all: the output went silent while the render thread sat on a *full* ring, which looks
nothing like a priority problem and cost an hour to pin on the one line that caused it. The comment
in `PatSpatialRoom.start` is still there.

---

## What actually limits this

Not the arithmetic — the scheduler. On a heavily loaded ten-core machine `PatSpatialEngineSmoke`
recorded the render thread falling short of the blocks a four-second window needed while it was busy
only about a third of that window. A thread that is idle two thirds of the time and still misses its
deadline is not a thread that needs faster code; it is a thread that is not being scheduled. That
run's figures are in `dev_docs/PAT_SPATIAL_MEASUREMENTS.md`.

Two things follow, and both are in the code rather than in advice:

* `PatSpatialRoom.busyMsTotal` against elapsed time separates the two ways of falling behind. Near
  100% busy *with* underruns means the work does not fit and this design is wrong. Far below it means
  the machine is oversubscribed, and the smoke says so instead of failing.
* **The ring is sized for the scheduler's stalls, not for this code's**, which is why it defaults to
  341 ms rather than the ~40 ms the arithmetic alone would justify.

### The numbers that tell you whether it held

Everything below is a field on the launch or the room, readable from the box while it plays:

| what to read | what it means |
| --- | --- |
| `launch.underruns` | frames the audio thread asked for and the ring could not supply. **Zero is the whole point** |
| `launch.room.ring.fill` / `.minFill` | how full the ring is, and how close it has ever come to empty |
| `launch.room.worstBlockMs` | the longest single block the render thread has taken |
| `launch.room.lateSourceBoots` | engines booted after the ring was already feeding an audio thread |
| `launch.room.busyMsTotal` | milliseconds of real work, to compare against elapsed time |
| `launch.sources[i].peakVoices` | the most voices ever sounding at once in that group's engine |

Latency costs nothing musically. The pattern is queried ahead, so notes stay in time with each other;
what the ring delays is only how soon a *start* or a *stop* is heard.

---

## The two ways to get it wrong

**A sample-rate mismatch is not a resampling.** The ring has a producer on one clock and a consumer
on the other, so at 44.1 against 48 the audio thread consumes 9% faster than the render thread
produces: the ring empties, and what does arrive is transposed up a tone and a half. Both symptoms
read as a synth problem rather than a clock problem, so `registerPatSpatialReturnValue` refuses the
mismatch outright and names the rate to pass instead.

**Attaching a consumer too early drains a ring that is still warming up.** `awaitReady()` returns
when Steam Audio is up, every prescanned engine has booted, *and* the ring has filled once — not when
the thread started. Wait for it before handing a launch to an audio engine.

---

## Hearing it in a box

```diffstatic {data-lang=kotlin}
showPatSpatial(
    pat("60 64 67 71").with(
        paramOf("spat", pat("a b a b")),
        param("az", sine().range(-85.0, 85.0)),
        paramOf("dist", pat("1.2 3 1.2 5")),
        param("lpf", 2200),
    ),
    cps = 2.0,
)
```

`showPatSpatial` puts a live waveform and spectrum in the box's output and hands back the
`PatSpatialLaunch`, so returning it as the box's last expression is what ties the sound to the box:
the box stays lit while it plays, and stopping it stops the render thread and closes every dough
engine and Steam Audio handle with it.

`launch.turnListener(deg)` turns the listener's head from any thread — positive to the right, so a
source dead ahead moves to your left. The render thread applies it at the top of a block.

---

## How the claims on this page are checked

| smoke | what it establishes |
| --- | --- |
| `synth.pat-spatial.smoke` | that a **tag** becomes a spatial parameter, on real audio. Patterns differing only in `az` or `dist` must produce audio differing in the matching way |
| `synth.pat-spatial.engine` | that it reaches a real output: the engine's own thread pulled the ring, the frame counter moved, the tap saw signal, nothing underran |
| `synth.pat-spatial.budget` | the per-block cost, idle and under load. Machine- and load-dependent; what reproduces is the *shape*, a median far inside budget and a tail far outside it |
| `synth.pat-spatial.sound` | the two published clips, plus the three claims they cannot carry themselves |
| `synth.sound.path.map` | that this path is declared, on the seam, and named on [sound_paths.md](sound_paths.md) — the guard that caught it arriving |

The first three need `./gradlew fetchSteamAudioNatives`; the fourth does not, which is exactly the
line this page's missing figure falls on.

The full API — every field, every default, every parameter — is in
[`afield.four.synth` in the generated reference](reference/patterns.md#patspatial-kt).
