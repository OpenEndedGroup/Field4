// Everything below is inferred. Put the caret inside a lambda body and ask for
// completion, or misspell a member, and the box answers from the JVM signature
// the callback is going to satisfy.
let Img = hostClass "afield.media.image.Img"

// `x` and `y` are Ints because Function2's type arguments say so, so `x * 4 + y`
// is integer arithmetic and `toFloat` is needed to leave it.
let img = Img |> hostGet "Companion" |> hostCall "generate" (8, 4, fun x y -> toFloat (x * 4 + y) / 32.0)

// `p` is a Vec3, so `normalize` and `vlength` are checked vector operations
// rather than dynamic calls that would only fail on the first run.
let unit =
  fline |> moveTo 0.0 0.0 |> lineTo 3.0 4.0
    |> hostCall1 "transform" (fun p -> normalize p * (vlength p / 5.0))

{
  nodes = unit |> hostGet "nodes" |> length
  width = img.width
  height = img.height
  sample = img |> hostCall "at" (3, 1, 0)
}