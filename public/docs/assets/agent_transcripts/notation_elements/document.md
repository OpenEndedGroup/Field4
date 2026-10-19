# Field Document: notation_elements

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. audit

<!-- field-box id="ne_audit" index="0" -->

### Narrative

From `code`

Calls every part of the API once and prints what came back. Run `elements` and `score` first.

```edn [field-box id="ne_audit" role="properties"]
{"id" "ne_audit" "index" 0 "name" "audit" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "audit" "_tombstone" false "frame" #afield/rect [80.0 640.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ne_audit_code_.kt" "lines" 50 "characters" 3396 "sha256" "382796277deffc1b0b6bf12c413cd1981691a568d096ee3bf47df67228b9e9da"}]}
```

```kotlin [field-property box="ne_audit" name="code" path="ne_audit_code_.kt"]
// Calls every part of the API once and prints what came back. Run `elements` and `score` first.
val elements = inside.boxes.values.first { it.name == "elements" }
val score = inside.boxes.values.first { it.name == "score" }
fun check(what: String, ok: Boolean, detail: Any? = "") = println((if (ok) "works  " else "BROKEN ") + what + "  " + detail)

check("marks", notation.marks.isNotEmpty(), notation.marks.size)
check("strokes", notation.strokes.any { it.name == "wave" }, notation.strokes.size)
check("arrows", notation.arrows.isNotEmpty(), notation.arrows.size)
check("lines", notation.lines.size >= 2, notation.lines.size)
check("texts", notation.texts.isNotEmpty(), notation.texts.size)
check("shapes", notation.shapes.size >= 6, notation.shapes.size)
check("frames", notation.frames.any { it.label == "score" }, notation.frames.map { it.label })
val wave = notation.named("wave")
check("named", wave != null)
check("get by id", wave != null && notation[wave.id] == wave)
check("arrowsFrom/To", notation.arrowsFrom(elements).isNotEmpty() && notation.arrowsTo(score).isNotEmpty())
val a = notation.arrowsFrom(elements).first()
check("arrow.fromBox/toBox", a.fromBox == elements && a.toBox == score, "${a.fromBox?.name} -> ${a.toBox?.name}")
check("selected", notation.selected.isEmpty(), "(nothing selected in a fresh page)")
check("mark props", wave!!.points.size == 61 && wave.pressures.isNotEmpty() && wave.owner == elements,
    "${wave.kind} ${wave.bounds} stroke=${wave.stroke}")
check("toFLine", wave.toFLine().nodes.size == 61)
check("yAt / xAt", wave.yAt(200.0).size == 1 && notation.named("C5")!!.xAt(235.0).size == 2)
check("vertical (world)", notation.vertical(140.0).isNotEmpty(), notation.vertical(140.0).size)
check("horizontal (world)", notation.horizontal(245.0).isNotEmpty())
check("intersect segment", notation.intersect(vec(80, 350), vec(440, 350)).isNotEmpty())
check("inside(frame)", notation.inside(notation.named("score")!!).size == 3)
val grouped = notation.marks.first { it.groups.isNotEmpty() }
check("groups / grouped", notation.grouped(grouped).size == 3, grouped.groups)
val sp = notation.space("score", x = 0.0..8.0, y = 72.0..60.0)
check("space(name) excludes its frame by id", sp.marks.none { it.kind == MarkType.FRAME } && sp.marks.any { it.label == "C5" })
check("space events/between/sounding", sp.events.size == 3 && sp.sounding(1.5).isNotEmpty() && sp.between(0.0, 8.0).size == 3)
check("space(box)", notation.space(score, 0.0..1.0, 0.0..1.0).bounds == score.frame)

// Writing, on marks this box owns.
val mine = notation("scratch") {
    rect(100, 650, 60, 40, name = "scratch") // over this box
    text("t", 160, 760, name = "t")
}
check("over(box)", notation.over(thisBox).any { it.name == "scratch" }, notation.over(thisBox).map { it.label })
val e = notation.edit(mine[0]) { fill = "#ffd8a8"; roughness = 2.0; strokeStyle = "dotted"; fillStyle = "zigzag"; moveBy(10.0, 0.0) }
check("edit", e != null && e.bounds.x == 110.0 && e.json["strokeStyle"].toString() == "\"dotted\"")
notation.named("scratch")!!.anchorTo(thisBox)
check("anchor / anchorTo", notation.named("scratch")!!.anchoredTo == thisBox)
notation.unanchor(mine[0])
check("unanchor", notation.named("scratch")!!.anchoredTo == null)
notation.delete(mine[1])
check("delete", notation.named("t") == null)
notation.clear("scratch")
check("clear(key)", notation.named("scratch") == null)
```

