import afield.graphics.render.lighting.DirectLightingDemo
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
var Box.plumbing by Inheritance<AutoCloseable>() /**/
val Once.plumbing by Onceing<AutoCloseable>() /**/
val Why.plumbing by Whying() /**/
var Box.scene by Inheritance<DirectLightingDemo>() /**/
val Once.scene by Onceing<DirectLightingDemo>() /**/
val Why.scene by Whying() /**/
var Box.guides by Inheritance<RenderToTexture>() /**/
val Once.guides by Onceing<RenderToTexture>() /**/
val Why.guides by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
