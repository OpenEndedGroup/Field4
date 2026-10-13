// A LIVE PARAMETER, WITH NO KOTLIN ANYWHERE.
//
// `prop<Double>(swirl)` in a Kotlin box is a source macro: it generates
// `var Box.swirl by Inheritance<Double>()` into the box's preamble. There is no
// function to call, so FieldML implements the delegate instead — the same
// breadth-first walk over `parents`, the same slot, the same box property the
// inspector shows. A Kotlin box's `swirl` and this box's `prop "swirl"` are one
// property.
//
// EDIT EITHER NUMBER AND RE-RUN THIS BOX, then re-run the box below.
let _ = setProp "swirl" 0.85
let _ = setProp "turns" 4

// `once` is the other generated delegate — get, or compute and store. Re-running
// this box does not change it, because the slot is no longer empty.
let firstRun =
  once "firstRunAt" (fun ignored ->
    hostClass "afield.AnimationClock" |> hostGet "INSTANCE" |> hostCall0 "nowSeconds"
  )

{
  swirl = prop "swirl" |> hostAsFloat
  turns = prop "turns" |> hostAsInt
  firstRunAt = firstRun |> hostAsFloat
  // `propOr` is the read that does not fail when nothing up the graph has it.
  missing = propOr "neverWritten" "not set" |> hostAsString
}