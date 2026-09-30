import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.targets.RenderToTexture
var Box.paintQuarter by Inheritance<RenderToTexture>() /**/
val Once.paintQuarter by Onceing<RenderToTexture>() /**/
val Why.paintQuarter by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
var Box.paintHalf by Inheritance<RenderToTexture>() /*from:aperture_half*/ /**/
val Once.paintHalf by Onceing<RenderToTexture>() /*from:aperture_half*/ /**/
val Why.paintHalf by Whying() /*from:aperture_half*/ /**/
var Box.paintSource by Inheritance<RenderToTexture>() /*from:aperture_source*/ /**/
val Once.paintSource by Onceing<RenderToTexture>() /*from:aperture_source*/ /**/
val Why.paintSource by Whying() /*from:aperture_source*/ /**/
var Box.apertureControl by Inheritance<afield.stdlib.Vec4>() /*from:aperture_source*/ /**/
val Once.apertureControl by Onceing<afield.stdlib.Vec4>() /*from:aperture_source*/ /**/
val Why.apertureControl by Whying() /*from:aperture_source*/ /**/
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
