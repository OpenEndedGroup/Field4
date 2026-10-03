import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.texture.LoadedTexture
var Box.camera by Inheritance<LoadedTexture>() /**/
val Once.camera by Onceing<LoadedTexture>() /**/
val Why.camera by Whying() /**/
/*graphics-input:tc*/ val Mesh.tc by FromInput()
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var tc : FloatBuffer; lateinit var pos : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("pos") { pos = this ; mesh.write("tc") { tc = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } } ;            };         }
var Box.window by Inheritance<Window>() /*from:cv_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:cv_renderer*/ /**/
val Why.window by Whying() /*from:cv_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:cv_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:cv_renderer*/ /**/
val Why.renderer by Whying() /*from:cv_renderer*/ /**/
