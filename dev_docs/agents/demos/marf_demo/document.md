# Field Document: marf_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isDatalogRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["2034cfde-3714-4224-8ceb-cba9ae68a0da" "fe2945eb-9afb-47a0-8326-0037020ab4c2" "379d378e-013c-4d64-bf26-d965695afa9c" "b38c3ac4-b5fc-4642-9543-c9e37c0c41f7"] "properties" {"children" #{#afield/box "2034cfde-3714-4224-8ceb-cba9ae68a0da" #afield/box "fe2945eb-9afb-47a0-8326-0037020ab4c2" #afield/box "379d378e-013c-4d64-bf26-d965695afa9c" #afield/box "b38c3ac4-b5fc-4642-9543-c9e37c0c41f7"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{}} "blockProperties" []}
```

## 2. marf_vector

<!-- field-box id="379d378e-013c-4d64-bf26-d965695afa9c" index="1" -->

### Narrative

From `code`

A stage's value need not be a number. One Vector3f reader, drawn as its three components:
one stage structure carrying three values, so the osc:2 stage bumps all of them at once.

```edn [field-box id="379d378e-013c-4d64-bf26-d965695afa9c" role="properties"]
{"id" "379d378e-013c-4d64-bf26-d965695afa9c" "index" 1 "name" "marf_vector" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "marf_vector" "frame" #afield/rect [60.0 226.0 340.0 136.0] "preamble" "" "nested" #{} "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "379d378e-013c-4d64-bf26-d965695afa9c_code_.kt" "lines" 61 "characters" 2535 "sha256" "d1ea32c175bdb30d6379d678195b6b4b61b2283ebb2fe97c6bb7e9ec314960c7"}]}
```

```kotlin [field-property box="379d378e-013c-4d64-bf26-d965695afa9c" name="code" path="379d378e-013c-4d64-bf26-d965695afa9c_code_.kt"]
// A stage's value need not be a number. One Vector3f reader, drawn as its three components:
// one stage structure carrying three values, so the osc:2 stage bumps all of them at once.
displayClearAll()          // drop anything a previous run left on another channel
displayOpen("lines")

val path = marfBankIn(
    MarfVector3f,
    stageTo(Vector3f(0f, 0f, 0f), 1.0, label = "origin"),
    stageTo(Vector3f(1f, 0f, 0f), 0.5, MarfCurve.Smooth, label = "x"),
    stageTo(Vector3f(1f, 1f, 0f), 1.5, MarfCurve.Exp(3.0), label = "xy"),
    stageTo(Vector3f(0f, 0.25f, 1f), 1.0, MarfCurve.Osc(2.0), label = "z")
).read(pat("<0 1 2 3 2 1>").nums(), fields = MarfFieldClock.STAGES, name = "vec")

val cycles = 6L
val W = 1060.0
val laneH = 96.0
val palette = listOf(
    vec(0.45, 0.82, 1.0, 0.95),
    vec(1.0, 0.60, 0.26, 0.95),
    vec(0.52, 0.90, 0.52, 0.95)
)
val component: List<(Vector3f) -> Double> = listOf({ v -> v.x.toDouble() }, { v -> v.y.toDouble() }, { v -> v.z.toDouble() })

val out = mutableListOf<FLine>()
component.forEachIndexed { lane, pick ->
    val top = 14.0 + lane * (laneH + 20.0)
    // The boundaries are the SAME in all three lanes: one reader, one stage structure.
    path.stagesIn(PatArc(PatQ.ZERO, PatQ.of(cycles))).forEach { s ->
        val bx = 20.0 + (s.start.toDouble() / cycles) * W
        out += fline {
            moveTo(bx, top); lineTo(bx, top + laneH)
            stroked = true; thickness = 1.0; color = vec(0.40, 0.44, 0.54, 0.85)
        }
    }
    out += fline {
        moveTo(20.0, top + laneH); lineTo(20.0 + W, top + laneH)
        stroked = true; thickness = 1.0; color = vec(0.32, 0.35, 0.43, 0.9)
    }
    out += fline {
        for (i in 0..600) {
            val t = i / 600.0 * cycles
            val px = 20.0 + (t / cycles) * W
            val py = top + laneH - pick(path.value(t)) * (laneH - 6.0)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        stroked = true; thickness = 1.8; color = palette[lane]
    }
}
lines(out, w = 1100.0, h = 360.0, fitToSize = true)

markdown(
    "legend",
    """
    **One `Vector3f` reader, three components** — <span style="color:#73d1ff">x</span>,
    <span style="color:#ff9942">y</span>, <span style="color:#85e585">z</span>.

    The stage boundaries fall in the same places in all three lanes, because there is one stage
    structure carrying three values. `signal { it.x.toDouble() }` is how a vector reader reaches
    the rest of the language, which is scalar nearly everywhere.
    """.trimIndent()
)
```

## 3. marf_readers

<!-- field-box id="2034cfde-3714-4224-8ceb-cba9ae68a0da" index="2" -->

### Narrative

From `code`

One bank, three readers. The bank has no position of its own; the readers do.

```edn [field-box id="2034cfde-3714-4224-8ceb-cba9ae68a0da" role="properties"]
{"id" "2034cfde-3714-4224-8ceb-cba9ae68a0da" "index" 2 "name" "marf_readers" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "marf_readers" "frame" #afield/rect [1276.63671875 69.22265625 340.0 136.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "2034cfde-3714-4224-8ceb-cba9ae68a0da_code_.kt" "lines" 62 "characters" 2443 "sha256" "2c09d6a47fdd8ca94d59137375cf93f6eef15709345d249ab585f24a2b61c20f"}]}
```

```kotlin [field-property box="2034cfde-3714-4224-8ceb-cba9ae68a0da" name="code" path="2034cfde-3714-4224-8ceb-cba9ae68a0da_code_.kt"]
// One bank, three readers. The bank has no position of its own; the readers do.
displayClearAll()          // drop anything a previous run left on another channel
displayOpen("lines")
displayOpen("legend")

val b = marfBank(
    rampTo(0.1, 1.0), rampTo(0.9, 0.5), holdAt(0.4, 2.0),
    oscTo(0.7, 1.0, cycles = 3.4), curveTo(0.2, 0.25, amount = 4.0)
)
val a = b.read(pat("<0 3>").nums(), fields = MarfFieldClock.STAGES, name = "A")
val c = b.read(pat("<0 1 2 3 4>").nums().slow(3), fields = MarfFieldClock.STAGES, name = "B")
val g = b.read(saw().slow(8).range(0.0, 4.0), duration = constant(0.25), name = "glide")

// Three lanes at one shared time scale, so reading down a column compares the same instant.
// The dashed verticals are stage boundaries — the structure the pattern algebra laid out.
val cycles = 8L
val W = 1060.0
val laneH = 96.0
val palette = listOf(
    vec(0.45, 0.82, 1.0, 0.95),
    vec(1.0, 0.60, 0.26, 0.95),
    vec(0.52, 0.90, 0.52, 0.95)
)

val out = mutableListOf<FLine>()
listOf(a, c, g).forEachIndexed { lane, reader ->
    val top = 14.0 + lane * (laneH + 20.0)
    reader.stagesIn(PatArc(PatQ.ZERO, PatQ.of(cycles))).forEach { s ->
        val bx = 20.0 + (s.start.toDouble() / cycles) * W
        out += fline {
            moveTo(bx, top); lineTo(bx, top + laneH)
            stroked = true; thickness = 1.0; color = vec(0.40, 0.44, 0.54, 0.85)
        }
    }
    out += fline {
        moveTo(20.0, top + laneH); lineTo(20.0 + W, top + laneH)
        stroked = true; thickness = 1.0; color = vec(0.32, 0.35, 0.43, 0.9)
    }
    out += fline {
        for (i in 0..800) {
            val t = i / 800.0 * cycles
            val px = 20.0 + (t / cycles) * W
            val py = top + laneH - reader.value(t) * (laneH - 6.0)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        stroked = true; thickness = 1.8; color = palette[lane]
    }
}
lines(out, w = 1100.0, h = 360.0, fitToSize = true)

markdown(
    "legend",
    """
    **One bank, three readers** — eight cycles, top to bottom:

    - <span style="color:#73d1ff">blue</span> — `read "<0 3>"`, one address per stage
    - <span style="color:#ff9942">orange</span> — `read slow 3 "<0 1 2 3 4>"`, each address held three stages
    - <span style="color:#85e585">green</span> — `read saw slow 8`, a fractional address blending two slots

    Same five stages in all three. Only the trajectory differs.
    """.trimIndent()
)
```

## 4. marf_path

<!-- field-box id="fe2945eb-9afb-47a0-8326-0037020ab4c2" index="3" -->

### Narrative

From `code`

Two readers of one bank as two coordinates. The path stays coherent without the
coordinates being synchronised, because both read the same material on their own clocks.

```edn [field-box id="fe2945eb-9afb-47a0-8326-0037020ab4c2" role="properties"]
{"id" "fe2945eb-9afb-47a0-8326-0037020ab4c2" "index" 3 "name" "marf_path" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "marf_path" "frame" #afield/rect [440.0 60.0 340.0 136.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fe2945eb-9afb-47a0-8326-0037020ab4c2_code_.kt" "lines" 24 "characters" 935 "sha256" "5b49437b8632b9b5a569d94fac2cf84bee658862797aaa66a1ab7c260dd279a4"}]}
```

```kotlin [field-property box="fe2945eb-9afb-47a0-8326-0037020ab4c2" name="code" path="fe2945eb-9afb-47a0-8326-0037020ab4c2_code_.kt"]
// Two readers of one bank as two coordinates. The path stays coherent without the
// coordinates being synchronised, because both read the same material on their own clocks.
displayClearAll()
displayOpen("lines")   // an unopened channel measures 0x0

val b = marfBank(
    rampTo(0.1, 1.0), rampTo(0.9, 0.5), holdAt(0.4, 2.0),
    oscTo(0.7, 1.0, cycles = 3.0), curveTo(0.2, 0.25, amount = 4.0)
)
val x = b.read(pat("<0 1 2 3 4>").nums(), fields = MarfFieldClock.STAGES, name = "x")
val y = b.read(pat("<0 2 4 1 3>").nums().slow(3), fields = MarfFieldClock.STAGES, name = "y")

val path = fline {
    for (i in 0..2400) {
        val t = i / 100.0
        val px = 40.0 + x.value(t) * 460.0
        val py = 20.0 + y.value(t) * 260.0
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    stroked = true
    thickness = 1.5
    color = vec(0.5, 0.85, 1.0, 0.8)
}
lines(listOf(path), w = 560.0, h = 300.0, fitToSize = true)
```

## 5. marf_sound

<!-- field-box id="b38c3ac4-b5fc-4642-9543-c9e37c0c41f7" index="4" -->

### Narrative

From `code`

MARF as control voltage into a real synth: pat -> Dough -> Steam Audio HRTF, realtime.
Three readers of one bank become where the sound is, how open its filter is, and how far away.
Nothing about the bank knows it is being read three times, or heard rather than drawn.

```edn [field-box id="b38c3ac4-b5fc-4642-9543-c9e37c0c41f7" role="properties"]
{"id" "b38c3ac4-b5fc-4642-9543-c9e37c0c41f7" "index" 4 "name" "marf_sound" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "marf_sound" "frame" #afield/rect [440.0 226.0 340.0 136.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "b38c3ac4-b5fc-4642-9543-c9e37c0c41f7_code_.kt" "lines" 25 "characters" 1218 "sha256" "a7d0bd74643f956595f319a6c3ef81572a59c8a90d6c984910e8d7ca34b67c0a"}]}
```

```kotlin [field-property box="b38c3ac4-b5fc-4642-9543-c9e37c0c41f7" name="code" path="b38c3ac4-b5fc-4642-9543-c9e37c0c41f7_code_.kt"]
// MARF as control voltage into a real synth: pat -> Dough -> Steam Audio HRTF, realtime.
// Three readers of one bank become where the sound is, how open its filter is, and how far away.
// Nothing about the bank knows it is being read three times, or heard rather than drawn.
displayClearAll()

val b = marfBank(
    rampTo(0.05, 1.0), rampTo(0.95, 0.5), holdAt(0.45, 2.0),
    oscTo(0.80, 1.0, cycles = 3.0), curveTo(0.15, 0.25, amount = 4.0)
)

// a fractional address, so the source glides rather than stepping between slots
val azR = b.read(saw().slow(8).range(0.0, 4.0), duration = constant(0.25), name = "az")
// reader A's stage shapes, heard as filter movement: the ramp, the step, the osc:3 wiggle
val cutoff = b.read(pat("<0 1 2 1 3 4>").nums(), fields = MarfFieldClock.STAGES, name = "cutoff")
// held addresses, so distance moves in steps and then sits still
val near = b.read(pat("<0 2 4 1 3>").nums().slow(3), fields = MarfFieldClock.STAGES, name = "near")

val voice = pat("bd ~ sd ~, hh*8").with(
    param("az", azR.signal().range(-80.0, 80.0)),
    param("lpf", cutoff.signal().rangex(250.0, 7000.0)),
    param("dist", near.signal().range(1.0, 5.0)),
    gain(0.9)
)

patSpatial(voice, cps = 2.0)
```

