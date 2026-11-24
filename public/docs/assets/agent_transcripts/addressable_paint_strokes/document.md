# Field Document: addressable_paint_strokes

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - Sprout, stroke by stroke

<!-- field-box id="paint_strokes_creature" index="0" -->

### Narrative

From `code`

points around an ellipse from angle a0 to a1 (radians), as [x0, y0, x1, y1, ...]

```edn [field-box id="paint_strokes_creature" role="properties"]
{"id" "paint_strokes_creature" "index" 0 "name" "1 - Sprout, stroke by stroke" "parents" ["root"] "children" ["paint_strokes_edit" "paint_strokes_note" "paint_strokes_sway"] "properties" {"children" #{#afield/box "paint_strokes_edit" #afield/box "paint_strokes_note" #afield/box "paint_strokes_sway"} "parents" #{#afield/box "root"} "name" "1 - Sprout, stroke by stroke" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_strokes_creature_code_.kt" "lines" 70 "characters" 2223 "sha256" "6c85ad3b387301357ae6d4a22a0a127e5210e0155adcd4693363da007414d308"}]}
```

```kotlin [field-property box="paint_strokes_creature" name="code" path="paint_strokes_creature_code_.kt"]
// points around an ellipse from angle a0 to a1 (radians), as [x0, y0, x1, y1, ...]
fun arc(cx: Double, cy: Double, rx: Double, ry: Double, a0: Double, a1: Double, n: Int = 24) = (0..n).flatMap {
    val a = a0 + (a1 - a0) * it / n
    listOf(cx + rx * cos(a), cy + ry * sin(a))
}

val sheet = PaintStrokes(700, aspect = 1.4, seed = 3)
// the body: rows of a hog flat, filling an ellipse
for (i in -5..5) {
    val y = 400.0 + i * 26
    val w = 190 * sqrt(1 - (i / 6.0).pow(2))
    sheet.add(
        listOf(500 - w, y, 500 + w, y + 6),
        color = if (i % 2 == 0) "#8a9a3a" else "#a0a848",
        brush = BrushKind.HogFlat,
        width = 34.0,
        group = "body",
    )
}
// legs, and leaves sprouting from the head
sheet.add(
    listOf(430.0, 540.0, 420.0, 640.0),
    color = "#4a3a20",
    brush = BrushKind.HogFlat,
    width = 26.0,
    group = "legs",
)
sheet.add(
    listOf(570.0, 540.0, 585.0, 640.0),
    color = "#4a3a20",
    brush = BrushKind.HogFlat,
    width = 26.0,
    group = "legs",
)
for (a in listOf(-2.3, -1.9, -1.57, -1.25, -0.85)) {
    val x = 500 + 60 * cos(a)
    val y = 290 + 30 * sin(a)
    sheet.add(
        listOf(x, y, x + 60 * cos(a) - 12, y + 60 * sin(a), x + 120 * cos(a), y + 110 * sin(a)),
        color = "#2f6a2a",
        brush = BrushKind.Filbert,
        width = 30.0,
        pressure = 0.9 to 0.4,
        group = "leaves",
    )
}
// eyes, a smiling mouth and a pink smudge on one cheek
for (x in listOf(440.0, 560.0)) {
    sheet.add(
        listOf(x - 8, 360.0, x + 8, 362.0),
        color = "#f4f0e4",
        width = 44.0,
        pressure = 1.0 to 1.0,
        group = "eyes",
    )
    sheet.add(listOf(x, 364.0, x + 8, 365.0), color = "#1a1a1a", width = 16.0, pressure = 1.0 to 1.0, group = "eyes")
}
sheet.add(arc(500.0, 420.0, 70.0, 45.0, 0.4, PI - 0.4), color = "#3a1a10", width = 10.0, group = "mouth")
sheet.add(
    listOf(400.0, 440.0, 410.0, 445.0),
    color = "#e07080",
    brush = BrushKind.Badger,
    width = 50.0,
    hiding = 0.6,
    group = "smudge",
)

// the records need no paint library; painting them does
if (PaintCanvas.isAvailable) display("main", sheet.png()) else markdown(PaintLibrary.describe())
println(sheet.describe())
```

## 2. 4 - Swayed, one key per frame

<!-- field-box id="paint_strokes_sway" index="1" -->

