fieldProbeHere(1000).display("probe")
displayOpen("probe")

var module = computeModuleHere {}

var m = ComputeMesh()

module += "mainMesh" to m
renderer += "module" to module

// N changes buffer allocation. Stop the draw child first; rerun compute, then draw.
val N = 100000
var inData = defaultBuffer {
    size = N.vec4s
}
    .buffer

var outData = defaultBuffer {
    size = N.vec4s
}
    .buffer

m.inData = { inData }

m.outData = { outData }

var x = 0.0

m.something = {
    x += 0.01
    x.f
}
prop<FrameBufferGroup>(outputPositions) = outData
prop<Int>(particleCount) = N

// uint push members accept Int lambdas; the shader checks the tail group.
m.count = { N }

m.x = (N + 63) / 64

m.y = 1

m.z = 1

inData.write {
    for (x in 0 until N) {
        put(vec(Math.random(), Math.random(), Math.random(), Math.random()))
    }
}

outData.read {
    print(get())
}
