// A compute shader moves 100,000 particles; the probe plots how far each one moved.
var module = computeModuleHere {}
var m = ComputeMesh()
module += "step" to m
renderer += "compute" to module
val N = 100000
var inData = defaultBuffer { size = N.vec4s }.buffer
var outData = defaultBuffer { size = N.vec4s }.buffer
m.inData = { inData }
m.outData = { outData }
m.particleCount = { N }
m.something = { (AnimationClock.nowSeconds() * 0.6).f }
fieldProbeHere(samples = 100000).display("probe")
displayOpen("probe")
prop<FrameBufferGroup>(outputPositions) = outData
m.x = (N + 63) / 64
m.y = 1
m.z = 1
val seeded = kotlin.random.Random(0x4f1e1d)
inData.write {
    for (i in 0 until N) {
        put(vec(seeded.nextDouble(), seeded.nextDouble(), seeded.nextDouble(), seeded.nextDouble()))
    }
}
println("compute launched, N=" + N)
