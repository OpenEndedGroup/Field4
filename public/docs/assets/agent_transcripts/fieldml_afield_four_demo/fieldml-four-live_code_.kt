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