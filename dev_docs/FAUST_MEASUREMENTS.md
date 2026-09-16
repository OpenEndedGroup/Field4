# Faust in Field: measurements, and the arrangements they decided

Moved out of `public/docs/faust.md` on 2026-09-13, where it was more than half the page and none of
it helped anybody write a Faust program. Everything here is measured by `synth.faust.poly` or
`synth.faust.smoke` on an M-series Mac, GraalVM CE 25.0.2. Re-run those rather than trusting these
numbers.

## Note onsets land on the sample

`FaustVoiceBank.render` enters the GraalVM context once and then walks the block in chunks that end
where the next event begins: apply the writes due at this exact frame, `compute` up to the next
event, repeat. `FaustDsp.setParam`'s queue is not used on this path, because it drains once per
`render` call, which is block granularity by construction.

Measured offline on a pattern of seven notes to a cycle, so that twelve of fourteen onsets fall
*inside* a block (`48000/7 = 6857`, and `6857 % 128 = 105`):

| | Frames | ms |
|---|---|---|
| median onset error | 1 | 0.021 |
| jitter (spread about the median) | ±7 | 0.15 |
| worst absolute error | 8 | 0.17 |
| what a per-block parameter write would give | up to 110 | 2.3 |

**None of that spread is the scheduler.** During a gap this program's output is exactly `0f` — the
envelope reaches zero and multiplies the oscillator away — which makes the first non-zero sample of a
note an exact fact about the render, with no threshold in it. It *is* the notated frame for 13 of 13
checkable notes, worst 0 frames.

The 0–8 spread in the table is the *onset detector* waiting for the attack ramp to clear its
amplitude threshold, which depends on where the free-running phasor happened to be when the envelope
opened. The two numbers answer different questions: the scheduler is exact to the sample, and an
onset detector on a bandlimited sawtooth resolves an attack to within 8 frames.

A patterned *parameter* shows it from the other side with no attack ramp in the way:
`pat("0 0 0 1 1 1 1")` on a `level` control rises at frame 20571 (91 frames into a block) and is
measured at frame 20571, error 0.

## How much a steal clicks

The statistic is the largest sample-to-sample delta across the steal, normalised by the local peak,
against the same statistic in the same note once it has settled:

