# Field Document: fieldml_frames_sequence_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isFieldMLRoot"]}
```

## 1. FieldML - a frames body that waits

<!-- field-box id="fieldml-seq-root" index="0" -->

```edn [field-box id="fieldml-seq-root" role="properties"]
{"id" "fieldml-seq-root" "index" 0 "name" "FieldML - a frames body that waits" "parents" ["root"] "children" ["fieldml-seq-renderer" "fieldml-seq-script"] "properties" {"children" #{#afield/box "fieldml-seq-renderer" #afield/box "fieldml-seq-script"} "parents" #{#afield/box "root"} "name" "FieldML - a frames body that waits" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 90.0] "isFieldMLRoot" true "mainLanguage" "fieldml"} "blockProperties" []}
```

## 2. window and renderer (FieldML)

<!-- field-box id="fieldml-seq-renderer" index="1" -->

### Narrative

From `code`

The window and the renderer, from FieldML.

`once "renderer"` runs its function the first time this box asks for that key and hands
back the same Renderer on every later run, so running this box again does NOT open a
second window. `Renderer.run` is a `suspend` member; `hostLaunch` starts it on Field's
runloop and calls the closure when it returns (when the window closes).

```edn [field-box id="fieldml-seq-renderer" role="properties"]
{"id" "fieldml-seq-renderer" "index" 1 "name" "window and renderer (FieldML)" "parents" ["root" "fieldml-seq-root"] "children" ["fieldml-seq-script"] "properties" {"children" #{#afield/box "fieldml-seq-script"} "parents" #{#afield/box "root" #afield/box "fieldml-seq-root"} "name" "window and renderer (FieldML)" "_tombstone" false "frame" #afield/rect [40.0 180.0 300.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-seq-renderer_code_.kt" "lines" 16 "characters" 796 "sha256" "9a7828c9eb771ff5d1b54588effdaac97c2fcbf165ba2c1d90ea985ce532a495"}]}
```

```kotlin [field-property box="fieldml-seq-renderer" name="code" path="fieldml-seq-renderer_code_.kt"]
// The window and the renderer, from FieldML.
//
// `once "renderer"` runs its function the first time this box asks for that key and hands
// back the same Renderer on every later run, so running this box again does NOT open a
// second window. `Renderer.run` is a `suspend` member; `hostLaunch` starts it on Field's
// runloop and calls the closure when it returns (when the window closes).
let r = once "renderer" (fun u ->
  let w = hostNew {title = "FieldML - a frames body that waits"} (hostClass "afield.graphics.vulkan.util.Window") in
  let r = hostNew {mainWindow = w} (hostClass "afield.graphics.vulkan.Renderer") in
  let _ = hostLaunch "run" () r (fun ignored -> print "renderer stopped") in
  r)

// Publish it where the box below looks for it.
let _ = setProp "renderer" r

show r
```

## 3. a script across frames

<!-- field-box id="fieldml-seq-script" index="2" -->

### Narrative

From `code`

A `frames` body written in FieldML that WAITS: it draws a step, waits, draws the next.

Run `window and renderer (FieldML)` first. `frames { }` is the Kotlin form whose body
drives the frame clock itself, with `frame()` and `delay(ms)`. Both are `suspend` members
of `Frames`; `hostAwait "delay" 40 f` calls one and waits for it, so the FieldML body below
stops at that line and carries on from it 40 ms later -- inside a recursive loop.
(`eachFrame` would call a body afresh every frame; this one is a script with a middle.)

```edn [field-box id="fieldml-seq-script" role="properties"]
{"id" "fieldml-seq-script" "index" 2 "name" "a script across frames" "parents" ["root" "fieldml-seq-renderer" "fieldml-seq-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-seq-renderer" #afield/box "fieldml-seq-root"} "name" "a script across frames" "_tombstone" false "frame" #afield/rect [400.0 40.0 300.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-seq-script_code_.kt" "lines" 79 "characters" 3583 "sha256" "a7580098c8c49ff76eb0ff69b7567d687907d95ccc45eb1b0b99e39d21637fe0"}]}
```

```kotlin [field-property box="fieldml-seq-script" name="code" path="fieldml-seq-script_code_.kt"]
// A `frames` body written in FieldML that WAITS: it draws a step, waits, draws the next.
//
// Run `window and renderer (FieldML)` first. `frames { }` is the Kotlin form whose body
// drives the frame clock itself, with `frame()` and `delay(ms)`. Both are `suspend` members
// of `Frames`; `hostAwait "delay" 40 f` calls one and waits for it, so the FieldML body below
// stops at that line and carries on from it 40 ms later -- inside a recursive loop.
// (`eachFrame` would call a body afresh every frame; this one is a script with a middle.)
let renderer = prop "renderer"

let ModuleKt = hostClass "afield.graphics.render.ModuleKt"
let Phased = hostClass "afield.runtime.lifecycle.PhasedKt"
let Pair = hostClass "kotlin.Pair"

let vertexSource = "#version 450\nlayout(location = 0) in vec3 pos;\nlayout(location = 1) in vec4 col;\nlayout(location = 0) out vec4 fcol;\nvoid main() { fcol = col; gl_Position = vec4(pos, 1.0); }"
let fragmentSource = "#version 450\nlayout(location = 0) in vec4 fcol;\nlayout(location = 0) out vec4 o;\nvoid main() { o = fcol; }"

// 4 is VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP: two vertices per column make a filled band.
let params =
  hostStaticCall "parameters" (renderer, fun p ->
    let _ = p |> hostSet "vertex" vertexSource in
    let _ = p |> hostSet "fragment" fragmentSource in
    let _ = p |> hostSet "primitiveType" 4 in
    p |> hostSet "name" "fieldmlSequence"
  ) ModuleKt

let module = hostNew (params) (hostClass "afield.graphics.render.Module")
let pointer = module |> hostExt ModuleKt "pointer" Unit
let mesh = hostNew (renderer, pointer) (hostClass "afield.graphics.render.Mesh")
let _ = pointer |> hostCall1 "plusAssign" (hostNew ("sequence", mesh) Pair)

let columns = 48
let _ = mesh |> hostCall "resize" (columns * 2, 0, pointer)
let target = renderer |> hostCall1 "renderTargetFor" 0
let _ = target |> hostCall1 "plusAssign_module" (hostNew ("fieldmlSequence", module) Pair)

let indices = range 0 columns

// Columns left of `k` are standing; the rest are flat on the floor, waiting their turn.
let positions k =
  concat (indices |> map (fun i ->
    let u = toFloat i / toFloat (columns - 1) in
    let x = u * 1.8 - 0.9 in
    let h = if i < k then 0.25 + 0.55 * (0.5 + 0.5 * sin (u * 7.0)) else 0.0 in
    [x, 0.8, 0.0, x, 0.8 - h * 1.6, 0.0]))

// Every pass round the script takes the next colour.
let colours n =
  let g = if mod 2 n == 0 then 0.55 else 0.2 in
  let b = if mod 2 n == 0 then 0.15 else 1.0 in
  concat (indices |> map (fun i -> [1.0, g, b, 1.0, 1.0, g, b, 1.0]))

let write name values = mesh |> hostCall "write" {name = name, builder = fun fb -> fb |> hostCall1 "put" values}

let script =
  hostStaticCall "frames" (fun f ->
    let wait ms = hostAwait "delay" ms f in
    // Raise one column, wait 40 ms, raise the next.
    let grow k n =
      if k <= columns then
        let _ = write "pos" (positions k) in
        let _ = write "col" (colours n) in
        let _ = wait 40 in
        grow (k + 1) n
      else Unit
    in
    // Then hold the finished band for most of a second, and start again in the other colour.
    let again n =
      let _ = grow 0 n in
      let _ = wait 700 in
      again (n + 1)
    in
    again 0
  ) Phased

let job = thisBox |> hostExt Phased "launchFrames" (script, "fieldml-sequence", "frames", true)
// A picture of the window in this box's `window` output, 60 frames from now.
let _ = thisBox |> hostExt (hostClass "afield.graphics.vulkan.SnapshotWindowKt") "snapshotWindow" (renderer, 60, "window", 640, false)

{ columns = columns, running = show job }
```

