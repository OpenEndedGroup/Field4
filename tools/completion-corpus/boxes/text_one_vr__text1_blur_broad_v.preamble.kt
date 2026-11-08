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
import afield.graphics.vulkan.targets.MultiviewStereoTarget
var Box.broadVerticalFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /**/
val Once.broadVerticalFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /**/
val Why.broadVerticalFactory by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:tapStep*/ var Structure.tapStep by Structure.FromStructure<Any>() // XY step in output pixels, kernel radius and gain
/*graphics-pc-binding:tapStep*/ var Mesh.tapStep by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // XY step in output pixels, kernel radius and gain
/*graphics-pc-binding-group:tapStep*/ var MeshGroup.tapStep by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // XY step in output pixels, kernel radius and gain
var Box.window by Inheritance<Window>() /*from:text1_context*/ /**/
val Once.window by Onceing<Window>() /*from:text1_context*/ /**/
val Why.window by Whying() /*from:text1_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:text1_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:text1_context*/ /**/
val Why.renderer by Whying() /*from:text1_context*/ /**/
var Box.stereo by Inheritance<MultiviewStereoTarget>() /*from:text1_context*/ /**/
val Once.stereo by Onceing<MultiviewStereoTarget>() /*from:text1_context*/ /**/
val Why.stereo by Whying() /*from:text1_context*/ /**/
var Box.showStereoPair by Inheritance<Boolean>() /*from:text1_context*/ /**/
val Once.showStereoPair by Onceing<Boolean>() /*from:text1_context*/ /**/
val Why.showStereoPair by Whying() /*from:text1_context*/ /**/
