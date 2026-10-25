# Field Document: studies_rings

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. rings

<!-- field-box id="rings_study" index="0" -->

### Narrative

From `code`

A study of this box's `rings` property: three numbers, a ring count, the brightness of
the dark bands, and warmth 0..1. Run this box first. It gives the document's AI4 chat ten
tools (InspectRingsStudy ... RestoreRingsRevision); the two boxes below drive the same verbs
from code. See public/docs/studies.md.


sourceFramesStudy saves the source property. Keep a person's edited text on rerun.

```edn [field-box id="rings_study" role="properties"]
{"id" "rings_study" "index" 0 "name" "rings" "parents" ["root"] "children" ["rings_change" "rings_try"] "properties" {"children" #{#afield/box "rings_change" #afield/box "rings_try"} "parents" #{#afield/box "root"} "name" "rings" "_tombstone" false "frame" #afield/rect [40.0 40.0 240.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rings_study_code_.kt" "lines" 32 "characters" 1469 "sha256" "aa7188ba74a17dfe720d55361a6e858e6000be9a819933e349b140d6852c1052"}]}
```

```kotlin [field-property box="rings_study" name="code" path="rings_study_code_.kt"]
/*
A study of this box's `rings` property: three numbers, a ring count, the brightness of
the dark bands, and warmth 0..1. Run this box first. It gives the document's AI4 chat ten
tools (InspectRingsStudy ... RestoreRingsRevision); the two boxes below drive the same verbs
from code. See public/docs/studies.md.
*/

// sourceFramesStudy saves the source property. Keep a person's edited text on rerun.
prop<String>(rings)
once.rings { "7 rings 0.05 ink 0.8 warmth" }
prop<SourceFramesStudy>(ringsStudy)

ringsStudy = sourceFramesStudy(
    "rings",
    studyFrames("rings, drawn on the CPU") { source: String, w: Int, h: Int, t: Double ->
        val (count, ink, warmth) = StudyKnobs.plainNumbers(source).map { it.value.toFloat() }
        val px = FloatArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val d = Math.hypot(x + 0.5 - w / 2.0, y + 0.5 - h / 2.0) / w
            val v = (0.5 + 0.5 * Math.cos(d * count * 12.0 - t * 2.0)).toFloat()
            val i = (y * w + x) * 4
            px[i] = v * warmth + ink * (1 - v)
            px[i + 1] = v * 0.5f + ink * (1 - v)
            px[i + 2] = v * (1 - warmth) + ink * (1 - v)
            px[i + 3] = 1f
        }
        afield.lang.stacksl.cpu.CpuRaster(w, h, px)
    },
)

registerStudyTools("Rings", ringsStudy, about = "The source is three numbers: ring count, ink brightness, warmth 0..1.")
println("rings study ready: " + ringsStudy.inspect(StudyCall.Inspect()).revision)
```

## 2. change it

<!-- field-box id="rings_change" index="1" -->

### Narrative

From `code`

Change the study: check the box's text in as the first revision if there is none yet,
then flip warmth (knob 2) between warm and cool. Each run publishes one revision and shows
it beside its parent with their difference. Run "rings" first.

```edn [field-box id="rings_change" role="properties"]
{"id" "rings_change" "index" 1 "name" "change it" "parents" ["root" "rings_study"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rings_study"} "name" "change it" "_tombstone" false "frame" #afield/rect [40.0 200.0 240.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rings_change_code_.kt" "lines" 30 "characters" 1087 "sha256" "f512268adfeccb7954f9ebcc0b95b3755d1ebf1dcea4d1769d144091159bbafd"}]}
```

```kotlin [field-property box="rings_change" name="code" path="rings_change_code_.kt"]
/*
Change the study: check the box's text in as the first revision if there is none yet,
then flip warmth (knob 2) between warm and cool. Each run publishes one revision and shows
it beside its parent with their difference. Run "rings" first.
*/

var now = ringsStudy.inspect(StudyCall.Inspect())
if (now.revision == "empty") {
    ringsStudy.edit(
        StudyCall.Edit(
            "empty",
            now.facts["liveSourceHash"] as String,
            ringsStudy.revisions.liveSource(),
            "the starting rings",
        ),
    )
    now = ringsStudy.inspect(StudyCall.Inspect())
}
val warmth = StudyKnobs.plainNumbers(now.facts["source"] as String)[2].value
val changed = ringsStudy.setNumbers(
    StudyCall.SetNumbers(
        now.revision,
        now.facts["liveSourceHash"] as String,
        [StudyCall.Number(2, if (warmth > 0.5) 0.2 else 0.8)],
        if (warmth > 0.5) "cooler" else "warmer",
    ),
)

println(changed.revision + ": " + ringsStudy.revisions.liveSource())
display("main", "<img src='${changed.toolImages.single().dataUrl}' style='width:340px'/>")
```

## 3. try things

<!-- field-box id="rings_try" index="2" -->

### Narrative

From `code`

Speculate without publishing: sweep the ring count, and try two candidates beside the
current revision. Nothing here changes the study; adopt a candidate from AI4 chat
(AdoptRingsVariant) or with ringsStudy.adoptVariant(...). Run "rings" first.

```edn [field-box id="rings_try" role="properties"]
{"id" "rings_try" "index" 2 "name" "try things" "parents" ["root" "rings_study"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rings_study"} "name" "try things" "_tombstone" false "frame" #afield/rect [460.0 200.0 240.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rings_try_code_.kt" "lines" 32 "characters" 1310 "sha256" "186c66080a56feae10f6ff39da277cb3e5a1c21e6c8ba6a79e26f703ba5b0aa1"}]}
```

```kotlin [field-property box="rings_try" name="code" path="rings_try_code_.kt"]
/*
Speculate without publishing: sweep the ring count, and try two candidates beside the
current revision. Nothing here changes the study; adopt a candidate from AI4 chat
(AdoptRingsVariant) or with ringsStudy.adoptVariant(...). Run "rings" first.
*/
if (ringsStudy.inspect(StudyCall.Inspect()).revision == "empty") println("no revision yet: run \"change it\" first")
else {
    val source = ringsStudy.revisions.liveSource()
    val swept = ringsStudy.sweep(StudyCall.Sweep(knob = 0, values = [3.0, 5.0, 7.0, 11.0, 15.0]))
    val tried = ringsStudy.tryVariants(
        StudyCall.TryVariants(
            [
                StudyCall.Variant(
                    "tight",
                    source = StudyKnobs.withPlainNumbers(source, mapOf(0 to 15.0)),
                    note = "15 rings",
                ),
                StudyCall.Variant(
                    "bright",
                    source = StudyKnobs.withPlainNumbers(source, mapOf(1 to 0.4)),
                    note = "lighter dark bands",
                ),
            ],
        ),
    )
    println("held: " + tried.facts["held"])
    display(
        "main",
        "<img src='${swept.toolImages.single().dataUrl}' style='width:100%'/>" +
            "<img src='${tried.toolImages.single().dataUrl}' style='width:340px'/>",
    )
}
```