## 2. hand

<!-- field-box id="ne_hand" index="1" -->

### Narrative

From `code`

Stands in for a hand: sends a stroke, an arrow and a text from the page over the same
`notation-scene` message the drawing layer sends. Needs the document open in a browser.

```edn [field-box id="ne_hand" role="properties"]
{"id" "ne_hand" "index" 1 "name" "hand" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "hand" "_tombstone" false "frame" #afield/rect [1100.0 640.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ne_hand_code_.kt" "lines" 21 "characters" 1601 "sha256" "2b71f80462cdba46fa102f713116e08e1ee8f8f99da9b080552d83a83aa92ff9"}]}
```

```kotlin [field-property box="ne_hand" name="code" path="ne_hand_code_.kt"]
// Stands in for a hand: sends a stroke, an arrow and a text from the page over the same
// `notation-scene` message the drawing layer sends. Needs the document open in a browser.
val listen = inside.boxes.values.first { it.name == "listen" }
val f = listen.frame
val t = System.currentTimeMillis()
display("hand", """
<script type="module">
import { send } from "./afield.mjs";
const base = (id, type, x, y, w, h) => ({ id, type, x, y, width: w, height: h, angle: 0,
    strokeColor: "#1e1e1e", backgroundColor: "transparent", fillStyle: "solid", strokeWidth: 2,
    strokeStyle: "solid", roughness: 1, opacity: 100, groupIds: [], frameId: null, roundness: null,
    seed: 1, version: 1, versionNonce: 1, isDeleted: false, boundElements: [], link: null, locked: false });
const stroke = { ...base("hand-stroke-$t", "freedraw", 1100, 760, 200, 40),
    points: [[0, 0], [50, 20], [100, 0], [150, 30], [200, 40]], pressures: [], simulatePressure: true };
const arrow = { ...base("hand-arrow-$t", "arrow", 1100, 670, ${f.x + f.w + 4 - 1100}, ${f.y + f.h / 2 - 670}),
    points: [[0, 0], [${f.x + f.w + 4 - 1100}, ${f.y + f.h / 2 - 670}]], startBinding: null, endBinding: null,
    startArrowhead: null, endArrowhead: "arrow" };
const text = { ...base("hand-text-$t", "text", 1100, 830, 120, 25), text: "by hand", originalText: "by hand",
    fontSize: 20, fontFamily: 5, textAlign: "left", verticalAlign: "top", containerId: null, lineHeight: 1.25 };
send("notation-scene", { scene: JSON.stringify({ type: "excalidraw", version: 2, elements: [stroke, arrow, text] }) });
</script>sent""".trimIndent())
```

## 3. listen

<!-- field-box id="ne_listen" index="2" -->

### Narrative

From `code`

Reactions: these run when a person draws (or `hand` stands in for one). Stop the box to remove them.

```edn [field-box id="ne_listen" role="properties"]
{"id" "ne_listen" "index" 2 "name" "listen" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "listen" "_tombstone" false "frame" #afield/rect [620.0 640.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ne_listen_code_.kt" "lines" 6 "characters" 480 "sha256" "b904110619c2d13f8bd847a7e99e88647552cee0b1c95567d9c1c3a83283b2cb"}]}
```

```kotlin [field-property box="ne_listen" name="code" path="ne_listen_code_.kt"]
// Reactions: these run when a person draws (or `hand` stands in for one). Stop the box to remove them.
notation.onStroke { println("stroke: ${it.points.size} points from ${it.start} to ${it.end}") }
notation.onArrow { println("arrow: from '${it.fromBox?.name}' to '${it.toBox?.name}'") }
notation.onText { println("text: '${it.text}'") }
notation.onChange { println("change: ${it.added.size} added, ${it.updated.size} updated, ${it.removed.size} removed") }
println("listening")
```

