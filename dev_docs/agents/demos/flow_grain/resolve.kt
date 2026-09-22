import afield.four.modules.fromContextWithNameOrNull

// Dependencies are explicit graph exports; resources stay in their parent box.
val flowLook = fromContextWithNameOrNull<afield.stdlib.Vec4>("flowLook")!!
val flowSurface = fromContextWithNameOrNull<RenderToTexture>("flowSurface")!!

// The color and motion attachment are both available to future postprocessing boxes.
var module = moduleHere {
    depthTest = false
    depthWrite = false
    primitiveType = triangles
    textures("colorTexture" to flowSurface.imageViewArray[0], "motionTexture" to flowSurface.imageViewArray[1])
}
val mesh = Mesh()
module += "present" to mesh
mesh.look = { flowLook }
mesh.resize(3)
renderer += "flow-resolve" to module
loop { true }.end {
    if (renderer.renderList.modules["flow-resolve"] === module) renderer.renderList.modules.remove("flow-resolve")
}
