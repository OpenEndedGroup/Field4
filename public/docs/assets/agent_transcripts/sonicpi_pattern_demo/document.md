# Field Document: sonicpi_pattern_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. 1 — A pattern, lowered to Sonic Pi

<!-- field-box id="sonicpi-pattern-lower" index="0" -->

### Narrative

From `code` tags: `#sonicpi`

Pat plays Sonic Pi — the program, before you hear it.

Field's pattern algebra keeps musical structure exact in rational cycle space. Sonic Pi has 66
synths, 42 FX, 206 samples and a sample-accurate scheduler. This is the join: a pattern is
lowered to ordinary Sonic Pi source that you can read, paste into a #sonicpi region, and edit.

public/docs/sonic_pi.md — "Field patterns as a Sonic Pi sequencer"

Nothing here needs Sonic Pi installed. It is a text transformation.

A triplet inside a binary bar — three even hits inside the third quarter. This is exactly where
a lowering that wrote `sleep 0.333` would start drifting, and it is why the emitter writes
Rational(1, 3) instead.

```edn [field-box id="sonicpi-pattern-lower" role="properties"]
{"id" "sonicpi-pattern-lower" "index" 0 "name" "1 — A pattern, lowered to Sonic Pi" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — A pattern, lowered to Sonic Pi" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sonicpi-pattern-lower_code_.kt" "lines" 28 "characters" 1421 "sha256" "a08d31e66451c6ba14a35c1a4e37a6f3f8e771b6979bf4b3c2d3602880555cfc"}]}
```

```kotlin [field-property box="sonicpi-pattern-lower" name="code" path="sonicpi-pattern-lower_code_.kt"]
// Pat plays Sonic Pi — the program, before you hear it.
//
// Field's pattern algebra keeps musical structure exact in rational cycle space. Sonic Pi has 66
// synths, 42 FX, 206 samples and a sample-accurate scheduler. This is the join: a pattern is
// lowered to ordinary Sonic Pi source that you can read, paste into a #sonicpi region, and edit.
//
//   public/docs/sonic_pi.md — "Field patterns as a Sonic Pi sequencer"
//
// Nothing here needs Sonic Pi installed. It is a text transformation.

// A triplet inside a binary bar — three even hits inside the third quarter. This is exactly where
// a lowering that wrote `sleep 0.333` would start drifting, and it is why the emitter writes
// Rational(1, 3) instead.
val groove = pat("bd ~ [bd bd bd] bd")

println(groove.sonicPiSource(cps = 0.5, cycles = 1, name = "groove"))

// use_bpm makes one Sonic Pi beat exactly one Field cycle, so the sleeps above ARE the pattern's
// cycle fractions. Nothing about them depends on the tempo — change cps and only use_bpm moves.

// Atoms resolve against the *installed* vocabulary, in order: a real sample name, then a real
// synth name, then a note, then the drum-machine aliases.
println()
println(SonicPiVocabulary)
val kit = SonicPiInstruments()
listOf("bd", "hh", "bd_tek", "tb303", "c4", "60", "nonesuch").forEach {
    println("  %-10s -> %s".format(it, kit.resolve(it) ?: "NOTHING — this atom would be silent"))
}
```

## 2. 2 — Hear it

<!-- field-box id="sonicpi-pattern-hear" index="1" -->

### Narrative

From `code`

Hear it.

The same lowering, played. Sonic Pi 5 has to be installed; Field starts it itself — owned mode,
so there is no Sonic Pi window and this box is the buffer. The box stays lit while the music
runs, and the stop button stops it.

Turn your volume down before you run this.

Three things a layer can carry beyond its pattern: which synth its bare notes play on, an FX
chain wrapped around its live_loop, and parameters from the pattern algebra. Every FX name and
every FX opt below is checked against the installed vocabulary when the layer is built — Sonic
Pi's answer to `with_fx :revreb` is a runtime error on the loop's own thread, minutes in.

Field's octave numbering puts middle C at c5 = 60, one octave below Sonic Pi's :c4. That is why
the lowering emits MIDI numbers rather than :symbols — the number the pattern meant survives.

