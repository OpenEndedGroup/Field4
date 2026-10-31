// 3. compute shader
//
// Runs a compute shader every frame over a small storage buffer and prints the results back on the CPU.
// Needs the renderer window box as its parent.
//
// The shader is in the `compute` property (open it with the editor's property switcher, `f1` or `ctrl-P`).
// It declares two storage buffers, `InputBuffer` and `OutputBuffer`, bound by descriptor. Newer lessons
// ("3b. compute particles") pass buffers as buffer references instead, which is usually the better choice.
var numVec4 = 16

// Storage buffers declared in the shader are allocated by the module; `ssboSizer` returns each one's size
// in bytes (here 16 vec4s of 4 floats of 4 bytes, for both buffers)
var module = computeModuleHere {
    ssboSizer = { ubo ->
        numVec4 * 4 * 4
    }
}

var m = ComputeMesh()
// must match `local_size_x` in the compute shader
var localSize = 16
// number of workgroups to dispatch: enough groups of `localSize` to cover every vec4
m.x = numVec4 / localSize
m.y = 1
m.z = 1
module += "mainInvocation" to m
renderer += "runCompute" to module

var fr = 0
// Write the initial input. `module.InputBuffer` exists because the shader declares that buffer.
module.InputBuffer.mapAndWrite {
    this.clear()
    for (n in 0 until numVec4) {
        this.add(n.toFloat() * 13.0)
    }
    this.clear()
}

frames {
    while (true) {

        // read the first 10 floats of the output back and print them
        module.OutputBuffer.mapAndRead {
            var s = "${fr}:: "
            this.clear()
            for (n in 0 until 10) s += this.get(n).toString() + " "
            print(s)
        }

        fr++
        // wait for the next frame
        frame()
    }
}
