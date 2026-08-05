var module = computeModuleHere {}
var m = ComputeMesh()
module += "step" to m
renderer += "compute" to module
val N = 100000
var inData = defaultBuffer { size = N.vec4s }.buffer
var outData = defaultBuffer { size = N.vec4s }.buffer
m.inData = { inData }
m.outData = { outData }
// The live renderer uses monotonic time; frame-synchronous docs capture replaces this
// with frameIndex/fps, so an already-running multi-box scene still restarts at movie t=0.
m.something = { (AnimationClock.nowSeconds() * 0.6).f }
// Full coverage makes the published probe card independent of GPU workgroup ordering;
// summaryHtml canonicalizes the scalar display order before plotting it.
fieldProbeHere(samples = 100000).display("probe")
displayOpen("probe")   // mark the channel open so the card starts expanded when it arrives
prop<FrameBufferGroup>(outputPositions) = outData
m.x = N / 64; m.y = 1; m.z = 1
val seeded = kotlin.random.Random(0x4f1e1d)
inData.write {
    for (i in 0 until N) {
        put(vec(seeded.nextDouble(), seeded.nextDouble(), seeded.nextDouble(), seeded.nextDouble()))
    }
}
println("compute launched, N=" + N)
