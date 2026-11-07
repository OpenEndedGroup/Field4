# Field Document: sentence_diagrams

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · A sentence, two structures

<!-- field-box id="sentence_both" index="0" -->

```edn [field-box id="sentence_both" role="properties"]
{"id" "sentence_both" "index" 0 "name" "1 · A sentence, two structures" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · A sentence, two structures" "_tombstone" false "frame" #afield/rect [0.0 0.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sentence_both_code_.kt" "lines" 29 "characters" 888 "sha256" "fcfee2ba126e2eb717b1d3ac8981d38f13d8a051f148adc07689d360680a03b7"}]}
```

```kotlin [field-property box="sentence_both" name="code" path="sentence_both_code_.kt"]
import afield.graphics.text.sentences.*

val d = SentenceDiagram().use(Sentences.example(2))
d.wordFont = font("Serif", 30.0)
d.heights = Heights.Flat()
d.wordGap = 50.0
d.layerGap = 50.0
d.tagFontSize = 12.0
d.labelFontSize = 11.0
// Constituents above, collapsed dependencies below; both share the same token row.
val svg = d.svg()

val figureId = "sentence-figure-${thisBox.id}"
display(
    "diagram",
    """
    <div id="$figureId" style="width:760px;background:#f4f0e6;padding:24px;box-sizing:border-box">
    <style>#$figureId svg { width:100%; height:auto; display:block; }</style>
    $svg
    </div>
    <script>
    (() => {
        const svg = document.getElementById("$figureId")?.querySelector("svg");
        if (svg) svg.setAttribute("viewBox", "0 0 " + svg.getAttribute("width") + " " + svg.getAttribute("height"));
    })();
    </script>
""",
)
displayOpen("diagram")
```

## 2. 3 · Dependency arcs and decorations

<!-- field-box id="sentence_undertow" index="1" -->

```edn [field-box id="sentence_undertow" role="properties"]
{"id" "sentence_undertow" "index" 1 "name" "3 · Dependency arcs and decorations" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · Dependency arcs and decorations" "_tombstone" false "frame" #afield/rect [1800.0 0.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sentence_undertow_code_.kt" "lines" 38 "characters" 1305 "sha256" "4d9db1e179513ff95c1b1a42234c838b703a54728ef603e5a8f79a9ab587a35a"}]}
```

```kotlin [field-property box="sentence_undertow" name="code" path="sentence_undertow_code_.kt"]
import afield.graphics.render.color
import afield.graphics.text.sentences.*

val d = SentenceDiagram().use(Sentences.example(3))
d.wordFont = font("Serif", 32.0)
d.heights = Heights.Wandering(seed = 17, amplitude = 20.0)
d.wordGap = 48.0
d.labelFontSize = 11.0
d.arcSpanRatio = .43
val marks = decorations {
    arc { dot(it.pointAt(.5), 3.0).also { f -> f.color = vec(.7, .3, .1, 1) } }
    underline { ring(it.left, 3.0).also { f -> f.color = vec(.7, .3, .1, 1) } }
}
val f = d.flines(decorate = marks)
val undertow = f.copy(
    treeEdges = emptyList(),
    wordEdges = emptyList(),
    texts = f.texts.filter { it.kind != TextKind.TAG },
)
val svg = afield.ui.visualization.drawToSVG(undertow.everything(style = DiagramStyle.onWhite), -1.0, -1.0)

val figureId = "sentence-figure-${thisBox.id}"
display(
    "diagram",
    """
    <div id="$figureId" style="width:760px;background:#f4f0e6;padding:24px;box-sizing:border-box">
    <style>#$figureId svg { width:100%; height:auto; display:block; }</style>
    $svg
    </div>
    <script>
    (() => {
        const svg = document.getElementById("$figureId")?.querySelector("svg");
        if (svg) svg.setAttribute("viewBox", "0 0 " + svg.getAttribute("width") + " " + svg.getAttribute("height"));
    })();
    </script>
""",
)
displayOpen("diagram")
```

## 3. 2 · The constituency canopy

