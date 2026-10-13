# Field Document: fieldml_frames_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isFieldMLRoot"]}
```

## 1. window and renderer

<!-- field-box id="fieldml-frames-renderer" index="0" -->

### Narrative

From `code`

Scaffolding, and it is Kotlin for two reasons worth naming.

`prop` and `once` are SOURCE MACROS -- mechanism A of dev_docs/FIELDML_HOST_BRIDGE.md.
They rewrite Kotlin source before it is compiled, so there is no JVM member for a
reflective bridge to reach. And `Renderer.run` is a `suspend` member: on the JVM it is
`run(Continuation)`, and FieldML can now *satisfy* a suspend function type but still
cannot *call into* one, because it has no continuation to pass and nowhere to resume.

Everything that actually draws, and everything that moves, is in the FieldML box.

```edn [field-box id="fieldml-frames-renderer" role="properties"]
{"id" "fieldml-frames-renderer" "index" 0 "name" "window and renderer" "parents" ["root"] "children" ["fieldml-frames-root"] "properties" {"children" #{#afield/box "fieldml-frames-root"} "parents" #{#afield/box "root"} "name" "window and renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-frames-renderer_code_.kt" "lines" 17 "characters" 809 "sha256" "562bfbf01c8a5da70cf7f7b1a99016deb0418e3f923a271c32273535abdd5377"}]}
```

```kotlin [field-property box="fieldml-frames-renderer" name="code" path="fieldml-frames-renderer_code_.kt"]
// Scaffolding, and it is Kotlin for two reasons worth naming.
//
// `prop` and `once` are SOURCE MACROS -- mechanism A of dev_docs/FIELDML_HOST_BRIDGE.md.
// They rewrite Kotlin source before it is compiled, so there is no JVM member for a
// reflective bridge to reach. And `Renderer.run` is a `suspend` member: on the JVM it is
// `run(Continuation)`, and FieldML can now *satisfy* a suspend function type but still
// cannot *call into* one, because it has no continuation to pass and nowhere to resume.
//
// Everything that actually draws, and everything that moves, is in the FieldML box.
prop<Window>(window)
prop<Renderer>(renderer)
once.renderer {
    window = Window("FieldML - a triangle that moves")
    Renderer(window)
}
println("renderer ready - now run the FieldML box")
start(renderer::run)
```

## 2. FieldML - a moving Field 4 scene

<!-- field-box id="fieldml-frames-root" index="1" -->

```edn [field-box id="fieldml-frames-root" role="properties"]
{"id" "fieldml-frames-root" "index" 1 "name" "FieldML - a moving Field 4 scene" "parents" ["root" "fieldml-frames-renderer"] "children" ["fieldml-frames-live"] "properties" {"children" #{#afield/box "fieldml-frames-live"} "parents" #{#afield/box "root" #afield/box "fieldml-frames-renderer"} "name" "FieldML - a moving Field 4 scene" "_tombstone" false "frame" #afield/rect [40.0 170.0 300.0 100.0] "isFieldMLRoot" true "mainLanguage" "fieldml"} "blockProperties" []}
```

## 3. A triangle that spins, driven by eachFrame

<!-- field-box id="fieldml-frames-live" index="2" -->

### Narrative

From `code`

A whole Field 4 scene, described from FieldML -- and, unlike the still-life demo
next door, one that MOVES. There is no hostAs anywhere in this box.

This box needs a live Vulkan device. Run the `window and renderer` box above it first;
that box owns the Window and the Renderer and this one walks up to find them.

The moving half is mechanism D of dev_docs/FIELDML_HOST_BRIDGE.md, closed 2026-08-19:
`eachFrame(block: suspend Frames.() -> Unit)` compiles to a Function2 whose last
parameter is a Continuation, and the bridge now drops that continuation, hands the block
the real `Frames`, and coerces the result to the type declared inside the continuation.

```edn [field-box id="fieldml-frames-live" role="properties"]
{"id" "fieldml-frames-live" "index" 2 "name" "A triangle that spins, driven by eachFrame" "parents" ["root" "fieldml-frames-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-frames-root"} "name" "A triangle that spins, driven by eachFrame" "_tombstone" false "frame" #afield/rect [400.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-frames-live_code_.kt" "lines" 77 "characters" 4490 "sha256" "6791f86bd7d7ab884dad44652a2e6514826d5fba3f5938274404444f9432204b"}]}
```

```kotlin [field-property box="fieldml-frames-live" name="code" path="fieldml-frames-live_code_.kt"]
// A whole Field 4 scene, described from FieldML -- and, unlike the still-life demo
// next door, one that MOVES. There is no hostAs anywhere in this box.
//
// This box needs a live Vulkan device. Run the `window and renderer` box above it first;
// that box owns the Window and the Renderer and this one walks up to find them.
//
// The moving half is mechanism D of dev_docs/FIELDML_HOST_BRIDGE.md, closed 2026-08-19:
// `eachFrame(block: suspend Frames.() -> Unit)` compiles to a Function2 whose last
// parameter is a Continuation, and the bridge now drops that continuation, hands the block
// the real `Frames`, and coerces the result to the type declared inside the continuation.
let read name box = box |> hostCall "getPropertyRaw" (name, hostNull)

let inherited name box =
  let here = box |> read name in
  if here != Unit then here
  else box |> hostGet "parents" |> hostAsList
         |> fold (fun found p -> if found != Unit then found else inherited name p) Unit

let renderer = thisBox |> inherited "renderer"

let ModuleKt = hostClass "afield.four.modules.ModuleKt"
let Phased = hostClass "afield.runtime.lifecycle.PhasedKt"
let Pair = hostClass "kotlin.Pair"

// One push-constant member, read by both stages. The name that matters is the MEMBER name
// (`spin`); Mesh.setMergedPushMembers keys offsets by it and ignores the block and instance
// names entirely.
let vertexSource = "#version 450\nlayout(push_constant) uniform Push { vec4 spin; } push;\nvec2 verts[3] = vec2[3](vec2(-0.7,-0.6), vec2(0.7,-0.6), vec2(0.0,0.7));\nvoid main() {\n  vec2 p = verts[gl_VertexIndex];\n  float c = cos(push.spin.x);\n  float s = sin(push.spin.x);\n  gl_Position = vec4(c*p.x - s*p.y, s*p.x + c*p.y, 0.0, 1.0);\n}"
let fragmentSource = "#version 450\nlayout(push_constant) uniform Push { vec4 spin; } push;\nlayout(location = 0) out vec4 o;\nvoid main() { o = vec4(0.5 + 0.5*cos(push.spin.x + vec3(0.0, 2.1, 4.2)), 1.0); }"

// parameters(renderer) { vertex = ...; fragment = ...; name = ... } is Module.kt:197. The
// receiver lambda's `p` is a Parameters, read straight off the JVM signature, so every
// member set below is checked at compile time.
let params =
  hostStaticCall "parameters" (renderer, fun p ->
    let _ = p |> hostSet "vertex" vertexSource in
    let _ = p |> hostSet "fragment" fragmentSource in
    p |> hostSet "name" "fieldmlSpin"
  ) ModuleKt

let module = hostNew (params) (hostClass "afield.four.modules.Module")
let pointer = module |> hostExt ModuleKt "pointer" Unit
let mesh = hostNew (renderer, pointer) (hostClass "afield.four.modules.Mesh")
let _ = pointer |> hostCall1 "plusAssign" (hostNew ("tri", mesh) Pair)
let _ = mesh |> hostCall "resize" (3, 0, pointer)
let target = renderer |> hostCall1 "renderTargetFor" 0
let _ = target |> hostCall1 "plusAssign_module" (hostNew ("fieldmlSpin", module) Pair)

// The value the animation writes. `Structure.set` types its value `Any`, and a FieldML Float
// arrives as a JVM Double -- which Structure's writer does not recognise, so it prints
// "unknown type" and the member silently keeps its old bytes (Structure.kt:424). A *typed*
// setter is the way across: `Vector4f.set(float, float, float, float)` really declares
// floats, so the bridge's numeric ladder narrows for us. The same object stays in the
// structure and is re-serialised on every draw, so mutating it is what moves the triangle.
let spin = hostNew (0.0, 0.0, 0.0, 0.0) (hostClass "afield.stdlib.linalg.Vector4f")
let _ = mesh |> hostGet "push" |> hostCall "set" ("spin", spin)

// Mechanism D. `f` is typed `Frames` off the JVM signature, so `f.frameNumber` is checked
// here rather than on the first frame -- and the block takes ONE parameter, not the two the
// erased Function2 has, because the continuation is not an argument anybody writes.
let animation =
  hostStaticCall "eachFrame" (fun f ->
    let t = (f |> hostGet "frameNumber" |> toFloat) * 0.04 in
    spin |> hostCall "set" (t, 0.0, 0.0, 1.0)
  ) Phased

// Box.launchFrames(frames, strand, mode, reportRunning) runs it on Field's Runloop with
// this box as the context, so `Stop` on this box stops the animation and re-running it
// bumps the strand generation rather than stacking a second loop.
let job = thisBox |> hostExt Phased "launchFrames" (animation, "fieldml-spin", "frames", true)

{
  moduleName = params |> hostGet "name"
  vertices = mesh |> hostGet "numVert"
  pushConstantBytes = module |> hostGet "pushConstantSize"
  animating = show job
}
```

