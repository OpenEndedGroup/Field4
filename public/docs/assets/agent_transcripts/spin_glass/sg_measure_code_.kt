val L = sgL
val N = L * L
val groups = (N + 63) / 64

// call fieldProbeHere BEFORE computeModuleHere so attachFieldProbe wires the SSBO.
// samples >= N keeps every site each frame, so the reported means are exact.
fieldProbeHere(samples = if (N < 262144) N else 262144).display("stats")
displayOpen("stats")

var module = computeModuleHere {}
val m = ComputeMesh()
// leading barrier: make the update box's spin writes visible to this read.
module += "sync" to computeBarrier()
module += "measure" to m
renderer += "spinGlassMeasure" to module

m.x = groups
m.y = 1
m.z = 1
m.spins = { sgSpins }
m.jRight = { sgJRight }
m.jDown = { sgJDown }
m.L = {
    L
} // Match the dispatch count captured for this measurement setup.
m.field = { sgField.toFloat() }
println("measure launched N=" + N)
