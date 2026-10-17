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
module +=
    "barrier" to
        computeTask { _, cmd, _ ->
            computeMemoryBarrier(cmd, null as VulkanBuffer?)
            graphicsMemoryBarrier(cmd, null as VulkanBuffer?)
        }
flowSimulation += "advect" to module
loop { true }
    .end {
        if (flowSimulation.modules["advect"] === module) flowSimulation.modules.remove("advect")
    }
