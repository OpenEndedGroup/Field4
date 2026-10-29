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
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*graphics-input:tc*/ val Mesh.tc by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var pos : FloatBuffer; lateinit var tc : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("tc") { tc = this ; mesh.write("pos") { pos = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } } ;            };         }
/*graphics-pc-structure:time*/ var Structure.time by Structure.FromStructure<Any>()
/*graphics-pc-binding:time*/ var Mesh.time by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:time*/ var MeshGroup.time by MeshGroup.BoundFromStructure<Number>()
var Box.execution by Inheritance<Box>() /*from:root*/ /**/
val Once.execution by Onceing<Box>() /*from:root*/ /**/
val Why.execution by Whying() /*from:root*/ /**/
var Box.window by Inheritance<Window>() /*from:demo_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:demo_renderer*/ /**/
val Why.window by Whying() /*from:demo_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:demo_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:demo_renderer*/ /**/
val Why.renderer by Whying() /*from:demo_renderer*/ /**/