## 4. elements

<!-- field-box id="ne_elements" index="3" -->

### Narrative

From `code`

Every kind of Excalidraw mark, written from code. The marks belong to this box:
run it again and they are replaced in place.

```edn [field-box id="ne_elements" role="properties"]
{"id" "ne_elements" "index" 3 "name" "elements" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "elements" "_tombstone" false "frame" #afield/rect [80.0 80.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ne_elements_code_.kt" "lines" 28 "characters" 1348 "sha256" "7fa44343be496fbc7836452e9ef4fee0df1720e014389cfe8b0bf56871064c96"}]}
```

```kotlin [field-property box="ne_elements" name="code" path="ne_elements_code_.kt"]
// Every kind of Excalidraw mark, written from code. The marks belong to this box:
// run it again and they are replaced in place.
val score = inside.boxes.values.first { it.name == "score" }
notation {
    roughness = 0.0
    text("shapes", 80, 180, size = 16)
    rect(80, 210, 120, 70, label = "rect", fill = "#a5d8ff")
    ellipse(220, 210, 120, 70, label = "ellipse", fill = "#b2f2bb", fillStyle = "cross-hatch")
    diamond(360, 205, 120, 80, label = "diamond", fill = "#ffec99", fillStyle = "hachure")

    text("strokes and lines", 80, 310, size = 16)
    line(listOf(vec(80, 350), vec(160, 330), vec(240, 370)), stroke = "#e03131")
    dashed = true
    line(listOf(vec(260, 350), vec(340, 330), vec(420, 370)), stroke = "#1971c2")
    dashed = false
    stroke((0..60).map { vec(80 + it * 6.0, 420 + 18 * sin(it / 6.0)) }, name = "wave",
        stroke = "#9c36b5", pressure = { 0.2 + 0.8 * it })

    text("a group: these drag together", 80, 470, size = 16)
    group {
        rect(80, 500, 60, 40, fill = "#ffc9c9", opacity = 60)
        ellipse(150, 500, 60, 40, fill = "#ffc9c9", opacity = 60)
        diamond(220, 495, 60, 50, fill = "#ffc9c9", opacity = 60)
    }

    arrow(from = thisBox, to = score, label = "reads")
}
println("${notation.mine.size} marks: " + notation.mine.groupingBy { it.kind.name.lowercase() }.eachCount())
```

## 5. score

<!-- field-box id="ne_score" index="4" -->

### Narrative

From `code`

A frame becomes a score: eight beats across, MIDI 72 at the top to 60 at the bottom.
The notes are drawn inside the frame block, so they belong to the frame and move with it.

```edn [field-box id="ne_score" role="properties"]
{"id" "ne_score" "index" 4 "name" "score" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "score" "_tombstone" false "frame" #afield/rect [620.0 80.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ne_score_code_.kt" "lines" 15 "characters" 879 "sha256" "f70d0b0fdbc543765b97487c8065ffac2ad86cbb0c289a0ec292dc044a1f02a1"}]}
```

```kotlin [field-property box="ne_score" name="code" path="ne_score_code_.kt"]
// A frame becomes a score: eight beats across, MIDI 72 at the top to 60 at the bottom.
// The notes are drawn inside the frame block, so they belong to the frame and move with it.
val frame = notation("sheet") {
    frame("score", 560, 180, 480, 240) {
        rect(620, 220, 120, 30, name = "C5", fill = "#1971c2")
        rect(740, 300, 180, 30, name = "E4", fill = "#2f9e44")
        stroke(listOf(vec(600, 400), vec(1000, 200)), name = "gliss", stroke = "#e03131")
    }
}.first()
val s = notation.space(frame, x = 0.0..8.0, y = 72.0..60.0)
println("in the score: " + s.marks.map { it.label })
s.events.forEach { println("  $it") }
println("sounding at beat 3: " + s.sounding(3.0).map { it.mark.label })
println("starting in [1, 3): " + s.between(1.0, 3.0).map { it.mark.label })
println("cut at beat 4: " + s.vertical(4.0).map { "${it.mark.label} %.1f".format(it.value) })
```

