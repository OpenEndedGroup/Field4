# Field Document: faust_polyphony

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. offline, with a plot

<!-- field-box id="faust_polyphony_offline" index="0" -->

### Narrative

From `code`

The identical path with no audio device and no GPU: the same FaustVoiceBank, rendered in 128-frame
blocks exactly as the audio thread would render it, into a WAV player and a waveform.

`sustain = 0.55` rather than 1.0 on purpose. At 1.0 the gate never falls between adjacent notes,
and what you get is one continuous tone with a single attack — which sounds wrong and, worse,
makes an onset detector report envelope ripple as notes. FAUST_PATTERN_SUSTAIN defaults to 0.9
for exactly that reason.

```edn [field-box id="faust_polyphony_offline" role="properties"]
{"id" "faust_polyphony_offline" "index" 0 "name" "offline, with a plot" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "offline, with a plot" "_tombstone" false "frame" #afield/rect [660.0 80.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_polyphony_offline_code_.kt" "lines" 44 "characters" 1478 "sha256" "a5eb1bdf634c41b6b37c31331be3e0488e30cc1861f195b8673478d8ec830ec5"}]}
```

```kotlin [field-property box="faust_polyphony_offline" name="code" path="faust_polyphony_offline_code_.kt"]
/*
The identical path with no audio device and no GPU: the same FaustVoiceBank, rendered in 128-frame
blocks exactly as the audio thread would render it, into a WAV player and a waveform.

`sustain = 0.55` rather than 1.0 on purpose. At 1.0 the gate never falls between adjacent notes,
and what you get is one continuous tone with a single attack — which sounds wrong and, worse,
makes an onset detector report envelope ripple as notes. FAUST_PATTERN_SUSTAIN defaults to 0.9
for exactly that reason.
*/
val render = showFaustPattern(
    """
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
gain   = hslider("gain", 0.22, 0, 1, 0.001);
gate   = button("gate");
env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
process = (os.sawtooth(freq) + os.sawtooth(freq * 1.005)) * 0.5
        : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
""",
    pat("c4 ef4 g4 bf4 c5 bf4 g4 ef4"),
    cycles = 2,
    cps = 1.0,
    voices = 8,
    sustain = 0.55,
    automate = mapOf("cutoff" to pat("900 2600 1400 5200").nums()),
)

println(
    render.notes.size.toString() +
        " notes over " +
        (render.seconds).f2 +
        "s, " +
        render.voices +
        " voices, peak concurrency " +
        render.peakConcurrency +
        ", " +
        render.automationApplied +
        " cutoff steps, " +
        render.steals +
        " steals, peak " +
        (render.peak).f3,
)
```

## 2. when it runs out of voices

<!-- field-box id="faust_polyphony_steal" index="1" -->

### Narrative

From `code`

The one knob in this feature with an *audible* consequence, and the only way to know what it
does is to render the same passage twice.

Four notes struck together on TWO voices, so two of them steal. A steal has to make the new note
attack — and writing gate=1 to a voice whose gate is already up does nothing at all, because
Faust envelopes trigger on a rising edge. So the gate is dropped and raised again, and THAT is
where the trade lives: `en.adsr` resets to zero on a trigger, so a one-sample dip drops the
outgoing note's whole amplitude in one sample. That is a click.

── CHANGE THIS LINE AND RE-RUN, then play the two players below ────────────────────────────

```edn [field-box id="faust_polyphony_steal" role="properties"]
{"id" "faust_polyphony_steal" "index" 1 "name" "when it runs out of voices" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "when it runs out of voices" "_tombstone" false "frame" #afield/rect [660.0 830.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_polyphony_steal_code_.kt" "lines" 79 "characters" 3326 "sha256" "486758d0fc175ccf3ce5667f7f6e1789bb2e855816e31a3524cf2dc943a4083e"}]}
```

