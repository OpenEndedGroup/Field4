import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:look*/ var Structure.look by Structure.FromStructure<Any>() // w: 0 color; 3 previous UV; 4 displacement magnified.
/*graphics-pc-binding:look*/ var Mesh.look by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // w: 0 color; 3 previous UV; 4 displacement magnified.
/*graphics-pc-binding-group:look*/ var MeshGroup.look by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // w: 0 color; 3 previous UV; 4 displacement magnified.
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
