// Samples against wall seconds, with labelled rules where accumulation restarted.
// Run the swap or restore box, then re-run this convergence box to see the reset.
import afield.graphics.scene.fabricated.*

prop<PathTracingRenderer>(tracer)
prop<PathTraceConvergence>(log)

println(log)
println("resets so far: " + log.resetMarks())
showConvergence(log)
