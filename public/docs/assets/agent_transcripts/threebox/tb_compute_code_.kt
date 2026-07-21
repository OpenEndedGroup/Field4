var module = computeModuleHere {}
var m = ComputeMesh()
module += "step" to m
renderer += "compute" to module
val N = 100000
var inData = defaultBuffer { size = N.vec4s }.buffer
var outData = defaultBuffer { size = N.vec4s }.buffer
m.inData = { inData }
m.outData = { outData }
var x = 0.0
m.something = { x += 0.01; x.f }
fieldProbeHere(samples = 4096).display("probe")
displayOpen("probe")   // mark the channel open so the card starts expanded when it arrives
prop<FrameBufferGroup>(outputPositions) = outData
m.x = N / 64; m.y = 1; m.z = 1
inData.write { for (i in 0 until N) put(vec(Math.random(), Math.random(), Math.random(), Math.random())) }
println("compute launched, N=" + N)
