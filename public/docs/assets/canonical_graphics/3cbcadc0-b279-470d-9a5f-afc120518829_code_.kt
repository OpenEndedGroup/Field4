import afield.four.texture.*
var bbc = LoadedTexture("/Users/marc/Desktop/bbc.jpg")
bbc.transferNow()

var bbc2 = LoadedTexture("/Users/marc/Desktop/bbc2.jpg")
bbc2.transferNow()

var asArray : Array<ImageView> = arrayOf(bbc.imageView, bbc2.imageView)

prop<Array<ImageView>>(ta) = asArray

var module = moduleHere {}
var m = Mesh()


module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m

var kc = KeyCamera(camera=camera)
m.set(4, 2) {
    val z = 0.0

    pos.add(0,0,0+z,
            1.0,0.1,z,
            1,1,z,
            0,1,z)

    tc.add(0,0,
           1,0,
           1,1,
           0,1)

    tri.add(0,1,2)
    tri.add(0,2,3)
}
