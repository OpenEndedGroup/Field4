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
/*stack-auto:program*/
var ModulePointer.program: String
    get() = afield.graphics.shader.stackAutoShaderSource(this, "program")
    set(value) { afield.graphics.shader.stackAutoShaderSource(this, "program", value) }
var ComputeModulePointer.program: String
    get() = afield.graphics.shader.stackAutoShaderSource(this, "program")
    set(value) { afield.graphics.shader.stackAutoShaderSource(this, "program", value) }
var MeshModulePointer.program: String
    get() = afield.graphics.shader.stackAutoShaderSource(this, "program")
    set(value) { afield.graphics.shader.stackAutoShaderSource(this, "program", value) }
/*end*/
/*graphics-ubo:program_numbers*/ val ModulePointer.program_numbers by FromUniform()
var Box.window by Inheritance<Window>() /*from:renderer*/ /**/
val Once.window by Onceing<Window>() /*from:renderer*/ /**/
val Why.window by Whying() /*from:renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:renderer*/ /**/
val Why.renderer by Whying() /*from:renderer*/ /**/
