# Field Document: flow_islands

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. two moons, and a generator

<!-- field-box id="fi_cloud" index="0" -->

```edn [field-box id="fi_cloud" role="properties"]
{"id" "fi_cloud" "index" 0 "name" "two moons, and a generator" "parents" ["root"] "children" ["fi_islands"] "properties" {"children" #{#afield/box "fi_islands"} "parents" #{#afield/box "root"} "name" "two moons, and a generator" "_tombstone" false "frame" #afield/rect [40.0 40.0 260.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fi_cloud_code_.kt" "lines" 17 "characters" 869 "sha256" "fe8e4370097d09c6ca68900a9caa8a446a31416262f0081c3a28843a1fc093d0"}]}
```

```kotlin [field-property box="fi_cloud" name="code" path="fi_cloud_code_.kt"]
prop<List<Vec3>>(moonTarget)
prop<List<Vec3>>(moonSamples)

// Two half-moons that interlock without touching: a target in two separate pieces.
val rnd = java.util.Random(3)
fun moon(i: Int, upper: Boolean): Vec3 {
    val a = PI * i / 99.0
    val jitter = vec(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, 0.0) * 14.0
    return if (upper) vec(100 * cos(a), 100 * sin(a), 0.0) + jitter
    else vec(100 - 100 * cos(a), 40 - 100 * sin(a), 0.0) + jitter
}
moonTarget = [true, false].flatMap { upper -> (0 until 100).map { moon(it, upper) } }

// From a Gaussian to the moons, then 200 points that were never in the training data.
val field = flowMatch(moonTarget, options = FlowMatchOptions(steps = 6000, seed = 11))
moonSamples = field.sample(200, seed = 12)
println("${moonSamples.size} generated, chamfer to target ${chamferDistance(moonSamples, moonTarget).f2}")
```

## 2. islands, by Datalog

<!-- field-box id="fi_islands" index="1" -->

```edn [field-box id="fi_islands" role="properties"]
{"id" "fi_islands" "index" 1 "name" "islands, by Datalog" "parents" ["root" "fi_cloud"] "children" ["fi_draw"] "properties" {"children" #{#afield/box "fi_draw"} "parents" #{#afield/box "root" #afield/box "fi_cloud"} "name" "islands, by Datalog" "_tombstone" false "frame" #afield/rect [40.0 120.0 260.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fi_islands_code_.kt" "lines" 44 "characters" 1570 "sha256" "91e187d449b9a7d7d4a31ea43f45520831c1eebe3f0b86a58b1c65555a57b177"}]}
```

```kotlin [field-property box="fi_islands" name="code" path="fi_islands_code_.kt"]
import afield.data.query.datalog.Datalog

prop<Datalog>(islandModel)

val rules =
    """
    #datalog
    % how many target points lie within 12 units of each generated one
    support(A, N) :- pt(A, X, Y), N = count : { tgt(_, U, V), hypot(X - U, Y - V) < 12 }.
    stray(A) :- support(A, 0).

    % join kept points closer than 18, and label each piece by its smallest id
    near(A, B) :- kept(A), kept(B), pt(A, X, Y), pt(B, U, V), A != B, hypot(X - U, Y - V) < 18.
    reach(A, A) :- kept(A).
    reach(A, C) :- reach(A, B), near(B, C).
    island(A, L) :- kept(A), L = min B : { reach(A, B) }.
    size(L, N) :- island(_, L), N = count : { island(B, L) }.
"""

fun model(keep: String): Datalog {
    val d = Datalog()
    d.facts("pt", moonSamples.mapIndexed { i, p -> [i, p.x.toDouble(), p.y.toDouble()] })
    d.facts("tgt", moonTarget.mapIndexed { i, p -> [i, p.x.toDouble(), p.y.toDouble()] })
    d.add(rules)
    d.add(keep)
    return d
}
fun sizes(d: Datalog) = d.query("size(L, N)").rows.map { it[1] as Long }.sortedDescending()

val everything = model("kept(A) :- pt(A, _, _).")
islandModel = model("kept(A) :- pt(A, _, _), !stray(A).")

val strays = islandModel.query("stray(A)").size
markdown(
    "islands",
    """
    ~~~
strays (no target point within 12)   $strays of ${moonSamples.size}
every point                          ${sizes(everything).size} islands, sizes ${sizes(everything)}
strays left out                      ${sizes(islandModel).size} islands, sizes ${sizes(islandModel)}
    ~~~
""",
)
println("islands counted: $strays strays")
```

## 3. drawn by island

<!-- field-box id="fi_draw" index="2" -->

```edn [field-box id="fi_draw" role="properties"]
{"id" "fi_draw" "index" 2 "name" "drawn by island" "parents" ["root" "fi_islands"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fi_islands"} "name" "drawn by island" "_tombstone" false "frame" #afield/rect [40.0 420.0 260.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fi_draw_code_.kt" "lines" 26 "characters" 951 "sha256" "356361994efea889ca280e60b723228a62a0084ef80694e830d954e0d8adac8f"}]}
```

```kotlin [field-property box="fi_draw" name="code" path="fi_draw_code_.kt"]
fun dots(points: List<Vec3>, size: Double, c: Vec4) = fline {
    points.forEach { moveTo(it) }
    pointed = true
    pointSize = size
    color = c
}

val island = islandModel.query("island(A, L)").rows.associate { (it[0] as Long).toInt() to it[1] as Long }
val labels = island.values.toSortedSet().toList()
val palette = [vec(0.44, 0.83, 0.78, 1.0), vec(0.98, 0.80, 0.35, 1.0), vec(0.6, 0.5, 0.95, 1.0)]
val strays = islandModel.query("stray(A)").column("A").map { moonSamples[(it as Long).toInt()] }

val byIsland = labels.mapIndexed { k, label ->
    dots(moonSamples.filterIndexed { i, _ -> island[i] == label }, 1.8, palette[k % palette.size])
}
lines(
    buildList {
        add(dots(moonTarget, 3.2, vec(0.45, 0.45, 0.52, 0.9)))
        addAll(byIsland)
        add(dots(strays, 2.6, vec(0.95, 0.30, 0.30, 1.0)))
    },
    w = 900.0,
    h = 440.0,
    fitToSize = true,
)
println("drawn: ${labels.size} islands and ${strays.size} strays")
```

