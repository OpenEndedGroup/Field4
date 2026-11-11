# Field Document: ordered_group_keyframes

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Ordered group keyframes

<!-- field-box id="ordered_group_demo" index="0" -->

```edn [field-box id="ordered_group_demo" role="properties"]
{"id" "ordered_group_demo" "index" 0 "name" "Ordered group keyframes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Ordered group keyframes" "_tombstone" false "frame" #afield/rect [40.0 40.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ordered_group_demo_code_.kt" "lines" 62 "characters" 2531 "sha256" "79ae743d85de84713c14a150e1c689cfc91a2fa21d744d34a8e395961249c284"}]}
```

```kotlin [field-property box="ordered_group_demo" name="code" path="ordered_group_demo_code_.kt"]
import afield.animation.keyframes.OrderedKeyframeGroup
import afield.document.workspace.FileManager
import afield.study.source.SourceBackedFunction

// An inherited property stays dynamic after source-only rehydration.
prop<Double>(ruleOffset) = 0.1
val group =
    OrderedKeyframeGroup({ 5 }, sourceBoxId = thisBox.id) { resolved ->
            println("Applied: " + resolved.values("cooling"))
        }
        .lane("cooling", 0.0) { it in 0.0..1.0 }
val a = group.capture()
val cooling = sourceBlock { screenNo: Int ->
    val ramp = 0.04 * screenNo
    ruleOffset + ramp
}
group["cooling"] = cooling
val b = group.capture()
val directory = FileManager.current().path("%document/keys/ordered-demo")
b.save(directory.resolve("wave"))
val restored = group.scope.loadKey(directory.resolve("wave"))
group.apply(restored)
check(kotlin.math.abs(group.resolve()["cooling", 4] - .26) < 1e-9)
ruleOffset = .2
check(kotlin.math.abs(group.resolve()["cooling", 4] - .36) < 1e-9)

// Blend outputs at each index, not the syntax of their programs. Save the midpoint too.
val middle = group.blend(a, restored) { .5 }
middle.save(directory.resolve("midpoint"))
val reloadedMiddle = group.scope.loadKey(directory.resolve("midpoint"))
check(kotlin.math.abs(group.resolve(reloadedMiddle)["cooling", 4] - .18) < 1e-9)
val stagger = group.transition(a, b, seconds = .75, durationSeconds = 1.0, staggerSeconds = .25)
group.apply(stagger)
check(group.resolve()["cooling", 4] == 0.0)

// Each endpoint retains its own box's inherited context across a saved blend.
val other = SourceBackedFunction<Int, Double>(
    "ordered_group_other",
    "{ index: Int -> otherOffset + 0.02 * index }",
)
val crossContext = group.blend(restored, group.scope.Key("cooling" to other)) { .5 }
crossContext.save(directory.resolve("cross-context"))
check(
    kotlin.math.abs(group.resolve(group.scope.loadKey(directory.resolve("cross-context")))["cooling", 4] - .37) < 1e-9,
)

// All indices validate before any writes. Keep the previous rules on failure.
val previous = group.capture()
val failure = runCatching {
    group["cooling"] = sourceBlock { screenNo: Int -> if (screenNo == 4) Double.NaN else .2 }
}
        .exceptionOrNull()!!
check(failure.message!!.contains("index 4"))
check(group.capture().values == previous.values)
markdown(
    "result",
    """
### Ordered keyframes passed
Five members, typed function source, inherited property access, native save/reload,
a saved pointwise midpoint, stagger and rejection of a non-finite final member.
""",
)
```

## 2. Another rule context

<!-- field-box id="ordered_group_other" index="1" -->

```edn [field-box id="ordered_group_other" role="properties"]
{"id" "ordered_group_other" "index" 1 "name" "Another rule context" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Another rule context" "_tombstone" false "frame" #afield/rect [40.0 0.0 480.0 30.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ordered_group_other_code_.kt" "lines" 1 "characters" 32 "sha256" "5e762134a1e45eb36fa6df47e673be5d6734aeb4997387c597a8b309d918201c"}]}
```

```kotlin [field-property box="ordered_group_other" name="code" path="ordered_group_other_code_.kt"]
prop<Double>(otherOffset) = 0.3
```

