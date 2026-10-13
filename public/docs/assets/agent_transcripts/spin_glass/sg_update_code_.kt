import afield.graphics.render.computeMemoryBarrier
import afield.graphics.render.computeTask

val L = 128
val N = L * L
val groups = (N + 63) / 64

// simulation parameters, published so the measure + view boxes can read them
prop<Int>(sgL) = L
prop<Double>(sgTemperature) = 3.0
prop<Double>(sgField) = 0.0
prop<Int>(sgMode) = 0 // 0 = Metropolis, 1 = heat-bath
prop<Int>(sgModel) = 0 // 0 = +/-J bimodal, 1 = Gaussian bonds
prop<Int>(sgSweeps) = 0
prop<Boolean>(sgAnneal) = true

// annealing schedule: temperature swings between Thi and Tlo every `period` sweeps
val Thi = 3.0
val Tlo = 0.04
val period = 6000
val model = sgModel

// in-place lattice state (single, persistent copy — not per-frame)
var spins = defaultBuffer { size = N.floats }.buffer
var jRight = defaultBuffer { size = N.floats }.buffer
var jDown = defaultBuffer { size = N.floats }.buffer

val rng = java.util.Random(20260720L)
/*
random initial spins, then bonds: Gaussian when sgModel == 1, else +/-1.
The bond draw is written out twice because a local `fun` that reads a prop
fails K2 codegen in a box script.
*/
spins.write { for (i in 0 until N) put(if (rng.nextBoolean()) 1f else -1f) }
jRight.write {
    for (i in 0 until N) put(if (model == 1) rng.nextGaussian().toFloat() else if (rng.nextBoolean()) 1f else -1f)
}
jDown.write {
    for (i in 0 until N) put(if (model == 1) rng.nextGaussian().toFloat() else if (rng.nextBoolean()) 1f else -1f)
}

prop<FrameBufferGroup>(sgSpins) = spins
prop<FrameBufferGroup>(sgJRight) = jRight
prop<FrameBufferGroup>(sgJDown) = jDown

var sweep = 0
var seed = 1
var T = Thi

var module = computeModuleHere {}
// a ComputeMesh must be added to its module before x/y/z or push constants are set
val even = ComputeMesh()
val odd = ComputeMesh()

// "tick" runs first each frame: advance the sweep counter + anneal schedule once.
module +=
    "tick" to
        computeTask { _, _, _ ->
            sweep += 1
            seed += 1
            sgSweeps = sweep
            if (sgAnneal) {
                val phase = (sweep % period).toDouble() / period.toDouble()
                T = Tlo + 0.5 * (Thi - Tlo) * (1.0 + Math.cos(2.0 * Math.PI * phase))
                sgTemperature = T
            } else {
                T = sgTemperature
            }
        }
module += "even" to even
module += "barrierE" to computeTask { _, cmd, _ -> computeMemoryBarrier(cmd) }
module += "odd" to odd
module += "barrierO" to computeTask { _, cmd, _ -> computeMemoryBarrier(cmd) }
renderer += "spinGlassUpdate" to module

// both half-sweeps share bindings; they differ only in checkerboard colour and seed
for (m in listOf(even, odd)) {
    m.x = groups
    m.y = 1
    m.z = 1
    m.spins = { spins }
    m.jRight = { jRight }
    m.jDown = { jDown }
    m.L = { L.toUInt() } // uint push members need UInt-returning lambdas
    m.mode = { sgMode.toUInt() }
    m.field = { sgField.toFloat() }
    m.temperature = { T.toFloat() }
}
even.color = { 0u }
odd.color = { 1u }
even.seed = { seed.toUInt() }
odd.seed = {
    (seed + 100003).toUInt()
} // offset so the two passes draw different randoms

println("update launched L=" + L + " N=" + N + " groups=" + groups)
