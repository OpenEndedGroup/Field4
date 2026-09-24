# Field Document: marf_field_clocks

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. marf clocks — refused

<!-- field-box id="marf_clocks_refused" index="0" -->

### Narrative

From `code`

Five of the ten address spellings from 03:38:58–03:42:10 UTC on 2026-09-21. Under
MarfFieldClock.STAGES each was read at one instant per cycle, so a pattern with two
steps in a cycle had one of them latched and the rest discarded — with no exception,
no warning and no log line. Four of these five are now refused where you type them.

```edn [field-box id="marf_clocks_refused" role="properties"]
{"id" "marf_clocks_refused" "index" 0 "name" "marf clocks — refused" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "marf clocks — refused" "_tombstone" false "frame" #afield/rect [60.0 60.0 430.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "marf_clocks_refused_code_.kt" "lines" 34 "characters" 1514 "sha256" "91d936dd8eedc50fb1154bdcf3572210019c02f8192bb9232e1f6973667c67b2"}]}
```

```kotlin [field-property box="marf_clocks_refused" name="code" path="marf_clocks_refused_code_.kt"]
// Five of the ten address spellings from 03:38:58–03:42:10 UTC on 2026-09-21. Under
// MarfFieldClock.STAGES each was read at one instant per cycle, so a pattern with two
// steps in a cycle had one of them latched and the rest discarded — with no exception,
// no warning and no log line. Four of these five are now refused where you type them.
displayClearAll()
displayOpen("verdict")

val bank = marfValues(0, 1, 2, 3, 4)

val readOut: (String, MarfFieldClock) -> String = { spec, clock ->
    runCatching {
        bank.read(pat(spec).nums(), fields = clock, name = "addr")
            .stagesIn(PatArc(PatQ.ZERO, PatQ.of(8)))
            .take(8)
            .joinToString(" ") { s -> s.address.toInt().toString() }
    }.fold({ seq -> "reads as `$seq`" }, { e -> "**REFUSED** — ${e.message}" })
}

val spellings = listOf("<0 3> 1", "1 <0 3>", "<0 3>*2", "<0 3>, 1", "<0 3>")

val body = StringBuilder()
body.append("## What `STAGES` now refuses\n\n")
spellings.forEach { spec ->
    body.append("`address = \"$spec\"`\n\n")
    body.append(": ").append(readOut(spec, MarfFieldClock.STAGES)).append("\n\n")
}
body.append("## The same five under `CYCLES`, which is exempt\n\n")
body.append("A field is a control voltage there, every pattern means something, and\n")
body.append("nothing below raises. This is the reading that was always safe.\n\n")
spellings.forEach { spec ->
    body.append("- `\"$spec\"` ").append(readOut(spec, MarfFieldClock.CYCLES)).append("\n")
}

