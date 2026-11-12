import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.volume.CloudScene
import afield.graphics.volume.NeuralCloud
import afield.graphics.volume.AmbientFit
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
var Box.cloudScene by Inheritance<CloudScene>() /*from:cloud_train*/ /**/
val Once.cloudScene by Onceing<CloudScene>() /*from:cloud_train*/ /**/
val Why.cloudScene by Whying() /*from:cloud_train*/ /**/
var Box.cloudNet by Inheritance<NeuralCloud>() /*from:cloud_train*/ /**/
val Once.cloudNet by Onceing<NeuralCloud>() /*from:cloud_train*/ /**/
val Why.cloudNet by Whying() /*from:cloud_train*/ /**/
var Box.cloudAmbient by Inheritance<AmbientFit>() /*from:cloud_train*/ /**/
val Once.cloudAmbient by Onceing<AmbientFit>() /*from:cloud_train*/ /**/
val Why.cloudAmbient by Whying() /*from:cloud_train*/ /**/
var Box.window by Inheritance<Window>() /*from:cloud_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:cloud_renderer*/ /**/
val Why.window by Whying() /*from:cloud_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:cloud_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:cloud_renderer*/ /**/
val Why.renderer by Whying() /*from:cloud_renderer*/ /**/
