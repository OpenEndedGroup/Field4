// Rebuild update, measure and view after changing the lattice size or bond model.
// Periodic checkerboard neighbours need a positive, even side length.
val latticeSide = 128
require(latticeSide > 0 && latticeSide % 2 == 0) { "L must be positive and even" }
val N = latticeSide * latticeSide
val groups = (N + 63) / 64

// Published controls. To set temperature live, first run sgAnneal = false.
prop<Int>(sgL) = latticeSide
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
val model = sgModel // Sampled while building bonds; changing it requires rebuilding.

// in-place lattice state (single, persistent copy — not per-frame)
val spinData = defaultBuffer { size = N.floats }.buffer
val rightBonds = defaultBuffer { size = N.floats }.buffer
val downBonds = defaultBuffer { size = N.floats }.buffer

val rng = java.util.Random(20260720L)
/*
random initial spins, then bonds: Gaussian when sgModel == 1, else +/-1.
The bond draw is written out twice because a local `fun` that reads a prop
fails K2 codegen in a box script.
*/
spinData.write { for (i in 0 until N) put(if (rng.nextBoolean()) 1f else -1f) }
rightBonds.write {
    for (i in 0 until N) put(if (model == 1) rng.nextGaussian().toFloat() else if (rng.nextBoolean()) 1f else -1f)
}
downBonds.write {
    for (i in 0 until N) put(if (model == 1) rng.nextGaussian().toFloat() else if (rng.nextBoolean()) 1f else -1f)
}

prop<FrameBufferGroup>(sgSpins) = spinData
prop<FrameBufferGroup>(sgJRight) = rightBonds
prop<FrameBufferGroup>(sgJDown) = downBonds

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
module += "barrierE" to computeBarrier()
module += "odd" to odd
// Publish the completed sweep to the measure pass and the fragment shader.
module += "barrierO" to computeBarrier(graphics = true)
renderer += "spinGlassUpdate" to module

// both half-sweeps share bindings; they differ only in checkerboard colour and seed
bindPush(even, odd) {
    x = groups
    y = 1
    z = 1
    spins = { spinData }
    jRight = { rightBonds }
    jDown = { downBonds }
    L = { latticeSide }
    mode = { sgMode }
    field = { sgField.toFloat() }
    temperature = { T.toFloat() }
}
even.color = { 0 }
odd.color = { 1 }
even.seed = { seed.toUInt() }
// Offset the seed so the two passes draw different randoms.
odd.seed = { (seed + 100003).toUInt() }

println("update launched L=" + latticeSide + " N=" + N + " groups=" + groups)
