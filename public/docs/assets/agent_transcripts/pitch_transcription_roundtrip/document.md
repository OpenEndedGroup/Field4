# Field Document: pitch_transcription_roundtrip

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 4 · POKE THIS — the register is the whole lever

<!-- field-box id="pitch_roundtrip_register" index="0" -->

```edn [field-box id="pitch_roundtrip_register" role="properties"]
{"id" "pitch_roundtrip_register" "index" 0 "name" "4 · POKE THIS — the register is the whole lever" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · POKE THIS — the register is the whole lever" "_tombstone" false "frame" #afield/rect [1210.0 1010.0 1120.0 900.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pitch_roundtrip_register_code_.kt" "lines" 44 "characters" 2020 "sha256" "17fa53d18ac885c9d3a111ff9d2b559374cbfa3f03028380d595a767e5a108a4"}]}
```

```kotlin [field-property box="pitch_roundtrip_register" name="code" path="pitch_roundtrip_register_code_.kt"]
import afield.four.synth.*

// Sixteen notes in half a second: 31 ms each.
val notation = "60 62 64 65 67 69 71 72 72 71 69 67 65 64 62 60"
val cycles = 2
val cps = 2.0
val sustain = 0.9
val audio = patRenderStereo(
    pat(notation), cycles, cps, patVoice(sustain = sustain, decay = 0.01)
).mono
val reference = patReferenceNotes(notation, cycles, cps, sustain = sustain)

// ─── CHANGE THIS ONE LINE ────────────────────────────────────────────────────────────
// The analysis window is derived from minHz, because YIN needs a frame at least twice the
// longest period it is asked to consider. So the lowest pitch you admit sets the shortest
// note you can resolve, and declaring the register truthfully is worth more than any
// threshold in this package.
//
//   patMidiToHz(59.0)  ≈ 247 Hz → window ≈ 16 ms → these 31 ms notes survive
//   PAT_PITCH_MIN_HZ   =   65 Hz → window ≈ 46 ms → longer than the notes
//
// Swap the line below for `val minHz = PAT_PITCH_MIN_HZ` and re-run. Measured on exactly
// this audio, onset recall falls from 0.91 to 0.03, every recovered note disappears, and
// the verdict below changes from PITCHED to UNRESOLVED — which names the fix rather than
// just reporting silence. The audio does not change. Only what the detector was promised.
val minHz = patMidiToHz(59.0)
// ─────────────────────────────────────────────────────────────────────────────────────

val window = patPitchFrameSpan(PatSoundDocs.SR, minHz)
val heard = showPatTranscriptionOf(
    audio,
    reference = reference,
    minGapSeconds = (1.0 / cps) / 16,
    minHz = minHz,
    maxHz = patMidiToHz(73.0),
    label = "31 ms notes, minHz = ${"%.0f".format(minHz)} Hz",
)

display("resolution", """
    minHz ${"%.1f".format(minHz)} Hz → analysis window $window samples (${"%.1f".format(1000.0 * window / PatSoundDocs.SR)} ms)
    the notes are ${"%.1f".format(1000.0 / cps / 16)} ms long
""".trimIndent())
display("verdict", heard.diagnosis.describe())
display("recovered", "pat(\"${heard.miniNotation}\")")
```

## 2. 3 · a line with no attacks in it

<!-- field-box id="pitch_roundtrip_legato" index="1" -->

```edn [field-box id="pitch_roundtrip_legato" role="properties"]
{"id" "pitch_roundtrip_legato" "index" 1 "name" "3 · a line with no attacks in it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · a line with no attacks in it" "_tombstone" false "frame" #afield/rect [50.0 670.0 1120.0 900.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pitch_roundtrip_legato_code_.kt" "lines" 48 "characters" 2029 "sha256" "4fdcb49d630e1aca8b2a90f49ee3d61e187e26ffe52f9754369d74c3df45d4b2"}]}
```

