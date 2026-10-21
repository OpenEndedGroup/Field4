import afield.graphics.scene.fabricated.SplatSet
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Scanlines, on a mesh instead of a cloud. SplatSet.fromGltf reads a .glb through Field's
glTF loader and puts one flat disc on every vertex, facing along its normal and sized
from how densely the vertices cover the surface. Then bright bands sweep up through it.

The model is the Khronos ToyCar sample committed in this repository; the path is
relative to the repository root, so point it at a .glb of your own if Field was started
elsewhere. Textures are not sampled, so the discs start in the material colours.
*/
var module = computeModuleHere {}

val path = "public/docs/assets/fabricate_demo/ToyCar.glb"
if (!java.io.File(path).isFile) {
    println("no model at ${java.io.File(path).absolutePath}: point `path` at a .glb of your own")
} else {
    // fitted(): centred and about a unit across, the size the insert's stripes are written for
    // the ToyCar sample sits on a draped cloth, its "Fabric" mesh; leave that out
    val car = SplatSet.fromGltf(path, meshes = { it != "Fabric" })
    // the wheels and chassis pack most of the vertices near the middle, so frame it larger
    val splats = SplatShader(module, car.fitted()) {
        size = 1.8
    }
    showControls(splats)
}
