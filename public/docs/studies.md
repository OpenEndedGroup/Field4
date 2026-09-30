# Studies

A study is a piece of text that draws, kept with its history. The text can be a StackSL program, a
RectSL scene, or three numbers in a box property; what makes it a study is that every accepted
change to it is a checked revision, and anything that changes it or looks at it answers with
pictures. The four conversation pages ([a function](function_conversation.md),
[a shader](stacksl_conversation.md), [a scene](rectsl_conversation.md),
[the StackSL in your shader](stackauto_conversation.md)) are a model working on a study through
AI4 tools. This page is the layer underneath them: the ten verbs every study shares, and how a
document's own box becomes a study with the same tools.

Everything here is `afield.stdlib`: `LiveStudy` and `registerStudyTools` in `LiveStudy.kt`,
`SourceFramesStudy` in `SourceFramesStudy.kt`. Every picture on the page is a box's real output.

## The ten verbs

A study offers some or all of these. `LiveStudy` has one method per verb, and a verb a study does
not implement refuses with a message saying so.

| Verb | `LiveStudy` method | What it does | Publishes |
| --- | --- | --- | --- |
| Inspect | `inspect` | Read the checked source, whether the box holds an unchecked edit, the history, the knobs, held variants | no |
| Edit | `edit` | Replace the whole source; it is checked, then made current | yes |
| Patch | `patch` | Exact, unique, non-overlapping replacements in the live source | yes |
| SetNumbers | `setNumbers` | Rewrite knobs (numbers, by index) in place | yes |
| Observe | `observe` | Frames of a revision at chosen times, with measurements, optionally over another revision and a difference | no |
| Compare | `compare` | Before, after and difference per time, and whether everything outside an allowed region stayed within one 8-bit level | no |
| Sweep | `sweep` | One knob at 2 to 9 values, side by side | no |
| TryVariants | `tryVariants` | Check and draw 1 to 8 candidates beside the current source, and hold them | no |
| AdoptVariant | `adoptVariant` | Publish a held candidate by label | yes |
| Restore | `restore` | Make an earlier revision current again, as a new revision | yes |

<!-- studies-verbs: the table above is checked against LiveStudy and StudyVerb by tools/test_studies_page.py -->

Every verb that publishes takes the revision and the source hash its caller last read from
Inspect, and refuses if either has moved since: the box's text may have been edited by a person, or
by another turn of the conversation. A candidate that does not draw is refused too, and nothing is
written. History is only appended to: Restore adds a revision rather than rewinding.

## A study in a dozen lines

`sourceFramesStudy(property, frames)` makes a study of one property of the box. The frames are
whatever you can draw: `studyFrames(description) { source, width, height, seconds -> … }` wraps one
function that returns a `CpuRaster` of linear RGBA. Here the property `rings` holds three numbers,
and the function reads them as a ring count, the brightness of the dark bands, and a warmth that
moves the light bands from blue to orange. The numbers are found by `StudyKnobs.plainNumbers`, which
is also the default for the study's knobs, so the painter and the study agree on what the numbers
are.

`registerStudyTools("Rings", rings)` then gives this document's AI4 chat the ten verbs as tools,
named after the prefix. The rest of the block does by hand what a model's first two calls would
do: make the first revision against `empty`, then look at it at three times.

<!--
FIELD_TEST
id: docs.studies.define
runner: backdoor-box
tags: [docs, study, ai4, tools]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "InspectRingsStudy"
    - "RestoreRingsRevision"
    - "Checked and activated"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/studies/define.png" alt="the main output channel: a list of ten tool names from InspectRingsStudy to RestoreRingsRevision, the line 'r1-… : Checked and activated', and a sheet titled 'studies-define study' showing the seven-ring pattern at t = 0, 0.5 and 1 seconds, the rings moving outward between frames, each frame labelled with its mean luma" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true timeout="120" name="studies-define" w="760" h="260"}
:::static
thisBox.setPropertyRaw("rings", "7 rings 0.05 ink 0.8 warmth")

