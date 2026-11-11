# Field Document: alife_tree_growth

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Trees that compete for light

<!-- field-box id="tree_growth" index="0" -->

### Narrative

From `code`

One tree, 30 seasons. Each step() is one season: shadow, light, Borchert-Honda allocation,
growth and shedding.

```edn [field-box id="tree_growth" role="properties"]
{"id" "tree_growth" "index" 0 "name" "Trees that compete for light" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Trees that compete for light" "_tombstone" false "frame" #afield/rect [60.0 60.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tree_growth_code_.kt" "lines" 56 "characters" 1905 "sha256" "9e05ecae88d97ba0e6f16daa4b61d0e6e27b16c1410844cfccb01bf7c2bc5002"}]}
```

```kotlin [field-property box="tree_growth" name="code" path="tree_growth_code_.kt"]
// One tree, 30 seasons. Each step() is one season: shadow, light, Borchert-Honda allocation,
// growth and shedding.
val tree = TreeGrowth(seed = 4L)
val seasons = [2, 5, 10, 20, 30]
val strip = ArrayList<Pair<String, Img>>()
var views: List<Pair<String, Img>> = []
for (s in 1..30) {
    tree.step()
    if (s in seasons)
        strip.add("season " + s + ": " + tree.nodeCount + " metamers" to tree.render(TreeView.STRUCTURE, 3))
    if (s == 20) {
        views = [
            "season 20: bud light" to tree.render(TreeView.LIGHT, 3),
            "resource flow (log)" to tree.render(TreeView.RESOURCE, 3),
            "shadow grid" to tree.render(TreeView.SHADOW, 3),
        ]
    }
}
println(tree)
println("a tree (one root, n-1 edges, acyclic): " + tree.isTree())

// Borchert-Honda only ever splits, so what reaches the buds is what the root was given.
println(
    "worst resource conservation error over 30 seasons: " + "%.1e".format(tree.history.maxOf { it.conservationError }),
)
val last = tree.history.last()
println(
    "season 30: " +
        last.buds +
        " buds read " +
        last.baseLight.f6 +
        " units of light; root gets " +
        last.baseResource.f6,
)

// Apical control: the same seed, four values of lambda.
val lambdas = [0.44, 0.5, 0.56, 0.62]
val crowns = lambdas.map { lam ->
    val t = TreeGrowth(params = TreeGrowthParams(lambda = lam), seed = 4L).stepTimes(30)
    val xs = (0 until t.nodeCount).map { t.x(it) }
    val ys = (0 until t.nodeCount).map { t.y(it) }
    println(
        "λ = " +
            lam +
            ": " +
            t.nodeCount +
            " metamers, crown " +
            (xs.max() - xs.min()).f0 +
            " wide, " +
            (ys.max() - ys.min()).f0 +
            " tall",
    )
    "lambda = " + lam to t.render(TreeView.STRUCTURE, 3)
}

showImg(montage(strip + views + crowns, columns = 5, cellSide = 300))
```

## 2. Apical control on a slider

<!-- field-box id="tree_growth_lambda" index="1" -->

### Narrative

From `code`

Apical control, live: drag the slider and the same seed regrows for 30 seasons under that lambda.
0.5 sends growth resource where the light is; above it the leader wins, below it the side branches.

```edn [field-box id="tree_growth_lambda" role="properties"]
{"id" "tree_growth_lambda" "index" 1 "name" "Apical control on a slider" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Apical control on a slider" "_tombstone" false "frame" #afield/rect [460.0 60.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tree_growth_lambda_code_.kt" "lines" 18 "characters" 753 "sha256" "68e6c497013c1e616600e39a92dce07c18fd17e58c37fa45d38e7f337da3fdac"}]}
```

```kotlin [field-property box="tree_growth_lambda" name="code" path="tree_growth_lambda_code_.kt"]
// Apical control, live: drag the slider and the same seed regrows for 30 seasons under that lambda.
// 0.5 sends growth resource where the light is; above it the leader wins, below it the side branches.
fun regrow(lam: Double) {
    val t = TreeGrowth(params = TreeGrowthParams(lambda = lam), seed = 4L).stepTimes(30)
    showImg(
        montage(
            [
                "lambda = " + lam.f2 + ": " + t.nodeCount + " metamers" to t.render(TreeView.STRUCTURE, 4),
                "bud light" to t.render(TreeView.LIGHT, 4),
            ],
            columns = 2,
            cellSide = 480,
        ),
    )
}
slider("apical control λ", min = 0.40, max = 0.66, step = 0.01, initialValue = 0.5) { regrow(it) }
displayOpen("controls")
regrow(0.5)
```

## 3. Thirty seasons, growing — launch me

<!-- field-box id="tree_growth_moving" index="2" -->

### Narrative

From `code`

Thirty seasons over seven seconds. TreeGrowth is mutable and step() returns it, so it is the
`step` here directly. render() frames the whole grid, so the tree grows inside a fixed frame.

```edn [field-box id="tree_growth_moving" role="properties"]
{"id" "tree_growth_moving" "index" 2 "name" "Thirty seasons, growing — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Thirty seasons, growing — launch me" "_tombstone" false "frame" #afield/rect [60.0 200.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tree_growth_moving_code_.kt" "lines" 16 "characters" 580 "sha256" "0a03158e750334b1127c84fbc5486c817f5d1a5e31ef8e734f5de5ceaf3de4a3"}]}
```

```kotlin [field-property box="tree_growth_moving" name="code" path="tree_growth_moving_code_.kt"]
// Thirty seasons over seven seconds. TreeGrowth is mutable and step() returns it, so it is the
// `step` here directly. render() frames the whole grid, so the tree grows inside a fixed frame.
animateEvolution(
    TreeGrowth(seed = 4L),
    totalSteps = 30,
    seconds = 7.0,
    columns = 2,
    cellSide = 450,
    step = { it.step() },
    panels = { t, n ->
        [
            "season " + n + ": " + t.nodeCount + " metamers" to t.render(TreeView.STRUCTURE, 4),
            "bud light, " + t.shedMetamersTotal + " shed" to t.render(TreeView.LIGHT, 4),
        ]
    },
)
```

