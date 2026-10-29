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
var Box.paintMedium by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /**/
val Once.paintMedium by Onceing<RenderToTexture>() /**/
val Why.paintMedium by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:aperture*/ var Structure.aperture by Structure.FromStructure<Any>()
/*graphics-pc-binding:aperture*/ var Mesh.aperture by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:aperture*/ var MeshGroup.aperture by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
var Box.paintHalf by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /*from:aperture_half*/ /**/
val Once.paintHalf by Onceing<RenderToTexture>() /*from:aperture_half*/ /**/
val Why.paintHalf by Whying() /*from:aperture_half*/ /**/
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
