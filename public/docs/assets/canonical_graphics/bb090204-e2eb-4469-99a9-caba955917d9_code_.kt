prop<ComputeModulePointer>(module)


module = computeModuleHere(parameters {
})

var m = ComputeMesh()
var localSize = 256
m.x = globalInvocationSize
m.y = 1
m.z = 1
module += "mainInvocation" to m
//renderer += "runCompute_sort1" to module // TODO, these should all be attached to a nice thing
