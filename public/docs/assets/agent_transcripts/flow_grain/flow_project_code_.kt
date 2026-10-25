// Dependencies are explicit graph exports; resources stay in their parent box.

var module = computeModuleHere {}

val mesh = ComputeMesh()
module += "dispatch" to mesh
mesh.x = 225 // 14400 cells / 64 threads.
mesh.y = 1
mesh.source = { advected }
mesh.target = { if (flowStep.get() % 2L == 0L) flowB else flowA }
mesh.auxiliary = { pressureA }
mesh.tiles = { tileHeaders }
mesh.clock = { flowClock }
mesh.emitter = { flowEmitter }
module += "barrier" to computeBarrier(graphics = true)
flowSimulation += "project" to module
loop { true }
    .end {
        if (flowSimulation.modules["project"] === module) flowSimulation.modules.remove("project")
    }
