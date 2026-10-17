import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.graphics.shader.*
import afield.graphics.scene.*
import afield.graphics.render.*
import afield.document.context.*
import afield.graphics.vulkan.buffers.*
import afield.graphics.vulkan.targets.RenderToTexture
import afield.io.syphon.SyphonTexture
var Box.syphonIn by Inheritance<SyphonTexture>() /**/
val Once.syphonIn by Onceing<SyphonTexture>() /**/
val Why.syphonIn by Whying() /**/
var Box.syphonProbeTarget by Inheritance<RenderToTexture>() /**/
val Once.syphonProbeTarget by Onceing<RenderToTexture>() /**/
val Why.syphonProbeTarget by Whying() /**/
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*graphics-input:tc*/ val Mesh.tc by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var pos : FloatBuffer; lateinit var tc : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("tc") { tc = this ; mesh.write("pos") { pos = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } } ;            };         }
var Box.window by Inheritance<Window>() /*from:syphon_zero_copy_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:syphon_zero_copy_renderer*/ /**/
val Why.window by Whying() /*from:syphon_zero_copy_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:syphon_zero_copy_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:syphon_zero_copy_renderer*/ /**/
val Why.renderer by Whying() /*from:syphon_zero_copy_renderer*/ /**/
