import afield.ui.output.ShapeAscii
import afield.graphics.vulkan.targets.RenderToTexture
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
var Box.ascii by Inheritance<ShapeAscii>() /**/
val Once.ascii by Onceing<ShapeAscii>() /**/
val Why.ascii by Whying() /**/
var Box.scene by Inheritance<RenderToTexture>() /**/
val Once.scene by Onceing<RenderToTexture>() /**/
val Why.scene by Whying() /**/
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*graphics-input:normal*/ val Mesh.normal by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var pos : FloatBuffer; lateinit var normal : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("normal") { normal = this ; mesh.write("pos") { pos = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } } ;            };         }
/*graphics-pc-structure:time*/ var Structure.time by Structure.FromStructure<Any>()
/*graphics-pc-binding:time*/ var Mesh.time by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:time*/ var MeshGroup.time by MeshGroup.BoundFromStructure<Number>()
/*graphics-pc-structure:aspect*/ var Structure.aspect by Structure.FromStructure<Any>()
/*graphics-pc-binding:aspect*/ var Mesh.aspect by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:aspect*/ var MeshGroup.aspect by MeshGroup.BoundFromStructure<Number>()
var Box.window by Inheritance<Window>() /*from:ascii_shape_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:ascii_shape_renderer*/ /**/
val Why.window by Whying() /*from:ascii_shape_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:ascii_shape_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:ascii_shape_renderer*/ /**/
val Why.renderer by Whying() /*from:ascii_shape_renderer*/ /**/
