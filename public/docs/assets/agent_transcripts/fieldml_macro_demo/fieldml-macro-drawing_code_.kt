// THE SAME PARAMETER, INHERITED.
//
// This box never writes `swirl`. It reads it, and the read walks up `parents`
// until an ancestor has it — which is what makes `prop` worth having over a
// plain variable. Run the box above with a different number and run this one
// again; nothing here changes.
//
// One thing about the wiring, because it is not guessable: this box has TWO parents.
// `swirl` comes down from the box above, but the FieldML runtime is only applied to the
// *direct* children of a FieldML root (LanguageRuntimeSupport.kt:57), so a grandchild
// would quietly run as Kotlin. Being a child of both is what makes it FieldML *and*
// an inheritor.
let swirl = prop "swirl" |> hostAsFloat
let turns = prop "turns" |> hostAsInt

linspace 0.0 1.0 320
  |> map (fun t ->
       polar (t * tau * toFloat turns) (10.0 + t * 105.0 * swirl) + vec2 125.0 125.0
     )
  |> fold (fun line point -> line |> lineToV point) fline
  |> stroked true
  |> filled false
  |> color (vec4 0.42 0.86 1.0 1.0)
  |> thickness 2.0
  |> drawSized "main" 250.0 250.0