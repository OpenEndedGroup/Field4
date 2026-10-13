# Field Document: fieldml_afield_four_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isFieldMLRoot"]}
```

## 1. window and renderer

<!-- field-box id="fieldml-four-renderer" index="0" -->

### Narrative

From `code`

Scaffolding, and it is Kotlin for two reasons worth naming.

`prop` and `once` are SOURCE MACROS -- mechanism A of dev_docs/FIELDML_HOST_BRIDGE.md.
They rewrite Kotlin source before it is compiled, so there is no JVM member for a
reflective bridge to reach. And `Renderer.run` is a `suspend` member: on the JVM it is
`run(Continuation)`, and FieldML can now *satisfy* a suspend function type but still
cannot *call into* one, because it has no continuation to pass and nowhere to resume.

Everything that actually draws, and everything that moves, is in the FieldML box.

```edn [field-box id="fieldml-four-renderer" role="properties"]
{"id" "fieldml-four-renderer" "index" 0 "name" "window and renderer" "parents" ["root"] "children" ["fieldml-four-root"] "properties" {"children" #{#afield/box "fieldml-four-root"} "parents" #{#afield/box "root"} "name" "window and renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-four-renderer_code_.kt" "lines" 17 "characters" 812 "sha256" "f4b46be74fea45b08da8104a3def7c563adc5fa2b738c1bb6fc03437f4fe12b3"}]}
```

```kotlin [field-property box="fieldml-four-renderer" name="code" path="fieldml-four-renderer_code_.kt"]
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
    window = Window("FieldML - a live afield.four scene")
    Renderer(window)
}
println("renderer ready - now run the FieldML box")
start(renderer::run)
```

## 2. FieldML — a live afield.four scene

<!-- field-box id="fieldml-four-root" index="1" -->

```edn [field-box id="fieldml-four-root" role="properties"]
{"id" "fieldml-four-root" "index" 1 "name" "FieldML — a live afield.four scene" "parents" ["root" "fieldml-four-renderer"] "children" ["fieldml-four-live"] "properties" {"children" #{#afield/box "fieldml-four-live"} "parents" #{#afield/box "root" #afield/box "fieldml-four-renderer"} "name" "FieldML — a live afield.four scene" "_tombstone" false "frame" #afield/rect [40.0 170.0 300.0 100.0] "isFieldMLRoot" true "mainLanguage" "fieldml"} "blockProperties" []}
```

## 3. A triangle on a real Vulkan device

<!-- field-box id="fieldml-four-live" index="2" -->

### Narrative

From `code`

A whole afield.four scene, described from FieldML, with no hostAs in it.

This box needs a live Vulkan device, so it does nothing useful unless an
ancestor box owns a running afield.four Window and Renderer. The backdoor's
--graphics-context flag makes exactly that box and parents new boxes to it;
in an ordinary session, connect this box to whichever box owns your renderer.

Verified on 2026-08-08: a light blue triangle in the native window, captured
with `field_backdoor.py --graphics-context ... native-screenshot`.

```edn [field-box id="fieldml-four-live" role="properties"]
{"id" "fieldml-four-live" "index" 2 "name" "A triangle on a real Vulkan device" "parents" ["root" "fieldml-four-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-four-root"} "name" "A triangle on a real Vulkan device" "_tombstone" false "frame" #afield/rect [400.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-four-live_code_.kt" "lines" 47 "characters" 2422 "sha256" "d44c9b57a9124c18d1be5ac56365165687c26271e136aabaa431197bff90d651"}]}
```

```kotlin [field-property box="fieldml-four-live" name="code" path="fieldml-four-live_code_.kt"]
// A whole afield.four scene, described from FieldML, with no hostAs in it.
//
// This box needs a live Vulkan device, so it does nothing useful unless an
// ancestor box owns a running afield.four Window and Renderer. The backdoor's
// --graphics-context flag makes exactly that box and parents new boxes to it;
// in an ordinary session, connect this box to whichever box owns your renderer.
//
// Verified on 2026-08-08: a light blue triangle in the native window, captured
// with `field_backdoor.py --graphics-context ... native-screenshot`.
let read name box = box |> hostCall "getPropertyRaw" (name, hostNull)

let inherited name box =
  let here = box |> read name in
  if here != Unit then here
  else box |> hostGet "parents" |> hostAsList
         |> fold (fun found p -> if found != Unit then found else inherited name p) Unit

let renderer = thisBox |> inherited "renderer"

let vertexSource = "#version 450\nvec2 verts[3] = vec2[3](vec2(-0.7,-0.6), vec2(0.7,-0.6), vec2(0.0,0.7));\nvoid main() { gl_Position = vec4(verts[gl_VertexIndex], 0.0, 1.0); }"
let fragmentSource = "#version 450\nlayout(location = 0) out vec4 o;\nvoid main() { o = vec4(0.35, 0.8, 1.0, 1.0); }"

// parameters(renderer) { vertex = ...; fragment = ...; name = ... } is
// Module.kt:197. The receiver lambda's `p` is a Parameters, read straight off
// the JVM signature -- so every member set below is checked at compile time.
// There is no hostAs anywhere in this box.
let params =
  hostStaticCall "parameters" (renderer, fun p ->
    let _ = p |> hostSet "vertex" vertexSource in
    let _ = p |> hostSet "fragment" fragmentSource in
    p |> hostSet "name" "fieldmlTriangle"
  ) (hostClass "afield.four.modules.ModuleKt")

let module = hostNew (params) (hostClass "afield.four.modules.Module")
let pointer = module |> hostExt (hostClass "afield.four.modules.ModuleKt") "pointer" Unit
let mesh = hostNew (renderer, pointer) (hostClass "afield.four.modules.Mesh")
let _ = pointer |> hostCall1 "plusAssign" (hostNew ("tri", mesh) (hostClass "kotlin.Pair"))
let _ = mesh |> hostCall "resize" (3, 0, pointer)
let target = renderer |> hostCall1 "renderTargetFor" 0
let _ = target |> hostCall1 "plusAssign_module" (hostNew ("fieldmlTriangle", module) (hostClass "kotlin.Pair"))

{
  renderer = show renderer
  moduleName = params |> hostGet "name"
  vertices = mesh |> hostGet "numVert"
  attached = target |> hostGet "modules" |> show
}
```

