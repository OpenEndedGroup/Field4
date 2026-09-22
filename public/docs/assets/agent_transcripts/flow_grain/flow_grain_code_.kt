import afield.four.modules.fromContextWithNameOrNull

// Dependencies are explicit graph exports; resources stay in their parent box.
val flowA = fromContextWithNameOrNull<FrameBufferGroup>("flowA")!!
val flowB = fromContextWithNameOrNull<FrameBufferGroup>("flowB")!!
val tileHeaders = fromContextWithNameOrNull<FrameBufferGroup>("tileHeaders")!!
val cameraHistory = fromContextWithNameOrNull<FrameBufferGroup>("cameraHistory")!!
val itemHistory = fromContextWithNameOrNull<FrameBufferGroup>("itemHistory")!!
val flowLook = fromContextWithNameOrNull<afield.stdlib.Vec4>("flowLook")!!
val flowClock = fromContextWithNameOrNull<afield.stdlib.Vec4>("flowClock")!!
val flowSurface = fromContextWithNameOrNull<RenderToTexture>("flowSurface")!!
val flowStep = fromContextWithNameOrNull<java.util.concurrent.atomic.AtomicLong>("flowStep")!!

var module = moduleHere {
    flowSurface.apply()
    primitiveType = triangles
    blend = false // Correspondence is a coordinate, never alpha-blended.
    depthTest = false
    depthWrite = false
}
val mesh = Mesh()
module += "grain surface" to mesh
mesh.velocity = { if (flowStep.get() % 2L == 0L) flowB else flowA }
mesh.tiles = { tileHeaders }
mesh.history = { cameraHistory }
mesh.clock = { flowClock }
mesh.look = { flowLook }
mesh.item = { itemHistory }
mesh.resize(3)
flowSurface += "flow-grain" to module
loop { true }.end { if (flowSurface.modules["flow-grain"] === module) flowSurface.modules.remove("flow-grain") }
