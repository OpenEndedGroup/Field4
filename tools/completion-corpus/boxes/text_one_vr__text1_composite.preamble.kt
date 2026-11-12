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
var Box.compositeModuleFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /**/
val Once.compositeModuleFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /**/
val Why.compositeModuleFactory by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:CompositeGrade:CompositeGrade*/
class CompositeGrade(
    members: List<Introspected.Member> = CompositeGradeLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var selectorRange by Structure.FromStructure<afield.math.linalg.Vector4f>() // selector minimum/maximum, cubic fold strength, reserved
    var selectorSource by Structure.FromStructure<afield.math.linalg.Vector4f>() // sharp/medium/broad green weights, reserved
    var paint by Structure.FromStructure<afield.math.linalg.Vector4f>() // reserved legacy layout; accumulated light is never normalized
    var colorFold by Structure.FromStructure<afield.math.linalg.Vector4f>() // red/green/blue fold gains, overall fold mix
    var foldLimits by Structure.FromStructure<afield.math.linalg.Vector4f>() // red/green/blue fold caps, fold scale
    var alphaFold by Structure.FromStructure<afield.math.linalg.Vector4f>() // fold gain, cap, strength, reserved
    var saturation by Structure.FromStructure<afield.math.linalg.Vector4f>() // base saturation, shadow desaturation, shadow knee, shadow slope
    var gain by Structure.FromStructure<afield.math.linalg.Vector4f>() // per-channel linear gain, reserved
    var gamma by Structure.FromStructure<afield.math.linalg.Vector4f>() // per-channel artistic gamma, master artistic gamma
    var shadowTone by Structure.FromStructure<afield.math.linalg.Vector4f>() // shadow hue RGB, luminance-preserving strength
    var highlightTone by Structure.FromStructure<afield.math.linalg.Vector4f>() // highlight hue RGB, luminance-preserving strength
    var toneRange by Structure.FromStructure<afield.math.linalg.Vector4f>() // shadow end, highlight start, luminance balance, reserved
    var background by Structure.FromStructure<afield.math.linalg.Vector4f>() // linear background RGB, strength
    var fxaa by Structure.FromStructure<afield.math.linalg.Vector4f>() // overall mix, relative edge threshold, absolute threshold, span pixels
    var subpixel by Structure.FromStructure<afield.math.linalg.Vector4f>() // subpixel smoothing, output-eye width/height, linear-light FXAA flag
    var supersampling by Structure.FromStructure<afield.math.linalg.Vector4f>() // per-pixel rotation mix, footprint radius, stable integer seed, display dither in 8-bit code steps
}
fun CompositeGradeByteSize(): Long = 256L
fun CompositeGradeLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("selectorRange", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("selectorSource", 16, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("paint", 32, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("colorFold", 48, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("foldLimits", 64, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("alphaFold", 80, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("saturation", 96, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("gain", 112, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("gamma", 128, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("shadowTone", 144, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("highlightTone", 160, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("toneRange", 176, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("background", 192, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("fxaa", 208, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("subpixel", 224, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("supersampling", 240, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asCompositeGrade(): CompositeGrade =
    asStructure(CompositeGrade(), CompositeGradeLayoutMembers())
fun BufferParameters.asCompositeGrade(): CompositeGrade =
    buffer.asCompositeGrade()
/*end*/
/*graphics-pc-structure:selection*/ var Structure.selection by Structure.FromStructure<Any>() // selector gain, offset, effect mix, source-green scale
/*graphics-pc-binding:selection*/ var Mesh.selection by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // selector gain, offset, effect mix, source-green scale
/*graphics-pc-binding-group:selection*/ var MeshGroup.selection by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // selector gain, offset, effect mix, source-green scale
/*graphics-pc-structure:reconstruction*/ var Structure.reconstruction by Structure.FromStructure<Any>() // red/blue green reconstruction weights, exposure, reserved
/*graphics-pc-binding:reconstruction*/ var Mesh.reconstruction by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // red/blue green reconstruction weights, exposure, reserved
/*graphics-pc-binding-group:reconstruction*/ var MeshGroup.reconstruction by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // red/blue green reconstruction weights, exposure, reserved
/*graphics-ubo:CompositeGrade*/ val ModulePointer.CompositeGrade by FromUniform()
/*graphics-ubo-member:CompositeGrade:selectorRange*/ var Structure.selectorRange by Structure.FromStructure<afield.math.linalg.Vector4f>() // selector minimum/maximum, cubic fold strength, reserved
/*graphics-ubo-member:CompositeGrade:selectorSource*/ var Structure.selectorSource by Structure.FromStructure<afield.math.linalg.Vector4f>() // sharp/medium/broad green weights, reserved
/*graphics-ubo-member:CompositeGrade:paint*/ var Structure.paint by Structure.FromStructure<afield.math.linalg.Vector4f>() // reserved legacy layout; accumulated light is never normalized
/*graphics-ubo-member:CompositeGrade:colorFold*/ var Structure.colorFold by Structure.FromStructure<afield.math.linalg.Vector4f>() // red/green/blue fold gains, overall fold mix
/*graphics-ubo-member:CompositeGrade:foldLimits*/ var Structure.foldLimits by Structure.FromStructure<afield.math.linalg.Vector4f>() // red/green/blue fold caps, fold scale
/*graphics-ubo-member:CompositeGrade:alphaFold*/ var Structure.alphaFold by Structure.FromStructure<afield.math.linalg.Vector4f>() // fold gain, cap, strength, reserved
/*graphics-ubo-member:CompositeGrade:saturation*/ var Structure.saturation by Structure.FromStructure<afield.math.linalg.Vector4f>() // base saturation, shadow desaturation, shadow knee, shadow slope
/*graphics-ubo-member:CompositeGrade:gain*/ var Structure.gain by Structure.FromStructure<afield.math.linalg.Vector4f>() // per-channel linear gain, reserved
/*graphics-ubo-member:CompositeGrade:gamma*/ var Structure.gamma by Structure.FromStructure<afield.math.linalg.Vector4f>() // per-channel artistic gamma, master artistic gamma
/*graphics-ubo-member:CompositeGrade:shadowTone*/ var Structure.shadowTone by Structure.FromStructure<afield.math.linalg.Vector4f>() // shadow hue RGB, luminance-preserving strength
/*graphics-ubo-member:CompositeGrade:highlightTone*/ var Structure.highlightTone by Structure.FromStructure<afield.math.linalg.Vector4f>() // highlight hue RGB, luminance-preserving strength
/*graphics-ubo-member:CompositeGrade:toneRange*/ var Structure.toneRange by Structure.FromStructure<afield.math.linalg.Vector4f>() // shadow end, highlight start, luminance balance, reserved
/*graphics-ubo-member:CompositeGrade:background*/ var Structure.background by Structure.FromStructure<afield.math.linalg.Vector4f>() // linear background RGB, strength
/*graphics-ubo-member:CompositeGrade:fxaa*/ var Structure.fxaa by Structure.FromStructure<afield.math.linalg.Vector4f>() // overall mix, relative edge threshold, absolute threshold, span pixels
/*graphics-ubo-member:CompositeGrade:subpixel*/ var Structure.subpixel by Structure.FromStructure<afield.math.linalg.Vector4f>() // subpixel smoothing, output-eye width/height, linear-light FXAA flag
/*graphics-ubo-member:CompositeGrade:supersampling*/ var Structure.supersampling by Structure.FromStructure<afield.math.linalg.Vector4f>() // per-pixel rotation mix, footprint radius, stable integer seed, display dither in 8-bit code steps
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
