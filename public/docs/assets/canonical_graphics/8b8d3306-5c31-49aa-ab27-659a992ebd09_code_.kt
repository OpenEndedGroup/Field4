// example of a compute shader, again, slightly off path here
// we'd use buffer references here not shader storage now
var numVec4 = 16

// we need a ssboSizer to size and allocate storage for this compute module
var module = computeModuleHere {
    ssboSizer = { ubo ->
        numVec4 * 4 * 4
    }
}

var m = ComputeMesh()
// TODO, would love to parse this
var localSize = 16
//  workgroup size
m.x = numVec4 / localSize
m.y = 1
m.z = 1
module += "mainInvocation" to m
renderer += "runCompute" to module

var fr = 0
// initial input
module.InputBuffer.mapAndWrite  {
    this.clear()
    for (n in 0 until numVec4) {
        this.add(n.toFloat()*13.0)
    }
    this.clear()
}

frames {
    while(true) {

        //print output
        module.OutputBuffer.mapAndRead {
            var s = "${fr}:: "
            this.clear()
            for(n in 0 until 10)
                s+=this.get(n).toString()+" "
            print(s)
        }

        fr++
        frame()
    }
}
