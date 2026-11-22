# Field Document: braitenberg_weight_cube

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - Twelve learners, five find the lover basin

<!-- field-box id="cube_many" index="0" -->

```edn [field-box id="cube_many" role="properties"]
{"id" "cube_many" "index" 0 "name" "2 - Twelve learners, five find the lover basin" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Twelve learners, five find the lover basin" "_tombstone" false "frame" #afield/rect [720.0 60.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cube_many_code_.kt" "lines" 26 "characters" 1047 "sha256" "24b3b992a9db43cdebea405a8b2e75fb1c72db454d943b11d483fcb1c63a03a0"}]}
```

```kotlin [field-property box="cube_many" name="code" path="cube_many_code_.kt"]
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val view = CubeView(size = 560.0)
val starts = kotlin.random.Random(11)

// Twelve learners, each starting at a random wiring and each with its own trials. They are
// independent, so they train in parallel.
val learners =
    (1..12).map { k ->
        val start = CubeWiring(
            starts.nextDouble(-2.5, 2.5),
            starts.nextDouble(-2.5, 2.5),
            starts.nextDouble(-2.5, 2.5),
        )
        WeightCubeLearner(lights, CubeReward.SEEK, start = start, seed = k.toLong())
    }
learners.parallelStream().forEach { it.train(2000) }
val drawn = learners.flatMap { l ->
    val end = l.measure(l.wiring, 99999)
    println("" + l.path[0] + " -> " + l.wiring + ", light " + end.light.f2)
    [
        view.path(l.path, vec(0.45, 0.75, 1.0, 0.55), 1.2),
        view.dot(l.path[0], vec(1.0, 1.0, 1.0, 0.6), 3.0),
        view.dot(l.wiring, vec(1.0, 0.6, 0.2, 1.0), 4.5),
    ]
}
lines(view.frame() + drawn, w = 560.0, h = 560.0)
```

## 2. 1 - One learner's path through the weight cube

<!-- field-box id="cube_path" index="1" -->

```edn [field-box id="cube_path" role="properties"]
{"id" "cube_path" "index" 1 "name" "1 - One learner's path through the weight cube" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - One learner's path through the weight cube" "_tombstone" false "frame" #afield/rect [60.0 60.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cube_path_code_.kt" "lines" 20 "characters" 1047 "sha256" "5ac28f5165c29546a4d06e844f7cd804a3246ccadab1c21b7d38eaaef1f7cd3c"}]}
```

```kotlin [field-property box="cube_path" name="code" path="cube_path_code_.kt"]
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val learner = WeightCubeLearner(lights, CubeReward.SEEK, seed = 2L).train(2000)
val view = CubeView(size = 560.0)

// Colour the path by time, blue for the first tries and orange for the last, and drop a faint
// shadow of it onto the floor of the cube so its depth reads.
val path = learner.path
val shadow = view.path(path.map { it.copy(drive = -3.0) }, vec(1.0, 1.0, 1.0, 0.18), 1.0)
val segments =
    (0 until path.size - 1 step 10).map { i ->
        val t = sqrt(i / 2000.0)
        view.path(path.subList(i, min(i + 11, path.size)), vec(0.3 + 0.7 * t, 0.6, 1.0 - 0.7 * t, 0.9))
    }
val marks =
    [0, 50, 250, 500, 2000].flatMap { n ->
        val w = path[n]
        println(n.toString() + " tries: " + w + ", light " + learner.measure(w, 99999).light.f2)
        [view.dot(w, vec(1.0, 1.0, 1.0, 1.0))] + view.label(n.toString(), w, vec(1.0, 1.0, 1.0, 0.9), 18.0)
    }
lines(view.frame() + shadow + segments + marks, w = 560.0, h = 560.0)
```

## 3. 3 - Four rewards, four basins

<!-- field-box id="cube_rewards" index="2" -->

```edn [field-box id="cube_rewards" role="properties"]
{"id" "cube_rewards" "index" 2 "name" "3 - Four rewards, four basins" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - Four rewards, four basins" "_tombstone" false "frame" #afield/rect [1380.0 60.0 600.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cube_rewards_code_.kt" "lines" 27 "characters" 1392 "sha256" "45b2f4c1d531b218303fc515b4236da90970a92ab4d11b08d9307a6f6aa2dc69"}]}
```

```kotlin [field-property box="cube_rewards" name="code" path="cube_rewards_code_.kt"]
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val view = CubeView(size = 560.0)
val colours = [
    CubeReward.SEEK to vec(1.0, 0.75, 0.25, 1.0),
    CubeReward.FLEE to vec(0.4, 0.75, 1.0, 1.0),
    CubeReward.FREEZE to vec(0.75, 0.75, 0.8, 1.0),
    CubeReward.CHARGE to vec(1.0, 0.35, 0.35, 1.0),
]

// Three learners per reward, all from the centre of the cube, trained in parallel.
val learners = colours.associate { (reward, _) ->
    reward to (1..3).map { k -> WeightCubeLearner(lights, reward, seed = 100L + k) }
}
learners.values.flatten().parallelStream().forEach { it.train(2000) }
val drawn = colours.flatMap { (reward, colour) ->
    val group = learners[reward]!!
    val ends = group.map { it.wiring }
    val mean =
        CubeWiring(ends.map { it.same }.average(), ends.map { it.cross }.average(), ends.map { it.drive }.average())
    val trial = group[0].measure(mean, 99999)
    println(reward.toString() + ": mean end " + mean + ", light " + trial.light.f2 + ", speed " + trial.speed.f2)
    group.flatMap { l ->
        [view.path(l.path, vec(colour.x, colour.y, colour.z, 0.45), 1.0), view.dot(l.wiring, colour, 4.0)]
    } + view.label(reward.name.lowercase(), mean, colour, 18.0)
}
val start = view.dot(CubeWiring(0.0, 0.0, 0.0), vec(1.0, 1.0, 1.0, 1.0), 4.0)
lines(view.frame() + drawn + start, w = 560.0, h = 560.0)
```

