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
var Box.flowSession by Inheritance<AutoCloseable>() /**/
val Once.flowSession by Onceing<AutoCloseable>() /**/
var Box.history by Inheritance<PingPongRenderToTexture>() /**/
val Once.history by Onceing<PingPongRenderToTexture>() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:time*/ var Structure.time by Structure.FromStructure<Any>()
/*graphics-pc-binding:time*/ var Mesh.time by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:time*/ var MeshGroup.time by MeshGroup.BoundFromStructure<Float>()
var Box.previousImage by Inheritance<HasImageView>() /**/
val Once.previousImage by Onceing<HasImageView>() /**/
var Box.currentImage by Inheritance<HasImageView>() /**/
val Once.currentImage by Onceing<HasImageView>() /**/
var Box.flow by Inheritance<afield.four.vision.OpticalFlowOperation>() /**/
val Once.flow by Onceing<afield.four.vision.OpticalFlowOperation>() /**/
var Box.motion by Inheritance<RenderToTexture>() /**/
val Once.motion by Onceing<RenderToTexture>() /**/
var Box.expectedMotion by Inheritance<Vector2f>() /**/
val Once.expectedMotion by Onceing<Vector2f>() /**/
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
