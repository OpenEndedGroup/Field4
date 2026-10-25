# Field Document: fieldml_mesh_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isFieldMLRoot"]}
```

## 1. window and renderer

<!-- field-box id="fieldml-mesh-renderer" index="0" -->

### Narrative

From `code`

Scaffolding, and Kotlin for two reasons named in the FieldML box next door:
`prop`/`once` are SOURCE MACROS (mechanism A), and `Renderer.run` is a suspend
member*, which the bridge can satisfy the type of but cannot call into.

```edn [field-box id="fieldml-mesh-renderer" role="properties"]
{"id" "fieldml-mesh-renderer" "index" 0 "name" "window and renderer" "parents" ["root"] "children" ["fieldml-mesh-root"] "properties" {"children" #{#afield/box "fieldml-mesh-root"} "parents" #{#afield/box "root"} "name" "window and renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-mesh-renderer_code_.kt" "lines" 13 "characters" 458 "sha256" "4bd915bf455170e001327a3502d0dae362439a6a0ade809695d8a738a109ae17"}]}
```

```kotlin [field-property box="fieldml-mesh-renderer" name="code" path="fieldml-mesh-renderer_code_.kt"]
/*
Scaffolding, and Kotlin for two reasons named in the FieldML box next door:
`prop`/`once` are SOURCE MACROS (mechanism A), and `Renderer.run` is a suspend
*member*, which the bridge can satisfy the type of but cannot call into.
*/
prop<Window>(window)
prop<Renderer>(renderer)
once.renderer {
    window = Window("FieldML - vertex data, written every frame")
    Renderer(window)
}
println("renderer ready - now run the FieldML box")
start(renderer::run)
```

## 2. FieldML - vertex data written every frame

<!-- field-box id="fieldml-mesh-root" index="1" -->

```edn [field-box id="fieldml-mesh-root" role="properties"]
{"id" "fieldml-mesh-root" "index" 1 "name" "FieldML - vertex data written every frame" "parents" ["root" "fieldml-mesh-renderer"] "children" ["fieldml-mesh-live"] "properties" {"children" #{#afield/box "fieldml-mesh-live"} "parents" #{#afield/box "root" #afield/box "fieldml-mesh-renderer"} "name" "FieldML - vertex data written every frame" "_tombstone" false "frame" #afield/rect [40.0 170.0 300.0 100.0] "isFieldMLRoot" true "mainLanguage" "fieldml"} "blockProperties" []}
```

## 3. Sixteen triangles, recomputed per frame

<!-- field-box id="fieldml-mesh-live" index="2" -->

### Narrative

From `code`

The mesh-writing surface: a vertex shader with REAL INPUTS, whose vertex data
is computed in FieldML and rewritten on every frame.

The still-life demo (fieldml_afield_four_demo) proved FieldML could build a
pipeline; fieldml_frames_demo made it move by writing one push constant. Both
drew a vertex shader that needs no inputs, which is the wall
dev_docs/FIELDML_HOST_BRIDGE.md named next. This box goes through it: sixteen
triangles, ninety-six floats of position and forty-eight of hue, all computed
here and written straight into the mesh's vertex buffers.

Run the `window and renderer` box above first; this one walks up to find it.

```edn [field-box id="fieldml-mesh-live" role="properties"]
{"id" "fieldml-mesh-live" "index" 2 "name" "Sixteen triangles, recomputed per frame" "parents" ["root" "fieldml-mesh-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-mesh-root"} "name" "Sixteen triangles, recomputed per frame" "_tombstone" false "frame" #afield/rect [400.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-mesh-live_code_.kt" "lines" 106 "characters" 5156 "sha256" "cd463f4edc26b440615a475e0b11c157420e8df73817c658e13d9b5d3e42ae7e"}]}
```

```kotlin [field-property box="fieldml-mesh-live" name="code" path="fieldml-mesh-live_code_.kt"]
// The mesh-writing surface: a vertex shader with REAL INPUTS, whose vertex data
// is computed in FieldML and rewritten on every frame.
//
// The still-life demo (fieldml_afield_four_demo) proved FieldML could build a
// pipeline; fieldml_frames_demo made it move by writing one push constant. Both
// drew a vertex shader that needs no inputs, which is the wall
// dev_docs/FIELDML_HOST_BRIDGE.md named next. This box goes through it: sixteen
// triangles, ninety-six floats of position and forty-eight of hue, all computed
// here and written straight into the mesh's vertex buffers.
//
// Run the `window and renderer` box above first; this one walks up to find it.
let read name box = box |> hostCall "getPropertyRaw" (name, hostNull)

let inherited name box =
  let here = box |> read name in
  if here != Unit then here
  else box |> hostGet "parents" |> hostAsList
         |> fold (fun found p -> if found != Unit then found else inherited name p) Unit

let renderer = thisBox |> inherited "renderer"

let ModuleKt = hostClass "afield.graphics.render.ModuleKt"
let Phased = hostClass "afield.runtime.lifecycle.PhasedKt"
let Pair = hostClass "kotlin.Pair"

// Two vertex inputs. `Mesh.resize` reads these back out of the compiled SPIR-V
// (Module.vi.inputs) and allocates one VertexBuffer per location, so declaring
// them here is what makes the buffers exist at all.
let vertexSource = "#version 450\nlayout(location = 0) in vec2 pos;\nlayout(location = 1) in float hue;\nlayout(location = 0) out float vHue;\nvoid main() {\n  vHue = hue;\n  gl_Position = vec4(pos, 0.0, 1.0);\n}"
let fragmentSource = "#version 450\nlayout(location = 0) in float vHue;\nlayout(location = 0) out vec4 o;\nvoid main() { o = vec4(0.5 + 0.5*cos(vHue + vec3(0.0, 2.1, 4.2)), 1.0); }"

let params =
  hostStaticCall "parameters" (renderer, fun p ->
    let _ = p |> hostSet "vertex" vertexSource in
    let _ = p |> hostSet "fragment" fragmentSource in
    p |> hostSet "name" "fieldmlRing"
  ) ModuleKt

let module = hostNew (params) (hostClass "afield.graphics.render.Module")
let pointer = module |> hostExt ModuleKt "pointer" Unit
let mesh = hostNew (renderer, pointer) (hostClass "afield.graphics.render.Mesh")
let _ = pointer |> hostCall1 "plusAssign" (hostNew ("ring", mesh) Pair)

let blades = 16
let verts = blades * 3
let _ = mesh |> hostCall "resize" (verts, 0, pointer)

let target = renderer |> hostCall1 "renderTargetFor" 0
let _ = target |> hostCall1 "plusAssign_module" (hostNew ("fieldmlRing", module) Pair)

// `FloatBuffer.put(float)` returns the buffer, so a fold over it is exactly a
// sequential write -- and `put` really is typed `float`, so the numeric ladder
// narrows the FieldML Float for us. This is the difference from a push constant,
// where the parameter is `Any` and nothing narrows (see the note in
// dev_docs/FIELDML_HOST_BRIDGE.md on `Structure.set`).
// The buffer comes LAST, because `b |> put1 x` desugars to `put1 x b`. Getting
// that backwards is a run-time error and not a compile one -- `b` is a function
// parameter, so there is no class to check the call against. That is the
// residual hole "Known holes" in dev_docs/FIELDML_HOST_BRIDGE.md describes, and
// it bit while this box was being written.
let put1 x b = b |> hostCall1 "put" x
let put2 x y b = b |> put1 x |> put1 y

// A ring of `blades` triangles, breathing. `0.6 *` on x is a hardcoded aspect
// correction so the ring reads as round in a wide window.
let tau = 6.28318530718
let blade t b k =
  let a = tau * (toFloat k) / (toFloat blades) + t * 0.35 in
  let w = 0.13 in
  let r0 = 0.10 + 0.04 * sin (t * 1.7 + a * 3.0) in
  let r1 = 0.72 + 0.20 * sin (t * 1.1 + a * 2.0) in
  b |> put2 (0.6 * r0 * cos a) (r0 * sin a)
    |> put2 (0.6 * r1 * cos (a - w)) (r1 * sin (a - w))
    |> put2 (0.6 * r1 * cos (a + w)) (r1 * sin (a + w))

let positions t buf = range 0 blades |> fold (fun b k -> blade t b k) buf

let hues t buf =
  range 0 blades |> fold (fun b k ->
    let h = tau * (toFloat k) / (toFloat blades) + t * 0.9 in
    b |> put1 h |> put1 h |> put1 h
  ) buf

// Mechanism D again -- `eachFrame(block: suspend Frames.() -> Unit)` -- but now
// the body writes a hundred and forty-four floats rather than four.
//
// The two writes use the two shapes deliberately. Location 0 goes through
// `Mesh.write(index, numElements, builder)`, three positional arguments with a
// trailing lambda. Location 1 goes through `Mesh.write(name, inside, builder)`
// BY NAME, leaving `inside = this.inside.current` to Kotlin -- a default that no
// positional call can omit, and the reason mechanisms B and C had to close too.
let animation =
  hostStaticCall "eachFrame" (fun f ->
    let t = (f |> hostGet "frameNumber" |> toFloat) * 0.03 in
    let _ = mesh |> hostCall "write" (0, 2, fun buf -> positions t buf) in
    mesh |> hostCall "write" {name = "hue", builder = fun buf -> hues t buf}
  ) Phased

let job = thisBox |> hostExt Phased "launchFrames" (animation, "fieldml-ring", "frames", true)

{
  moduleName = params |> hostGet "name"
  vertices = mesh |> hostGet "numVert"
  vertexBuffers = mesh |> hostGet "vn" |> show
  animating = show job
}
```