| `stealReleaseFrames` | Normalised step across a steal | Stolen note is |
|---|---|---|
| `1` (the default) | 2.71× the settled step | on time |
| `1440` (30 ms, longer than this program's release) | 0.04× | 30 ms late |

A normalised step near 1.0 means the largest single-sample jump is as big as the whole local peak,
which is not a slope: it is the outgoing note's amplitude vanishing in one sample. With the gate dip
the envelope's local peak rises 1.36–1.39× across a steal; without it, 1.00×, which is the audible
symptom of a voice that never re-attacks.

## `render` is not allocation-free

An allocation in an audio callback is a GC pause in the audio path — a dropout — and it is invisible
to a µs-per-block average. Measured with `getCurrentThreadAllocatedBytes` over 4000 blocks:

| | Bytes per 128-frame block |
|---|---|
| `FaustVoiceBank`, 8 voices, steady state | 7386 (923 per voice) |
| `FaustVoiceBank`, 1 voice — the like-for-like comparison | 984 |
| `FaustDsp`, one voice — the monophonic path | 1075 |

One voice of the bank costs 0.92× what one `FaustDsp` costs, doing the same DSP and paying the same
once-per-block context enter and queue drain. Fitting the two bank measurements as a line gives 915
bytes per voice and a fixed 69 bytes per block, which places the source: `Value.readBuffer`, the one
bulk copy per channel per block that gets the samples out of WebAssembly, present in both paths.
Both bank measurements are held to a bound by `synth.faust.poly`.

The `voices = 1` row exists because the obvious comparison is wrong in a way that flatters this
code. Dividing an 8-voice block by 8 spreads the bank's fixed cost across eight voices while
`FaustDsp` pays its own once, for one. Measuring 1 and 8 voices in the *same run* and quoting the
slope and intercept is what makes the claim checkable; two figures from different runs, solved as a
pair, will happily imply a negative fixed cost per block.

**Open.** Removing the ~0.9 KB per voice per block means changing how samples leave WebAssembly for
*both* the monophonic and polyphonic paths — a `ByteBuffer`-backed view rather than a copying
`readBuffer`, if the polyglot API allows one. Not started.

## One context or N

`FaustDsp` gives each voice its own GraalVM context, and its KDoc records that the per-block
`Context.enter` dominates what is left after `compute`. That is the argument for hosting every voice
of a bank in one context. Measured with every voice sounding, against a 2667 µs budget for a
128-frame block:

| Voices | One context | N contexts | Ratio | One context |
|---|---|---|---|---|
| 1 | 2.1 µs | 2.5 µs | 1.20× | 1295× realtime |
| 2 | 4.2 µs | 3.8 µs | 0.91× | 640× realtime |
| 4 | 8.2 µs | 11.0 µs | 1.34× | 323× realtime |
| 8 | 16.9 µs | 22.6 µs | 1.34× | 158× realtime |
| 16 | 32.7 µs | 29.7 µs | 0.91× | 82× realtime |

Each figure is the **median of five timed windows** rather than the mean of one. `render` allocates
~7.4 KB per block at eight voices — ~15 MB over a 2000-block window — and an ordinary young
collection inside that moves the whole average with nothing in the number to say so: a single-window
mean reports 238 µs for eight voices in the same output that measures 13.4 µs for the same
configuration a few lines later. The median leaves the shape legible, and 2.1, 4.2, 8.2, 16.9, 32.7
is linear in voices.

At steady state the arrangement barely matters: once Truffle has compiled everything the per-block
`Context.enter` is a few hundred nanoseconds, and one context and N contexts are within noise of each
other either way round. Two measurements still decide it:

- **construction** — 6 ms for sixteen voices in one context against 46 ms for sixteen contexts
  (7.8×), because N contexts each compile the same WebAssembly module again;
- **sub-block scheduling**, the stronger of the two. A block split at eight event boundaries costs
  33.0 µs against 15.0 µs undisturbed (2.2×), still 81× realtime with a note starting every 0.33 ms.
  The N-context arrangement would pay N context entries per *chunk* rather than per block, and
  `FaustDsp`'s per-block parameter queue could not express the split at all.

These figures are much cheaper than the 60.4 µs quoted for a single `FaustDsp` block on the page, and
both are correct: that one is measured after 500 warm-up blocks, these after several seconds of real
rendering, by which point Truffle has compiled the wasm properly. The first bank built in a process
really does cost 118 µs a block until it warms.

## The three arrangements of the JVM↔wasm crossing

`FaustDsp`'s KDoc records all three, for one voice end to end:

- a call per *sample* to copy the output: 148 µs a block (18× realtime)
- one bulk buffer read per *channel* instead: 94 µs (28×)
- resolving the JS entry points once instead of per block: 60 µs (44×)

## Why the audio thread takes no lock

A callback that can block behind another thread has a worst case measured in that thread's
scheduling rather than in its own work, and the symptom is a dropout, not a slow number.

The shape that does *not* work is one priority queue under a mutex taken by both sides. `render`
acquires such a lock several times **per chunk**, and a chunk is as short as the gap between two
notes, while a feeder calling `schedule` with a cycle of notes holds it across one heap insert per
note, with the audio thread waiting behind the whole batch.

## The assertion that proves polyphony

`pat("[c4,e4,g4]")` is one chord — three events on the same arc, three notes on the same frame —
whose fundamentals are 130.81, 164.81 and 196.00 Hz. Nothing else can reach the band 100–230 Hz,
because the lowest second harmonic in the chord is 261.6 Hz, so every peak there is somebody's
fundamental.

Rendered at four voices it contains **three** (130.0, 166.2, 196.5 Hz; worst error 0.86%, all within
a factor of 1.14 in level). The identical render at `voices = 1` contains **one**, at 196.5 Hz, and
reports `steals == 2`. A monophonic path cannot produce the first result however it is driven.

## The eight-voice / one-voice clip pair on the docs page

Moved out of `public/docs/faust.md` on 2026-09-14. The page keeps the result — three notes sound at
`voices = 8`, one at `voices = 1` — and the allocator arithmetic a caller sizing `voices` acts on.
This is the evidence behind it.

`pat("[c4,ef4,g4] [af3,c4,ef4] [bf3,d4,f4] [ef4,g4,bf4]")` at `cps = 0.5`, two cycles, rendered once
at eight voices and once at one with nothing else changed. During the first chord the band
100–230 Hz can hold nothing but fundamentals: `[c4,ef4,g4]` is 130.81, 155.56 and 196.00 Hz, and the
chord's lowest second harmonic is 261.6 Hz. So a spectral peak in that band *is* a note sounding.

| | peaks in 100–230 Hz | measured | voices sounding at once | voice steals |
|---|---|---|---|---|
| `voices = 8` | **3** | 127.3, 160.7, 190.9 Hz | 3 | 0 |
| `voices = 1` | **1** | 190.9 Hz | 1 | 16 |

The measured frequencies are one FFT bin's worth off the notated ones — 5.9 Hz at this window
length — which is the resolution, not the tuning. The one-voice peak is `g4`'s 190.9 Hz rather than
`c4`'s because a steal leaves the last note of each chord sounding.

Two things constrain how this pair was built. The instrument is one sawtooth per voice rather than
the detuned pair used elsewhere on the page, because two sawtooths 0.5% apart put a beating pair
into the band and the count stops meaning anything. And the comparison has to be two renders of one
notation rather than one recording: a chord and an arpeggio sound different for reasons that have
nothing to do with voice allocation, so a single clip would settle nothing.

Both clips, both counts and the gap between them are asserted by `synth.faust.poly.sound`.

## Why the one-voice render steals sixteen times and not twenty-three

Displaced from `public/docs/faust.md` on 2026-09-16: the page keeps the rule for reading `steals`,
which is what a reader sizing `voices` acts on. This is the derivation.

Twenty-four notes on one voice would be twenty-three steals if every note had to take the voice from
the one before it. But `FAUST_PATTERN_SUSTAIN` is 0.9, so the gate falls a tenth of a beat before the
next chord and the program's 30 ms release finishes inside that gap. A voice that has stopped moving
air is *finished*, and a finished voice is reused rather than stolen.

What is left is two steals per chord -- the second and third notes, which arrive on the very same
frame as the first and cannot wait for anything -- times eight chords. Sixteen.

So `steals` is a count of same-frame simultaneity, not of voice exhaustion.

## Open: seven counts on `faust.md` that nothing guards

Checked on 2026-09-16 against `build/processedResources/jvm/main/faust-vocabulary.json`, and **all
seven are currently correct**:

| page says | field in the vocabulary | value |
|---|---|---|
| 1008 functions | `functions` | 1008 |
| 990 with a summary | `summary` non-empty | 990 |
| 940 with a signature | `usage` non-empty | 940 |
| 37 library prefixes | `prefixes` | 37 |
| `os.` 78 | `prefix == "os"` | 78 |
| `fi.` 109 | `prefix == "fi"` | 109 |
| `ba.` 90 / `pm.` 150 | `prefix` | 90 / 150 |

Nothing asserts any of them. The page itself says the vocabulary is regenerated from the vendored
compiler (`tools/generate_faust_vocabulary.py`), so **a compiler bump silently falsifies all seven**
-- which is exactly the drift the page claims it is immune to. Note the field is named `usage`, not
`signature`, so a naive guard grepping for `signature` finds zero and would pass vacuously.

The cheap fix is a derived-count check over the generated JSON, in the shape `DerivedCountGuard`
already uses. That needs a test change and a build, which the firing that found this did not have
disk for.
