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
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:StereoViews:StereoViews*/
class StereoViews(
    members: List<Introspected.Member> = StereoViewsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var stereoVpL by Structure.FromStructure<afield.math.linalg.Matrix4f>() // left-eye view/projection
    var stereoVpR by Structure.FromStructure<afield.math.linalg.Matrix4f>() // right-eye view/projection
    var stereoRightL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left-eye world right
    var stereoRightR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right-eye world right
    var stereoUpL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left-eye world up
    var stereoUpR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right-eye world up
    var stereoProjectionL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left projection scale and raster size
    var stereoProjectionR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right projection scale and raster size
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
/*graphics-pc-structure:a*/ var Structure.a by Structure.FromStructure<Any>() // world start/centre, radius or half-width in metres
/*graphics-pc-binding:a*/ var Mesh.a by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // world start/centre, radius or half-width in metres
/*graphics-pc-binding-group:a*/ var MeshGroup.a by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // world start/centre, radius or half-width in metres
/*graphics-pc-structure:b*/ var Structure.b by Structure.FromStructure<Any>() // world end, 1 = circular joint / 0 = segment
/*graphics-pc-binding:b*/ var Mesh.b by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // world end, 1 = circular joint / 0 = segment
/*graphics-pc-binding-group:b*/ var MeshGroup.b by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // world end, 1 = circular joint / 0 = segment
/*graphics-pc-structure:tint*/ var Structure.tint by Structure.FromStructure<Any>() // linear RGBA; zero alpha hides unavailable observations
/*graphics-pc-binding:tint*/ var Mesh.tint by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // linear RGBA; zero alpha hides unavailable observations
/*graphics-pc-binding-group:tint*/ var MeshGroup.tint by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // linear RGBA; zero alpha hides unavailable observations
/*graphics-ubo:StereoViews*/ val ModulePointer.StereoViews by FromUniform()
/*graphics-ubo-member:StereoViews:stereoVpL*/ var Structure.stereoVpL by Structure.FromStructure<afield.math.linalg.Matrix4f>() // left-eye view/projection
/*graphics-ubo-member:StereoViews:stereoVpR*/ var Structure.stereoVpR by Structure.FromStructure<afield.math.linalg.Matrix4f>() // right-eye view/projection
/*graphics-ubo-member:StereoViews:stereoRightL*/ var Structure.stereoRightL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left-eye world right
/*graphics-ubo-member:StereoViews:stereoRightR*/ var Structure.stereoRightR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right-eye world right
/*graphics-ubo-member:StereoViews:stereoUpL*/ var Structure.stereoUpL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left-eye world up
/*graphics-ubo-member:StereoViews:stereoUpR*/ var Structure.stereoUpR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right-eye world up
/*graphics-ubo-member:StereoViews:stereoProjectionL*/ var Structure.stereoProjectionL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left projection scale and raster size
/*graphics-ubo-member:StereoViews:stereoProjectionR*/ var Structure.stereoProjectionR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right projection scale and raster size
var Box.trackingMarks by Inheritance<MutableList<Array<Vector4f>>>() /**/
val Once.trackingMarks by Onceing<MutableList<Array<Vector4f>>>() /**/
val Why.trackingMarks by Whying() /**/
var Box.window by Inheritance<Window>() /*from:body1_context*/ /**/
val Once.window by Onceing<Window>() /*from:body1_context*/ /**/
val Why.window by Whying() /*from:body1_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:body1_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:body1_context*/ /**/
val Why.renderer by Whying() /*from:body1_context*/ /**/
var Box.stereo by Inheritance<MultiviewStereoTarget>() /*from:body1_context*/ /**/
val Once.stereo by Onceing<MultiviewStereoTarget>() /*from:body1_context*/ /**/
val Why.stereo by Whying() /*from:body1_context*/ /**/
