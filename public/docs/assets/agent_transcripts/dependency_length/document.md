# Field Document: dependency_length

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 5 · Ratio per sentence, five texts

<!-- field-box id="dl_ratios" index="0" -->

```edn [field-box id="dl_ratios" role="properties"]
{"id" "dl_ratios" "index" 0 "name" "5 · Ratio per sentence, five texts" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 · Ratio per sentence, five texts" "_tombstone" false "frame" #afield/rect [0.0 1400.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dl_ratios_code_.kt" "lines" 11 "characters" 322 "sha256" "d80fbe16956a0da64cb7c62bed68ac757749aa0f6dafbf6f9737f0f1245b2148"}]}
```

```kotlin [field-property box="dl_ratios" name="code" path="dl_ratios_code_.kt"]
import afield.graphics.text.sentences.*

val texts = SentenceTexts.all()
display(
    "ratios",
    violin(
        *texts.map { t -> t.key to t.measure().filter { it.length >= 8 }.map { it.ratio } }.toTypedArray(),
        references = List(texts.size) { 1.0 },
        yTitle = "as written ÷ free random mean",
    ),
)
```

## 2. 2 · Gettysburg, sentence by sentence

<!-- field-box id="dl_gettysburg" index="1" -->

```edn [field-box id="dl_gettysburg" role="properties"]
{"id" "dl_gettysburg" "index" 1 "name" "2 · Gettysburg, sentence by sentence" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · Gettysburg, sentence by sentence" "_tombstone" false "frame" #afield/rect [900.0 0.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dl_gettysburg_code_.kt" "lines" 17 "characters" 697 "sha256" "88ea5ef8d0a26061c6036bd5b35dcc7eb301543ffeecd186ef3608d37fe004a7"}]}
```

```kotlin [field-property box="dl_gettysburg" name="code" path="dl_gettysburg_code_.kt"]
import afield.graphics.text.sentences.*

val text = SentenceTexts.load("gettysburg")
val ratioFont = font("Serif", 18.0)
val openingFont = font("Serif", 13.0)
var y = 0.0
val figure =
    text.measure().flatMap { m ->
        // The arcs start at x = 270, leaving a column for the ratio and the opening words.
        val row =
            m.tree.arcLines(at = P(270.0, y), spacing = 8.0, depth = 0.3) +
                outlinedText(m.ratio.f2, P(0.0, y + 6), ratioFont) +
                outlinedText(m.tree.words.take(4).joinToString(" ") + " …", P(50.0, y + 5), openingFont)
        y += m.tree.arcDepth(spacing = 8.0, depth = 0.3) + 30.0
        row
    }
display("figure", figureSvg(figure))
```

## 3. 4 · Five texts side by side

<!-- field-box id="dl_table" index="2" -->

```edn [field-box id="dl_table" role="properties"]
{"id" "dl_table" "index" 2 "name" "4 · Five texts side by side" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · Five texts side by side" "_tombstone" false "frame" #afield/rect [900.0 700.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dl_table_code_.kt" "lines" 23 "characters" 861 "sha256" "4ef7df13adf14a26ee921c8026ff22fa556e64ccbf76bd2f165ddf04530c9282"}]}
```

```kotlin [field-property box="dl_table" name="code" path="dl_table_code_.kt"]
import afield.graphics.text.sentences.*

val rows =
    SentenceTexts.all().map { t ->
        val m = t.measure()
        val observed = m.sumOf { it.lengths.observed }.toDouble()
        "| ${t.title} | ${m.size} | %.1f | %.2f | %.2f | %.2f | %.0f%% |"
            .format(
                m.map { it.length }.average(),
                observed / m.sumOf { it.lengths.freeMean },
                observed / m.sumOf { it.lengths.fixedOrderMean },
                observed / m.sumOf { it.lengths.optimal },
                100.0 * m.count { it.lengths.observed < it.lengths.freeMean } / m.size,
            )
    }
markdown(
    """
    | text | sentences | mean words | ÷ free random | ÷ fixed order | ÷ shortest | shorter than chance |
    | --- | ---: | ---: | ---: | ---: | ---: | ---: |
    ${rows.joinToString("\n    ")}
    """
        .trimIndent(),
)
```

