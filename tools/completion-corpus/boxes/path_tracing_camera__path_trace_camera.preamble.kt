import afield.four.Renderer
import afield.four.util.Window
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.fabricated.PathTracingRenderer
var Box.tracer by Inheritance<PathTracingRenderer>() /**/
val Once.tracer by Onceing<PathTracingRenderer>() /**/
/*start*/
/*compute-structure-type:Triangle:Triangle*/
class Triangle(
    members: List<Introspected.Member> = TriangleLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var a by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var b by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var c by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var na by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var nb by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var nc by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var uv by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var color by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun TriangleByteSize(): Long = 208L
fun TriangleLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("a", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("b", 16, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("c", 32, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("na", 48, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("nb", 64, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("nc", 80, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("uv", 96, PrimitiveType.vec4.left(), 16, false),
        Introspected.Member("color", 192, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asTriangle(): Triangle =
    asStructure(Triangle(), TriangleLayoutMembers())
fun BufferParameters.asTriangle(): Triangle =
    buffer.asTriangle()
/*end*/
/*start*/
/*compute-structure-type:Node:Node*/
class Node(
    members: List<Introspected.Member> = NodeLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var lo by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var hi by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var data by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun NodeByteSize(): Long = 48L
fun NodeLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("lo", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("hi", 16, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("data", 32, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asNode(): Node =
    asStructure(Node(), NodeLayoutMembers())
fun BufferParameters.asNode(): Node =
    buffer.asNode()
/*end*/
/*start*/
/*compute-structure-type:Material:Material*/
class Material(
    members: List<Introspected.Member> = MaterialLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var base by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var emission by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var optical by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var absorption by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var maps by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var coverage by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var textureParams by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun MaterialByteSize(): Long = 112L
fun MaterialLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("base", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("emission", 16, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("optical", 32, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("absorption", 48, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("maps", 64, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("coverage", 80, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("textureParams", 96, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asMaterial(): Material =
    asStructure(Material(), MaterialLayoutMembers())
fun BufferParameters.asMaterial(): Material =
    buffer.asMaterial()
/*end*/
/*start*/
/*compute-structure-type:Triangles:Triangles*/
class Triangles(
    members: List<Introspected.Member> = TrianglesLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val items: Material
        get() = childStructure("items") { members, baseOffset, pathPrefix, root ->
            Material(members, baseOffset, pathPrefix, root)
        }
}
fun TrianglesByteSize(): Long = 112L
fun TrianglesLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("items", 0, MaterialLayoutMembers().right(), 0, false),
    )
fun HasBuffer.asTriangles(): Triangles =
    asStructure(Triangles(), TrianglesLayoutMembers())
fun BufferParameters.asTriangles(): Triangles =
    buffer.asTriangles()
/*end*/
/*start*/
/*compute-structure-type:Nodes:Nodes*/
class Nodes(
    members: List<Introspected.Member> = NodesLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val items: Material
        get() = childStructure("items") { members, baseOffset, pathPrefix, root ->
            Material(members, baseOffset, pathPrefix, root)
        }
}
fun NodesByteSize(): Long = 112L
fun NodesLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("items", 0, MaterialLayoutMembers().right(), 0, false),
    )
fun HasBuffer.asNodes(): Nodes =
    asStructure(Nodes(), NodesLayoutMembers())
fun BufferParameters.asNodes(): Nodes =
    buffer.asNodes()
/*end*/
/*start*/
/*compute-structure-type:Materials:Materials*/
class Materials(
    members: List<Introspected.Member> = MaterialsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val items: Material
        get() = childStructure("items") { members, baseOffset, pathPrefix, root ->
            Material(members, baseOffset, pathPrefix, root)
        }
}
fun MaterialsByteSize(): Long = 112L
fun MaterialsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("items", 0, MaterialLayoutMembers().right(), 0, false),
    )
fun HasBuffer.asMaterials(): Materials =
    asStructure(Materials(), MaterialsLayoutMembers())
fun BufferParameters.asMaterials(): Materials =
    buffer.asMaterials()
/*end*/
/*start*/
/*compute-structure-type:Accumulation:Accumulation*/
class Accumulation(
    members: List<Introspected.Member> = AccumulationLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val items: Material
        get() = childStructure("items") { members, baseOffset, pathPrefix, root ->
            Material(members, baseOffset, pathPrefix, root)
        }
}
fun AccumulationByteSize(): Long = 112L
fun AccumulationLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("items", 0, MaterialLayoutMembers().right(), 0, false),
    )
fun HasBuffer.asAccumulation(): Accumulation =
    asStructure(Accumulation(), AccumulationLayoutMembers())
fun BufferParameters.asAccumulation(): Accumulation =
    buffer.asAccumulation()
/*end*/
/*start*/
/*compute-structure-type:Distribution:Distribution*/
class Distribution(
    members: List<Introspected.Member> = DistributionLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val items: Material
        get() = childStructure("items") { members, baseOffset, pathPrefix, root ->
            Material(members, baseOffset, pathPrefix, root)
        }
}
fun DistributionByteSize(): Long = 112L
fun DistributionLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("items", 0, MaterialLayoutMembers().right(), 0, false),
    )
fun HasBuffer.asDistribution(): Distribution =
    asStructure(Distribution(), DistributionLayoutMembers())
fun BufferParameters.asDistribution(): Distribution =
    buffer.asDistribution()
/*end*/
/*start*/
/*compute-structure-type:Directions:Directions*/
class Directions(
    members: List<Introspected.Member> = DirectionsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val items: Material
        get() = childStructure("items") { members, baseOffset, pathPrefix, root ->
            Material(members, baseOffset, pathPrefix, root)
        }
}
fun DirectionsByteSize(): Long = 112L
fun DirectionsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("items", 0, MaterialLayoutMembers().right(), 0, false),
    )
fun HasBuffer.asDirections(): Directions =
    asStructure(Directions(), DirectionsLayoutMembers())
fun BufferParameters.asDirections(): Directions =
    buffer.asDirections()
/*end*/
/*start*/
/*compute-structure-type:TextureData:TextureData*/
class TextureData(
    members: List<Introspected.Member> = TextureDataLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val items: Material
        get() = childStructure("items") { members, baseOffset, pathPrefix, root ->
            Material(members, baseOffset, pathPrefix, root)
        }
}
fun TextureDataByteSize(): Long = 112L
fun TextureDataLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("items", 0, MaterialLayoutMembers().right(), 0, false),
    )
fun HasBuffer.asTextureData(): TextureData =
    asStructure(TextureData(), TextureDataLayoutMembers())
fun BufferParameters.asTextureData(): TextureData =
    buffer.asTextureData()
/*end*/
/*start*/
/*compute-structure-type:SceneBuffers:SceneBuffers*/
class SceneBuffers(
    members: List<Introspected.Member> = SceneBuffersLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var triangles by Structure.FromStructure<afield.four.buffers.HasBuffer>()
    var nodes by Structure.FromStructure<afield.four.buffers.HasBuffer>()
    var materials by Structure.FromStructure<afield.four.buffers.HasBuffer>()
    var accumulation by Structure.FromStructure<afield.four.buffers.HasBuffer>()
    var distribution by Structure.FromStructure<afield.four.buffers.HasBuffer>()
    var directions by Structure.FromStructure<afield.four.buffers.HasBuffer>()
    var textures by Structure.FromStructure<afield.four.buffers.HasBuffer>()
}
fun SceneBuffersByteSize(): Long = 56L
fun SceneBuffersLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("triangles", 0, PrimitiveType.deviceptr.left(), 0, true),
        Introspected.Member("nodes", 8, PrimitiveType.deviceptr.left(), 0, true),
        Introspected.Member("materials", 16, PrimitiveType.deviceptr.left(), 0, true),
        Introspected.Member("accumulation", 24, PrimitiveType.deviceptr.left(), 0, true),
        Introspected.Member("distribution", 32, PrimitiveType.deviceptr.left(), 0, true),
        Introspected.Member("directions", 40, PrimitiveType.deviceptr.left(), 0, true),
        Introspected.Member("textures", 48, PrimitiveType.deviceptr.left(), 0, true),
    )
fun HasBuffer.asSceneBuffers(): SceneBuffers =
    asStructure(SceneBuffers(), SceneBuffersLayoutMembers())
fun BufferParameters.asSceneBuffers(): SceneBuffers =
    buffer.asSceneBuffers()
/*end*/
/*start*/
/*compute-structure-type:Frame:Frame*/
class Frame(
    members: List<Introspected.Member> = FrameLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var origin by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var right by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var up by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var forward by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    var image by Structure.FromStructure<afield.stdlib.linalg.Vector4f>() // width, height, first sample index, samples per dispatch
    var scene by Structure.FromStructure<afield.stdlib.linalg.Vector4f>() // node count, environment width, environment height, max bounces
    var lighting by Structure.FromStructure<afield.stdlib.linalg.Vector4f>() // environment intensity, yaw radians, ray epsilon, show environment background
    var background by Structure.FromStructure<afield.stdlib.linalg.Vector4f>() // premultiplied linear RGBA for primary misses
    var sampling by Structure.FromStructure<afield.stdlib.linalg.Vector4f>() // indirect clamp, pixel jitter width, aperture radius, focus-plane distance
}
fun FrameByteSize(): Long = 144L
fun FrameLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("origin", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("right", 16, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("up", 32, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("forward", 48, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("image", 64, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("scene", 80, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("lighting", 96, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("background", 112, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("sampling", 128, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asFrame(): Frame =
    asStructure(Frame(), FrameLayoutMembers())
fun BufferParameters.asFrame(): Frame =
    buffer.asFrame()
/*end*/
/*compute-ubo:SceneBuffers*/ val ModulePointer.SceneBuffers by FromUniform()
/*compute-ubo-mesh:SceneBuffers*/ var Mesh.SceneBuffers by Mesh.FromStructure<Structure>()
/*compute-ubo-member:SceneBuffers:triangles*/ var Structure.triangles by Structure.FromStructure<afield.four.buffers.HasBuffer>()
/*compute-ubo-member:SceneBuffers:nodes*/ var Structure.nodes by Structure.FromStructure<afield.four.buffers.HasBuffer>()
/*compute-ubo-member:SceneBuffers:materials*/ var Structure.materials by Structure.FromStructure<afield.four.buffers.HasBuffer>()
/*compute-ubo-member:SceneBuffers:accumulation*/ var Structure.accumulation by Structure.FromStructure<afield.four.buffers.HasBuffer>()
/*compute-ubo-member:SceneBuffers:distribution*/ var Structure.distribution by Structure.FromStructure<afield.four.buffers.HasBuffer>()
/*compute-ubo-member:SceneBuffers:directions*/ var Structure.directions by Structure.FromStructure<afield.four.buffers.HasBuffer>()
/*compute-ubo-member:SceneBuffers:textures*/ var Structure.textures by Structure.FromStructure<afield.four.buffers.HasBuffer>()
/*compute-ubo:Frame*/ val ModulePointer.Frame by FromUniform()
/*compute-ubo-mesh:Frame*/ var Mesh.Frame by Mesh.FromStructure<Structure>()
/*compute-ubo-member:Frame:origin*/ var Structure.origin by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
/*compute-ubo-member:Frame:right*/ var Structure.right by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
/*compute-ubo-member:Frame:up*/ var Structure.up by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
/*compute-ubo-member:Frame:forward*/ var Structure.forward by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
/*compute-ubo-member:Frame:image*/ var Structure.image by Structure.FromStructure<afield.stdlib.linalg.Vector4f>() // width, height, first sample index, samples per dispatch
/*compute-ubo-member:Frame:scene*/ var Structure.scene by Structure.FromStructure<afield.stdlib.linalg.Vector4f>() // node count, environment width, environment height, max bounces
/*compute-ubo-member:Frame:lighting*/ var Structure.lighting by Structure.FromStructure<afield.stdlib.linalg.Vector4f>() // environment intensity, yaw radians, ray epsilon, show environment background
/*compute-ubo-member:Frame:background*/ var Structure.background by Structure.FromStructure<afield.stdlib.linalg.Vector4f>() // premultiplied linear RGBA for primary misses
/*compute-ubo-member:Frame:sampling*/ var Structure.sampling by Structure.FromStructure<afield.stdlib.linalg.Vector4f>() // indirect clamp, pixel jitter width, aperture radius, focus-plane distance
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
