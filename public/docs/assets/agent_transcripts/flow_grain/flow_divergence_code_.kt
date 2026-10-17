// Dependencies are explicit graph exports; resources stay in their parent box.

var module = computeModuleHere {}

val mesh = ComputeMesh()
module += "dispatch" to mesh
mesh.x = 225 // 14400 cells / 64 threads.
mesh.y = 1
mesh.source = { advected }
mesh.target = { divergence }
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
flowSimulation += "divergence" to module
loop { true }
    .end {
        if (flowSimulation.modules["divergence"] === module) flowSimulation.modules.remove("divergence")
    }
