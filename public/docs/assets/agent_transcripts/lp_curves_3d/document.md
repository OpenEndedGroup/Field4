# Field Document: lp_curves_3d

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. a fan of central paths, turning

<!-- field-box id="lp3_fan" index="0" -->

```edn [field-box id="lp3_fan" role="properties"]
{"id" "lp3_fan" "index" 0 "name" "a fan of central paths, turning" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a fan of central paths, turning" "_tombstone" false "frame" #afield/rect [80.0 640.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp3_fan_code_.kt" "lines" 17 "characters" 692 "sha256" "c77b0dd2996214b48dc8a1b06610ab9b437e9e600544eb82f934670f35f76dea"}]}
```

```kotlin [field-property box="lp3_fan" name="code" path="lp3_fan_code_.kt"]
val random = kotlin.random.Random(11)
val cloud = List(70) {
    vec(random.nextDouble(-1.6, 1.6), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
}
val polytope = LpPolytope.hull(cloud)
val directions = 120
val fan = polytope.centralFan(directions = directions, samples = 80)

three(name = "fan", width = 700, height = 470) {
    mesh(polytope.toMesh(), wireframe = false, opacity = 0.12, cullBackFaces = false)
    lines(polytope.toFLine())
    lines(fan.toFLines(alpha = 0.85, thickness = 1.2))
    camera(yaw = 32.0, pitch = 24.0)
    title("${fan.size} central paths over a ${polytope.constraintCount}-face hull")
    turntable(degreesPerSecond = 20.0)
}
"${fan.size} paths"
```

## 2. every route to one vertex, in 3-D

<!-- field-box id="lp3_routes" index="1" -->

```edn [field-box id="lp3_routes" role="properties"]
{"id" "lp3_routes" "index" 1 "name" "every route to one vertex, in 3-D" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "every route to one vertex, in 3-D" "_tombstone" false "frame" #afield/rect [80.0 80.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp3_routes_code_.kt" "lines" 29 "characters" 1289 "sha256" "4f7c26ee93f921d2311d185e838220b5ac2fa550755579761292b71c544f1641"}]}
```

```kotlin [field-property box="lp3_routes" name="code" path="lp3_routes_code_.kt"]
val random = kotlin.random.Random(11)
val cloud = List(70) {
    vec(random.nextDouble(-1.6, 1.6), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
}
val polytope = LpPolytope.hull(cloud)
val c = vec(1.0, 0.5, 0.3)
val far = polytope.vertices.indexOf(polytope.optimalVertex(vec(-1.0, -0.5, -0.3)))

val central = polytope.centralPath(c)
val pd = polytope.primalDual(c)
val walk = polytope.simplexWalk(c, startVertex = far)
val spiral = polytope.pdhg(c, iterations = 800, tau = 0.25, sigma = 0.25)

three(name = "routes", width = 760, height = 470) {
    mesh(polytope.toMesh(), wireframe = false, opacity = 0.1, cullBackFaces = false)
    lines(polytope.toFLine(vec(0.62, 0.70, 0.80, 0.5)))
    lines(
        central.toFLine(vec(1.0, 0.72, 0.32, 1.0), 2.4),
        pd.toFLine(vec(0.55, 0.9, 0.6, 1.0), 1.6),
        walk.toFLine(vec(0.6, 0.75, 1.0, 1.0), 3.0),
        spiral.trajectory.toFLine(vec(1.0, 0.55, 0.85, 0.8), 1.0),
    )
    camera(yaw = 20.0, pitch = 20.0)
    title("${polytope.vertices.size} hull vertices, ${polytope.constraintCount} faces")
    caption(
        "orange: central path · green: primal-dual, ${pd.samples.size - 1} steps · blue: simplex, ${walk.samples.size - 1} pivots · pink: PDHG",
    )
}
"every route ends at ${polytope.optimalVertex(c)}"
```

