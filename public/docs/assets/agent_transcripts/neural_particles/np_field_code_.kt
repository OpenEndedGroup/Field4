import afield.stdlib.FlowCoupling
import afield.stdlib.FlowMatchOptions
import afield.stdlib.flowMatch
import afield.stdlib.contourComponents
import afield.stdlib.mixtureSource

// Written out in full because `prop<>` rewrites the box's code and the rewrite does not
// carry the snippet's own imports — the same trap the `four_simulation_chain` bundle box
// documents.
prop<afield.stdlib.FlowMatchExport>(neuralExport)

// A word, not a letter — and only because the source has the word's topology. A
// standard Gaussian cannot become five disjoint outlines: it measures a chamfer of
// 13.1 against a floor of 0.8, and no amount of training moves it, because a flow is a
// continuous invertible map and one blob cannot tear into five.
//
// Three things get it to 5.2, and they were each worth a measurement:
//   one ellipse per *closed subpath* (a "D" is two — outer and counter, and treating
//   the glyph as one component costs a factor of two in landing error);
//   each ellipse rotated into phase with the contour it becomes (without that the
//   source is worse than a blob);
//   and no jitter on the source, which sounds like a detail and is a factor of four —
//   thickening the ellipse into an annulus destroys the correspondence that is doing
//   all the work.
//
// `contourComponents` does the first, `mixtureSource` the second, and the third is now
// just the default. Points come back in arc-length order and the pairing is by index,
// which is what makes PAIRED mean anything here.
val contours = layoutText("FIELD", typeface("Helvetica", 260.0)).toFLines()
val pairing = mixtureSource(contourComponents(contours, total = 12000), seed = 5)

val field = flowMatch(
    pairing.to,
    pairing.from,
    FlowMatchOptions(steps = 20000, coupling = FlowCoupling.PAIRED, seed = 4)
)
neuralExport = field.export()

println("trained on ${pairing.size} pairs: loss ${"%.4g".format(field.loss(200))}, ${field.parameterCount} parameters")
