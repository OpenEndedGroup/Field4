# Field Document: fieldml_suspend_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isFieldMLRoot"]}
```

## 1. FieldML - a mesh written every frame

<!-- field-box id="fieldml-mesh-root" index="0" -->

```edn [field-box id="fieldml-mesh-root" role="properties"]
{"id" "fieldml-mesh-root" "index" 0 "name" "FieldML - a mesh written every frame" "parents" ["root"] "children" ["fieldml-mesh-renderer" "fieldml-mesh-ribbon"] "properties" {"children" #{#afield/box "fieldml-mesh-renderer" #afield/box "fieldml-mesh-ribbon"} "parents" #{#afield/box "root"} "name" "FieldML - a mesh written every frame" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 90.0] "isFieldMLRoot" true "mainLanguage" "fieldml"} "blockProperties" []}
```

## 2. window and renderer (FieldML)

<!-- field-box id="fieldml-mesh-renderer" index="1" -->

### Narrative

From `code`

The window and the renderer, from FieldML -- and the line that used to have to be Kotlin.

`Renderer.run` is a `suspend` member: on the JVM it is `run(Continuation)`. `hostLaunch`
starts it as its own coroutine on Field's runloop and calls the closure when it returns
(when the window closes). Run this box once; running it again opens a second window.

```edn [field-box id="fieldml-mesh-renderer" role="properties"]
{"id" "fieldml-mesh-renderer" "index" 1 "name" "window and renderer (FieldML)" "parents" ["root" "fieldml-mesh-root"] "children" ["fieldml-mesh-ribbon"] "properties" {"children" #{#afield/box "fieldml-mesh-ribbon"} "parents" #{#afield/box "root" #afield/box "fieldml-mesh-root"} "name" "window and renderer (FieldML)" "_tombstone" false "frame" #afield/rect [40.0 180.0 300.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-mesh-renderer_code_.kt" "lines" 14 "characters" 723 "sha256" "4a18208bf33514a0306f2ce82ab2d1a2634c65fa28cfe7a6a0d6da9bf6b0d474"}]}
```

```kotlin [field-property box="fieldml-mesh-renderer" name="code" path="fieldml-mesh-renderer_code_.kt"]
// The window and the renderer, from FieldML -- and the line that used to have to be Kotlin.
//
// `Renderer.run` is a `suspend` member: on the JVM it is `run(Continuation)`. `hostLaunch`
// starts it as its own coroutine on Field's runloop and calls the closure when it returns
// (when the window closes). Run this box once; running it again opens a second window.
let w = hostNew {title = "FieldML - a mesh written every frame"} (hostClass "afield.graphics.vulkan.util.Window")
let r = hostNew {mainWindow = w} (hostClass "afield.graphics.vulkan.Renderer")

// Publish it where the box below looks for it.
let _ = setProp "renderer" r

let job = hostLaunch "run" () r (fun ignored -> print "renderer stopped")

show job
```

## 3. A ribbon rewritten every frame

<!-- field-box id="fieldml-mesh-ribbon" index="2" -->

### Narrative

From `code`

A ribbon whose VERTICES are rewritten every frame by FieldML.

Run `window and renderer (FieldML)` first. The vertex shader takes real inputs -- `pos`
and `col` -- and the per-frame body fills both buffers through `Mesh.write`.
`prop` reads the way a Kotlin box's `renderer` does: here first, then up through the parents.

```edn [field-box id="fieldml-mesh-ribbon" role="properties"]
{"id" "fieldml-mesh-ribbon" "index" 2 "name" "A ribbon rewritten every frame" "parents" ["root" "fieldml-mesh-renderer" "fieldml-mesh-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-mesh-renderer" #afield/box "fieldml-mesh-root"} "name" "A ribbon rewritten every frame" "_tombstone" false "frame" #afield/rect [400.0 40.0 560.0 720.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-mesh-ribbon_code_.kt" "lines" 64 "characters" 2926 "sha256" "204dcabfc4606e03871433ad50013baf9e91f48b2c73997cba30e4ce6a9d65ac"}]}
```

```kotlin [field-property box="fieldml-mesh-ribbon" name="code" path="fieldml-mesh-ribbon_code_.kt"]
// A ribbon whose VERTICES are rewritten every frame by FieldML.
//
// Run `window and renderer (FieldML)` first. The vertex shader takes real inputs -- `pos`
// and `col` -- and the per-frame body fills both buffers through `Mesh.write`.
// `prop` reads the way a Kotlin box's `renderer` does: here first, then up through the parents.
let renderer = prop "renderer"

let ModuleKt = hostClass "afield.graphics.render.ModuleKt"
let Phased = hostClass "afield.runtime.lifecycle.PhasedKt"
let Pair = hostClass "kotlin.Pair"

let vertexSource = "#version 450\nlayout(location = 0) in vec3 pos;\nlayout(location = 1) in vec4 col;\nlayout(location = 0) out vec4 fcol;\nvoid main() { fcol = col; gl_Position = vec4(pos, 1.0); }"
let fragmentSource = "#version 450\nlayout(location = 0) in vec4 fcol;\nlayout(location = 0) out vec4 o;\nvoid main() { o = fcol; }"

// 4 is VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP: two vertices per column make a ribbon.
let params =
  hostStaticCall "parameters" (renderer, fun p ->
    let _ = p |> hostSet "vertex" vertexSource in
    let _ = p |> hostSet "fragment" fragmentSource in
    let _ = p |> hostSet "primitiveType" 4 in
    p |> hostSet "name" "fieldmlRibbon"
  ) ModuleKt

let module = hostNew (params) (hostClass "afield.graphics.render.Module")
let pointer = module |> hostExt ModuleKt "pointer" Unit
let mesh = hostNew (renderer, pointer) (hostClass "afield.graphics.render.Mesh")
let _ = pointer |> hostCall1 "plusAssign" (hostNew ("ribbon", mesh) Pair)

let columns = 64
let _ = mesh |> hostCall "resize" (columns * 2, 0, pointer)
let target = renderer |> hostCall1 "renderTargetFor" 0
let _ = target |> hostCall1 "plusAssign_module" (hostNew ("fieldmlRibbon", module) Pair)

let indices = range 0 columns

// x across the window, y a travelling wave, the ribbon's width breathing with it.
let positions t =
  concat (indices |> map (fun i ->
    let u = toFloat i / toFloat (columns - 1) in
    let x = u * 1.8 - 0.9 in
    let y = 0.45 * sin (u * 9.0 + t) in
    let h = 0.08 + 0.06 * cos (u * 5.0 - t * 1.3) in
    [x, y - h, 0.0, x, y + h, 0.0]))

let colours t =
  concat (indices |> map (fun i ->
    let u = toFloat i / toFloat (columns - 1) in
    let g = 0.5 + 0.5 * sin (u * 6.0 + t) in
    [1.0, g, 0.2, 1.0, 0.2, g, 1.0, 1.0]))

// `Mesh.write(name, inside = default, builder: FloatBuffer.() -> Unit)`: a record makes it a
// named call, so the module defaults, and `fb` is the mapped staging buffer.
let write name values = mesh |> hostCall "write" {name = name, builder = fun fb -> fb |> hostCall1 "put" values}

let animation =
  hostStaticCall "eachFrame" (fun f ->
    let t = (f |> hostGet "frameNumber" |> toFloat) * 0.05 in
    let _ = write "pos" (positions t) in
    write "col" (colours t)
  ) Phased

let job = thisBox |> hostExt Phased "launchFrames" (animation, "fieldml-ribbon", "frames", true)

{ vertices = mesh |> hostGet "numVert", animating = show job }
```

