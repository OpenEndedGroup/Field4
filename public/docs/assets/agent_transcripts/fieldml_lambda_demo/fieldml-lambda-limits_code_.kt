// The other half of the story, and the honest half. Reflection cannot always
// say what a callback will be handed, and where it cannot the parameter keeps
// the open type variable it has always had — claiming a type there would
// refuse programs that work today.
let Collections = hostClass "java.util.Collections"

{
  // Comparator<? super T>: T is erased at the use site, so `a` and `b` stay
  // open. They are still perfectly usable — inferred from the body, as before.
  reversedMax = hostStaticCall "max" ([5, 1, 9, 3], fun a b -> b - a) Collections |> hostAsInt

  // FLine.byFiltering((Vec3?, Node) -> Boolean). The first parameter really can
  // arrive null, so it stays open; the second is not nullable and is typed.
  kept =
    fline |> moveTo 0.0 0.0 |> lineTo 10.0 0.0 |> lineTo 20.0 8.0
      |> hostCall1 "byFiltering" (fun previous node -> true)
      |> hostGet "nodes" |> length

  // And the third case: a receiver that is a *function parameter* has no known
  // class yet, so neither the callback inside `let f line = ...` nor the result
  // of calling it gets a type. `hostAs` remains the way out of that one, and it
  // is needed twice here — once going in, once coming out.
  viaHelper =
    let scale line =
      line |> hostCall1 "transform" (fun raw ->
        let p = hostAs "Vec3" raw in
        vec3 (p.x * 0.5) p.y p.z
      )
    in scale (fline |> moveTo 0.0 0.0 |> lineTo 10.0 0.0)
         |> hostAs "FLine" |> hostGet "nodes" |> length
}