```edn [field-box id="sonicpi-pattern-hear" role="properties"]
{"id" "sonicpi-pattern-hear" "index" 1 "name" "2 — Hear it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — Hear it" "_tombstone" false "frame" #afield/rect [760.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sonicpi-pattern-hear_code_.kt" "lines" 31 "characters" 1391 "sha256" "42cf6c74cf0d68255592620ec55e6f1cb5e8fc4af5b01c2f097ae5712897f4dc"}]}
```

```kotlin [field-property box="sonicpi-pattern-hear" name="code" path="sonicpi-pattern-hear_code_.kt"]
// Hear it.
//
// The same lowering, played. Sonic Pi 5 has to be installed; Field starts it itself — owned mode,
// so there is no Sonic Pi window and this box is the buffer. The box stays lit while the music
// runs, and the stop button stops it.
//
// Turn your volume down before you run this.

// Three things a layer can carry beyond its pattern: which synth its bare notes play on, an FX
// chain wrapped around its live_loop, and parameters from the pattern algebra. Every FX name and
// every FX opt below is checked against the installed vocabulary when the layer is built — Sonic
// Pi's answer to `with_fx :revreb` is a runtime error on the loop's own thread, minutes in.
//
// Field's octave numbering puts middle C at c5 = 60, one octave below Sonic Pi's :c4. That is why
// the lowering emits MIDI numbers rather than :symbols — the number the pattern meant survives.
sonicPiPattern(
    sonicPiLayer("drums", pat("bd ~ [bd bd bd] bd")),
    sonicPiLayer(
        "hats",
        pat("[hh hh hh hh]").degradeBy(0.35, salt = 4) with gain(0.35),
        fx = listOf(sonicPiFx("echo", "phase" to 0.125, "decay" to 2, "mix" to 0.3))
    ),
    sonicPiLayer(
        "bass",
        pat("<e3 g3> ~ e4 ~").every(4) { it.rev() } with cutoff(700.0),
        synth = "tb303",
        fx = listOf(sonicPiFx("reverb", "room" to 0.7, "mix" to 0.4))
    ),
    cps = 0.5,
    cycles = 4
)
```

## 3. 3 — Field's clock, Sonic Pi's instrument

<!-- field-box id="sonicpi-pattern-live" index="2" -->

### Narrative

From `code`

The other direction: Field's clock is the sequencer, Sonic Pi is the instrument.

One /run-code per event instead of a live_loop. Slightly less accurate — a UDP hop of jitter per
note, measured at a few milliseconds — and completely live: edit the pattern below, re-run, and
the next event goes out changed, with no live_loop to redefine.

The thing a compiled loop cannot do is on the last line: a continuous signal sampled once per
note, so the hats breathe.

```edn [field-box id="sonicpi-pattern-live" role="properties"]
{"id" "sonicpi-pattern-live" "index" 2 "name" "3 — Field's clock, Sonic Pi's instrument" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — Field's clock, Sonic Pi's instrument" "_tombstone" false "frame" #afield/rect [760.0 220.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sonicpi-pattern-live_code_.kt" "lines" 11 "characters" 577 "sha256" "e51c90cc946ab06defaae6d55468efaa354d2195cbcbc2c5c04169175407e23e"}]}
```

```kotlin [field-property box="sonicpi-pattern-live" name="code" path="sonicpi-pattern-live_code_.kt"]
// The other direction: Field's clock is the sequencer, Sonic Pi is the instrument.
//
// One /run-code per event instead of a live_loop. Slightly less accurate — a UDP hop of jitter per
// note, measured at a few milliseconds — and completely live: edit the pattern below, re-run, and
// the next event goes out changed, with no live_loop to redefine.
//
// The thing a compiled loop cannot do is on the last line: a continuous signal sampled once per
// note, so the hats breathe.

(pat("bd*4, [~ cp]*2, hh*8") with gain(sine().range(0.15, 0.5)))
    .sonicPiLive(cps = 0.5)
```

