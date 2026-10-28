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
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:aaBlend*/ var Structure.aaBlend by Structure.FromStructure<Any>()
/*graphics-pc-binding:aaBlend*/ var Mesh.aaBlend by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:aaBlend*/ var MeshGroup.aaBlend by MeshGroup.BoundFromStructure<Number>()
/*graphics-pc-structure:edgeThreshold*/ var Structure.edgeThreshold by Structure.FromStructure<Any>()
/*graphics-pc-binding:edgeThreshold*/ var Mesh.edgeThreshold by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:edgeThreshold*/ var MeshGroup.edgeThreshold by MeshGroup.BoundFromStructure<Number>()
/*graphics-pc-structure:edgeThresholdMin*/ var Structure.edgeThresholdMin by Structure.FromStructure<Any>()
/*graphics-pc-binding:edgeThresholdMin*/ var Mesh.edgeThresholdMin by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:edgeThresholdMin*/ var MeshGroup.edgeThresholdMin by MeshGroup.BoundFromStructure<Number>()
/*graphics-pc-structure:spanMax*/ var Structure.spanMax by Structure.FromStructure<Any>()
/*graphics-pc-binding:spanMax*/ var Mesh.spanMax by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:spanMax*/ var MeshGroup.spanMax by MeshGroup.BoundFromStructure<Number>()
var Box.window by Inheritance<Window>() /*from:stereo_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:stereo_renderer*/ /**/
val Why.window by Whying() /*from:stereo_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:stereo_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:stereo_renderer*/ /**/
val Why.renderer by Whying() /*from:stereo_renderer*/ /**/
