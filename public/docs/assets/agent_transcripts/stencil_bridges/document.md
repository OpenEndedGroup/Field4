# Field Document: stencil_bridges

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. a word, cut out of a plate

<!-- field-box id="sb_cut" index="0" -->

```edn [field-box id="sb_cut" role="properties"]
{"id" "sb_cut" "index" 0 "name" "a word, cut out of a plate" "parents" ["root"] "children" ["sb_judge"] "properties" {"children" #{#afield/box "sb_judge"} "parents" #{#afield/box "root"} "name" "a word, cut out of a plate" "_tombstone" false "frame" #afield/rect [40.0 40.0 260.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sb_cut_code_.kt" "lines" 44 "characters" 1559 "sha256" "abd00454e6748670e989fadc43265fbfebd568f157471a2a0c68615e902dc254"}]}
```

```kotlin [field-property box="sb_cut" name="code" path="sb_cut_code_.kt"]
import afield.geometry.solid.manifold.JoinType
import afield.geometry.solid.manifold.ManifoldLibrary
import afield.geometry.solid.manifold.Section

prop<TextLayout>(stencilWord)
prop<Section>(stencilBlank)
prop<List<List<Any>>>(stencilPieces)
prop<List<List<Any>>>(stencilGlyphs)

stencilWord = layoutText("BRIDGE", typeface("SansSerif", 120.0))
stencilPieces = []
stencilGlyphs = stencilWord.glyphs.mapIndexed { g, p ->
    [g, p.text, p.origin.x.toDouble(), p.origin.x.toDouble() + p.glyph.advance]
}

run {
    if (!ManifoldLibrary.isAvailable) {
        println(ManifoldLibrary.describe())
        return@run
    }
    // The plate: the word's bounds, grown by a 14-unit margin with round corners.
    val (lo, hi) = Section.ofFLines(stencilWord.toFLines(), sampleDistance = 0.5).bounds
    stencilBlank = Section.square((hi.x - lo.x).toDouble(), (hi.y - lo.y).toDouble())
        .translated(lo.x.toDouble(), lo.y.toDouble())
        .offset(14.0, JoinType.ROUND)

    val letters = Section.ofFLines(stencilWord.toFLines(), sampleDistance = 0.5)
    val pieces = (stencilBlank - letters).decompose()
    stencilPieces = pieces.mapIndexed { i, s ->
        val (a, b) = s.bounds
        [i, (a.x + b.x) / 2.0, (a.y + b.y) / 2.0, s.area]
    }
    markdown(
        "cut",
        """
        ~~~
    word                        BRIDGE, ${stencilWord.glyphs.size} glyphs
    counters, from the font     ${stencilWord.holeCount}
    plate minus letters         ${pieces.size} pieces
        ~~~
    """,
    )
}
println("cut: ${stencilPieces.size} pieces")
```

## 2. which letters fall out, by Datalog

<!-- field-box id="sb_judge" index="1" -->

```edn [field-box id="sb_judge" role="properties"]
{"id" "sb_judge" "index" 1 "name" "which letters fall out, by Datalog" "parents" ["root" "sb_cut"] "children" ["sb_bridge"] "properties" {"children" #{#afield/box "sb_bridge"} "parents" #{#afield/box "root" #afield/box "sb_cut"} "name" "which letters fall out, by Datalog" "_tombstone" false "frame" #afield/rect [1240.0 40.0 260.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sb_judge_code_.kt" "lines" 37 "characters" 1167 "sha256" "65b91dacbd2dbff156f883564b87f8a8014e8d1b43dd50d03e25298dc5e16c12"}]}
```

