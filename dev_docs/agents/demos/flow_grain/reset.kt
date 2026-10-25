// Dependencies are explicit graph exports; resources stay in their parent box.
val resetFlow = fromContextWithNameOrNull<afield.runtime.lifecycle.SimulationReset>("resetFlow")!!

// Ordinary execution queues a reset; the existing renderer consumes it once at its boundary.
println("Reset requested: ${resetFlow.request()}")
