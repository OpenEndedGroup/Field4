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
/*graphics-input:col*/ val Mesh.col by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var pos : FloatBuffer; lateinit var col : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("col") { col = this ; mesh.write("pos") { pos = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } } ;            };         }
/*graphics-pc-structure:p*/ var Structure.p by Structure.FromStructure<Any>()
/*graphics-pc-binding:p*/ var Mesh.p by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-binding-group:p*/ var MeshGroup.p by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-structure:m*/ var Structure.m by Structure.FromStructure<Any>()
/*graphics-pc-binding:m*/ var Mesh.m by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-binding-group:m*/ var MeshGroup.m by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>()
var Box.window by Inheritance<Window>() /*from:stereo_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:stereo_renderer*/ /**/
val Why.window by Whying() /*from:stereo_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:stereo_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:stereo_renderer*/ /**/
val Why.renderer by Whying() /*from:stereo_renderer*/ /**/
