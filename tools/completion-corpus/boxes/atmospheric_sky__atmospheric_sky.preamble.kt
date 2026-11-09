import afield.graphics.sky.SkyView
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
var Box.skyView by Inheritance<SkyView>() /**/
val Once.skyView by Onceing<SkyView>() /**/
val Why.skyView by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:viewport*/ var Structure.viewport by Structure.FromStructure<Any>() // width, height, altitude (km), sun elevation (radians)
/*graphics-pc-binding:viewport*/ var Mesh.viewport by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // width, height, altitude (km), sun elevation (radians)
/*graphics-pc-binding-group:viewport*/ var MeshGroup.viewport by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // width, height, altitude (km), sun elevation (radians)
/*graphics-pc-structure:view*/ var Structure.view by Structure.FromStructure<Any>() // sun azimuth, camera pitch, exposure, vertical field of view (radians)
/*graphics-pc-binding:view*/ var Mesh.view by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // sun azimuth, camera pitch, exposure, vertical field of view (radians)
/*graphics-pc-binding-group:view*/ var MeshGroup.view by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // sun azimuth, camera pitch, exposure, vertical field of view (radians)
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
