# Field Document: shared_types_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 4 - Receive it, still typed

<!-- field-box id="shared_types_receive" index="0" -->

### Narrative

From `code`

_particle arrives from box 3. These lines only COMPILE because the type crossed
the box boundary: a box-declared class lands here as Any?, and .x does not
resolve on Any?.

```edn [field-box id="shared_types_receive" role="properties"]
{"id" "shared_types_receive" "index" 0 "name" "4 - Receive it, still typed" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - Receive it, still typed" "_tombstone" false "frame" #afield/rect [480.0 190.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shared_types_receive_code_.kt" "lines" 8 "characters" 306 "sha256" "6e5c9eb136bc383a03943be48dc2c46c36aa12424193189d859f8b3ab29a4111"}]}
```

```kotlin [field-property box="shared_types_receive" name="code" path="shared_types_receive_code_.kt"]
/*
_particle arrives from box 3. These lines only COMPILE because the type crossed
the box boundary: a box-declared class lands here as Any?, and .x does not
resolve on Any?.
*/
println("received: " + _particle)
println("x + y = " + (_particle.x + _particle.y))
println("momentum = " + _particle.momentum)
```

## 2. 2 - Use it, with full fidelity

<!-- field-box id="shared_types_use" index="1" -->

### Narrative

From `code`

Named arguments, copy, destructuring and the data-class toString all work,
because shared.Particle is a real compiled class with real metadata -- not a
type name re-spelled as text.

```edn [field-box id="shared_types_use" role="properties"]
{"id" "shared_types_use" "index" 1 "name" "2 - Use it, with full fidelity" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Use it, with full fidelity" "_tombstone" false "frame" #afield/rect [480.0 40.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shared_types_use_code_.kt" "lines" 11 "characters" 395 "sha256" "933e2966a3d1bc618ae34e1b47390b7a0c332d98d61f0e3b27807f8e262b1326"}]}
```

```kotlin [field-property box="shared_types_use" name="code" path="shared_types_use_code_.kt"]
/*
Named arguments, copy, destructuring and the data-class toString all work,
because shared.Particle is a real compiled class with real metadata -- not a
type name re-spelled as text.
*/
val p = shared.Particle(x = 3.0, y = 4.0, mass = 2.0)
println("toString: " + p)
println("copy: " + p.copy(mass = 10.0))
val (x, y, m) = p
println("destructured: $x $y $m")
println("momentum: " + p.momentum)
```

## 3. 1 - Declare shared types

<!-- field-box id="shared_types_declare" index="2" -->

### Narrative

From `code`

One publish, and every box in this document can name these types. They are
compiled as ordinary Kotlin into a directory on the shared classloader, NOT
declared in this box's snippet -- which is why they escape this box at all.

```edn [field-box id="shared_types_declare" role="properties"]
{"id" "shared_types_declare" "index" 2 "name" "1 - Declare shared types" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Declare shared types" "_tombstone" false "frame" #afield/rect [40.0 40.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shared_types_declare_code_.kt" "lines" 16 "characters" 437 "sha256" "cbdf548c8323d9f349c9762c60fdf3854c7f41281968137711fad8c00d8194ab"}]}
```

```kotlin [field-property box="shared_types_declare" name="code" path="shared_types_declare_code_.kt"]
/*
One publish, and every box in this document can name these types. They are
compiled as ordinary Kotlin into a directory on the shared classloader, NOT
declared in this box's snippet -- which is why they escape this box at all.
*/
println(
    sharedTypes(
        """
package shared

data class Particle(val x: Double, val y: Double, val mass: Double) {
    val momentum: Double get() = mass * kotlin.math.hypot(x, y)
}
""",
    ),
)
```

## 4. 3 - Export a value

<!-- field-box id="shared_types_export" index="3" -->

### Narrative

From `code`

@rootExported publishes the value to the document root, so any box can reach
it. Before shared types the *value* crossed but its type did not.

```edn [field-box id="shared_types_export" role="properties"]
{"id" "shared_types_export" "index" 3 "name" "3 - Export a value" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - Export a value" "_tombstone" false "frame" #afield/rect [40.0 190.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "shared_types_export_code_.kt" "lines" 6 "characters" 258 "sha256" "9a22fdffaa22d6ac95a206bcb1b2f033fde02ff86a98a2e25bd3e121744947de"}]}
```

```kotlin [field-property box="shared_types_export" name="code" path="shared_types_export_code_.kt"]
// @rootExported publishes the value to the document root, so any box can reach
// it. Before shared types the *value* crossed but its type did not.
@rootExported
val particle = shared.Particle(x = 1.5, y = 2.5, mass = 4.0)

println("exported: " + particle)
```