val rings = sourceFramesStudy("rings", studyFrames("rings, drawn on the CPU") { source: String, w: Int, h: Int, t: Double ->
    val (count, ink, warmth) = StudyKnobs.plainNumbers(source).map { it.value.toFloat() }
    val px = FloatArray(w * h * 4)
    for (y in 0 until h) for (x in 0 until w) {
        val d = Math.hypot(x + 0.5 - w / 2.0, y + 0.5 - h / 2.0) / w
        val v = (0.5 + 0.5 * Math.cos(d * count * 12.0 - t * 2.0)).toFloat()
        val i = (y * w + x) * 4
        px[i] = v * warmth + ink * (1 - v); px[i + 1] = v * 0.5f + ink * (1 - v); px[i + 2] = v * (1 - warmth) + ink * (1 - v); px[i + 3] = 1f
    }
    afield.stacksl.cpu.CpuRaster(w, h, px)
})

val tools = registerStudyTools("Rings", rings, about = "The source is three numbers: ring count, ink brightness, warmth 0..1.")

val empty = rings.inspect(StudyCall.Inspect())
val first = rings.edit(StudyCall.Edit("empty", empty.facts["liveSourceHash"] as String, "7 rings 0.05 ink 0.8 warmth", "seven warm rings"))
val seen = rings.observe(StudyCall.Observe(seconds = listOf(0.0, 0.5, 1.0)))

display("main", "<pre>" + tools.values.joinToString("\n") { it.name } + "</pre>" +
    "<pre>${first.revision}: ${first.facts["message"]}</pre>" +
    "<img src='${seen.toolImages.single().dataUrl}' style='width:720px'/>")
```

The revision is named `r1-` and the first twelve hex digits of its source's hash, so the same source
always gets the same name. The sheet is the picture a model receives from Observe, as an image
rather than as text; the numbers beside it (mean luma, clipping, detail, palette, motion between
frames) come back in the report's facts. The study keeps its revisions in the document, under
`studies/<box id>/rings`, and the sheet is saved there too.

!!! note "Saving the property"
    A property is written to the document on save only if its name is in
    `Box.ioPersistentProperties`, and a name you make up, such as `rings`, is not. The study's
    revisions are files under `studies/` and survive, but the box's own text does not unless you add
    the name: `Box.ioPersistentProperties.add("rings")`. The starting point below does this in its
    first box.

## Changing it

SetNumbers rewrites a knob in the box's own text and publishes the result, and its report carries a
preview: the new revision, its parent, and their difference. The block also tries an Edit with the
revision and hash it read before the change, which is what a stale model turn looks like, and asks
Compare whether the change kept brightness.

<!--
FIELD_TEST
id: docs.studies.change
runner: backdoor-box
tags: [docs, study, ai4, tools]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "7 rings 0.05 ink 0.2 warmth"
    - "Conflict"
    - "luma preserved: false"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/studies/change.png" alt="the main output channel: 'box now holds: 7 rings 0.05 ink 0.2 warmth', the stale edit refused with 'Conflict: the revision or the live source changed since you read them', 'luma preserved: false', and below it a sheet headed 'cooler rings' with the new blue rings on top, the orange parent beneath, and their difference map" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true timeout="120" name="studies-change" w="760" h="260"}
:::static
thisBox.setPropertyRaw("rings", "7 rings 0.05 ink 0.8 warmth")

val rings = sourceFramesStudy("rings", studyFrames("rings, drawn on the CPU") { source: String, w: Int, h: Int, t: Double ->
    val (count, ink, warmth) = StudyKnobs.plainNumbers(source).map { it.value.toFloat() }
    val px = FloatArray(w * h * 4)
    for (y in 0 until h) for (x in 0 until w) {
        val d = Math.hypot(x + 0.5 - w / 2.0, y + 0.5 - h / 2.0) / w
        val v = (0.5 + 0.5 * Math.cos(d * count * 12.0 - t * 2.0)).toFloat()
        val i = (y * w + x) * 4
        px[i] = v * warmth + ink * (1 - v); px[i + 1] = v * 0.5f + ink * (1 - v); px[i + 2] = v * (1 - warmth) + ink * (1 - v); px[i + 3] = 1f
    }
    afield.stacksl.cpu.CpuRaster(w, h, px)
})

val first = rings.edit(StudyCall.Edit("empty", rings.inspect(StudyCall.Inspect()).facts["liveSourceHash"] as String, "7 rings 0.05 ink 0.8 warmth", "seven warm rings"))
val hash = rings.inspect(StudyCall.Inspect()).facts["liveSourceHash"] as String

val cooler = rings.setNumbers(StudyCall.SetNumbers(first.revision, hash, listOf(StudyCall.Number(2, 0.2)), "cooler rings"))
val late = runCatching { rings.edit(StudyCall.Edit(first.revision, hash, "3 rings 0.5 ink 0.5 warmth", "an edit from a stale read")) }
val kept = rings.compare(StudyCall.Compare(first.revision, cooler.revision, mode = "luma"))

display("main", "<pre>box now holds: ${thisBox.getPropertyRaw("rings", "")}\n" +
    "stale edit: ${late.exceptionOrNull()?.message}\n" +
    "luma preserved: ${kept.facts["preservedOutsideRegion"]}</pre>" +
    "<img src='${cooler.toolImages.single().dataUrl}' style='width:340px'/>")
```

