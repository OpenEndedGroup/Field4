import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
var Box.pointModuleFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /**/
val Once.pointModuleFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /**/
val Why.pointModuleFactory by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Marker:MarkerBlock*/
// GLSL block `Marker` is MarkerBlock here: Marker would hide afield.document.graph.Marker.
class MarkerBlock(
    members: List<Introspected.Member> = MarkerBlockLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var position by Structure.FromStructure<afield.math.linalg.Vector4f>() // diagram-local XYZ and radius in metres
    var color by Structure.FromStructure<afield.math.linalg.Vector4f>() // linear RGBA before score-driven opacity
    var shape by Structure.FromStructure<afield.math.linalg.Vector4f>() // SDF kind, ring width, rotation and aspect
}
fun MarkerBlockByteSize(): Long = 48L
fun MarkerBlockLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("position", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("color", 16, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("shape", 32, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asMarkerBlock(): MarkerBlock =
    asStructure(MarkerBlock(), MarkerBlockLayoutMembers())
fun BufferParameters.asMarkerBlock(): MarkerBlock =
    buffer.asMarkerBlock()
/*end*/
/*start*/
/*graphics-structure-type:DiagramMarkers:DiagramMarkers*/
class DiagramMarkers(
    members: List<Introspected.Member> = DiagramMarkersLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val item: MarkerBlock
        get() = childStructure("item") { members, baseOffset, pathPrefix, root ->
            MarkerBlock(members, baseOffset, pathPrefix, root)
        }
}
fun DiagramMarkersByteSize(): Long = 48L
fun DiagramMarkersLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("item", 0, MarkerBlockLayoutMembers().right(), 48, false),
    )
fun HasBuffer.asDiagramMarkers(): DiagramMarkers =
    asStructure(DiagramMarkers(), DiagramMarkersLayoutMembers())
fun BufferParameters.asDiagramMarkers(): DiagramMarkers =
    buffer.asDiagramMarkers()
/*end*/
/*start*/
/*graphics-structure-type:StereoViews:StereoViews*/
class StereoViews(
    members: List<Introspected.Member> = StereoViewsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var stereoVpL by Structure.FromStructure<afield.math.linalg.Matrix4f>() // left-eye projection/view
    var stereoVpR by Structure.FromStructure<afield.math.linalg.Matrix4f>() // right-eye projection/view
    var stereoRightL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left-eye camera right
    var stereoRightR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right-eye camera right
    var stereoUpL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left-eye camera up
    var stereoUpR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right-eye camera up
    var stereoProjectionL by Structure.FromStructure<afield.math.linalg.Vector4f>() // projection XY and viewport size
    var stereoProjectionR by Structure.FromStructure<afield.math.linalg.Vector4f>() // projection XY and viewport size
}
fun StereoViewsByteSize(): Long = 224L
fun StereoViewsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("stereoVpL", 0, PrimitiveType.mat4.left(), 0, false),
        Introspected.Member("stereoVpR", 64, PrimitiveType.mat4.left(), 0, false),
        Introspected.Member("stereoRightL", 128, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("stereoRightR", 144, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("stereoUpL", 160, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("stereoUpR", 176, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("stereoProjectionL", 192, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("stereoProjectionR", 208, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asStereoViews(): StereoViews =
    asStructure(StereoViews(), StereoViewsLayoutMembers())
fun BufferParameters.asStereoViews(): StereoViews =
    buffer.asStereoViews()
/*end*/
/*graphics-pc-structure:model*/ var Structure.model by Structure.FromStructure<Any>() // diagram-local to world, shared by both eyes
/*graphics-pc-binding:model*/ var Mesh.model by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>() // diagram-local to world, shared by both eyes
/*graphics-pc-binding-group:model*/ var MeshGroup.model by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>() // diagram-local to world, shared by both eyes
/*graphics-pc-structure:diagramData*/ var Structure.diagramData by Structure.FromStructure<Any>() // position/radius, linear RGBA and SDF parameters
/*graphics-pc-binding:diagramData*/ var Mesh.diagramData by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // position/radius, linear RGBA and SDF parameters
/*graphics-pc-binding-group:diagramData*/ var MeshGroup.diagramData by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // position/radius, linear RGBA and SDF parameters
/*graphics-pc-structure:options*/ var Structure.options by Structure.FromStructure<Any>() // viewport width/height, minimum stroke/marker radius in pixels
/*graphics-pc-binding:options*/ var Mesh.options by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // viewport width/height, minimum stroke/marker radius in pixels
/*graphics-pc-binding-group:options*/ var MeshGroup.options by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // viewport width/height, minimum stroke/marker radius in pixels
/*graphics-ubo:StereoViews*/ val ModulePointer.StereoViews by FromUniform()
/*graphics-ubo-member:StereoViews:stereoVpL*/ var Structure.stereoVpL by Structure.FromStructure<afield.math.linalg.Matrix4f>() // left-eye projection/view
/*graphics-ubo-member:StereoViews:stereoVpR*/ var Structure.stereoVpR by Structure.FromStructure<afield.math.linalg.Matrix4f>() // right-eye projection/view
/*graphics-ubo-member:StereoViews:stereoRightL*/ var Structure.stereoRightL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left-eye camera right
/*graphics-ubo-member:StereoViews:stereoRightR*/ var Structure.stereoRightR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right-eye camera right
/*graphics-ubo-member:StereoViews:stereoUpL*/ var Structure.stereoUpL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left-eye camera up
/*graphics-ubo-member:StereoViews:stereoUpR*/ var Structure.stereoUpR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right-eye camera up
/*graphics-ubo-member:StereoViews:stereoProjectionL*/ var Structure.stereoProjectionL by Structure.FromStructure<afield.math.linalg.Vector4f>() // projection XY and viewport size
/*graphics-ubo-member:StereoViews:stereoProjectionR*/ var Structure.stereoProjectionR by Structure.FromStructure<afield.math.linalg.Vector4f>() // projection XY and viewport size
var Box.window by Inheritance<Window>() /*from:text1_context*/ /**/
val Once.window by Onceing<Window>() /*from:text1_context*/ /**/
val Why.window by Whying() /*from:text1_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:text1_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:text1_context*/ /**/
val Why.renderer by Whying() /*from:text1_context*/ /**/
var Box.stereo by Inheritance<MultiviewStereoTarget>() /*from:text1_context*/ /**/
val Once.stereo by Onceing<MultiviewStereoTarget>() /*from:text1_context*/ /**/
val Why.stereo by Whying() /*from:text1_context*/ /**/
var Box.showStereoPair by Inheritance<Boolean>() /*from:text1_context*/ /**/
val Once.showStereoPair by Onceing<Boolean>() /*from:text1_context*/ /**/
val Why.showStereoPair by Whying() /*from:text1_context*/ /**/
