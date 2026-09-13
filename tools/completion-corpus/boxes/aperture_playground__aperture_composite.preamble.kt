import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.targets.RenderToTexture
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:displayControl*/ var Structure.displayControl by Structure.FromStructure<Any>()
/*graphics-pc-binding:displayControl*/ var Mesh.displayControl by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-binding-group:displayControl*/ var MeshGroup.displayControl by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
var Box.paintMedium by Inheritance<RenderToTexture>() /**/
val Once.paintMedium by Onceing<RenderToTexture>() /**/
var Box.paintBroad by Inheritance<RenderToTexture>() /**/
val Once.paintBroad by Onceing<RenderToTexture>() /**/
var Box.paintHalf by Inheritance<RenderToTexture>() /**/
val Once.paintHalf by Onceing<RenderToTexture>() /**/
var Box.paintQuarter by Inheritance<RenderToTexture>() /**/
val Once.paintQuarter by Onceing<RenderToTexture>() /**/
var Box.paintSource by Inheritance<RenderToTexture>() /**/
val Once.paintSource by Onceing<RenderToTexture>() /**/
var Box.apertureControl by Inheritance<afield.stdlib.Vec4>() /**/
val Once.apertureControl by Onceing<afield.stdlib.Vec4>() /**/
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
