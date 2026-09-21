import afield.four.modules.GltfSupport
import afield.four.modules.GpuSkinner
import afield.four.modules.GpuSkinning
import afield.four.modules.RiggedFixture
import afield.stdlib.linalg.Matrix4f

// Sixty-four characters, one mesh, one draw call, all of them animating -- and not one vertex is
// re-uploaded after setup. Box 4 above is the CPU skinner: it rewrites the packed vertex buffer
// every pose, which costs 2.8 ms for a 50,000-vertex character and cannot be done 64 times.
//
// EDIT `count` AND RE-RUN. The per-frame CPU cost is `count x bones` matrices, so 2 here.
val count = 64

// `renderer` and `window` come from box 0, which publishes them with `prop<Renderer>(renderer)`.
// This box is connected beneath it, so the bare name resolves -- RUN BOX 0 FIRST. Before box 0
// has run there is no Renderer anywhere and this box fails with "must pass in a value for
// context parameter afield.four.Renderer", which is the same footgun box 4 documents.
val model = GltfSupport.loadGltfBasic(
    RiggedFixture.locate(), GltfSupport.GltfLoadConfig(loadTextures = false)
)
val inst = model.meshes.first { it.skin != null }

// The shaders come straight from the library constants, so this box cannot drift from the code
// `GpuSkinningSmoke` grades. `skinnedFragmentShader` rather than the bundled one because the
// bundled glTF fragment shader never reads vColor -- it outputs the normal -- so the per-instance
// tint would be invisible and "the crowd is varied" would be unfalsifiable.
val module = Module(
    Parameters(
        renderer = renderer,
        vertex = GpuSkinning.skinnedVertexShader,
        fragment = GpuSkinning.skinnedFragmentShader,
        name = "gpu skinned crowd"
    )
).pointer()

val m = Mesh(renderer, module)
module += "crowd" to m
renderer += "gpu skinned crowd" to module

val camera = Camera(renderer)
camera.p.orthoness = 0.25
camera.p.matchDepth = 2.0
camera.target = vec(0, 2, 0)
camera.at = vec(6.5, 8.5, -17)
camera.p()

val skinner = GpuSkinner(inst, instances = count, renderer = renderer, name = "crowd")
skinner.bindTo(m, camera, instanceCount = count, tint = true)

// Instance i on an 8x8 grid. The placement is multiplied into that instance's palette, so it
// costs one mul per bone and nothing at all on the GPU -- there is no instance-matrix buffer.
fun place(i: Int): Matrix4f {
    val x = (i % 8 - 3.5f) * 2.2f
    val z = (i / 8 - 3.5f) * 2.2f
    return Matrix4f().translate(vec(x, 0, z))
}

var n = 0
loop {
    val t = n / 60f
    // The whole per-frame CPU cost: `count` palettes of 2 matrices each. Every instance is at its
    // own phase of its own blend of two clips, which is the per-instance clip blending -- it is
    // evaluated here, on the CPU, because it is O(instances x bones) and bones are units.
    skinner.uploadPalettes((0 until count).map { i ->
        skinner.blendedPalette(
            place(i),
            "bend", (t + i * 0.09f) % 2f,
            "curl", (t * 0.7f + i * 0.05f) % 2f,
            mix = i / (count - 1f)
        )
    })
    skinner.updateCamera(m, camera)
    if (n % 120 == 0) println("frame $n: $count instances, ${count * skinner.boneCount} matrices uploaded, one draw call")
    n++
    frame()
}
