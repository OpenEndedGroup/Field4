import afield.syphon.*

// without the library this stops here and says how to build it
check(Syphon.isAvailable) { Syphon.describe() }

// a server's name, app name or uuid
val inp = syphonClient("Field GPU")
prop<LoadedTexture>(syphonIn) = inp.texture().texture

val module = moduleHere()
val quad = Mesh()
module += "quad" to quad
renderer += "syphonView" to module
quad.set(4, 2) {
    pos.add(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)
    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}
inp
