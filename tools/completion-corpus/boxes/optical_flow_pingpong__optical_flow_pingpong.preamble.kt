import afield.graphics.vulkan.targets.PingPongRenderToTexture
import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.texture.HasImageView
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.Vector2f
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.flowSession by Inheritance<AutoCloseable>() /**/
val Once.flowSession by Onceing<AutoCloseable>() /**/
val Why.flowSession by Whying() /**/
var Box.history by ReactiveInheritance<PingPongRenderToTexture>(Reactive.DOWN) /**/
val Once.history by Onceing<PingPongRenderToTexture>() /**/
val Why.history by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:time*/ var Structure.time by Structure.FromStructure<Any>()
/*graphics-pc-binding:time*/ var Mesh.time by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:time*/ var MeshGroup.time by MeshGroup.BoundFromStructure<Number>()
var Box.previousImage by ReactiveInheritance<HasImageView>(Reactive.DOWN) /**/
val Once.previousImage by Onceing<HasImageView>() /**/
val Why.previousImage by Whying() /**/
var Box.currentImage by ReactiveInheritance<HasImageView>(Reactive.DOWN) /**/
val Once.currentImage by Onceing<HasImageView>() /**/
val Why.currentImage by Whying() /**/
var Box.flow by Inheritance<afield.media.vision.OpticalFlowOperation>() /**/
val Once.flow by Onceing<afield.media.vision.OpticalFlowOperation>() /**/
val Why.flow by Whying() /**/
var Box.motion by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /**/
val Once.motion by Onceing<RenderToTexture>() /**/
val Why.motion by Whying() /**/
var Box.expectedMotion by Inheritance<Vector2f>() /**/
val Once.expectedMotion by Onceing<Vector2f>() /**/
val Why.expectedMotion by Whying() /**/
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
