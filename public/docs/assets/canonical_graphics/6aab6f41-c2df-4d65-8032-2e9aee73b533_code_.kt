import org.joml.primitives.Rectanglef
var positions = buffer {
    size = splatNum.vec4s
    addressable()
    hosted()
}.buffer

positions.mapAndWrite {
    this.put(splatPos)
}

var dists = buffer {
    size = splatNum.ints
    addressable()
    hosted()
}.buffer

var indices = buffer {
    size = splatNum.ints
    storage()
    addressable()
    hosted()
}.buffer

var indirectBuffer = buffer {
    size = 8.ints
    storage()
    addressable()
    hosted()
    dest()
}.buffer

prop<VulkanBuffer>(splatPositions) = positions
prop<VulkanBuffer>(splatDistances) = dists
prop<VulkanBuffer>(splatIndices) = indices
prop<VulkanBuffer>(indirect) = indirectBuffer

var module = computeModuleHere(parameters {
})

var m = ComputeMesh()

module += "zero" to zeroBuffer(indirect)
module += "mainInvocation" to m

m.splatPositions = { positions }
m.splatDistances = { dists }
m.splatIndices = { indices }
m.indirect = { indirectBuffer }
m.splatNum = { splatNum.toUInt() }
m.cameraPosition = { vec(0,0,0,0) }


var workGroupSize = 32
m.x = 1+ splatNum / workGroupSize
m.y = 1
m.z = 1

//renderer += "distanceCompute" to module
renderer.computeList.others += "distanceCompute" to target { cmd, index ->  }

renderer.computeList.others += "distanceCompute" to target { cmd, index ->
    module.render(cmd, Rectanglef())
}

if (false) {
    indirect.mapAndRead_ints {
        print(this.get(1))
    }


    dists.mapAndRead_ints {
        for(n in 0 until 10) {
            System.out.println(get(n)-1317050000)
        }
    }
}
