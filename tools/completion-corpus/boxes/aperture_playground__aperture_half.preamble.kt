import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.targets.RenderToTexture
var Box.paintHalf by Inheritance<RenderToTexture>() /**/
val Once.paintHalf by Onceing<RenderToTexture>() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
var Box.paintSource by Inheritance<RenderToTexture>() /**/
val Once.paintSource by Onceing<RenderToTexture>() /**/
var Box.apertureControl by Inheritance<afield.stdlib.Vec4>() /**/
val Once.apertureControl by Onceing<afield.stdlib.Vec4>() /**/
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
