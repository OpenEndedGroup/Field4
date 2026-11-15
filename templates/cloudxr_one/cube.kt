// moduleHere wires the shader tabs, push constants and the standard reload command.
val cubeModule = moduleHere {
    stereo.configure(this)
    depthTest = true
    depthWrite = true
    blend = false
}

val cube = Mesh()
cubeModule += "red-cube" to cube
cube.resize(36, 0) // Six faces, two triangles each; positions come from gl_VertexIndex.
// Leave the centre clear for the editor in IDE mode.
val cubeX = if (System.getenv("FIELD_CLOUDXR_IDE") == "1") 1.15f else 0f
cube.push["centreAndSize"] = vec(cubeX, 0f, -1.5f, .4f)
cube.push["rotation"] = vec(.25f, .5f, 0f, 0f)
stereo.layered += "red-cube" to cubeModule

loop { frame() }.end {
    cubeModule.disable()
    stereo.layered.modules.remove("red-cube", cubeModule)
}
