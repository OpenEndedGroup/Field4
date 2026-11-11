import afield.ui.output.ControlValueHandle
import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.scene.video.StereoVideoSurface
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:image*/ var Structure.image by Structure.FromStructure<Any>() // packed width/height, reserved, reserved
/*graphics-pc-binding:image*/ var Mesh.image by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // packed width/height, reserved, reserved
/*graphics-pc-binding-group:image*/ var MeshGroup.image by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // packed width/height, reserved, reserved
/*graphics-pc-structure:colorimetry*/ var Structure.colorimetry by Structure.FromStructure<Any>() // Kr, Kb, full-range flag, reserved
/*graphics-pc-binding:colorimetry*/ var Mesh.colorimetry by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // Kr, Kb, full-range flag, reserved
/*graphics-pc-binding-group:colorimetry*/ var MeshGroup.colorimetry by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // Kr, Kb, full-range flag, reserved
/*graphics-pc-structure:weights*/ var Structure.weights by Structure.FromStructure<Any>() // incoming normalized weight, history valid, reserved, reserved
/*graphics-pc-binding:weights*/ var Mesh.weights by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // incoming normalized weight, history valid, reserved, reserved
/*graphics-pc-binding-group:weights*/ var MeshGroup.weights by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // incoming normalized weight, history valid, reserved, reserved
var Box.movie by Inheritance<StereoVideoSurface>() /*from:video1_screen*/ /**/
val Once.movie by Onceing<StereoVideoSurface>() /*from:video1_screen*/ /**/
val Why.movie by Whying() /*from:video1_screen*/ /**/
var Box.aspectControl by Inheritance<ControlValueHandle<Double>>() /*from:video1_screen*/ /**/
val Once.aspectControl by Onceing<ControlValueHandle<Double>>() /*from:video1_screen*/ /**/
val Why.aspectControl by Whying() /*from:video1_screen*/ /**/
var Box.window by Inheritance<Window>() /*from:video1_context*/ /**/
val Once.window by Onceing<Window>() /*from:video1_context*/ /**/
val Why.window by Whying() /*from:video1_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:video1_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:video1_context*/ /**/
val Why.renderer by Whying() /*from:video1_context*/ /**/
var Box.stereo by Inheritance<MultiviewStereoTarget>() /*from:video1_context*/ /**/
val Once.stereo by Onceing<MultiviewStereoTarget>() /*from:video1_context*/ /**/
val Why.stereo by Whying() /*from:video1_context*/ /**/
