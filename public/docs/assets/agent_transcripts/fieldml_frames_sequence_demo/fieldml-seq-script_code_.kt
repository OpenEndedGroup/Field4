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