Knob 2 is the third number, `0.8`, and the box's property now reads `0.2` in its place. The stale
edit changed nothing. Compare in `luma` mode reports `false` because the warm and cool colours do
not have the same brightness; with an `allowedRegion` of `[u0, v0, u1, v1]` it would instead ask
whether everything outside that rectangle held still, which is how a model checks that an edit to
one part of a picture left the rest alone.

## Trying things without publishing

Sweep and TryVariants draw candidates beside what is current and publish nothing. Sweep takes one
knob and a list of values. TryVariants takes whole sources or replacements, checks each one, draws
the ones that pass and holds them under their labels; AdoptVariant then publishes one of them. The
third candidate here is missing two of its numbers, so the painter throws and the study refuses it.

<!--
FIELD_TEST
id: docs.studies.speculate
runner: backdoor-box
tags: [docs, study, ai4, tools]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "label=short, ok=false"
    - "0.1 warmth"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/studies/speculate.png" alt="the main output channel: three lines of variant results, tight and cold ok and short refused with 'That source does not draw', the line 'adopted r2-… ; box now holds: 7 rings 0.05 ink 0.1 warmth', a sweep sheet of five ring patterns at 3, 5, 7 (marked now), 11 and 15 rings, and a variants sheet: the current rings, the tight and cold candidates, and a red error tile for short" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true timeout="120" name="studies-speculate" w="760" h="260"}
:::static
thisBox.setPropertyRaw("rings", "7 rings 0.05 ink 0.8 warmth")

val rings = sourceFramesStudy("rings", studyFrames("rings, drawn on the CPU") { source: String, w: Int, h: Int, t: Double ->
    val (count, ink, warmth) = StudyKnobs.plainNumbers(source).map { it.value.toFloat() }
    val px = FloatArray(w * h * 4)
    for (y in 0 until h) for (x in 0 until w) {
        val d = Math.hypot(x + 0.5 - w / 2.0, y + 0.5 - h / 2.0) / w
        val v = (0.5 + 0.5 * Math.cos(d * count * 12.0 - t * 2.0)).toFloat()
        val i = (y * w + x) * 4
        px[i] = v * warmth + ink * (1 - v); px[i + 1] = v * 0.5f + ink * (1 - v); px[i + 2] = v * (1 - warmth) + ink * (1 - v); px[i + 3] = 1f
    }
    afield.stacksl.cpu.CpuRaster(w, h, px)
})

val first = rings.edit(StudyCall.Edit("empty", rings.inspect(StudyCall.Inspect()).facts["liveSourceHash"] as String, "7 rings 0.05 ink 0.8 warmth", "seven warm rings"))

val swept = rings.sweep(StudyCall.Sweep(knob = 0, values = listOf(3.0, 5.0, 7.0, 11.0, 15.0)))
val tried = rings.tryVariants(StudyCall.TryVariants(listOf(
    StudyCall.Variant("tight", source = "15 rings 0.05 ink 0.8 warmth", note = "twice the rings"),
    StudyCall.Variant("cold", edits = listOf(StudySourceEdit("0.8 warmth", "0.1 warmth"))),
    StudyCall.Variant("short", source = "7 rings"))))

val now = rings.inspect(StudyCall.Inspect())
val adopted = rings.adoptVariant(StudyCall.Adopt(now.revision, now.facts["liveSourceHash"] as String, "cold"))

display("main", "<pre>" + (tried.facts["variants"] as List<*>).joinToString("\n") + "\n" +
    "adopted ${adopted.revision}; box now holds: ${thisBox.getPropertyRaw("rings", "")}</pre>" +
    "<img src='${swept.toolImages.single().dataUrl}' style='width:720px'/>" +
    "<img src='${tried.toolImages.single().dataUrl}' style='width:340px'/>")