```kotlin [field-property box="faust_polyphony_steal" name="code" path="faust_polyphony_steal_code_.kt"]
/*
The one knob in this feature with an *audible* consequence, and the only way to know what it
does is to render the same passage twice.

Four notes struck together on TWO voices, so two of them steal. A steal has to make the new note
attack — and writing gate=1 to a voice whose gate is already up does nothing at all, because
Faust envelopes trigger on a rising edge. So the gate is dropped and raised again, and THAT is
where the trade lives: `en.adsr` resets to zero on a trigger, so a one-sample dip drops the
outgoing note's whole amplitude in one sample. That is a click.

── CHANGE THIS LINE AND RE-RUN, then play the two players below ────────────────────────────
*/
val steal = 1L // 1 = default; 1440 = a 30 ms release at 48 kHz
// ───────────────────────────────────────────────────────────────────────────────────────────
val voice =
    """
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
gain   = hslider("gain", 0.22, 0, 1, 0.001);
gate   = button("gate");
env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
process = os.sawtooth(freq) : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
"""

/*
Genuine over-subscription, which takes a moment's care to arrange: a pair held for the WHOLE
cycle, stacked with a second pair entering halfway through. Two voices, four notes, and the late
pair arrives while the first pair's gates are still up — so it must steal. (Writing this as
`pat("[c4,e4] [g4,b4]")` looks equivalent and is not: those pairs do not overlap, the first has
already released, and the render reports 0 steals and measures nothing. The `steals` line below
is printed for exactly that reason.)
*/
val busy = stack(pat("[c4,e4]"), pat("~ [g4,b4]"))

/*
The statistic is scale-free on purpose: the largest single-sample step as a fraction of the
local peak, at the steal, against the same thing in the same note once it has settled. Raw
deltas would just tell you that a louder, higher sawtooth has a bigger wrap.
*/
for (frames in [steal, 1L, 1440L].distinct()) {
    val r = faustPatternRender(
        voice,
        busy,
        cycles = 1,
        cps = 1.0,
        voices = 2,
        sustain = 0.9,
        gain = 0.18,
        stealReleaseFrames = frames,
    )
    val at = r.notes.map { it.startFrame }.distinct().sorted().last().toInt()
    val mono = r.mono()
    val atSteal = faustNormalisedStep(mono, at - 4, 64)
    val settled = faustNormalisedStep(mono, at + 3000, 1000)
    val ratio = atSteal / settled
    println(
        "stealReleaseFrames = " +
            frames +
            " (" +
            (frames * 1000.0 / r.sampleRate).f0 +
            "ms):  " +
            r.steals +
            " steals at frame " +
            at +
            ",  normalised step across it " +
            ratio.f2 +
            "x the settled one; step/local peak = ${atSteal.f3}, settled = ${settled.f3}",
    )
    display(
        "steal=" + frames,
        r.wav(normalizePeak = 0.9f),
    )
}
println()
println("A ratio near 1.0 means the normalized step resembles the settled baseline.")
println("The separate step/local-peak figure measures the jump relative to local amplitude.")
println("Compare both real players: a longer steal release trades onset timing for a softer transition.")
```

## 3. Faust instrument

<!-- field-box id="faust_polyphony_play" index="2" -->

### Narrative

From `code`

Eight instances of one compiled Faust program, allocated to notes by a pattern. Nothing is
recompiled per voice: a Faust wasm factory built with internal memory keeps its whole state at
offset 0 of its own memory, so eight WebAssembly instances of one module are eight independent
voices, and they all live in one GraalVM context.

`freq` / `gate` / `gain` is Faust's own polyphonic convention — the three names `-nvoices` looks
for — and they are found by LABEL, never by index. Faust lists controls alphabetically, so index
0 in this program is `cutoff`; an allocator that wrote pitch to index 0 would sweep a filter and
never change a note.

The first compile of a session is slow (13-22s of GraalVM warm-up, then ~600ms). Run
`FaustEngine.warmInBackground()` in another box first if you would rather wait now.

```edn [field-box id="faust_polyphony_play" role="properties"]
{"id" "faust_polyphony_play" "index" 2 "name" "Faust instrument" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Faust instrument" "_tombstone" false "frame" #afield/rect [80.0 80.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_polyphony_play_code_.kt" "lines" 46 "characters" 1885 "sha256" "91201268b74a625a23a2cd12233704dbc4ec04e2254bd7660286c276a8ecdd81"}]}
```

