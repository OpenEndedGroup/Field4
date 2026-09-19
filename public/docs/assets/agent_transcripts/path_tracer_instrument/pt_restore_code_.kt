// The same no-argument call that answered UNCHANGED in box 3 now RELOADS, because the live
// pipeline is the patched one. Nothing about the tracer had to be rebuilt to get here: same
// scene, same environment, same accumulation buffers, same camera.
import afield.four.fabricated.*

prop<PathTracingRenderer>(tracer)

println(tracer.reloadIntegrator())
println("reset: '" + tracer.lastResetReason + "' generation=" + tracer.resetGeneration)

// And the instrument again, so you can read the generation count going up and the reason
// changing. Re-run box 2 to see the reset land on the convergence curve as a labelled rule.
display("output", tracer)