```

A refused candidate still has a row on the sheet, with the reason in place of a frame, so a model
sees what it got wrong in the same picture as what worked.

## A study of your own

`SourceFramesStudy` implements all ten verbs, but `LiveStudy` asks only for `inspect`. A study that
implements less offers less, and `registerStudyTools` takes a `verbs` set to register only some of
the tools.

<!--
FIELD_TEST
id: docs.studies.refuse
runner: backdoor-box
tags: [docs, study, ai4, tools]
timeoutSec: 90
waitSec: 1.0
expect:
  outputContains:
    - "InspectLookStudy, ObserveLookStudy"
    - "This study does not offer Observe"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/studies/refuse.png" alt="the main output channel: 'tools: InspectLookStudy, ObserveLookStudy', 'inspect: source=nothing to edit here' in braces, and 'observe: This study does not offer Observe'" wait="1.0" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true timeout="90" name="studies-refuse" w="760" h="120"}
:::static
val lookOnly = object : LiveStudy {
    override fun inspect(q: StudyRequest.Inspect) = StudyReport("current", mapOf("source" to "nothing to edit here"))
}
val tools = registerStudyTools("Look", lookOnly, verbs = setOf(StudyVerb.INSPECT, StudyVerb.OBSERVE))

val read = lookOnly.inspect(StudyCall.Inspect())
val look = runCatching { lookOnly.observe(StudyCall.Observe(seconds = listOf(0.0))) }

display("main", "<pre>tools: ${tools.values.joinToString { it.name }}\n" +
    "inspect: ${read.facts}\n" +
    "observe: ${look.exceptionOrNull()?.message}</pre>")
```

The refusal is a `StudyRejection` (an `IllegalArgumentException`), thrown by `LiveStudy`'s default
method for every verb the study leaves out.

## What a report carries

Every verb answers with a `StudyReport`: the revision it is about, a map of `facts`, and
`toolImages`. The facts are serialised as the tool's JSON output. The images travel separately,
through `CarriesToolImages`, as real images the model can look at; the section
[Returning something the model can read — and see](aiFeatures.md#returning-something-the-model-can-read-and-see)
explains why an image inside the JSON would be wasted. The same class comes back from the function,
StackSL, RectSL and stack.auto studies, so a result reads the same whichever produced it.

## The library's studies

StackSL, RectSL and stack.auto are built from the same pieces a `SourceFramesStudy` is, and the
function study keeps its revisions through the first of them:

| Piece | What it does |
| --- | --- |
| `SourceStudy` | Revisions on disk, the revision-and-hash guard, compare-and-swap publication into the box |
| `StudyFrames` | Where frames come from: a CPU reference, or the GPU of a running window |
| `StudyEvidence` | Measurements and the sheets the pictures are laid out on |
| `StudyCompare` | Before/after/difference and sweeps |
| `StudyVariants` | Held candidates and adoption |

Each library study keeps its own request classes, because their names are the tool names a model
has been shown (`InspectStackSLStudy`, `SweepRectSLNumber`, …), and each implements the matching
`StudyRequest` interface. An adapter such as `StackSLLiveStudy` turns the study into a `LiveStudy`,
and one call, `registerStudyTools(scope, gate, studyOf, StudyToolClasses(…))`, registers all of its
standard tools. What is left in its `*StudyTools.kt` file is what only that language has: StackSL's
stage traces and knob sensitivities, RectSL's timeline, the exports. The function study keeps its
own Edit, Patch, Observe and Compare, because its frames are time windows of a signal rather than
pictures.

The starting point **studies_rings** (in the [agent transcript gallery](agent_transcripts.md#studies_rings), and
on the welcome screen's starting points) is this page as a document: `rings` defines the study, and
two boxes under it, `change it` and `try things`, drive its verbs. Open it and run the three boxes
in order.

`study.document.registrar` (`src/jvmMain/kotlin/afield/examples/ai/DocumentStudySmokeTest.kt`)
drives all ten verbs on a document's study through the same tool registry an agent calls, with no
model. The design notes are in `dev_docs/ai/AGENT_TOOL_EXTENSIONS.md`, under "Studies: one registrar
for the standard verbs".
