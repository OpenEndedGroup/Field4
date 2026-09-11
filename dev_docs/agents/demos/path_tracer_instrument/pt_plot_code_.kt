// Samples against wall seconds, with a labelled dashed rule wherever accumulation
// restarted and why. Run box 3 or 4 first and re-run this to see a mark appear.
//
// This is one call to `showSignals`, the house terse-plot helper. There is no new plotting
// code anywhere in this feature: `showSignals` already takes named series, a shared x, and
// `marks` drawn as full-height dashed rules carrying their label into the legend.
import afield.four.fabricated.*

prop<PathTracingRenderer>(tracer)
prop<PathTraceConvergence>(log)

println(log)
println("resets so far: " + log.resetMarks())
showConvergence(log)
