import afield.media.browser.PuppetBrowser
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
var Box.browserControl by Inheritance<afield.media.browser.PuppetBrowser>() /**/
val Once.browserControl by Onceing<afield.media.browser.PuppetBrowser>() /**/
val Why.browserControl by Whying() /**/
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*graphics-input:tc*/ val Mesh.tc by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var pos : FloatBuffer; lateinit var tc : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("tc") { tc = this ; mesh.write("pos") { pos = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } } ;            };         }
var Box.window by Inheritance<Window>() /*from:field_ui_texture_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:field_ui_texture_renderer*/ /**/
val Why.window by Whying() /*from:field_ui_texture_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:field_ui_texture_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:field_ui_texture_renderer*/ /**/
val Why.renderer by Whying() /*from:field_ui_texture_renderer*/ /**/
