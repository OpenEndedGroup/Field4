import afield.four.modules.fromContextWithNameOrNull
import afield.four.buffers.VulkanBuffer
import afield.four.modules.computeMemoryBarrier
import afield.four.modules.graphicsMemoryBarrier
import afield.four.modules.computeTask

// Dependencies are explicit graph exports; resources stay in their parent box.
val flowA = fromContextWithNameOrNull<FrameBufferGroup>("flowA")!!
val flowB = fromContextWithNameOrNull<FrameBufferGroup>("flowB")!!
val particleA = fromContextWithNameOrNull<FrameBufferGroup>("particleA")!!
val particleB = fromContextWithNameOrNull<FrameBufferGroup>("particleB")!!
val tileHeaders = fromContextWithNameOrNull<FrameBufferGroup>("tileHeaders")!!
val flowClock = fromContextWithNameOrNull<afield.stdlib.Vec4>("flowClock")!!
val flowEmitter = fromContextWithNameOrNull<afield.stdlib.Vec4>("flowEmitter")!!
val flowSimulation = fromContextWithNameOrNull<afield.four.targets.ComputeTarget>("flowSimulation")!!
val flowStep = fromContextWithNameOrNull<java.util.concurrent.atomic.AtomicLong>("flowStep")!!

var module = computeModuleHere {}

val mesh = ComputeMesh()
module += "advect particles" to mesh
mesh.x = 250
mesh.y = 1
mesh.source = { if (flowStep.get() % 2L == 0L) particleA else particleB }
mesh.target = { if (flowStep.get() % 2L == 0L) particleB else particleA }
mesh.auxiliary = { if (flowStep.get() % 2L == 0L) flowB else flowA }
mesh.tiles = { tileHeaders }
mesh.clock = { flowClock }
mesh.emitter = { flowEmitter }
module += "barrier" to computeTask { _, cmd, _ ->
    computeMemoryBarrier(cmd, null as VulkanBuffer?)
    graphicsMemoryBarrier(cmd, null as VulkanBuffer?)
}
flowSimulation += "particles" to module
loop { true }.end {
    if (flowSimulation.modules["particles"] === module) flowSimulation.modules.remove("particles")
}
