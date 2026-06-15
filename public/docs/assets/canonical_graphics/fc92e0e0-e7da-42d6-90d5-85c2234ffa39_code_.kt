var numVec4 = 2_560_000

var InputBuffer = buffer {
    size = numVec4 * 4 * 4L
    hosted()
    storage()
}
var VertexData = buffer {
    size = numVec4 * 4 * 4L
    //notHosted()
    hosted()
    storage()
}
var DrawingControl = buffer {
    zerod()
}

//%-
var module = computeModuleHere(parameters {
})

var m = ComputeMesh()
var localSize = 16
m.x = numVec4 / localSize
m.y = 1
m.z = 1
module += "mainInvocation" to m
renderer += "runCompute" to module

// could be better
// prop<VulkanBuffer>(buffer) = module.InputBuffer.proxying!!
prop<VulkanBuffer>(buffer) = module.VertexData.proxying!!


var fr = 0
module.InputBuffer.mapAndWrite  {
    this.clear()
    for (n in 0 until numVec4) {
        this.put(Math.random().toFloat()*8)
        this.put(Math.random().toFloat()*8)
        this.put((Math.random().toFloat()-0.5)*8)
        this.put((Math.random().toFloat()-0.5)*8)
    }
    this.clear()
}

frames {
    while(true) {
        fr++

         module.InputBuffer.mapAndRead {
            var s = "${fr}:: "
            this.clear()
            for(n in 0 until 2)
                s+=this.get(100+n).toString()+" "
            _print(s)
        }

        frame()
    }
}
