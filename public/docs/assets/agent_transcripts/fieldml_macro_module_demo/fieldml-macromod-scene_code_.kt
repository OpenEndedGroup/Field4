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
let ModuleKt = hostClass "afield.four.modules.ModuleKt"
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
let mesh = hostNew (renderer, pointer) (hostClass "afield.four.modules.Mesh")
let _ = pointer |> hostCall1 "plusAssign" (hostNew ("tri", mesh) Pair)
let _ = mesh |> hostCall "resize" (3, 0, pointer)
let target = renderer |> hostCall1 "renderTargetFor" 0
let _ = target |> hostCall1 "plusAssign_mp" pointer

let spin = hostNew (0.0, 0.0, 0.0, 0.0) (hostClass "afield.stdlib.linalg.Vector4f")
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