```kotlin [field-property box="pitch_roundtrip_legato" name="code" path="pitch_roundtrip_legato_code_.kt"]
import afield.four.synth.*

// One oscillator, continuous phase, stepped frequency. The amplitude never moves, so the RMS
// envelope is flat and there is no attack anywhere except the entry from silence.
//
// patRenderStereo CANNOT produce this. It adds one voice per event and gives each its own
// 3 ms attack ramp, so even at sustain = 1.0 a "legato" Pat render still has one amplitude
// rise per note — and an energy detector handles it fine. That was measured, and it is why
// this box uses Dough instead.
val steps = listOf(60.0, 62.0, 64.0, 65.0, 67.0, 69.0, 71.0, 72.0)
val stepSeconds = 0.25
val sr = 48_000
val audio = doughRenderMono(
    patSteppedSine(steps, stepSeconds), (steps.size * stepSeconds).toFloat(), sr
)

val reference = steps.mapIndexed { i, midi ->
    PatRefNote(
        startSample = (i * stepSeconds * sr).toInt(),
        endSample = ((i + 1) * stepSeconds * sr).toInt(),
        midi = midi,
    )
}
val scheduled = reference.map { it.startSample }
val tolerance = (0.030 * sr).toInt()
val frames = patF0Frames(audio, sr, minHz = patMidiToHz(59.0), maxHz = patMidiToHz(73.0))

val energy = patDetectOnsetSamples(audio, sr, stepSeconds)
val pitch = patDetectPitchChangeSamples(audio, sr, stepSeconds, frames)
val merged = patDetectNoteOnsets(audio, sr, stepSeconds, frames)

display("the counterfactual", """
    eight notes, one continuous tone, flat envelope:
      ${patTallyOnsets(energy, scheduled, tolerance).describe("energy  (amplitude envelope)")}
      ${patTallyOnsets(pitch, scheduled, tolerance).describe("pitch   (periodicity)      ")}
      ${patTallyOnsets(merged, scheduled, tolerance).describe("merged  (the union)        ")}
    The energy detector is not misconfigured. The evidence it consumes is not in this signal.
""".trimIndent())

showPatTranscriptionOf(
    audio,
    sampleRate = sr,
    reference = reference,
    minGapSeconds = stepSeconds,
    minHz = patMidiToHz(59.0),
    maxHz = patMidiToHz(73.0),
    label = "legato, no attacks — 48 kHz Dough render",
)
```

## 3. 5 · a chord, refused rather than guessed at

<!-- field-box id="pitch_roundtrip_refusal" index="2" -->

```edn [field-box id="pitch_roundtrip_refusal" role="properties"]
{"id" "pitch_roundtrip_refusal" "index" 2 "name" "5 · a chord, refused rather than guessed at" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 · a chord, refused rather than guessed at" "_tombstone" false "frame" #afield/rect [50.0 1630.0 1120.0 900.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pitch_roundtrip_refusal_code_.kt" "lines" 33 "characters" 1475 "sha256" "fe456d90d306982ac860dfb3d5a010203e22f86a74184987c8fd1c8089e3a63e"}]}
```

```kotlin [field-property box="pitch_roundtrip_refusal" name="code" path="pitch_roundtrip_refusal_code_.kt"]
import afield.four.synth.*

// Three simultaneous pitches. A monophonic detector's correct output here is a stated
// inability, not the loudest note. `~` is the only thing mini-notation can say for a span
// that sounded and was not named, so the refusal lives in the warnings and the verdict.
val notation = "[60,64,67] ~ [62,65,69] ~"
val cycles = 2
val cps = 0.75
val sustain = 0.85
val audio = patRenderStereo(
    pat(notation), cycles, cps, patVoice(sustain = sustain, decay = 0.03)
).mono

val heard = showPatTranscriptionOf(
    audio,
    reference = patReferenceNotes(notation, cycles, cps, sustain = sustain),
    minGapSeconds = (1.0 / cps) / 4,
    minHz = patMidiToHz(59.0),
    maxHz = patMidiToHz(73.0),
    label = "a triad — outlines with nothing in them",
)

display("named", "${heard.notes.size} notes  ·  pat(\"${heard.miniNotation}\")")
display("verdict", heard.diagnosis.describe())
display("warnings", heard.warnings.joinToString("\n\n"))
display("how it knows", """
    the voicing gate does nearly all of this: three sines in a non-harmonic ratio are not
    periodic at any single lag, so YIN rejects them frame by frame without consulting a
    spectrum. The spectrum is then only asked one question — sparse or dense — which is what
    separates a chord (a few discrete partials) from a snare (a continuum).
    Compare: a sawtooth is a full harmonic stack on ONE note and is NOT refused,
    because timbre is not polyphony.
""".trimIndent())
```

## 4. 2 · notate → render → hear it back

<!-- field-box id="pitch_roundtrip_clean" index="3" -->

```edn [field-box id="pitch_roundtrip_clean" role="properties"]
{"id" "pitch_roundtrip_clean" "index" 3 "name" "2 · notate → render → hear it back" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · notate → render → hear it back" "_tombstone" false "frame" #afield/rect [1210.0 50.0 1120.0 900.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pitch_roundtrip_clean_code_.kt" "lines" 27 "characters" 1047 "sha256" "609817c917fd0e210762ab4a79da7f49f56bf0bb9805794d0bb30ec20b89761c"}]}
```

