# Field Document: pattern_language_composition

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 1 — continuous signals

<!-- field-box id="pat_demo_signals" index="0" -->

### Narrative

From `code`

A signal is not a new type: it is a PatPattern<Double> that answers EVERY arc with
exactly one event, sampled at the arc's exact rational midpoint. Nothing plays a
continuous pattern directly — you discretise it, and segment(n) is how.

```edn [field-box id="pat_demo_signals" role="properties"]
{"id" "pat_demo_signals" "index" 0 "name" "1 — continuous signals" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — continuous signals" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_demo_signals_code_.kt" "lines" 24 "characters" 1150 "sha256" "675cf9b2144f2e1d071da4927a78a8b9d562f7dd46ef2af8abaaa0c3ffe0a291"}]}
```

```kotlin [field-property box="pat_demo_signals" name="code" path="pat_demo_signals_code_.kt"]
// A signal is not a new type: it is a PatPattern<Double> that answers EVERY arc with
// exactly one event, sampled at the arc's exact rational midpoint. Nothing plays a
// continuous pattern directly — you discretise it, and segment(n) is how.
showPatRoll("signals-segment")

// The same renderer, live, on whatever you feel like editing:
showPatRollOf(
    "sine()" to sine().segment(8).texts(),
    "sine().rev()" to sine().segment(8).rev().texts(),
    "sine().quantise(0.25)" to sine().quantise(0.25).segment(8).texts(),
    title = "edit me — a signal, reversed, and quantised",
    subtitle = "the midpoint convention is what makes rev() line up",
    cycles = 2,
    channel = "live",
)
displayOpen("main"); displayOpen("live")

// Exactness, not floating point: the midpoint of 3/7 .. 5/7 is exactly 4/7.
val arc = PatArc(PatQ.of(3, 7), PatQ.of(5, 7))
println("midpoint of $arc = ${patArcMidpoint(arc)}")
println("segment(4) arcs = " + sine().segment(4)
    .events(PatArc(PatQ.ZERO, PatQ.ONE)).map { "${it.arc}" })
println("segment(4) values = " + sine().segment(4)
    .events(PatArc(PatQ.ZERO, PatQ.ONE)).map { "%.4f".format(it.value) })
```

## 2. 2 — whose rhythm survives

<!-- field-box id="pat_demo_structure" index="1" -->

### Narrative

From `code`

When two patterns meet, one of three things happens to the rhythm. The bars say where
the structure comes from, exactly as in Tidal.

```edn [field-box id="pat_demo_structure" role="properties"]
{"id" "pat_demo_structure" "index" 1 "name" "2 — whose rhythm survives" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — whose rhythm survives" "_tombstone" false "frame" #afield/rect [820.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_demo_structure_code_.kt" "lines" 25 "characters" 1299 "sha256" "8eb0976d9384eb59d1740791b53ea5efd509aa23967e411c45133cdc9b6ceb32"}]}
```

```kotlin [field-property box="pat_demo_structure" name="code" path="pat_demo_structure_code_.kt"]
// When two patterns meet, one of three things happens to the rhythm. The bars say where
// the structure comes from, exactly as in Tidal.
val twos = pat("0 1").nums()
val threes = pat("10 20 30").nums()

showPatRoll("structure-choice")
displayOpen("main")

// A lambda, not a named `fun`: K2 cannot codegen a named function inside a box that
// captures the script's implicit Box receiver (which `println` needs).
val table = { label: String, p: PatPattern<Double> ->
    val events = p.events(PatArc(PatQ.ZERO, PatQ.ONE))
        .sortedBy { it.arc.start.toDouble() }
    println("%-10s %d events  %s".format(label, events.size,
        events.joinToString(" ") { "[${it.arc.start}..${it.arc.end}]=${patFormatNumber(it.value)}" }))
}
table("|+| both", twos.`|+|`(threes))   // 4 events: the intersection grid
table("|+ left", twos.`|+`(threes))     // 2 events: exactly the left's
table("+| right", twos.`+|`(threes))    // 3 events: exactly the right's

// `/`, `<` and `>` are illegal in a Kotlin identifier, so division is `|over|` and the
// take-value family spells the arrow as an L or an R. The bar keeps its Tidal meaning.
table("|over|", twos.`|over|`(threes))
println("bd sd `#` x y z w = " + (pat("bd sd").`#`(pat("x y z w")))
    .events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value })
