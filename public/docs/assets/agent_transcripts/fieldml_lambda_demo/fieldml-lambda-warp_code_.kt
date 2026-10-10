// FLine.transform takes a Kotlin (Vec3) -> Vec3. The type checker reads that
// signature off the receiver and gives `p` a type, so `p.x` is a checked Vec3
// component and there is no `hostAs` anywhere in this box. Misspell `p.x` as
// `p.xx` and the box says so before it runs.
//
// Change 26.0 for the wave height, 20.0 for its wavelength.
linspace 0.0 280.0 140
  |> map (fun x -> vec2 x 70.0)
  |> fold (fun line point -> line |> lineToV point) fline
  |> hostCall1 "transform" (fun p ->
       vec3 p.x (p.y + 26.0 * sin (p.x / 20.0)) p.z
     )
  |> stroked true
  |> filled false
  |> color (vec4 0.35 0.8 1.0 1.0)
  |> thickness 2.5
  |> drawSized "main" 320.0 170.0