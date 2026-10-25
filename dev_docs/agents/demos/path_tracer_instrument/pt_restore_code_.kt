// Restore the packaged integrator after the swap box changes the live pipeline.
// The scene, camera and accumulation buffers are reused.
import afield.graphics.scene.fabricated.*

prop<PathTracingRenderer>(tracer)

println(tracer.reloadIntegrator())
println("reset: '" + tracer.lastResetReason + "' generation=" + tracer.resetGeneration)

// And the instrument again, so you can read the generation count going up and the reason
// changing. Re-run the convergence box to see the reset as a labelled rule.
display("output", tracer)