```

## 3. 6 — playground (edit me)

<!-- field-box id="pat_demo_playground" index="2" -->

### Narrative

From `code`

A little piece. Change any string or any number and re-run this box: the roll and the
audio player both come from the same pattern, so they can never disagree.

```edn [field-box id="pat_demo_playground" role="properties"]
{"id" "pat_demo_playground" "index" 2 "name" "6 — playground (edit me)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — playground (edit me)" "_tombstone" false "frame" #afield/rect [820.0 1020.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_demo_playground_code_.kt" "lines" 35 "characters" 1653 "sha256" "87d5c6178bb3e907f444fcbb9eedf4aab421cd196868b67fac573b55fed84bed"}]}
```

```kotlin [field-property box="pat_demo_playground" name="code" path="pat_demo_playground_code_.kt"]
// A little piece. Change any string or any number and re-run this box: the roll and the
// audio player both come from the same pattern, so they can never disagree.
val kick = struct(euclid(3, 8), pat("bd"))
val snare = pat("~ sd ~ sd") with gain(0.8)
val hats = pat("hh*16?0.3") with gain(sine().range(0.1, 0.55))
val drums = stack(kick, snare, hats).off(1, 16) { it with gain(0.18) }

// A bass line spelled in a scale, with a filter that opens across two cycles.
val bass = run(8).scale("minPent", root = 36).texts()
    .with(sustain(0.5), cutoff(saw().rangex(200, 2400)))

// A chord pad, voice-led so it does not leap, arpeggiated up.
val pad = chords("<c'min7 a'maj7> f'maj7").voiceLead(center = 67).arpeggiate().texts()

showPatRollOf(
    "drums" to drums,
    "bass" to bass,
    "pad" to pad,
    title = "a groove, from the arrangement surface",
    subtitle = "euclid / off / ?degrade / scale / voiceLead / arpeggiate",
    cycles = 2,
)
displayOpen("main")

showPatAudio(drums, cycles = 4, cps = 2.0,
    voice = patVoice(decay = 0.01, sustain = 0.08), channel = "drums")
showPatAudio(bass, cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.SAW, decay = 0.03, sustain = 0.5), channel = "bass")
showPatAudio(pad, cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.05, sustain = 0.6), channel = "pad")
displayOpen("drums"); displayOpen("bass"); displayOpen("pad")

println("drums: ${drums.events(PatArc(PatQ.ZERO, PatQ.ONE)).size} events/cycle")
println("bass : ${bass.events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value }}")
println("pad  : ${pad.events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value }}")
```

## 4. 5 — voice leading

<!-- field-box id="pat_demo_voice_leading" index="3" -->

### Narrative

From `code`

Spelled from each chord's own root, a progression makes the hand leap. voiceLead()
picks each chord's inversion and octave to minimise how far the voices have to move.

```edn [field-box id="pat_demo_voice_leading" role="properties"]
{"id" "pat_demo_voice_leading" "index" 3 "name" "5 — voice leading" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — voice leading" "_tombstone" false "frame" #afield/rect [60.0 1020.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_demo_voice_leading_code_.kt" "lines" 29 "characters" 1303 "sha256" "195480d7d0a0fdcb5c7f2e487a97f08ab2e945b042c8001c5aa322f295626229"}]}
```

```kotlin [field-property box="pat_demo_voice_leading" name="code" path="pat_demo_voice_leading_code_.kt"]
// Spelled from each chord's own root, a progression makes the hand leap. voiceLead()
// picks each chord's inversion and octave to minimise how far the voices have to move.
val progression = chords("c'maj7 a'min7 d'min7 g'dom7")
val asWritten = progression.events(PatArc(PatQ.ZERO, PatQ.ONE))
    .sortedBy { it.arc.start.toDouble() }.map { it.value }
val voiceLed = progression.voiceLead(center = 60).events(PatArc(PatQ.ZERO, PatQ.ONE))
    .sortedBy { it.arc.start.toDouble() }.map { it.value }

showPatRoll("voice-leading")
displayOpen("main")

println("as written : $asWritten")
println("voice-led  : $voiceLed")
println("movement   : %.0f semitones as written, %.0f voice-led".format(
    patTotalVoiceMovement(asWritten), patTotalVoiceMovement(voiceLed)))
println("harmony unchanged: " + asWritten.zip(voiceLed).all { (a, b) ->
    a.map { Math.floorMod(it, 12) }.toSortedSet() == b.map { Math.floorMod(it, 12) }.toSortedSet()
})

