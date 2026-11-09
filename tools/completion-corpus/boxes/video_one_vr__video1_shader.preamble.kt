import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.scene.video.StereoVideoSurface
import afield.ui.output.ControlValueHandle
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
/*graphics-pc-structure:model*/ var Structure.model by Structure.FromStructure<Any>() // screen-local metres to room space
/*graphics-pc-binding:model*/ var Mesh.model by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>() // screen-local metres to room space
/*graphics-pc-binding-group:model*/ var MeshGroup.model by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>() // screen-local metres to room space
/*graphics-pc-structure:surface*/ var Structure.surface by Structure.FromStructure<Any>() // arc width, height, bend radians, strip count
/*graphics-pc-binding:surface*/ var Mesh.surface by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // arc width, height, bend radians, strip count
/*graphics-pc-binding-group:surface*/ var MeshGroup.surface by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // arc width, height, bend radians, strip count
/*graphics-pc-structure:image*/ var Structure.image by Structure.FromStructure<Any>() // packed source width/height, swap eyes, vertical flip
/*graphics-pc-binding:image*/ var Mesh.image by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // packed source width/height, swap eyes, vertical flip
/*graphics-pc-binding-group:image*/ var MeshGroup.image by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // packed source width/height, swap eyes, vertical flip
/*graphics-pc-structure:colorimetry*/ var Structure.colorimetry by Structure.FromStructure<Any>() // Kr, Kb, full-range flag, reserved
/*graphics-pc-binding:colorimetry*/ var Mesh.colorimetry by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // Kr, Kb, full-range flag, reserved
/*graphics-pc-binding-group:colorimetry*/ var MeshGroup.colorimetry by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // Kr, Kb, full-range flag, reserved
/*graphics-pc-structure:accumulation*/ var Structure.accumulation by Structure.FromStructure<Any>() // processed mix, valid image, reserved, reserved
/*graphics-pc-binding:accumulation*/ var Mesh.accumulation by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // processed mix, valid image, reserved, reserved
/*graphics-pc-binding-group:accumulation*/ var MeshGroup.accumulation by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // processed mix, valid image, reserved, reserved
/*graphics-ubo:StereoViews*/ val ModulePointer.StereoViews by FromUniform()
/*graphics-ubo-member:StereoViews:stereoVpL*/ var Structure.stereoVpL by Structure.FromStructure<afield.math.linalg.Matrix4f>() // left-eye projection/view
/*graphics-ubo-member:StereoViews:stereoVpR*/ var Structure.stereoVpR by Structure.FromStructure<afield.math.linalg.Matrix4f>() // right-eye projection/view
/*graphics-ubo-member:StereoViews:stereoRightL*/ var Structure.stereoRightL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left-eye camera right
/*graphics-ubo-member:StereoViews:stereoRightR*/ var Structure.stereoRightR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right-eye camera right
/*graphics-ubo-member:StereoViews:stereoUpL*/ var Structure.stereoUpL by Structure.FromStructure<afield.math.linalg.Vector4f>() // left-eye camera up
/*graphics-ubo-member:StereoViews:stereoUpR*/ var Structure.stereoUpR by Structure.FromStructure<afield.math.linalg.Vector4f>() // right-eye camera up
/*graphics-ubo-member:StereoViews:stereoProjectionL*/ var Structure.stereoProjectionL by Structure.FromStructure<afield.math.linalg.Vector4f>() // projection XY and viewport size
/*graphics-ubo-member:StereoViews:stereoProjectionR*/ var Structure.stereoProjectionR by Structure.FromStructure<afield.math.linalg.Vector4f>() // projection XY and viewport size
var Box.movie by Inheritance<StereoVideoSurface>() /*from:video1_screen*/ /**/
val Once.movie by Onceing<StereoVideoSurface>() /*from:video1_screen*/ /**/
val Why.movie by Whying() /*from:video1_screen*/ /**/
var Box.aspectControl by Inheritance<ControlValueHandle<Double>>() /*from:video1_screen*/ /**/
val Once.aspectControl by Onceing<ControlValueHandle<Double>>() /*from:video1_screen*/ /**/
val Why.aspectControl by Whying() /*from:video1_screen*/ /**/
var Box.window by Inheritance<Window>() /*from:video1_context*/ /**/
val Once.window by Onceing<Window>() /*from:video1_context*/ /**/
val Why.window by Whying() /*from:video1_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:video1_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:video1_context*/ /**/
val Why.renderer by Whying() /*from:video1_context*/ /**/
var Box.stereo by Inheritance<MultiviewStereoTarget>() /*from:video1_context*/ /**/
val Once.stereo by Onceing<MultiviewStereoTarget>() /*from:video1_context*/ /**/
val Why.stereo by Whying() /*from:video1_context*/ /**/
