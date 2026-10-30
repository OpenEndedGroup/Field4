// Dependencies are explicit graph exports; resources stay in their parent box.
val divergence = fromContextWithNameOrNull<FrameBufferGroup>("divergence")!!
val pressureA = fromContextWithNameOrNull<FrameBufferGroup>("pressureA")!!
val pressureB = fromContextWithNameOrNull<FrameBufferGroup>("pressureB")!!
val tileHeaders = fromContextWithNameOrNull<FrameBufferGroup>("tileHeaders")!!
val flowClock = fromContextWithNameOrNull<afield.math.linalg.Vec4>("flowClock")!!
val flowEmitter = fromContextWithNameOrNull<afield.math.linalg.Vec4>("flowEmitter")!!
val flowSimulation = fromContextWithNameOrNull<afield.graphics.vulkan.targets.ComputeTarget>("flowSimulation")!!

var module = computeModuleHere {}

// The same editable shader performs each iteration. Every dependency has a barrier.
for (i in 0 until 24) {
    val mesh = ComputeMesh()
    module += "relax-$i" to mesh
    mesh.x = 225
    mesh.y = 1
    mesh.source = { if (i % 2 == 0) pressureA else pressureB }
    mesh.target = { if (i % 2 == 0) pressureB else pressureA }
    mesh.auxiliary = { divergence }
    mesh.tiles = { tileHeaders }
    mesh.clock = { flowClock }
    mesh.emitter = { flowEmitter }
    module +=
        "barrier-$i" to
            computeBarrier()
}
flowSimulation += "pressure" to module
loop { true }
    .end {
        if (flowSimulation.modules["pressure"] === module) flowSimulation.modules.remove("pressure")
    }
