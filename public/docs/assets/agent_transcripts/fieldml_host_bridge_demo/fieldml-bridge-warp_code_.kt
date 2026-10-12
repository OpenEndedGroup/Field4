// FLine.transform takes a Kotlin (Vector3f) -> Vector3f. The value it
// hands back has no static type, so hostAs is where it gets one.
// Change 24.0 to change the wave height.
let wobble line =
  line |> hostCall1 "transform" (fun raw ->
    let p = hostAs "Vec3" raw in
    vec3 p.x (p.y + 24.0 * sin (p.x / 22.0)) p.z
  )

linspace 0.0 260.0 120
  |> map (fun x -> vec2 x 60.0)
  |> fold (fun line point -> line |> lineToV point) fline
  |> wobble
  |> stroked true
  |> filled false
  |> color (vec4 1.0 0.55 0.2 1.0)
  |> thickness 2.5
  |> drawSized "main" 300.0 160.0