## 4. 3 · Length against sentence length

<!-- field-box id="dl_growth" index="3" -->

```edn [field-box id="dl_growth" role="properties"]
{"id" "dl_growth" "index" 3 "name" "3 · Length against sentence length" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · Length against sentence length" "_tombstone" false "frame" #afield/rect [0.0 700.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dl_growth_code_.kt" "lines" 37 "characters" 1209 "sha256" "d4a1bfe616b83176d40365b74217ba13c65094cc1ccfc774c3875b4de3bc1735"}]}
```

```kotlin [field-property box="dl_growth" name="code" path="dl_growth_code_.kt"]
import afield.graphics.text.sentences.*
import space.kscience.plotly.*
import space.kscience.plotly.models.ScatterMode

val measured = SentenceTexts.all().flatMap { it.measure() }
val words = measured.map { it.length }
val series = [
    "free random reordering (mean of 100)" to measured.map { it.lengths.freeMean },
    "fixed order random reordering (mean of 100)" to measured.map { it.lengths.fixedOrderMean },
    "as written" to measured.map { it.lengths.observed.toDouble() },
    "shortest possible" to measured.map { it.lengths.optimal.toDouble() },
]
val plot = Plotly.plot {
    for ((name, values) in series) {
        scatter {
            this.name = name
            x.set(words)
            y.set(values)
            mode = ScatterMode.markers
            marker { size = 4.0 }
        }
    }
    layout {
        title = "Total dependency length, ${measured.size} sentences from five texts"
        xaxis { title = "words in the sentence" }
        yaxis { title = "total dependency length" }
    }
}
plot.darkMode()
// darkMode() places the legend beside the plot; put it inside, over the empty upper left.
plot.layout {
    legend {
        x = 0.02
        y = 0.98
    }
}
display(plot)
```

## 5. 1 · One sentence and its reorderings

<!-- field-box id="dl_reorderings" index="4" -->

```edn [field-box id="dl_reorderings" role="properties"]
{"id" "dl_reorderings" "index" 4 "name" "1 · One sentence and its reorderings" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · One sentence and its reorderings" "_tombstone" false "frame" #afield/rect [0.0 0.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dl_reorderings_code_.kt" "lines" 29 "characters" 1157 "sha256" "3b90d87cf09f0f43a2a7364385083e61150d5cf52d91c5ddc81014f92bc67e71"}]}
```

```kotlin [field-property box="dl_reorderings" name="code" path="dl_reorderings_code_.kt"]
import afield.graphics.text.sentences.*

// "It is altogether fitting and proper that we should do this", without its full stop.
val tree = SentenceTexts.load("gettysburg").sentences[4].tree()!!
val rng = java.util.Random(5)
val rows =
    listOf("as spoken" to tree.observed) +
        (1..3).map { "shuffled" to Linearization.freeRandom(tree, rng) } +
        listOf("shortest" to Linearization.optimal(tree))

val caption = font("Serif", 30.0)
var y = 0.0
val figure = rows.flatMap { (name, order) ->
    val d = SentenceDiagram().use(tree.sentence(order))
    d.scheme = Scheme.UD
    d.wordFont = font("Serif", 30.0)
    d.wordGap = 22.0
    d.heights = Heights.Flat(y)
    d.arcLabels = false
    d.x0 = 250.0
    val f = d.flines()
    // Keep the words and the arcs; the constituency tree is a stand-in for a reordered sentence.
    val arcs =
        f.copy(treeEdges = emptyList(), wordEdges = emptyList(), texts = f.texts.filter { it.kind == TextKind.WORD })
    val label = outlinedText("$name  ${tree.length(order)}", P(0.0, y), caption)
    y = d.diagram().bounds.y1 + 60.0
    arcs.everything() + label
}
display("figure", figureSvg(figure))
```

