import afield.document.context.fromContextWithNameOrNull

// Dependencies are explicit graph exports; resources stay in their parent box.

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