// Hear both: two cycles as written, two voice-led.
val arped = progression.arpeggiate().texts()
val led = progression.voiceLead(center = 60).arpeggiate().texts()
showPatAudio(
    arped.whenCycle({ it >= 2 }) { led },
    cycles = 4, cps = 1.0,
    voice = patVoice(wave = PatWave.SINE, decay = 0.02, sustain = 0.45),
    channel = "sound",
)
displayOpen("sound")
```

## 5. 3 — a parameter, not a staircase

<!-- field-box id="pat_demo_parameter" index="4" -->

### Narrative

From `code`

Before tonight the only way to attach a value to an atom was zip(...) with a string
pattern: one literal per note, typed by hand. Now a parameter can be a FUNCTION, and
the note count does not change when you read it.

```edn [field-box id="pat_demo_parameter" role="properties"]
{"id" "pat_demo_parameter" "index" 4 "name" "3 — a parameter, not a staircase" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — a parameter, not a staircase" "_tombstone" false "frame" #afield/rect [60.0 520.0 330.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_demo_parameter_code_.kt" "lines" 27 "characters" 1222 "sha256" "cfbd4b6bea20a14089fe8bfc51a65195f380501aa27c2bba31e84c66a970ae35"}]}
```

```kotlin [field-property box="pat_demo_parameter" name="code" path="pat_demo_parameter_code_.kt"]
// Before tonight the only way to attach a value to an atom was zip(...) with a string
// pattern: one literal per note, typed by hand. Now a parameter can be a FUNCTION, and
// the note count does not change when you read it.
showPatRoll("continuous-parameter")
displayOpen("main")

val curve = sine().rangex(300, 6000)
for (n in listOf(4, 8, 16)) {
    val notes = (pat("45*$n") with cutoff(curve))
        .events(PatArc(PatQ.ZERO, PatQ.ONE)).sortedBy { it.arc.start.toDouble() }
    println("bd*%-2d -> %2d notes, cutoff %s".format(n, notes.size,
        notes.joinToString(" ") { "%.0f".format(it.num("cutoff")) }))
}
// appBoth is the other choice: it intersects both sides, so a 16-step sweep cuts
// each note where the curve steps:
println("appBoth against a 16-step sweep -> " +
    pat("45*4").appBoth(curve.segment(16)) { a, _ -> a }
        .events(PatArc(PatQ.ZERO, PatQ.ONE)).size + " events")

// Hear it: a 110 Hz saw whose filter follows the curve, one value latched per note.
showPatAudio(
    pat("45*16") with cutoff(curve) with gain(0.7),
    cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.SAW, baseHz = 110.0, decay = 0.012, sustain = 0.3),
    channel = "sound",
)
displayOpen("sound")
```

## 6. 7 — groove and substitution

<!-- field-box id="pat_demo_groove" index="5" -->

### Narrative

From `code`

swing() is exact rational time, not a nudge in milliseconds: every off-beat lands a
third of a subdivision late and the pattern stays exactly quantised.

```edn [field-box id="pat_demo_groove" role="properties"]
{"id" "pat_demo_groove" "index" 5 "name" "7 — groove and substitution" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 — groove and substitution" "_tombstone" false "frame" #afield/rect [60.0 1560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_demo_groove_code_.kt" "lines" 33 "characters" 1435 "sha256" "aa3d6d76090e9b58ec4ed50779e84481ca5d1beb4e139297fe4d9e8fdf2abad5"}]}
```

```kotlin [field-property box="pat_demo_groove" name="code" path="pat_demo_groove_code_.kt"]
// swing() is exact rational time, not a nudge in milliseconds: every off-beat lands a
// third of a subdivision late and the pattern stays exactly quantised.
val hats = pat("hh*8")
showPatRoll("groove")
displayOpen("main")

println("straight : " + hats.events(PatArc(PatQ.ZERO, PatQ.ONE)).map { "${it.arc.start}" })
println("swing(4) : " + hats.swing(4).events(PatArc(PatQ.ZERO, PatQ.ONE))
    .sortedBy { it.arc.start.toDouble() }.map { "${it.arc.start}" })

// squeeze replaces each event with a whole pattern, compressed into that event's arc —
// mini-notation nesting, over Kotlin values. Compare `join`, which does not rescale.
val nested = pat("a b").squeeze { if (it == "a") pat("1 2 3") else pat("4 5") }
println("squeeze  : " + nested.events(PatArc(PatQ.ZERO, PatQ.ONE))
    .sortedBy { it.arc.start.toDouble() }.map { "${it.value}@${it.arc.start}" })

// Two cycles straight, then two swung. This is the one to listen to.
showPatAudio(
    hats.whenCycle({ it >= 2 }) { hats.swing(4) },
    cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.NOISE, decay = 0.008, sustain = 0.06),
    channel = "swing",
)
displayOpen("swing")

