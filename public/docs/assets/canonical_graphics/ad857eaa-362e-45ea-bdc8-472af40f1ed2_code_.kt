// 3b. compute particles
//
// A compute shader that moves 100,000 particles held in GPU buffers, and publishes the output buffer so a
// child box ("3b. draw particles") can draw it straight from the GPU. Needs the renderer window box as its
// parent.
//
// The shader is in the `compute` property (open it with the editor's property switcher, `f1` or `ctrl-P`).
// It receives both buffers as buffer references in its push constants, and calls `fieldProbe` on one
// value per particle so the distribution of that value can be watched in this box's "probe" output.

// A probe that samples up to 1000 values from the shader and shows them in the "probe" output channel
fieldProbeHere(1000).display("probe")
displayOpen("probe")

var module = computeModuleHere {}

var m = ComputeMesh()

module += "mainMesh" to m
renderer += "module" to module

// N sets the buffer sizes, so changing it reallocates them: stop the draw child first, rerun this box, then
// rerun the draw box.
val N = 100000
var inData = defaultBuffer {
    size = N.vec4s
}
    .buffer

var outData = defaultBuffer {
    size = N.vec4s
}
    .buffer

// `m.inData`, `m.outData`, `m.something` and `m.count` match the push-constant members the compute shader
// declares. Each is a lambda, evaluated again every time the shader is dispatched.
m.inData = { inData }

m.outData = { outData }

var x = 0.0

// an animation clock for the shader, advanced on every dispatch
m.something = {
    x += 0.01
    x.f
}
// Publish the output buffer and the particle count to every box beneath this one
prop<FrameBufferGroup>(outputPositions) = outData
prop<Int>(particleCount) = N

// `count` is a uint in the shader; an Int lambda feeds it. The shader uses it to skip the extra invocations in
// the last workgroup.
m.count = { N }

// Dispatch N / 64 workgroups, rounded up (the shader's `local_size_x` is 64)
m.x = (N + 63) / 64

m.y = 1

m.z = 1

// Random starting values: xy is the position, zw are per-particle rates used by the shader
inData.write {
    for (x in 0 until N) {
        put(vec(Math.random(), Math.random(), Math.random(), Math.random()))
    }
}

// Read the first output value back once and print it
outData.read {
    print(get())
}
