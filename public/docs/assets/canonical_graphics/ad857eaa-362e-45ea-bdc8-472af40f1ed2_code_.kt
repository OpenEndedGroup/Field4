var module = computeModuleHere {}
var m = ComputeMesh()

module += "mainMesh" to m
renderer += "module" to module

val N = 10000
var inData = defaultBuffer {
    size = N.vec4s
}.buffer

var outData = defaultBuffer {
    size = N.vec4s
}.buffer

m.inData = { inData }
m.outData = { outData }
var x = 0.0
m.something = {
    x+= 0.01
    x.f
 }


prop<FrameBufferGroup>(outputPositions) = outData

m.x = N / 64
m.y = 1
m.z = 1


 inData.write {
    for(x in 0 until N) {
        put(vec(Math.random(),Math.random(),Math.random(),Math.random()))
    }
}

outData.read {
    print(get())
}