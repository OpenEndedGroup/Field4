# Field Document: browse_embeds

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - column(): Finder columns

<!-- field-box id="column_box" index="0" -->

### Narrative

From `code`

The same kind of value as Finder-style columns: choosing a row opens it in the next column.

```edn [field-box id="column_box" role="properties"]
{"id" "column_box" "index" 0 "name" "2 - column(): Finder columns" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - column(): Finder columns" "_tombstone" false "frame" #afield/rect [620.0 60.0 440.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "column_box_code_.kt" "lines" 9 "characters" 439 "sha256" "ed64119db421dc6b553ab059ae4fa793f15e2f6e2fd13364de00859f799b5de5"}]}
```

```kotlin [field-property box="column_box" name="code" path="column_box_code_.kt"]
// The same kind of value as Finder-style columns: choosing a row opens it in the next column.
data class Layer(val name: String, val visible: Boolean, val points: IntArray)

val document = linkedMapOf(
    "layers" to [Layer("dots", true, intArrayOf(2, 4, 8, 16)), Layer("lines", false, intArrayOf())],
    "camera" to mapOf("x" to 10, "y" to 20, "zoom" to 1.5),
    "title" to "a document\nwith two lines",
)
column("columns", document)
```

## 2. 4 - logs(): one channel, with find

<!-- field-box id="logs_box" index="1" -->

### Narrative

From `code`

logs(...) streams Field's log into a panel; this one only shows the "demo" channel.
Type in its find box (or press /) to narrow it; scroll up and new rows wait behind "↓ N new".

```edn [field-box id="logs_box" role="properties"]
{"id" "logs_box" "index" 1 "name" "4 - logs(): one channel, with find" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - logs(): one channel, with find" "_tombstone" false "frame" #afield/rect [620.0 760.0 440.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "logs_box_code_.kt" "lines" 23 "characters" 812 "sha256" "f34dc88a75f06f3173778dee67413f04b90ccbba913a8e108b7512fce8e5357e"}]}
```

```kotlin [field-property box="logs_box" name="code" path="logs_box_code_.kt"]
// logs(...) streams Field's log into a panel; this one only shows the "demo" channel.
// Type in its find box (or press /) to narrow it; scroll up and new rows wait behind "↓ N new".
logs("logs") { channel("demo") }

val log = afield.ai.diagnostics.LogDispatcher
log.log(
    afield.ai.diagnostics.LogEvent(
        "demo",
        "starting a job",
        blockId = "job (1)",
        boundary = afield.ai.diagnostics.BlockBoundary.BEGIN,
    ),
)
for (i in 1..3) log.log(afield.ai.diagnostics.LogEvent("demo", "step $i of 3", blockId = "job (1)"))
log.log(
    afield.ai.diagnostics.LogEvent(
        "demo",
        "done",
        blockId = "job (1)",
        boundary = afield.ai.diagnostics.BlockBoundary.END,
    ),
)
log.log(afield.ai.diagnostics.LogEvent("demo warning", "a warning row is coloured"))
```

## 3. 3 - a tree that follows the value

<!-- field-box id="live_box" index="2" -->

### Narrative

From `code`

Open rows follow the value. This box keeps running and adds a reading every half second;
open "readings" in the tree and watch its size and last rows change without re-running.

```edn [field-box id="live_box" role="properties"]
{"id" "live_box" "index" 2 "name" "3 - a tree that follows the value" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - a tree that follows the value" "_tombstone" false "frame" #afield/rect [60.0 760.0 440.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "live_box_code_.kt" "lines" 16 "characters" 635 "sha256" "8308af7a1a5503d8b4ea5e5409fd7a5e4361a8427a7694464dd7e6e3ff4e7c7b"}]}
```

```kotlin [field-property box="live_box" name="code" path="live_box_code_.kt"]
// Open rows follow the value. This box keeps running and adds a reading every half second;
// open "readings" in the tree and watch its size and last rows change without re-running.
val readings = mutableListOf<Double>()
val state = linkedMapOf<String, Any?>("readings" to readings, "last" to null)
tree("live", state)

frames {
    var next = System.currentTimeMillis()
    while (frame()) {
        if (System.currentTimeMillis() >= next && readings.size < 120) {
            next += 500
            readings += Math.round(Math.sin(readings.size * 0.3) * 1000) / 1000.0
            state["last"] = readings.last()
        }
    }
}
```

## 4. 1 - tree(): any value, lazily

<!-- field-box id="tree_box" index="3" -->

### Narrative

From `code`

Any value can go into tree(...): maps, lists, arrays, data classes, your own classes.
Each row shows name, type and a one-line summary; click a triangle (or use the arrow keys) to open.
Type **/tag into the filter: it runs scene.graphPaths("**/tag") and lists the matching paths.
Press p on a row to copy its path, e.g. particle/age, then scene.graphPath("particle/age") reads it.

```edn [field-box id="tree_box" role="properties"]
{"id" "tree_box" "index" 3 "name" "1 - tree(): any value, lazily" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - tree(): any value, lazily" "_tombstone" false "frame" #afield/rect [60.0 60.0 440.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tree_box_code_.kt" "lines" 28 "characters" 1077 "sha256" "0537a44a726dedba1226ce4f0730a3e45e61e7dcfd138bff6ebd023f1f480d9c"}]}
```

```kotlin [field-property box="tree_box" name="code" path="tree_box_code_.kt"]
// Any value can go into tree(...): maps, lists, arrays, data classes, your own classes.
// Each row shows name, type and a one-line summary; click a triangle (or use the arrow keys) to open.
// Type **/tag into the filter: it runs scene.graphPaths("**/tag") and lists the matching paths.
// Press p on a row to copy its path, e.g. particle/age, then scene.graphPath("particle/age") reads it.
data class Particle(val position: Vec3, val age: Double, val tag: String)
class Node(val name: String) {
    var next: Node? = null
}

val a = Node("a")
val b = Node("b")
a.next = b
b.next = a // a loop: shown with ↺, not opened forever

val scene = linkedMapOf(
    "particle" to Particle(Vec3(1f, 2f, 0.5f), 0.25, "spark"),
    "weights" to doubleArrayOf(0.1, 0.4, 0.5),
    "many" to List(5000) { it * it }, // arrives 200 rows at a time
    "byKey" to
        mapOf(
            Particle(Vec3(0f, 0f, 0f), 0.0, "origin") to "an object as a key",
            1 to "one",
            "1" to "the string one",
        ),
    "ring" to a,
    "missing" to null,
)
tree("tree", scene)
```

