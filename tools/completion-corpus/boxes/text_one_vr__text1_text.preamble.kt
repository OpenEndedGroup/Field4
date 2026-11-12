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
var Box.textModuleFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /**/
val Once.textModuleFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /**/
val Why.textModuleFactory by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:SlugData:SlugData*/
class SlugData(
    members: List<Introspected.Member> = SlugDataLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var words by Structure.FromStructure<UInt>() // immutable Slug header, curve and band atlas in 32-bit words
}
fun SlugDataByteSize(): Long = 4L
fun SlugDataLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("words", 0, PrimitiveType.uint.left(), 4, false),
    )
fun HasBuffer.asSlugData(): SlugData =
    asStructure(SlugData(), SlugDataLayoutMembers())
fun BufferParameters.asSlugData(): SlugData =
    buffer.asSlugData()
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
/*graphics-pc-structure:model*/ var Structure.model by Structure.FromStructure<Any>() // local glyph plane to world; eye VP is applied in this vertex shader
/*graphics-pc-binding:model*/ var Mesh.model by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>() // local glyph plane to world; eye VP is applied in this vertex shader
/*graphics-pc-binding-group:model*/ var MeshGroup.model by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>() // local glyph plane to world; eye VP is applied in this vertex shader
/*graphics-pc-structure:slugData*/ var Structure.slugData by Structure.FromStructure<Any>() // immutable word-outline atlas
/*graphics-pc-binding:slugData*/ var Mesh.slugData by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // immutable word-outline atlas
/*graphics-pc-binding-group:slugData*/ var MeshGroup.slugData by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // immutable word-outline atlas
/*graphics-pc-structure:viewport*/ var Structure.viewport by Structure.FromStructure<Any>() // dimensions of this eye, for perspective-dependent quad dilation
/*graphics-pc-binding:viewport*/ var Mesh.viewport by Mesh.BoundFromStructure<afield.math.linalg.Vector2f>() // dimensions of this eye, for perspective-dependent quad dilation
/*graphics-pc-binding-group:viewport*/ var MeshGroup.viewport by MeshGroup.BoundFromStructure<afield.math.linalg.Vector2f>() // dimensions of this eye, for perspective-dependent quad dilation
/*graphics-pc-structure:tint*/ var Structure.tint by Structure.FromStructure<Any>() // linear RGBA, with score-driven word opacity
/*graphics-pc-binding:tint*/ var Mesh.tint by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // linear RGBA, with score-driven word opacity
/*graphics-pc-binding-group:tint*/ var MeshGroup.tint by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // linear RGBA, with score-driven word opacity
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
