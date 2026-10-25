// the webcam frame box 1 publishes, filling the window
prop<LoadedTexture>(camera) = NumpyShared.image("camera").texture()

var module = moduleHere {}
var m = Mesh()
module += m named "camera"
renderer += module

// Field's NDC is y-up and an image's v runs down, so the top edge (y = 1) samples v = 0
m.set(4, 6) {
    pos.add(-1, -1, 0.9, 1, -1, 0.9, 1, 1, 0.9, -1, 1, 0.9)
    tc.add(0, 1, 1, 1, 1, 0, 0, 0)
    tri.add(0, 1, 2, 0, 2, 3)
}
