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
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:displayControl*/ var Structure.displayControl by Structure.FromStructure<Any>()
/*graphics-pc-binding:displayControl*/ var Mesh.displayControl by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:displayControl*/ var MeshGroup.displayControl by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
var Box.paintMedium by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /*from:aperture_medium*/ /**/
val Once.paintMedium by Onceing<RenderToTexture>() /*from:aperture_medium*/ /**/
val Why.paintMedium by Whying() /*from:aperture_medium*/ /**/
var Box.paintBroad by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /*from:aperture_broad*/ /**/
val Once.paintBroad by Onceing<RenderToTexture>() /*from:aperture_broad*/ /**/
val Why.paintBroad by Whying() /*from:aperture_broad*/ /**/
var Box.paintHalf by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /*from:aperture_half*/ /**/
val Once.paintHalf by Onceing<RenderToTexture>() /*from:aperture_half*/ /**/
val Why.paintHalf by Whying() /*from:aperture_half*/ /**/
var Box.paintQuarter by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /*from:aperture_quarter*/ /**/
val Once.paintQuarter by Onceing<RenderToTexture>() /*from:aperture_quarter*/ /**/
val Why.paintQuarter by Whying() /*from:aperture_quarter*/ /**/
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