```edn [field-box id="paint_strokes_sway" role="properties"]
{"id" "paint_strokes_sway" "index" 1 "name" "4 - Swayed, one key per frame" "parents" ["root" "paint_strokes_creature"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "paint_strokes_creature"} "name" "4 - Swayed, one key per frame" "_tombstone" false "frame" #afield/rect [1500.0 780.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_strokes_sway_code_.kt" "lines" 19 "characters" 667 "sha256" "ec6f9bd1dbcb613b596977d93b95cfee5502c3de68bc9231c53baab7953d0058"}]}
```

```kotlin [field-property box="paint_strokes_sway" name="code" path="paint_strokes_sway_code_.kt"]
run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }
    val sheet = fromContext<PaintStrokes>()
    // one key per frame: how far the leaves sway and the legs step; every frame is the same records, moved
    val keys = listOf(0.0, 30.0, 0.0, -30.0)
    val frames = keys.map { k ->
        val f = sheet.copy()
        f.apply(
            """[{"op": "move", "at": "@leaves", "dx": $k},
                    {"op": "bend", "at": "@mouth", "dy": ${abs(k) / 2}},
                    {"op": "move", "at": "@legs", "dy": ${-abs(k) / 3}}]""",
        )
        f.render()
    }
    display("main", pngRow(frames))
}
```

## 3. 2 - Edited by address

<!-- field-box id="paint_strokes_edit" index="2" -->

```edn [field-box id="paint_strokes_edit" role="properties"]
{"id" "paint_strokes_edit" "index" 2 "name" "2 - Edited by address" "parents" ["root" "paint_strokes_creature"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "paint_strokes_creature"} "name" "2 - Edited by address" "_tombstone" false "frame" #afield/rect [80.0 780.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_strokes_edit_code_.kt" "lines" 33 "characters" 1209 "sha256" "62e940abf75c9e2925edf0c92e4ba82f294ca215b365c35a757dedcd1b66ab8b"}]}
```

```kotlin [field-property box="paint_strokes_edit" name="code" path="paint_strokes_edit_code_.kt"]
run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }
    val sheet = fromContext<PaintStrokes>()
    val edited = sheet.copy()
    edited.apply(
        """[
      {"op": "recolor", "at": "@leaves", "color": "#c0602a"},
      {"op": "bend", "at": "@mouth", "dy": -45},
      {"op": "move", "at": "@smudge", "dx": 200}
    ]""",
    )
    val before = sheet.render()
    val after = edited.render()
    // how much of the picture changed, and how much of that lies outside the edited strokes
    val a = before.render()
    val b = after.render()
    val w = before.widthPx
    val touched =
        edited.select("@leaves, @mouth, @smudge").flatMap { listOf(sheet[it]!!.bounds(), edited[it]!!.bounds()) }
    fun inside(i: Int) = touched.any { r ->
        val x = (i % w) * 1000.0 / w
        val y = (i / w) * 1000.0 / w
        x in r[0] - 20..r[2] + 20 && y in r[1] - 20..r[3] + 20
    }
    val changed = a.indices.filter { a[it] != b[it] }
    println(
        "${changed.size} of ${a.size} pixels changed; ${changed.count { !inside(it) }} more than 20 units outside the edited strokes",
    )
    display("main", pngRow(listOf(before, after)))
}
```

## 4. 3 - A pinned note, for the AI

<!-- field-box id="paint_strokes_note" index="3" -->

```edn [field-box id="paint_strokes_note" role="properties"]
{"id" "paint_strokes_note" "index" 3 "name" "3 - A pinned note, for the AI" "parents" ["root" "paint_strokes_creature"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "paint_strokes_creature"} "name" "3 - A pinned note, for the AI" "_tombstone" false "frame" #afield/rect [1500.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "paint_strokes_note_code_.kt" "lines" 23 "characters" 857 "sha256" "99b40640cae56e38e66d4431bafa209ceb5f8ceb4090b759780b7614cf771872"}]}
```

```kotlin [field-property box="paint_strokes_note" name="code" path="paint_strokes_note_code_.kt"]
run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }
    val original = fromContext<PaintStrokes>()
    // the sheet the AI edits is kept on the box, so re-running the box keeps its edits
    val sheet = thisBox.properties.getOrPut("strokes.sheet") {
        original.copy().also { it.pin(660.0, 430.0, "more moss on this side") }
    } as PaintStrokes

    fun show() {
        display("main", pngRow([original.render(), sheet.render()]))
        markdown(
            "status",
            "${sheet.strokes.size} strokes, ${sheet.notes.size} notes " + sheet.notes.joinToString { "\"${it.text}\"" },
        )
    }

    // the strokes_* tools: open this box's AI panel and ask it to act on the note
    agentExtension("strokes") { strokeTools(sheet = { sheet }, changed = { show() }) }
    show()
}
```

