import afield.four.Renderer
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var pos : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("pos") { pos = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } ;            };         }
/*graphics-pc-structure:p*/ var Structure.p by Structure.FromStructure<Any>()
/*graphics-pc-binding:p*/ var Mesh.p by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>()
/*graphics-pc-binding-group:p*/ var MeshGroup.p by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Matrix4f>()
/*graphics-pc-structure:m*/ var Structure.m by Structure.FromStructure<Any>()
/*graphics-pc-binding:m*/ var Mesh.m by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>()
/*graphics-pc-binding-group:m*/ var MeshGroup.m by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Matrix4f>()
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
