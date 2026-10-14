let Img = hostClass "afield.media.image.Img"
let Imaging = hostClass "afield.media.image.Imaging_fieldKt"

// Two callbacks, six typed parameters, no casts.
//
// Img.Companion.generate wants a Kotlin (Int, Int) -> Float, and Img.mapIndexed
// wants an (Int, Int, Int, Float) -> Float. Those shapes exist only on the
// *use site's* generic type — `Function4.invoke` itself erases to Objects — so
// reading them there is what makes `x + y` below integer arithmetic.
let flat = Img |> hostGet "Companion" |> hostCall "generate" (220, 220, fun x y -> 0.0)

let rings =
  flat |> hostCall1 "mapIndexed" (fun x y c v ->
    let u = (toFloat x - 110.0) / 110.0 in
    let w = (toFloat y - 110.0) / 110.0 in
    let r = sqrt (u * u + w * w) in
    sin (r * 13.0 - toFloat ((x + y) |> mod 9) * 0.2) * exp (0.0 - r * 1.2) * 0.5 + 0.5
  )

let coloured = rings |> hostExt Imaging "colormap" ("magma", hostNull, hostNull)

hostStaticCall "showImg" (thisBox, coloured, "main", 330, "Img.mapIndexed, four typed parameters") Imaging