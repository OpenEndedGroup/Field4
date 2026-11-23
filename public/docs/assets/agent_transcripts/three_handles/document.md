# Field Document: three_handles

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - A curve you shape

<!-- field-box id="curve" index="0" -->

### Narrative

From `code`

Drag the four yellow points. The curve, its control polygon and the length at its end
follow every move, because each drag calls draw() again with the point where it was put.

```edn [field-box id="curve" role="properties"]
{"id" "curve" "index" 0 "name" "1 - A curve you shape" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - A curve you shape" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "curve_code_.kt" "lines" 31 "characters" 1017 "sha256" "973eadc37eb3232864971d7b670d9d06cee914eb30a4fc58be7773a4e424856a"}]}
```

```kotlin [field-property box="curve" name="code" path="curve_code_.kt"]
/*
Drag the four yellow points. The curve, its control polygon and the length at its end
follow every move, because each drag calls draw() again with the point where it was put.
*/
val points = mutableListOf(vec(-60, 0, 0), vec(-30, 60, 30), vec(30, -50, -30), vec(60, 20, 0))

fun draw() {
    three(name = "curve", height = 480) {
        val curve = fline {
            moveTo(points[0])
            cubicTo(points[1], points[2], points[3])
        }
        val polygon = fline {
            moveTo(points[0])
            lineTo(points[1])
            lineTo(points[2])
            lineTo(points[3])
            color = vec(1.0, 1.0, 1.0, 0.3)
        }
        lines([curve, polygon])
        val end = points[3]
        label(vec(end.x, end.y + 14, end.z), "%.1f long".format(curve.length()), style = "tag")
        points.forEachIndexed { i, p ->
            handle("p$i", at = p, label = "p$i") { drag ->
                points[i] = drag.position
                draw()
            }
        }
    }
}
draw()
```

## 2. 2 - A slab and a post

<!-- field-box id="slab" index="1" -->

### Narrative

From `code`

The blue handle runs on a vertical rail (axis = y): it sets the slab's height. The orange
one slides only across the slab's top (normal = y), carrying the post with it. Field holds
each handle to its line or plane before the callback sees the position.

```edn [field-box id="slab" role="properties"]
{"id" "slab" "index" 1 "name" "2 - A slab and a post" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - A slab and a post" "_tombstone" false "frame" #afield/rect [60.0 720.0 420.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "slab_code_.kt" "lines" 44 "characters" 1368 "sha256" "54c223bd8c631deff5ed180d4cf1ff1a81d2f01b016a203d63b9f6691f48dd70"}]}
```

```kotlin [field-property box="slab" name="code" path="slab_code_.kt"]
/*
The blue handle runs on a vertical rail (axis = y): it sets the slab's height. The orange
one slides only across the slab's top (normal = y), carrying the post with it. Field holds
each handle to its line or plane before the callback sees the position.
*/
var height = 20.0
var post = vec(20, 0, 10)

fun draw() {
    three(name = "slab", height = 480) {
        mesh(
            boxMesh(120, height, 60).translated(0.0, height / 2, 0.0),
            id = "slab",
            wireframe = false,
            material = ThreeSurfaceMaterial(color = floatArrayOf(0.85f, 0.55f, 0.3f)),
        )
        mesh(
            boxMesh(6, 40, 6).translated(post.x.toDouble(), height + 20, post.z.toDouble()),
            id = "post",
            wireframe = false,
        )
        handle(
            "height",
            at = vec(-60.0, height, 30.0),
            label = "height %.0f".format(height),
            axis = vec(0, 1, 0),
            color = "#4fc3f7",
        ) { drag ->
            height = maxOf(2.0, drag.position.y.toDouble())
            draw()
        }
        handle(
            "post",
            at = vec(post.x.toDouble(), height, post.z.toDouble()),
            label = "post",
            normal = vec(0, 1, 0),
            color = "#ff8a65",
        ) { drag ->
            post = drag.position
            draw()
        }
    }
}
draw()
```

