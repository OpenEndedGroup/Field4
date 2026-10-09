import afield.python.NumpyShared

// box 1's `inset_view`, in the top-right corner
prop<LoadedTexture>(inset) = NumpyShared.image("inset").texture()

var module = moduleHere { }
var m = Mesh()
module += m named "inset"
renderer += module

// Field's NDC is y-up and an image's v runs down, so the top edge (y = 0.98) samples v = 0
m.set(4, 6) {
    pos.add(0.45, 0.45, 0.5,  0.98, 0.45, 0.5,  0.98, 0.98, 0.5,  0.45, 0.98, 0.5)
    tc.add(0, 1,  1, 1,  1, 0,  0, 0)
    tri.add(0, 1, 2,  0, 2, 3)
}