<!-- field-box id="sentence_canopy" index="2" -->

```edn [field-box id="sentence_canopy" role="properties"]
{"id" "sentence_canopy" "index" 2 "name" "2 · The constituency canopy" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · The constituency canopy" "_tombstone" false "frame" #afield/rect [900.0 0.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sentence_canopy_code_.kt" "lines" 33 "characters" 1191 "sha256" "82be5dea6efd9528d272da1d2166718cf49995bde8b5460dfc8da1aebf09876c"}]}
```

```kotlin [field-property box="sentence_canopy" name="code" path="sentence_canopy_code_.kt"]
import afield.graphics.render.color
import afield.graphics.text.sentences.*

val d = SentenceDiagram().use(Sentences.example(2))
d.wordFont = font("Serif", 32.0)
d.heights = Heights.ByTreeDepth(jitter = 0.0)
d.layerGap = 55.0
d.wordGap = 36.0
d.tagFontSize = 13.0
val marks = decorations {
    node { if (!it.node.leaf) ring(it.position, 3.5).also { f -> f.color = vec(.7, .3, .1, 1) } else null }
}
val f = d.flines(decorate = marks)
val canopy = f.copy(arcs = emptyList(), texts = f.texts.filter { it.kind != TextKind.RELATION })
val svg = afield.ui.visualization.drawToSVG(canopy.everything(style = DiagramStyle.onWhite), -1.0, -1.0)

val figureId = "sentence-figure-${thisBox.id}"
display(
    "diagram",
    """
    <div id="$figureId" style="width:760px;background:#f4f0e6;padding:24px;box-sizing:border-box">
    <style>#$figureId svg { width:100%; height:auto; display:block; }</style>
    $svg
    </div>
    <script>
    (() => {
        const svg = document.getElementById("$figureId")?.querySelector("svg");
        if (svg) svg.setAttribute("viewBox", "0 0 " + svg.getAttribute("width") + " " + svg.getAttribute("height"));
    })();
    </script>
""",
)
displayOpen("diagram")
```

## 4. 4 · Reed-Kellogg sentence furniture

<!-- field-box id="sentence_furniture" index="3" -->

```edn [field-box id="sentence_furniture" role="properties"]
{"id" "sentence_furniture" "index" 3 "name" "4 · Reed-Kellogg sentence furniture" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · Reed-Kellogg sentence furniture" "_tombstone" false "frame" #afield/rect [2700.0 0.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sentence_furniture_code_.kt" "lines" 33 "characters" 1091 "sha256" "523b734ece13ad707b6afa0343e34ef2e330d26e214306e95f9c7624e207ecba"}]}
```

```kotlin [field-property box="sentence_furniture" name="code" path="sentence_furniture_code_.kt"]
import afield.graphics.text.sentences.*

val d = SentenceDiagram().use(Sentences.example(4))
d.wordFont = font("Serif", 32.0)
// Here grammar places words on shelves/slants; it is a different layout, not a style on the arc tree.
val config = KelloggConfig(
    slantAngle = 30.0,
    nestAngleStep = 10.0,
    maxSlantAngle = 48.0,
    fontSize = 32.0,
    metrics = Java2DMetrics(d.wordFont!!),
    modifierSize = 23.0,
    modifierMetrics = Java2DMetrics(d.wordFont!!.deriveFont(23f)),
)
val svg = d.kelloggSvg(kelloggConfig = config)

val figureId = "sentence-figure-${thisBox.id}"
display(
    "diagram",
    """
    <div id="$figureId" style="width:760px;background:#f4f0e6;padding:24px;box-sizing:border-box">
    <style>#$figureId svg { width:100%; height:auto; display:block; }</style>
    $svg
    </div>
    <script>
    (() => {
        const svg = document.getElementById("$figureId")?.querySelector("svg");
        if (svg) svg.setAttribute("viewBox", "0 0 " + svg.getAttribute("width") + " " + svg.getAttribute("height"));
    })();
    </script>
""",
)
displayOpen("diagram")
```