```kotlin [field-property box="faust_polyphony_play" name="code" path="faust_polyphony_play_code_.kt"]
/*
Eight instances of one compiled Faust program, allocated to notes by a pattern. Nothing is
recompiled per voice: a Faust wasm factory built with internal memory keeps its whole state at
offset 0 of its own memory, so eight WebAssembly instances of one module are eight independent
voices, and they all live in one GraalVM context.

`freq` / `gate` / `gain` is Faust's own polyphonic convention — the three names `-nvoices` looks
for — and they are found by LABEL, never by index. Faust lists controls alphabetically, so index
0 in this program is `cutoff`; an allocator that wrote pitch to index 0 would sweep a filter and
never change a note.

The first compile of a session is slow (13-22s of GraalVM warm-up, then ~600ms). Run
`FaustEngine.warmInBackground()` in another box first if you would rather wait now.
*/
val inst = faustPoly(
    """
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
gain   = hslider("gain", 0.22, 0, 1, 0.001);
gate   = button("gate");
env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
process = (os.sawtooth(freq) + os.sawtooth(freq * 1.005)) * 0.5
        : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
""",
    voices = 8,
)

/*
Two patterns, one timeline. The chords are notes; the cutoff is a parameter. Both are scheduled
in frames and applied inside the audio block rather than at its edge, so a filter that opens on
the beat opens on the *sample* of the beat.

The slider that appears below is `cutoff` only. `freq`, `gate` and `gain` are deliberately not
in the control group: the notes write them, and a slider fighting the notes for them would look
broken.
*/
inst.play(
    pat("[c4,ef4,g4] [af3,c4,ef4] [bf3,d4,f4] [ef4,g4,bf4]"),
    cps = 0.5,
    sustain = 0.85,
    automate = mapOf("cutoff" to pat("900 2600 1400 5200").nums()),
)

inst
```

## 4. onsets, in samples

<!-- field-box id="faust_polyphony_timing" index="3" -->

### Narrative

From `code`

Where the notes actually landed, in samples, against where they were notated.

Seven notes to a cycle on purpose: 48000/7 = 6857, and 6857 % 128 = 105, so twelve of the
fourteen onsets fall *inside* a 128-frame audio block rather than on its edge. A scheduler that
wrote parameters once per block — which is what FaustDsp's parameter queue is — would drag every
one of those forward to the next boundary, up to 110 frames late.

Before believing any onset count: check the signal has onsets. faustSilentGaps must find real
silence between the notes, or the "onsets" are envelope ripple in a continuous tone.

```edn [field-box id="faust_polyphony_timing" role="properties"]
{"id" "faust_polyphony_timing" "index" 3 "name" "onsets, in samples" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "onsets, in samples" "_tombstone" false "frame" #afield/rect [660.0 470.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_polyphony_timing_code_.kt" "lines" 96 "characters" 4214 "sha256" "37f79ff6a38117c4b06264c94dfd6b8b5a362b89f3cebc8b356487a6a0eb2340"}]}
```

