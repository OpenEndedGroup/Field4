import afield.four.Renderer
import afield.four.util.Window
import afield.stdlib.linalg.Vector2f
import afield.four.targets.RenderToTexture
import afield.four.texture.HasImageView
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.targets.PingPongRenderToTexture
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
var Box.flowSession by Inheritance<AutoCloseable>() /*from:optical_flow_pingpong*/ /**/
val Once.flowSession by Onceing<AutoCloseable>() /*from:optical_flow_pingpong*/ /**/
val Why.flowSession by Whying() /*from:optical_flow_pingpong*/ /**/
var Box.history by Inheritance<PingPongRenderToTexture>() /*from:optical_flow_pingpong*/ /**/
val Once.history by Onceing<PingPongRenderToTexture>() /*from:optical_flow_pingpong*/ /**/
val Why.history by Whying() /*from:optical_flow_pingpong*/ /**/
var Box.previousImage by Inheritance<HasImageView>() /*from:optical_flow_pingpong*/ /**/
val Once.previousImage by Onceing<HasImageView>() /*from:optical_flow_pingpong*/ /**/
val Why.previousImage by Whying() /*from:optical_flow_pingpong*/ /**/
var Box.currentImage by Inheritance<HasImageView>() /*from:optical_flow_pingpong*/ /**/
val Once.currentImage by Onceing<HasImageView>() /*from:optical_flow_pingpong*/ /**/
val Why.currentImage by Whying() /*from:optical_flow_pingpong*/ /**/
var Box.flow by Inheritance<afield.four.vision.OpticalFlowOperation>() /*from:optical_flow_pingpong*/ /**/
val Once.flow by Onceing<afield.four.vision.OpticalFlowOperation>() /*from:optical_flow_pingpong*/ /**/
val Why.flow by Whying() /*from:optical_flow_pingpong*/ /**/
var Box.motion by Inheritance<RenderToTexture>() /*from:optical_flow_pingpong*/ /**/
val Once.motion by Onceing<RenderToTexture>() /*from:optical_flow_pingpong*/ /**/
val Why.motion by Whying() /*from:optical_flow_pingpong*/ /**/
var Box.expectedMotion by Inheritance<Vector2f>() /*from:optical_flow_pingpong*/ /**/
val Once.expectedMotion by Onceing<Vector2f>() /*from:optical_flow_pingpong*/ /**/
val Why.expectedMotion by Whying() /*from:optical_flow_pingpong*/ /**/
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