```kotlin [field-property box="sb_judge" name="code" path="sb_judge_code_.kt"]
import afield.data.query.datalog.Datalog

prop<List<Int>>(needsBridge)

val d = Datalog()
d.facts("piece", stencilPieces)
d.facts("glyph", stencilGlyphs)
d.add(
    """
    #datalog
    % the plate is the piece with the largest area; every other piece is an island
    biggest(M) :- M = max A : { piece(_, _, _, A) }.
    plate(P) :- piece(P, _, _, A), biggest(A).
    island(P, X) :- piece(P, X, _, _), !plate(P).

    % an island belongs to the glyph whose advance contains its centre
    falls(G, P) :- island(P, X), glyph(G, _, X0, X1), X > X0, X < X1.
    lost(G, C, N) :- glyph(G, C, _, _), N = count : { falls(G, P) }, N > 0.
    needs(G) :- falls(G, _).
    safe(C) :- glyph(G, C, _, _), !needs(G).
""",
)

val lost = d.query("lost(G, C, N)").rows.sortedBy { it[0] as Long }
needsBridge = lost.map { (it[0] as Long).toInt() }
val safe = d.query("safe(C)").column("C")
markdown(
    "judged",
    """
    ~~~
islands               ${d.query("island(P, X)").size}
letters that lose one ${lost.joinToString("  ") { "${it[1]}:${it[2]}" }}
letters that are safe ${safe.joinToString("  ")}
    ~~~
""",
)
println("judged: ${needsBridge.size} letters need bridges")
```

## 3. bridged, and counted again

<!-- field-box id="sb_bridge" index="2" -->

```edn [field-box id="sb_bridge" role="properties"]
{"id" "sb_bridge" "index" 2 "name" "bridged, and counted again" "parents" ["root" "sb_judge"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sb_judge"} "name" "bridged, and counted again" "_tombstone" false "frame" #afield/rect [2440.0 40.0 260.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sb_bridge_code_.kt" "lines" 46 "characters" 1772 "sha256" "0c7c17b4e7a7c8305c3443f70cf0c02f8701fbe1a01d8e91b287b2b639cd9ee3"}]}
```

```kotlin [field-property box="sb_bridge" name="code" path="sb_bridge_code_.kt"]
import afield.geometry.solid.manifold.ManifoldLibrary
import afield.geometry.solid.manifold.Section

run {
    if (!ManifoldLibrary.isAvailable) {
        println(ManifoldLibrary.describe())
        return@run
    }
    val letters = Section.ofFLines(stencilWord.toFLines(), sampleDistance = 0.5)
    val (lo, hi) = letters.bounds

    // One bar, 5 units wide, down the middle of each letter Datalog listed.
    val bars = Section.union(needsBridge.map { g ->
        val p = stencilWord.glyphs[g]
        val mid = p.origin.x + p.glyph.advance / 2.0
        Section.square(5.0, (hi.y - lo.y).toDouble() + 2.0)
            .translated(mid - 2.5, lo.y.toDouble() - 1.0)
    })
    val before = (stencilBlank - letters).decompose()
    val after = (stencilBlank - (letters - bars)).decompose()

    // Plate in grey, islands in red; the bridged plate below it.
    fun draw(pieces: List<Section>, dy: Double) = pieces.mapIndexed { i, s ->
        val plate = s.area == pieces.maxOf { it.area }
        s.toFLine().transform { it + vec(0.0, dy, 0.0) }.also {
            it.filled = true
            it.stroked = false
            it.color = if (plate) vec(0.62, 0.64, 0.70, 1.0) else vec(0.95, 0.30, 0.30, 1.0)
        }
    }
    val gap = (hi.y - lo.y) + 60.0
    lines(draw(before, 0.0) + draw(after, gap), w = 900.0, h = 520.0, fitToSize = true)
    markdown(
        "bridged",
        """
        ~~~
    bars cut              ${needsBridge.size}, one per listed letter
    pieces before         ${before.size}
    pieces after          ${after.size}
    plate area before     ${before.maxOf { it.area }.f2}
    plate area after      ${after.maxOf { it.area }.f2}
        ~~~
    """,
    )
    println("bridged: ${before.size} pieces before, ${after.size} after")
}
```

