var numVec4 = 2560

var module = computeModuleHere(parameters {
    ssboSizer = { ubo ->
        numVec4 * 4 * 4
    }
})

var m = ComputeMesh()
var localSize = 16
m.x = numVec4 / localSize
m.y = 1
m.z = 1
module += "mainInvocation" to m
renderer += "runCompute" to module

// could be better
prop<VulkanBuffer>(buffer) = module.InputBuffer.proxying!!


var fr = 0
module.InputBuffer.mapAndWrite  {
    this.clear()
    for (n in 0 until numVec4) {
        this.add(Math.random().toFloat())
        this.add(Math.random().toFloat())
        this.add(Math.random().toFloat()-0.5)
        this.add(Math.random().toFloat()-0.5)
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
                s+=this.get(n).toString()+" "
            _print(s)
        }

        frame()
    }
}
