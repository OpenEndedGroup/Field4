// THE LIVE PARAMETER. Run this while the scene above is spinning.
//
// `setRootProp` puts the slot on the *document root*, so every box in the document
// shares the one value -- which is how a sibling box reaches the scene's `eachFrame`
// body. That body reads it every frame, so the speed of a running Vulkan scene
// changes without the scene box being re-run, re-compiled, or touched.
//
// Change the number and run this box again. Nothing else moves.
let _ = setRootProp "spinRate" 0.30

{
  spinRate = rootProp "spinRate" |> hostAsFloat
  // ...and proof the loop is running: the scene box writes its frame number into a
  // shared slot every frame, and this reads it. Run this box twice; the number grows.
  framesDrawn = propOr "framesDrawn" 0 |> hostAsInt
}