markdown("verdict", body.toString())
```

## 2. marf clocks — STEPS

<!-- field-box id="marf_clocks_steps" index="1" -->

### Narrative

From `code`

The other half. MarfFieldClock.STEPS subdivides each cycle of STAGES: a field
contributes one stage per onset, and at least one per cycle. So "<0 3> 1" is the
three-entry sequence it looks like rather than an error, and the bottom lane shows
the property that makes STEPS a clock rather than a re-spelling of the address —
the address and the duration walk their OWN grids, one entry each per stage.

```edn [field-box id="marf_clocks_steps" role="properties"]
{"id" "marf_clocks_steps" "index" 1 "name" "marf clocks — STEPS" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "marf clocks — STEPS" "_tombstone" false "frame" #afield/rect [900.0 60.0 430.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "marf_clocks_steps_code_.kt" "lines" 77 "characters" 3256 "sha256" "9c469aa6b156d1a44e6c6fbcb6ab3599189d630fb3f9f4790334e0d7423af54a"}]}
```

```kotlin [field-property box="marf_clocks_steps" name="code" path="marf_clocks_steps_code_.kt"]
// The other half. MarfFieldClock.STEPS subdivides each cycle of STAGES: a field
// contributes one stage per onset, and at least one per cycle. So "<0 3> 1" is the
// three-entry sequence it looks like rather than an error, and the bottom lane shows
// the property that makes STEPS a clock rather than a re-spelling of the address —
// the address and the duration walk their OWN grids, one entry each per stage.
displayClearAll()
displayOpen("lines")
displayOpen("key")

val bank = marfBank(
    rampTo(0.1, 1.0), rampTo(0.9, 1.0), holdAt(0.45, 1.0),
    oscTo(0.75, 1.0, cycles = 3.0), curveTo(0.2, 1.0, amount = 4.0)
)

val readers = listOf(
    "<0 3>  STAGES" to bank.read(pat("<0 3>").nums(), fields = MarfFieldClock.STAGES, name = "a"),
    "<0 3> 1  STEPS" to bank.read(pat("<0 3> 1").nums(), fields = MarfFieldClock.STEPS, name = "b"),
    "<0 3> 1 2  STEPS" to bank.read(pat("<0 3> 1 2").nums(), fields = MarfFieldClock.STEPS, name = "c"),
    "<0 3> 1 + dur <1 2>" to bank.read(
        pat("<0 3> 1").nums(),
        duration = pat("<1 2>").nums(),
        fields = MarfFieldClock.STEPS,
        name = "d"
    )
)

val cycles = 12L
val W = 1000.0
val laneH = 74.0
val palette = listOf(
    vec(0.45, 0.82, 1.0, 0.95),
    vec(1.0, 0.60, 0.26, 0.95),
    vec(0.52, 0.90, 0.52, 0.95),
    vec(0.85, 0.62, 1.0, 0.95)
)

val out = mutableListOf<FLine>()
readers.forEachIndexed { lane, entry ->
    val reader = entry.second
    val top = 16.0 + lane * (laneH + 18.0)
    // Dashed verticals are the stage boundaries the step grid laid out.
    reader.stagesIn(PatArc(PatQ.ZERO, PatQ.of(cycles))).forEach { s ->
        val bx = 60.0 + (s.start.toDouble() / cycles) * W
        out += fline {
            moveTo(bx, top); lineTo(bx, top + laneH)
            stroked = true; thickness = 1.0; color = vec(0.40, 0.44, 0.54, 0.85)
        }
    }
    out += fline {
        moveTo(60.0, top + laneH); lineTo(60.0 + W, top + laneH)
        stroked = true; thickness = 1.0; color = vec(0.32, 0.35, 0.43, 0.9)
    }
    out += fline {
        for (i in 0..1200) {
            val t = i / 1200.0 * cycles
            val px = 60.0 + (t / cycles) * W
            val py = top + laneH - reader.value(t) * (laneH - 6.0)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        stroked = true; thickness = 1.8; color = palette[lane]
    }
}
lines(out, w = 1080.0, h = 380.0, fitToSize = true)

val key = StringBuilder()
key.append("**Twelve cycles, top to bottom.** The address each lane actually walked:\n\n")
readers.forEachIndexed { lane, entry ->
    val seq = entry.second.stagesIn(PatArc(PatQ.ZERO, PatQ.of(cycles)))
        .take(8).joinToString(" ") { s -> s.address.toInt().toString() }
    key.append("- `${entry.first}` — `$seq`\n")
}
key.append("\nThe top lane is the one spelling that was always read correctly. Lanes two\n")
key.append("and three are spellings that used to collapse onto it. The bottom lane is the\n")
key.append("same address as lane two against `duration = \"<1 2>\"` — two grids of different\n")
key.append("lengths, each advancing one entry per stage, which is why its boundaries fall\n")
key.append("in different places while its addresses match lane two's exactly.\n")
markdown("key", key.toString())
```

## 3. marf clocks — the night's ten

<!-- field-box id="marf_clocks_ledger" index="2" -->

### Narrative

From `code`

All ten spellings, all three clocks, side by side. `operations.yaml` in the
marf_demo document records these being tried one after another about five seconds
apart. Read the STAGES column: eight of the ten are one of three sequences.

```edn [field-box id="marf_clocks_ledger" role="properties"]
{"id" "marf_clocks_ledger" "index" 2 "name" "marf clocks — the night's ten" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "marf clocks — the night's ten" "_tombstone" false "frame" #afield/rect [900.0 820.0 430.0 140.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "marf_clocks_ledger_code_.kt" "lines" 54 "characters" 2397 "sha256" "b3c3054ac2c0d86fdf489adc8777d1a7d33859d70ab89898842fdb809cd2be79"}]}
```

```kotlin [field-property box="marf_clocks_ledger" name="code" path="marf_clocks_ledger_code_.kt"]
// All ten spellings, all three clocks, side by side. `operations.yaml` in the
// marf_demo document records these being tried one after another about five seconds
// apart. Read the STAGES column: eight of the ten are one of three sequences.
displayClearAll()
displayOpen("ledger")

val bank = marfValues(0, 1, 2, 3, 4)
val spellings = listOf(
    "<0 3>", "<0 3> 1", "1 <0 3>", "<0 3>*2", "<0 3>!2",
    "<0 3>, 1", "<0 3> 1*8", "<0 3> 1 2", "<0 3> <0 4>", "3 <0 3>"
)

val cell: (String, MarfFieldClock) -> String = { spec, clock ->
    runCatching {
        bank.read(pat(spec).nums(), fields = clock, name = "l")
            .stagesIn(PatArc(PatQ.ZERO, PatQ.of(8)))
            .take(8)
            .joinToString("") { s -> s.address.toInt().toString() }
    }.fold({ seq -> "`$seq`" }, { _ -> "_refused_" })
}

val table = StringBuilder()
table.append("| address | `CYCLES` | `STAGES` | `STEPS` |\n")
table.append("| --- | --- | --- | --- |\n")
spellings.forEach { spec ->
    table.append("| `$spec` ")
    table.append("| ").append(cell(spec, MarfFieldClock.CYCLES)).append(" ")
    table.append("| ").append(cell(spec, MarfFieldClock.STAGES)).append(" ")
    table.append("| ").append(cell(spec, MarfFieldClock.STEPS)).append(" |\n")
}

val stagesDistinct = spellings.mapNotNull { spec ->
    runCatching {
        bank.read(pat(spec).nums(), fields = MarfFieldClock.STAGES, name = "l")
            .stagesIn(PatArc(PatQ.ZERO, PatQ.of(8))).take(8).map { s -> s.address }
    }.getOrNull()
}
val stepsDistinct = spellings.mapNotNull { spec ->
    runCatching {
        bank.read(pat(spec).nums(), fields = MarfFieldClock.STEPS, name = "l")
            .stagesIn(PatArc(PatQ.ZERO, PatQ.of(8))).take(8).map { s -> s.address }
    }.getOrNull()
}
table.append("\n")
table.append("`STAGES` now accepts ${stagesDistinct.size} of the ten and reads ")
table.append("${stagesDistinct.distinct().size} distinct sequence(s) out of them. ")
table.append("`STEPS` accepts ${stepsDistinct.size} and reads ")
table.append("${stepsDistinct.distinct().size}. ")
table.append("The one neither can read is the stack, `\"<0 3>, 1\"` — two layers on one ")
table.append("instant, one latched value per stage, and no choice of clock recovers the ")
table.append("second. That is refused too, and says so rather than naming a mode that ")
table.append("would not help.\n")

markdown("ledger", table.toString())
```

