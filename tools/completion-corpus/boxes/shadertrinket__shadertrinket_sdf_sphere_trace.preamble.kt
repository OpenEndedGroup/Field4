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
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:ShadertoyUniforms:ShadertoyUniforms*/
class ShadertoyUniforms(
    members: List<Introspected.Member> = ShadertoyUniformsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var iResolution by Structure.FromStructure<afield.math.linalg.Vector3f>()
    var iTime by Structure.FromStructure<Float>()
    var iTimeDelta by Structure.FromStructure<Float>()
    var iFrameRate by Structure.FromStructure<Float>()
    var iFrame by Structure.FromStructure<Int>()
    var iMouse by Structure.FromStructure<afield.math.linalg.Vector4f>()
    var iDate by Structure.FromStructure<afield.math.linalg.Vector4f>()
    var iChannelResolution0 by Structure.FromStructure<afield.math.linalg.Vector3f>()
    var iChannelTime0 by Structure.FromStructure<Float>()
    var iChannelResolution1 by Structure.FromStructure<afield.math.linalg.Vector3f>()
    var iChannelTime1 by Structure.FromStructure<Float>()
    var iChannelResolution2 by Structure.FromStructure<afield.math.linalg.Vector3f>()
    var iChannelTime2 by Structure.FromStructure<Float>()
    var iChannelResolution3 by Structure.FromStructure<afield.math.linalg.Vector3f>()
    var iChannelTime3 by Structure.FromStructure<Float>()
}
fun ShadertoyUniformsByteSize(): Long = 128L
fun ShadertoyUniformsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("iResolution", 0, PrimitiveType.vec3.left(), 0, false),
        Introspected.Member("iTime", 12, PrimitiveType.float.left(), 0, false),
        Introspected.Member("iTimeDelta", 16, PrimitiveType.float.left(), 0, false),
        Introspected.Member("iFrameRate", 20, PrimitiveType.float.left(), 0, false),
        Introspected.Member("iFrame", 24, PrimitiveType.int.left(), 0, false),
        Introspected.Member("iMouse", 32, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("iDate", 48, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("iChannelResolution0", 64, PrimitiveType.vec3.left(), 0, false),
        Introspected.Member("iChannelTime0", 76, PrimitiveType.float.left(), 0, false),
        Introspected.Member("iChannelResolution1", 80, PrimitiveType.vec3.left(), 0, false),
        Introspected.Member("iChannelTime1", 92, PrimitiveType.float.left(), 0, false),
        Introspected.Member("iChannelResolution2", 96, PrimitiveType.vec3.left(), 0, false),
        Introspected.Member("iChannelTime2", 108, PrimitiveType.float.left(), 0, false),
        Introspected.Member("iChannelResolution3", 112, PrimitiveType.vec3.left(), 0, false),
        Introspected.Member("iChannelTime3", 124, PrimitiveType.float.left(), 0, false),
    )
fun HasBuffer.asShadertoyUniforms(): ShadertoyUniforms =
    asStructure(ShadertoyUniforms(), ShadertoyUniformsLayoutMembers())
fun BufferParameters.asShadertoyUniforms(): ShadertoyUniforms =
    buffer.asShadertoyUniforms()
/*end*/
/*graphics-ubo:ShadertoyUniforms*/ val ModulePointer.ShadertoyUniforms by FromUniform()
/*graphics-ubo-member:ShadertoyUniforms:iResolution*/ var Structure.iResolution by Structure.FromStructure<afield.math.linalg.Vector3f>()
/*graphics-ubo-member:ShadertoyUniforms:iTime*/ var Structure.iTime by Structure.FromStructure<Float>()
/*graphics-ubo-member:ShadertoyUniforms:iTimeDelta*/ var Structure.iTimeDelta by Structure.FromStructure<Float>()
/*graphics-ubo-member:ShadertoyUniforms:iFrameRate*/ var Structure.iFrameRate by Structure.FromStructure<Float>()
/*graphics-ubo-member:ShadertoyUniforms:iFrame*/ var Structure.iFrame by Structure.FromStructure<Int>()
/*graphics-ubo-member:ShadertoyUniforms:iMouse*/ var Structure.iMouse by Structure.FromStructure<afield.math.linalg.Vector4f>()
/*graphics-ubo-member:ShadertoyUniforms:iDate*/ var Structure.iDate by Structure.FromStructure<afield.math.linalg.Vector4f>()
/*graphics-ubo-member:ShadertoyUniforms:iChannelResolution0*/ var Structure.iChannelResolution0 by Structure.FromStructure<afield.math.linalg.Vector3f>()
/*graphics-ubo-member:ShadertoyUniforms:iChannelTime0*/ var Structure.iChannelTime0 by Structure.FromStructure<Float>()
/*graphics-ubo-member:ShadertoyUniforms:iChannelResolution1*/ var Structure.iChannelResolution1 by Structure.FromStructure<afield.math.linalg.Vector3f>()
/*graphics-ubo-member:ShadertoyUniforms:iChannelTime1*/ var Structure.iChannelTime1 by Structure.FromStructure<Float>()
/*graphics-ubo-member:ShadertoyUniforms:iChannelResolution2*/ var Structure.iChannelResolution2 by Structure.FromStructure<afield.math.linalg.Vector3f>()
/*graphics-ubo-member:ShadertoyUniforms:iChannelTime2*/ var Structure.iChannelTime2 by Structure.FromStructure<Float>()
/*graphics-ubo-member:ShadertoyUniforms:iChannelResolution3*/ var Structure.iChannelResolution3 by Structure.FromStructure<afield.math.linalg.Vector3f>()
/*graphics-ubo-member:ShadertoyUniforms:iChannelTime3*/ var Structure.iChannelTime3 by Structure.FromStructure<Float>()
var Box.window by Inheritance<Window>() /*from:shadertrinket_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:shadertrinket_renderer*/ /**/
val Why.window by Whying() /*from:shadertrinket_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:shadertrinket_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:shadertrinket_renderer*/ /**/
val Why.renderer by Whying() /*from:shadertrinket_renderer*/ /**/
