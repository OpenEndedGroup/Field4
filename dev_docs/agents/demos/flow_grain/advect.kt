// Dependencies are explicit graph exports; resources stay in their parent box.

var module = computeModuleHere {}

val mesh = ComputeMesh()
module += "dispatch" to mesh
mesh.x = 225 // 14400 cells / 64 threads.
mesh.y = 1
mesh.source = { if (flowStep.get() % 2L == 0L) flowA else flowB }
mesh.target = { advected }
mesh.auxiliary = { pressureA }
mesh.tiles = { tileHeaders }
mesh.clock = { flowClock }
mesh.emitter = { flowEmitter }
module += "barrier" to computeBarrier(graphics = true)
val simulation = flowSimulation
simulation += "advect" to module
loop {}
    .end {
        if (simulation.modules["advect"] === module) simulation.modules.remove("advect")
    }
