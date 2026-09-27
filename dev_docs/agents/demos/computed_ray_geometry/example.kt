import afield.controlGroup
import afield.displayOpen
import afield.four.modules.*

prop<ClusterGeometryDemo>(lab)
fromContextWithNameOrNull<ClusterGeometryDemo>("lab")?.close()
check(renderer.device.supportsClusterAccelerationStructure) {
    "This Starting Point needs enabled VK_NV_cluster_acceleration_structure and a ray-tracing pipeline."
}
lab = ClusterGeometryDemo(renderer, producerFactory={ computeModuleHere(it) }).install()
var animate=true
var topology=0
var view=0
controlGroup("computed ray geometry") {
    checkbox("Animate computed vertices",initialValue=true) { animate=it }
    slider("Topology: alternate diagonals",min=0.0,max=1.0,step=1.0,initialValue=0.0) { topology=it.toInt() }
    slider("0: Surface · 1: Clusters · 2: Triangles",min=0.0,max=2.0,step=1.0,initialValue=0.0) { view=it.toInt() }
}
displayOpen("computed ray geometry")
println("Edit the compute shader: it writes the positions, local indices and cluster descriptions. 768 clusters / 98,304 triangles.")
frames {
    while(true) {
        lab.animate=animate;lab.topology=topology;lab.view=view
        frame()
    }
}.end { lab.close() }
