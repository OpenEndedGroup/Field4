import afield.four.buffers.FrameBufferGroup

var particles = computeModuleHere {}
var step = ComputeMesh()
particles += "step" to step
renderer += "pattern-particles" to particles

val N = 24000

var seeds = defaultBuffer { size = N.vec4s; writeOnce() }.buffer
var positions = defaultBuffer { size = N.vec4s }.buffer
prop<FrameBufferGroup>(outputPositions) = positions

seeds.write {
    for (i in 0 until N) put(vec(Math.random(), Math.random(), 0.0, 0.0))
}

step.seeds = { seeds }
step.outData = { positions }
step.count = { N.toUInt() }   // a uint push member needs a UInt-returning lambda

// The whole point of the chain, in five lines: a push-constant float bound to a lambda that
// reads the band energy the audio box published. The renderer evaluates these once per
// frame, so the shader sees this frame's analysis and nothing is copied by hand.
var t = 0.0
step.time = { t += 1.0 / 60.0; t.f }
step.band0 = { (bandEnergy.getOrElse(0) { 0.0 } * 2.4).f }
step.band1 = { (bandEnergy.getOrElse(1) { 0.0 } * 2.4).f }
step.band2 = { (bandEnergy.getOrElse(2) { 0.0 } * 2.4).f }
step.band3 = { (bandEnergy.getOrElse(3) { 0.0 } * 2.4).f }

step.x = N / 64
step.y = 1
step.z = 1
println("$N particles, four rings, four bands")
