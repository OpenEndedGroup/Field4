import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.texture.LoadedTexture
import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.ui.output.ShapeAscii
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.cells by Inheritance<RenderToTexture>() /**/
val Once.cells by Onceing<RenderToTexture>() /**/
val Why.cells by Whying() /**/
var Box.sceneTex by Inheritance<RenderToTexture>() /**/
val Once.sceneTex by Onceing<RenderToTexture>() /**/
val Why.sceneTex by Whying() /**/
var Box.glyphVectors by Inheritance<LoadedTexture>() /**/
val Once.glyphVectors by Onceing<LoadedTexture>() /**/
val Why.glyphVectors by Whying() /**/
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var pos : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("pos") { pos = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } ;            };         }
/*graphics-pc-structure:cell*/ var Structure.cell by Structure.FromStructure<Any>()
/*graphics-pc-binding:cell*/ var Mesh.cell by Mesh.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-binding-group:cell*/ var MeshGroup.cell by MeshGroup.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-structure:contrast*/ var Structure.contrast by Structure.FromStructure<Any>()
/*graphics-pc-binding:contrast*/ var Mesh.contrast by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:contrast*/ var MeshGroup.contrast by MeshGroup.BoundFromStructure<Number>()
/*graphics-pc-structure:directional*/ var Structure.directional by Structure.FromStructure<Any>()
/*graphics-pc-binding:directional*/ var Mesh.directional by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:directional*/ var MeshGroup.directional by MeshGroup.BoundFromStructure<Number>()
var Box.ascii by Inheritance<ShapeAscii>() /*from:ascii_shape_gpu_scene*/ /**/
val Once.ascii by Onceing<ShapeAscii>() /*from:ascii_shape_gpu_scene*/ /**/
val Why.ascii by Whying() /*from:ascii_shape_gpu_scene*/ /**/
var Box.scene by Inheritance<RenderToTexture>() /*from:ascii_shape_gpu_scene*/ /**/
val Once.scene by Onceing<RenderToTexture>() /*from:ascii_shape_gpu_scene*/ /**/
val Why.scene by Whying() /*from:ascii_shape_gpu_scene*/ /**/
var Box.window by Inheritance<Window>() /*from:ascii_shape_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:ascii_shape_renderer*/ /**/
val Why.window by Whying() /*from:ascii_shape_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:ascii_shape_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:ascii_shape_renderer*/ /**/
val Why.renderer by Whying() /*from:ascii_shape_renderer*/ /**/