```kotlin [field-property box="pitch_roundtrip_clean" name="code" path="pitch_roundtrip_clean_code_.kt"]
import afield.four.synth.*

// The notation, and the ground truth Field already owns because it wrote it.
val notation = "60 62 64 65 67 69 71 72"
val cycles = 2
val cps = 1.0
val sustain = 0.85
val voice = patVoice(sustain = sustain, decay = 0.03)

val audio = patRenderStereo(pat(notation), cycles, cps, voice).mono
val reference = patReferenceNotes(notation, cycles, cps, sustain = sustain)

// One call: transcribe, score against the notation, draw both on one axis.
val heard = showPatTranscriptionOf(
    audio,
    reference = reference,
    minGapSeconds = (1.0 / cps) / 8,
    minHz = patMidiToHz(59.0),
    maxHz = patMidiToHz(73.0),
    label = "notated against recovered",
)

display("authored", "pat(\"$notation\") × $cycles cycles at cps $cps")
display("recovered", "pat(\"${heard.miniNotation}\") at cps ${"%.3f".format(heard.cps)}")
display("pitch error", heard.notes
    .joinToString("\n") { "MIDI ${it.midi}  ${"%+.1f".format(it.centsOffset)} cents  conf ${"%.2f".format(it.confidence)}" })
display("summary", heard.describe())
```

## 5. 1 · hearing pitch — the loop and its last arrow

<!-- field-box id="pitch_roundtrip_guide" index="4" -->

```edn [field-box id="pitch_roundtrip_guide" role="properties"]
{"id" "pitch_roundtrip_guide" "index" 4 "name" "1 · hearing pitch — the loop and its last arrow" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · hearing pitch — the loop and its last arrow" "_tombstone" false "frame" #afield/rect [50.0 50.0 1120.0 560.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pitch_roundtrip_guide_code_.kt" "lines" 30 "characters" 2278 "sha256" "6b451564758a17816713b0b76091d4e0cb32429f9aecea891b451ff78f82cd6d"}]}
```

```kotlin [field-property box="pitch_roundtrip_guide" name="code" path="pitch_roundtrip_guide_code_.kt"]
import afield.four.synth.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.55 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">notation → sound → notation</h2>
  <p>Field already rendered notation to sound exactly, and already heard <b>rhythm</b> back
  (<code>PatOnsetDetection.kt</code>). What it could not do was hear <b>pitch</b>, or write
  what it heard back out as something it can re-render. These boxes are that half.</p>
  <p>Every box below is pure CPU: <code>patRenderStereo</code> or <code>doughRenderMono</code>
  makes a finite buffer, <code>patTranscribe</code> reads it, and the figure overlays the
  notation Field authored (hollow outline) on what the detector heard (solid bar) on one
  shared MIDI axis. Nothing here talks to <code>services/field-audio-ai</code>, opens an audio
  device, or needs model weights.</p>
  <p>The measurement is unusually cheap to trust: the reference is not somebody's annotation
  of the audio, it <i>is</i> the audio's cause. Box 2 is the clean case. Boxes 3 and 5 are
  the two ways this fails on purpose. <b>Box 4 is the one to poke</b> — it has the single
  parameter that matters most, and a comment saying what should happen when you change it.</p>
  <p style="margin:12px 0 0;color:#98a6b3">Pitch error is reported in <b>cents</b>, not in note
  names. One of the corpus cases is exactly 50 cents from equal temperament, where the correct
  note name genuinely does not exist — a measurement 0.1 cents out flips it a semitone.</p>
</section>
""".trimIndent())

display("resolution", """
    the analysis window is 2·(sampleRate/minHz) of frame plus one longest lag,
    so the register you admit sets the shortest note you can resolve:
      minHz  65 Hz (the wide default) → ${patPitchFrameSpan(PatSoundDocs.SR, 65.0)} samples = ${"%.1f".format(1000.0 * patPitchFrameSpan(PatSoundDocs.SR, 65.0) / PatSoundDocs.SR)} ms
      minHz 261 Hz (a treble line)    → ${patPitchFrameSpan(PatSoundDocs.SR, 261.0)} samples = ${"%.1f".format(1000.0 * patPitchFrameSpan(PatSoundDocs.SR, 261.0) / PatSoundDocs.SR)} ms
    That ratio is what box 4 is about.
""".trimIndent())
```