```kotlin [field-property box="faust_polyphony_timing" name="code" path="faust_polyphony_timing_code_.kt"]
/*
Where the notes actually landed, in samples, against where they were notated.

Seven notes to a cycle on purpose: 48000/7 = 6857, and 6857 % 128 = 105, so twelve of the
fourteen onsets fall *inside* a 128-frame audio block rather than on its edge. A scheduler that
wrote parameters once per block — which is what FaustDsp's parameter queue is — would drag every
one of those forward to the next boundary, up to 110 frames late.

Before believing any onset count: check the signal has onsets. faustSilentGaps must find real
silence between the notes, or the "onsets" are envelope ripple in a continuous tone.
*/
val render = faustPatternRender(
    """
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
gain   = hslider("gain", 0.22, 0, 1, 0.001);
gate   = button("gate");
env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
process = (os.sawtooth(freq) + os.sawtooth(freq * 1.005)) * 0.5
        : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
""",
    pat("c4 e4 g4 a4 c5 d5 e5"),
    cycles = 2,
    cps = 1.0,
    voices = 8,
    sustain = 0.5,
)

val mono = render.mono()
val gaps = faustSilentGaps(mono, render.sampleRate)
val notated = render.notes.map { it.startFrame }.sorted()
val onsets = faustOnsets(mono, render.sampleRate)

// Search on BOTH sides of each expected onset. An early attack must count as early,
// and an already-sounding note must not pass merely because its expected sample is nonzero.
val searchRadius = 256
val quietFrames = 64
fun onsetError(samples: FloatArray, expected: Int): Int? {
    val from = maxOf(quietFrames, expected - searchRadius)
    val to = minOf(samples.lastIndex, expected + searchRadius)
    val transitions =
        (from..to).filter { at ->
            samples[at] != 0f && (at - quietFrames until at).all { samples[it] == 0f }
        }
    // Missing silence, no attack, or several eligible attacks makes this note inconclusive.
    return transitions.singleOrNull()?.minus(expected)
}

// The first note has no preceding gap in the buffer, so it cannot establish this check.
val checkable = notated.filter { it >= quietFrames && it < mono.size }
val timingErrors = checkable.map { onsetError(mono, it.toInt()) }
val measured = timingErrors.filterNotNull()
println("SCHEDULER: ${measured.count { it == 0 }} exact of ${checkable.size} eligible notes")
println("signed onset errors (early < 0, late > 0, null = inconclusive): $timingErrors")
if (checkable.isNotEmpty() && timingErrors.all { it == 0 }) {
    println("sample-exact for every eligible onset, with $quietFrames preceding zero samples")
} else {
    println("sample-exactness not established: inspect signed errors and missing silent gaps")
}
println()
println("silent gaps:  " + gaps.size + " (so the signal really has attacks)")
println(
    "notated:      " + notated.size + " notes, " + notated.count { it % 128L != 0L } + " of them off the block grid",
)
println("detected:     " + onsets.size + " onsets")
if (onsets.size == notated.size) {
    val errors = onsets.mapIndexed { i, o -> o - notated[i].toInt() }
    val median = errors.sorted()[errors.size / 2]
    println("error/note:   " + errors)
    println(
        "DETECTOR:     median " +
            median +
            " frames, jitter +/-" +
            errors.maxOf { Math.abs(it - median) } +
            " frames, worst " +
            errors.maxOf { Math.abs(it) } +
            " — against up to 110 if the writes were quantised to the block",
    )
    println("Threshold detection waits for the attack ramp to clear an amplitude threshold.")
    println("Compare it with the separate zero-to-nonzero transition check above.")
}

// Player and plot on SEPARATE channels: display() replaces a channel rather than appending,
// so both on "waveform" left only whichever went out last.
displayOpen("audio")
display("audio", render.wav(normalizePeak = 0.9f))
showSignals(
    "mono" to (0 until render.frames step 24).map { mono[it] },
    x = (0 until render.frames step 24).map { it.toDouble() / render.sampleRate },
    channel = "waveform",
    title = "14 notes, 8 voices",
    xTitle = "seconds",
    yTitle = "amplitude",
)
```

## 5. what it refuses

<!-- field-box id="faust_polyphony_refusal" index="4" -->

### Narrative

From `code`

A polyphonic program has to declare `freq` and `gate`. Faust hands its whole declared interface
back as JSON, so Field can check — and the interesting question is what happens when it fails.

Writing pitch to control index 0 and hoping would produce silence, or a filter sweep, and there
is nothing in silence to read. So this refuses, and names what is missing, what the program did
declare, and the line of Faust that would fix it.

```edn [field-box id="faust_polyphony_refusal" role="properties"]
{"id" "faust_polyphony_refusal" "index" 4 "name" "what it refuses" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "what it refuses" "_tombstone" false "frame" #afield/rect [80.0 830.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_polyphony_refusal_code_.kt" "lines" 22 "characters" 995 "sha256" "241feaada9a30cab764711fe9be73960cb74fc3396f1b1f59a05d31828c8ded9"}]}
```

