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
var Box.paintHalf by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /**/
val Once.paintHalf by Onceing<RenderToTexture>() /**/
val Why.paintHalf by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
var Box.paintSource by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /*from:aperture_source*/ /**/
val Once.paintSource by Onceing<RenderToTexture>() /*from:aperture_source*/ /**/
val Why.paintSource by Whying() /*from:aperture_source*/ /**/
var Box.apertureControl by Inheritance<afield.math.linalg.Vec4>() /*from:aperture_source*/ /**/
val Once.apertureControl by Onceing<afield.math.linalg.Vec4>() /*from:aperture_source*/ /**/
val Why.apertureControl by Whying() /*from:aperture_source*/ /**/
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
