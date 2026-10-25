import afield.io.syphon.*

// without the library this stops here and says how to build it
check(Syphon.isAvailable) { Syphon.describe() }

prop<RenderToTexture>(syphonTarget) = RenderToTexture {
    width = 640
    height = 360
}
renderer.renderList += "syphonTarget" to syphonTarget

// a quad over the whole target, coloured by its texture coordinates
val module = moduleHere()
val quad = Mesh()
module += "quad" to quad
syphonTarget += "gradient" to module
quad.set(4, 2) {
    pos.add(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)
    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}

// every frame syphonTarget renders goes to Syphon, copied on the GPU
syphonServer("Field GPU").followOnGpu(syphonTarget)
