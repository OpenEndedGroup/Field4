import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.targets.RenderToTexture
import afield.four.lighting.DirectLightingDemo
var Box.plumbing by Inheritance<AutoCloseable>() /**/
val Once.plumbing by Onceing<AutoCloseable>() /**/
var Box.scene by Inheritance<DirectLightingDemo>() /**/
val Once.scene by Onceing<DirectLightingDemo>() /**/
var Box.guides by Inheritance<RenderToTexture>() /**/
val Once.guides by Onceing<RenderToTexture>() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
