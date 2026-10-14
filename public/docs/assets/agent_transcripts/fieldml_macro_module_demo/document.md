# Field Document: fieldml_macro_module_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isFieldMLRoot"]}
```

## 1. window and renderer (Kotlin)

<!-- field-box id="fieldml-macromod-renderer" index="0" -->

### Narrative

From `code`

Scaffolding, and there is now exactly ONE reason it is Kotlin.

`Renderer.run` is a `suspend` member: on the JVM it is `run(Continuation)`, and
FieldML can *satisfy* a suspend function type but still cannot *call into* one --
it has no continuation to pass and nowhere to resume. That is the whole of it.

`prop` and `once` used to be the other reason, and they are not any more: they are
source macros, mechanism A of dev_docs/FIELDML_HOST_BRIDGE.md, and reachable from
FieldML since 2026-08-20. They are still written in Kotlin here only because this
box has to exist anyway.

```edn [field-box id="fieldml-macromod-renderer" role="properties"]
{"id" "fieldml-macromod-renderer" "index" 0 "name" "window and renderer (Kotlin)" "parents" ["root"] "children" ["fieldml-macromod-root"] "properties" {"children" #{#afield/box "fieldml-macromod-root"} "parents" #{#afield/box "root"} "name" "window and renderer (Kotlin)" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-macromod-renderer_code_.kt" "lines" 18 "characters" 822 "sha256" "d699789ef6583bcee7b709b2095664b1a60f6a72402ad1ae471b11a49447bd19"}]}
```

```kotlin [field-property box="fieldml-macromod-renderer" name="code" path="fieldml-macromod-renderer_code_.kt"]
// Scaffolding, and there is now exactly ONE reason it is Kotlin.
//
// `Renderer.run` is a `suspend` member: on the JVM it is `run(Continuation)`, and
// FieldML can *satisfy* a suspend function type but still cannot *call into* one --
// it has no continuation to pass and nowhere to resume. That is the whole of it.
//
// `prop` and `once` used to be the other reason, and they are not any more: they are
// source macros, mechanism A of dev_docs/FIELDML_HOST_BRIDGE.md, and reachable from
// FieldML since 2026-08-20. They are still written in Kotlin here only because this
// box has to exist anyway.
prop<Window>(window)
prop<Renderer>(renderer)
once.renderer {
    window = Window("FieldML - a scene built by moduleHere")
    Renderer(window)
}
println("renderer ready - now run the scene box")
start(renderer::run)
```

## 2. FieldML — a scene built by a source macro

<!-- field-box id="fieldml-macromod-root" index="1" -->

```edn [field-box id="fieldml-macromod-root" role="properties"]
{"id" "fieldml-macromod-root" "index" 1 "name" "FieldML — a scene built by a source macro" "parents" ["root" "fieldml-macromod-renderer"] "children" ["fieldml-macromod-scene" "fieldml-macromod-live"] "properties" {"children" #{#afield/box "fieldml-macromod-scene" #afield/box "fieldml-macromod-live"} "parents" #{#afield/box "root" #afield/box "fieldml-macromod-renderer"} "name" "FieldML — a scene built by a source macro" "_tombstone" false "frame" #afield/rect [40.0 170.0 300.0 100.0] "isFieldMLRoot" true "mainLanguage" "fieldml"} "blockProperties" []}
```

## 3. the live parameter

<!-- field-box id="fieldml-macromod-live" index="2" -->

### Narrative

From `code`

THE LIVE PARAMETER. Run this while the scene above is spinning.

`setRootProp` puts the slot on the *document root*, so every box in the document
shares the one value -- which is how a sibling box reaches the scene's `eachFrame`
body. That body reads it every frame, so the speed of a running Vulkan scene
changes without the scene box being re-run, re-compiled, or touched.

Change the number and run this box again. Nothing else moves.

```edn [field-box id="fieldml-macromod-live" role="properties"]
{"id" "fieldml-macromod-live" "index" 2 "name" "the live parameter" "parents" ["root" "fieldml-macromod-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-macromod-root"} "name" "the live parameter" "_tombstone" false "frame" #afield/rect [400.0 300.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-macromod-live_code_.kt" "lines" 16 "characters" 771 "sha256" "f8e01d704b09083c21e17d8cfaf4742c0c58c2e6cb1c461a6ece84f40b49e230"}]}
```

```kotlin [field-property box="fieldml-macromod-live" name="code" path="fieldml-macromod-live_code_.kt"]
// THE LIVE PARAMETER. Run this while the scene above is spinning.
//
// `setRootProp` puts the slot on the *document root*, so every box in the document
// shares the one value -- which is how a sibling box reaches the scene's `eachFrame`
// body. That body reads it every frame, so the speed of a running Vulkan scene
// changes without the scene box being re-run, re-compiled, or touched.
//
// Change the number and run this box again. Nothing else moves.
let _ = setRootProp "spinRate" 0.30

{
  spinRate = rootProp "spinRate" |> hostAsFloat
  // ...and proof the loop is running: the scene box writes its frame number into a
  // shared slot every frame, and this reads it. Run this box twice; the number grows.
  framesDrawn = propOr "framesDrawn" 0 |> hostAsInt
}
```

## 4. moduleHere, from FieldML

<!-- field-box id="fieldml-macromod-scene" index="3" -->

### Narrative

From `code`

A WHOLE FIELD 4 SCENE BUILT BY A SOURCE MACRO, FROM FIELDML.

The still-life and the moving demos next door hand-build their pipeline:
`parameters(renderer) { vertex = ...; fragment = ... }`, then `Module(params)`,
then `.pointer()`. This box does not. It calls Field's `moduleHere` -- the macro
a Kotlin graphics box actually uses -- which means the GLSL lives in this box's
own `vertex` and `fragment` properties, exactly where a Kotlin box keeps it, with
this box's include resolvers, source names and shader-error reporting attached.

Mechanism A of dev_docs/FIELDML_HOST_BRIDGE.md. There is no hostAs anywhere here.

```edn [field-box id="fieldml-macromod-scene" role="properties"]
{"id" "fieldml-macromod-scene" "index" 3 "name" "moduleHere, from FieldML" "parents" ["root" "fieldml-macromod-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-macromod-root"} "name" "moduleHere, from FieldML" "_tombstone" false "frame" #afield/rect [400.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-macromod-scene_code_.kt" "lines" 62 "characters" 3493 "sha256" "3618ea7e5308982ca26e279ab7be270eab12a96c5716f2ef7781bb01ff89ff2d"}]}
```

```kotlin [field-property box="fieldml-macromod-scene" name="code" path="fieldml-macromod-scene_code_.kt"]
// A WHOLE FIELD 4 SCENE BUILT BY A SOURCE MACRO, FROM FIELDML.
//
// The still-life and the moving demos next door hand-build their pipeline:
// `parameters(renderer) { vertex = ...; fragment = ... }`, then `Module(params)`,
// then `.pointer()`. This box does not. It calls Field's `moduleHere` -- the macro
// a Kotlin graphics box actually uses -- which means the GLSL lives in this box's
// own `vertex` and `fragment` properties, exactly where a Kotlin box keeps it, with
// this box's include resolvers, source names and shader-error reporting attached.
//
// Mechanism A of dev_docs/FIELDML_HOST_BRIDGE.md. There is no hostAs anywhere here.
let ModuleKt = hostClass "afield.graphics.render.ModuleKt"
let Phased = hostClass "afield.runtime.lifecycle.PhasedKt"
let Pair = hostClass "kotlin.Pair"

let renderer = prop "renderer"

// The shaders go into the box's properties, because that is what `moduleHere`
// expands against: its expansion preprocesses and compiles both stages from these
// and assigns the prepared result back onto the box.
let write name value = thisBox |> hostCall "setPropertyRaw" (name, value, false)

let _ = write "vertex" "#version 450\nlayout(push_constant) uniform Push { vec4 spin; } push;\nvec2 verts[3] = vec2[3](vec2(-0.7,-0.6), vec2(0.7,-0.6), vec2(0.0,0.7));\nvoid main() {\n  vec2 p = verts[gl_VertexIndex];\n  float c = cos(push.spin.x);\n  float s = sin(push.spin.x);\n  gl_Position = vec4(c*p.x - s*p.y, s*p.x + c*p.y, 0.0, 1.0);\n}"
let _ = write "fragment" "#version 450\nlayout(push_constant) uniform Push { vec4 spin; } push;\nlayout(location = 0) out vec4 o;\nvoid main() { o = vec4(0.5 + 0.5*cos(push.spin.x + vec3(0.0, 2.1, 4.2)), 1.0); }"

let params = hostStaticCall "parameters" (renderer, fun p -> p |> hostSet "name" "fieldmlMacroSpin") ModuleKt

// THE LINE THIS DEMO IS ABOUT. `moduleHere` is a source macro: it rewrites its call
// site to `_moduleHere(...)` and does its real work while expanding. `hostMacro` runs
// the expansion -- which compiles both stages above -- and then calls the `Box`
// extension the rewrite names. A ModulePointer comes back, already this box's `_shader`.
let pointer = hostMacro "moduleHere" "(params)" (params)

let module = pointer |> hostGet "current"
let mesh = hostNew (renderer, pointer) (hostClass "afield.graphics.render.Mesh")
let _ = pointer |> hostCall1 "plusAssign" (hostNew ("tri", mesh) Pair)
let _ = mesh |> hostCall "resize" (3, 0, pointer)
let target = renderer |> hostCall1 "renderTargetFor" 0
let _ = target |> hostCall1 "plusAssign_mp" pointer

let spin = hostNew (0.0, 0.0, 0.0, 0.0) (hostClass "afield.math.linalg.Vector4f")
let _ = mesh |> hostGet "push" |> hostCall "set" ("spin", spin)

let animation =
  hostStaticCall "eachFrame" (fun f ->
    // The rate is read from the shared slot EVERY FRAME, so the box below can change
    // the speed of a running Vulkan scene without this box being touched.
    let rate = propOr "spinRate" 0.04 |> hostAsFloat in
    let n = f |> hostGet "frameNumber" in
    let _ = setRootProp "framesDrawn" n in
    let t = (n |> toFloat) * rate in
    spin |> hostCall "set" (t, 0.0, 0.0, 1.0)
  ) Phased

let job = thisBox |> hostExt Phased "launchFrames" (animation, "fieldml-macro-spin", "frames", true)

{
  builtBy = (hostMacroExpand "moduleHere" "(params)").call
  vertices = mesh |> hostGet "numVert"
  pushConstantBytes = module |> hostGet "pushConstantSize"
  spinRate = propOr "spinRate" 0.04 |> hostAsFloat
  animating = show job
}
```

