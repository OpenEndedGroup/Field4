import afield.four.Renderer
import afield.four.util.Window
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.modules.ClusterGeometryDemo
var Box.lab by Inheritance<ClusterGeometryDemo>() /**/
val Once.lab by Onceing<ClusterGeometryDemo>() /**/
/*start*/
/*compute-structure-type:Cluster:Cluster*/
class Cluster(
    members: List<Introspected.Member> = ClusterLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var range by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var identity by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun ClusterByteSize(): Long = 32L
fun ClusterLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("range", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("identity", 16, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asCluster(): Cluster =
    asStructure(Cluster(), ClusterLayoutMembers())
fun BufferParameters.asCluster(): Cluster =
    buffer.asCluster()
/*end*/
/*start*/
/*compute-structure-type:Positions:Positions*/
class Positions(
    members: List<Introspected.Member> = PositionsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var data by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun PositionsByteSize(): Long = 16L
fun PositionsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("data", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asPositions(): Positions =
    asStructure(Positions(), PositionsLayoutMembers())
fun BufferParameters.asPositions(): Positions =
    buffer.asPositions()
/*end*/
/*start*/
/*compute-structure-type:Indices:Indices*/
class Indices(
    members: List<Introspected.Member> = IndicesLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var data by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun IndicesByteSize(): Long = 16L
fun IndicesLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("data", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asIndices(): Indices =
    asStructure(Indices(), IndicesLayoutMembers())
fun BufferParameters.asIndices(): Indices =
    buffer.asIndices()
/*end*/
/*start*/
/*compute-structure-type:Clusters:Clusters*/
class Clusters(
    members: List<Introspected.Member> = ClustersLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var data by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun ClustersByteSize(): Long = 16L
fun ClustersLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("data", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asClusters(): Clusters =
    asStructure(Clusters(), ClustersLayoutMembers())
fun BufferParameters.asClusters(): Clusters =
    buffer.asClusters()
/*end*/
/*compute-pc-structure:positions*/ var Structure.positions by Structure.FromStructure<Any>()
/*compute-pc-binding:positions*/ var ComputeMesh.positions by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-binding-group:positions*/ var ComputeMeshGroup.positions by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-structure:indices*/ var Structure.indices by Structure.FromStructure<Any>()
/*compute-pc-binding:indices*/ var ComputeMesh.indices by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-binding-group:indices*/ var ComputeMeshGroup.indices by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-structure:descriptions*/ var Structure.descriptions by Structure.FromStructure<Any>()
/*compute-pc-binding:descriptions*/ var ComputeMesh.descriptions by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-binding-group:descriptions*/ var ComputeMeshGroup.descriptions by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-structure:columns*/ var Structure.columns by Structure.FromStructure<Any>()
/*compute-pc-binding:columns*/ var ComputeMesh.columns by ComputeMesh.BoundFromStructure<UInt>()
/*compute-pc-binding-group:columns*/ var ComputeMeshGroup.columns by ComputeMeshGroup.BoundFromStructure<UInt>()
/*compute-pc-structure:rows*/ var Structure.rows by Structure.FromStructure<Any>()
/*compute-pc-binding:rows*/ var ComputeMesh.rows by ComputeMesh.BoundFromStructure<UInt>()
/*compute-pc-binding-group:rows*/ var ComputeMeshGroup.rows by ComputeMeshGroup.BoundFromStructure<UInt>()
/*compute-pc-structure:time*/ var Structure.time by Structure.FromStructure<Any>()
/*compute-pc-binding:time*/ var ComputeMesh.time by ComputeMesh.BoundFromStructure<Float>()
/*compute-pc-binding-group:time*/ var ComputeMeshGroup.time by ComputeMeshGroup.BoundFromStructure<Float>()
/*compute-pc-structure:topology*/ var Structure.topology by Structure.FromStructure<Any>()
/*compute-pc-binding:topology*/ var ComputeMesh.topology by ComputeMesh.BoundFromStructure<UInt>()
/*compute-pc-binding-group:topology*/ var ComputeMeshGroup.topology by ComputeMeshGroup.BoundFromStructure<UInt>()
/*compute-pc-structure:invalidCluster*/ var Structure.invalidCluster by Structure.FromStructure<Any>()
/*compute-pc-binding:invalidCluster*/ var ComputeMesh.invalidCluster by ComputeMesh.BoundFromStructure<Int>()
/*compute-pc-binding-group:invalidCluster*/ var ComputeMeshGroup.invalidCluster by ComputeMeshGroup.BoundFromStructure<Int>()
/*compute-pc-structure:invalidMode*/ var Structure.invalidMode by Structure.FromStructure<Any>()
/*compute-pc-binding:invalidMode*/ var ComputeMesh.invalidMode by ComputeMesh.BoundFromStructure<Int>()
/*compute-pc-binding-group:invalidMode*/ var ComputeMeshGroup.invalidMode by ComputeMeshGroup.BoundFromStructure<Int>()
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
