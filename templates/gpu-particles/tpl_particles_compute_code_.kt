// Launch the renderer, then this box, then the draw box under it. This box moves 100 000
// particles on the GPU every frame and publishes their positions as `particlePositions`.
var module = computeModuleHere {}
var m = ComputeMesh()
module += "step" to m
// Keyed by this box's id, so a second copy under the same renderer adds to the first.
renderer += "particles_" + id.take(8) to module

val N = 100000
var inData = defaultBuffer { size = N.vec4s }.buffer
var outData = defaultBuffer { size = N.vec4s }.buffer
m.inData = { inData }
m.outData = { outData }
m.particleCount = { N }
m.something = { (AnimationClock.nowSeconds() * 0.6).f }
prop<FrameBufferGroup>(particlePositions) = outData
m.x = (N + 63) / 64
m.y = 1
m.z = 1

val seeded = kotlin.random.Random(0x4f1e1d)
inData.write {
    for (i in 0 until N) {
        put(vec(seeded.nextDouble(), seeded.nextDouble(), seeded.nextDouble(), seeded.nextDouble()))
    }
}
