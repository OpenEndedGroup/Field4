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
var Box.strokeModuleFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /**/
val Once.strokeModuleFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /**/
val Why.strokeModuleFactory by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Segment:Segment*/
class Segment(
    members: List<Introspected.Member> = SegmentLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var previous by Structure.FromStructure<afield.math.linalg.Vector4f>() // previous centreline XYZ, half-width at A in metres
    var a by Structure.FromStructure<afield.math.linalg.Vector4f>() // segment start XYZ, cumulative path distance
    var b by Structure.FromStructure<afield.math.linalg.Vector4f>() // segment end XYZ, cumulative path distance
    var next by Structure.FromStructure<afield.math.linalg.Vector4f>() // next centreline XYZ, half-width at B in metres
    var colorA by Structure.FromStructure<afield.math.linalg.Vector4f>() // linear RGBA at segment start
    var colorB by Structure.FromStructure<afield.math.linalg.Vector4f>() // linear RGBA at segment end
    var ink by Structure.FromStructure<afield.math.linalg.Vector4f>() // dash period, duty, phase and first/last flags
    var trim by Structure.FromStructure<afield.math.linalg.Vector4f>() // visible path-distance interval, two reserved values
}
fun SegmentByteSize(): Long = 128L
fun SegmentLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("previous", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("a", 16, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("b", 32, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("next", 48, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("colorA", 64, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("colorB", 80, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("ink", 96, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("trim", 112, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asSegment(): Segment =
    asStructure(Segment(), SegmentLayoutMembers())
fun BufferParameters.asSegment(): Segment =
    buffer.asSegment()
/*end*/
/*start*/
/*graphics-structure-type:DiagramSegments:DiagramSegments*/
class DiagramSegments(
    members: List<Introspected.Member> = DiagramSegmentsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val item: Segment
        get() = childStructure("item") { members, baseOffset, pathPrefix, root ->
            Segment(members, baseOffset, pathPrefix, root)
        }
}
fun DiagramSegmentsByteSize(): Long = 128L
fun DiagramSegmentsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("item", 0, SegmentLayoutMembers().right(), 128, false),
    )
fun HasBuffer.asDiagramSegments(): DiagramSegments =
    asStructure(DiagramSegments(), DiagramSegmentsLayoutMembers())
fun BufferParameters.asDiagramSegments(): DiagramSegments =
    buffer.asDiagramSegments()
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
/*graphics-pc-structure:diagramData*/ var Structure.diagramData by Structure.FromStructure<Any>() // CPU-authored centreline samples, never eye-dependent triangles
/*graphics-pc-binding:diagramData*/ var Mesh.diagramData by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // CPU-authored centreline samples, never eye-dependent triangles
/*graphics-pc-binding-group:diagramData*/ var MeshGroup.diagramData by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // CPU-authored centreline samples, never eye-dependent triangles
/*graphics-pc-structure:options*/ var Structure.options by Structure.FromStructure<Any>() // viewport width/height, minimum stroke/marker radius in pixels
/*graphics-pc-binding:options*/ var Mesh.options by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // viewport width/height, minimum stroke/marker radius in pixels
/*graphics-pc-binding-group:options*/ var MeshGroup.options by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // viewport width/height, minimum stroke/marker radius in pixels
/*graphics-pc-structure:clipPlane*/ var Structure.clipPlane by Structure.FromStructure<Any>() // world-space backdrop clipping, zero for the main scene
/*graphics-pc-binding:clipPlane*/ var Mesh.clipPlane by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // world-space backdrop clipping, zero for the main scene
/*graphics-pc-binding-group:clipPlane*/ var MeshGroup.clipPlane by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // world-space backdrop clipping, zero for the main scene
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