```kotlin [field-property box="faust_polyphony_refusal" name="code" path="faust_polyphony_refusal_code_.kt"]
/*
A polyphonic program has to declare `freq` and `gate`. Faust hands its whole declared interface
back as JSON, so Field can check — and the interesting question is what happens when it fails.

Writing pitch to control index 0 and hoping would produce silence, or a filter sweep, and there
is nothing in silence to read. So this refuses, and names what is missing, what the program did
declare, and the line of Faust that would fix it.
*/
val program = faustCompile(
    """
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 1200, 60, 8000, 1);
freq   = hslider("freq [unit:Hz]", 220, 40, 4000, 0.001);
process = os.sawtooth(freq) : fi.lowpass(2, cutoff) * 0.2 <: _,_;
""",
)

println("it declares, in the order Faust lists them (alphabetical, not as written):")
program.ui.writable.forEach { println("  " + it.displayLabel + "  " + it.type + "  index " + it.index) }
println()
println(runCatching { FaustPolyControls.of(program) }.exceptionOrNull()?.message ?: "accepted?!")
```

## 6. three notes at once

<!-- field-box id="faust_polyphony_chord" index="5" -->

### Narrative

From `code`

Compare three simultaneous fundamentals with the one-voice rendering.

Counting attacks cannot tell polyphony from monophony: a single voice that steals itself
produces exactly the same number of attacks as three voices. What cannot be faked is the
spectrum. `[c4,e4,g4]` is one chord — three events on the same arc, so three notes on the same
frame — whose fundamentals are 130.81, 164.81 and 196.00 Hz. The band 100-230 Hz can hold
nothing else: the lowest second harmonic in the chord is 261.6 Hz. So a peak in that band is
somebody's fundamental, and counting them counts notes sounding.

ONE oscillator per voice here, not the detuned pair the other boxes use — that matters, and it
was found by running this: two sawtooths 0.5% apart put a *beating pair* in the band, so four
voices reported four peaks and one voice reported two, which muddies the very thing the box is
for. A measurement wants one partial per note.

```edn [field-box id="faust_polyphony_chord" role="properties"]
{"id" "faust_polyphony_chord" "index" 5 "name" "three notes at once" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "three notes at once" "_tombstone" false "frame" #afield/rect [80.0 470.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_polyphony_chord_code_.kt" "lines" 40 "characters" 2119 "sha256" "48f134d7076a0cf7caab3db0ba4235ea5804292f02147ce6daf2971885014f00"}]}
```

```kotlin [field-property box="faust_polyphony_chord" name="code" path="faust_polyphony_chord_code_.kt"]
/*
Compare three simultaneous fundamentals with the one-voice rendering.

Counting attacks cannot tell polyphony from monophony: a single voice that steals itself
produces exactly the same number of attacks as three voices. What cannot be faked is the
spectrum. `[c4,e4,g4]` is one chord — three events on the same arc, so three notes on the same
frame — whose fundamentals are 130.81, 164.81 and 196.00 Hz. The band 100-230 Hz can hold
nothing else: the lowest second harmonic in the chord is 261.6 Hz. So a peak in that band is
somebody's fundamental, and counting them counts notes sounding.

ONE oscillator per voice here, not the detuned pair the other boxes use — that matters, and it
was found by running this: two sawtooths 0.5% apart put a *beating pair* in the band, so four
voices reported four peaks and one voice reported two, which muddies the very thing the box is
for. A measurement wants one partial per note.
*/
val voice =
    """
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
gain   = hslider("gain", 0.22, 0, 1, 0.001);
gate   = button("gate");
env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
process = os.sawtooth(freq) : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
"""

val chord = pat("[c4,e4,g4]")
val poly = faustPatternRender(voice, chord, cycles = 1, cps = 1.0, voices = 4, sustain = 0.9, gain = 0.18)
val one = faustPatternRender(voice, chord, cycles = 1, cps = 1.0, voices = 1, sustain = 0.9, gain = 0.18)

val at = (0.12 * poly.sampleRate).toInt()
println("notated:    " + poly.notes.map { (it.hz).f2 })
println("4 voices:   " + faustSpectrumPeaks(poly.mono(), at, 32768, poly.sampleRate, 100.0, 230.0, 0.15))
println("1 voice:    " + faustSpectrumPeaks(one.mono(), at, 32768, one.sampleRate, 100.0, 230.0, 0.15))
println("steals:     4 voices " + poly.steals + ", 1 voice " + one.steals)
println("concurrency: 4 voices " + poly.peakConcurrency + ", 1 voice " + one.peakConcurrency)

display("chord", poly.wav(normalizePeak = 0.9f))
display("one voice", one.wav(normalizePeak = 0.9f))
```