// And a full bar from one Euclidean figure: bd on the onsets, hh on everything else.
showPatAudio(
    euclidFull(3, 8, pat("bd"), pat("hh") with gain(0.35)),
    cycles = 4, cps = 2.0,
    voice = patVoice(decay = 0.012, sustain = 0.1),
    channel = "euclid",
)
displayOpen("euclid")
```

## 7. 4 — hear the difference

<!-- field-box id="pat_demo_gain" index="6" -->

### Narrative

From `code`

Sixteen hats a cycle. `whenCycle` is the existing operator for "from cycle 2 on, play
this instead", so the first half of the file is what the old surface could express and
the second half is the identical pattern with the gain following a sine.

```edn [field-box id="pat_demo_gain" role="properties"]
{"id" "pat_demo_gain" "index" 6 "name" "4 — hear the difference" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — hear the difference" "_tombstone" false "frame" #afield/rect [820.0 520.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_demo_gain_code_.kt" "lines" 29 "characters" 1304 "sha256" "7e7b11b372e7a91039ca54b8687ddb425980dfdf54b22161f3162a218031912a"}]}
```

```kotlin [field-property box="pat_demo_gain" name="code" path="pat_demo_gain_code_.kt"]
// Sixteen hats a cycle. `whenCycle` is the existing operator for "from cycle 2 on, play
// this instead", so the first half of the file is what the old surface could express and
// the second half is the identical pattern with the gain following a sine.
val hats = pat("hh*16")
val demo = hats.whenCycle({ it >= 2 }) { hats with gain(sine().range(0.08, 1.0)) }

showPatRollOf(
    "flat (cycles 0-1)" to hats,
    "gain(sine()) (cycles 2-3)" to (hats with gain(sine().range(0.08, 1.0))),
    title = "the same sixteen hats, before and after",
    subtitle = "rectangle height is the gain each note latched",
    cycles = 2, paramKey = "gain",
)
displayOpen("main")

val audio = showPatAudio(
    demo, cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.NOISE, decay = 0.006, sustain = 0.05),
    channel = "sound",
)
displayOpen("sound")

// The same measurement the smoke test makes, live: per-note level, flat then modulated.
val onsets = PatSoundDocs.onsetPeaks(audio.mono)
val flat = onsets.take(onsets.size / 2).map { it.rms }
val modulated = onsets.drop(onsets.size / 2).map { it.rms }
println("${onsets.size} hats detected")
println("flat half level CV      = %.4f".format(PatSoundDocs.variation(flat)))
println("modulated half level CV = %.4f".format(PatSoundDocs.variation(modulated)))
```

## 8. 8 — mini-notation markers

<!-- field-box id="pat_demo_mini_notation" index="7" -->

### Narrative

From `code`

Three standalone step markers. Each is shorthand for a bracketed form, so each pair
below must come out arc-for-arc identical — and the roll draws them one above the other
so you can check by eye as well as by assertion.

```edn [field-box id="pat_demo_mini_notation" role="properties"]
{"id" "pat_demo_mini_notation" "index" 7 "name" "8 — mini-notation markers" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "8 — mini-notation markers" "_tombstone" false "frame" #afield/rect [820.0 1560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pat_demo_mini_notation_code_.kt" "lines" 37 "characters" 1811 "sha256" "910e66c79a53c38827207abde7073903730c2923962b1732ff2d4f19d8397ac1"}]}
```

```kotlin [field-property box="pat_demo_mini_notation" name="code" path="pat_demo_mini_notation_code_.kt"]
// Three standalone step markers. Each is shorthand for a bracketed form, so each pair
// below must come out arc-for-arc identical — and the roll draws them one above the other
// so you can check by eye as well as by assertion.
showPatRoll("mini-notation")
displayOpen("main")

val pairs = listOf(
    "bd sd . hh hh hh" to "[bd sd] [hh hh hh]",   // `.` groups
    "bd _ _ sd" to "bd@3 sd",                     // `_` extends
    "bd ! ! sd" to "bd!3 sd",                     // bare `!` repeats
)
for ((shorthand, bracketed) in pairs) {
    val a = pat(shorthand).events(PatArc(PatQ.ZERO, PatQ.ONE)).sortedBy { it.arc.start.toDouble() }
    val b = pat(bracketed).events(PatArc(PatQ.ZERO, PatQ.ONE)).sortedBy { it.arc.start.toDouble() }
    val same = a.map { it.arc } == b.map { it.arc } && a.map { it.value } == b.map { it.value }
    println("%-20s == %-22s : %s   %s".format(shorthand, bracketed, same,
        a.joinToString(" ") { "${it.value}@${it.arc.start}" }))
}

// A `.` inside a token is still part of the token — decimals and sample payloads are safe.
println("0.5 1.25 -> " + pat("0.5 1.25").nums()
    .events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value })
println("my_atom  -> " + pat("my_atom b").events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value })

// A sample-index suffix becomes an `n` parameter without changing the atom.
pat("bd:3 sd 808:12").events(PatArc(PatQ.ZERO, PatQ.ONE)).forEach {
    println("atom ${it.value} -> n=${it.annotations["n"]}, span=\"${it.sourceSpan?.text}\"")
}

// Hear the dot: two cycles of five even notes, then the same five notes grouped.
showPatAudio(
    pat("bd sd hh hh hh").whenCycle({ it >= 2 }) { pat("bd sd . hh hh hh") },
    cycles = 4, cps = 1.0,
    voice = patVoice(decay = 0.012, sustain = 0.12),
    channel = "dot",
)
displayOpen("dot")
```

