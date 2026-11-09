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
var Box.cellsTex by Inheritance<RenderToTexture>() /**/
val Once.cellsTex by Onceing<RenderToTexture>() /**/
val Why.cellsTex by Whying() /**/
var Box.atlasTex by Inheritance<LoadedTexture>() /**/
val Once.atlasTex by Onceing<LoadedTexture>() /**/
val Why.atlasTex by Whying() /**/
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var pos : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("pos") { pos = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } ;            };         }
/*graphics-pc-structure:ink*/ var Structure.ink by Structure.FromStructure<Any>()
/*graphics-pc-binding:ink*/ var Mesh.ink by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:ink*/ var MeshGroup.ink by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-structure:background*/ var Structure.background by Structure.FromStructure<Any>()
/*graphics-pc-binding:background*/ var Mesh.background by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:background*/ var MeshGroup.background by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-structure:cell*/ var Structure.cell by Structure.FromStructure<Any>()
/*graphics-pc-binding:cell*/ var Mesh.cell by Mesh.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-binding-group:cell*/ var MeshGroup.cell by MeshGroup.BoundFromStructure<afield.math.linalg.Vector2f>()
var Box.cells by Inheritance<RenderToTexture>() /*from:ascii_shape_gpu_match*/ /**/
val Once.cells by Onceing<RenderToTexture>() /*from:ascii_shape_gpu_match*/ /**/
val Why.cells by Whying() /*from:ascii_shape_gpu_match*/ /**/
var Box.sceneTex by Inheritance<RenderToTexture>() /*from:ascii_shape_gpu_match*/ /**/
val Once.sceneTex by Onceing<RenderToTexture>() /*from:ascii_shape_gpu_match*/ /**/
val Why.sceneTex by Whying() /*from:ascii_shape_gpu_match*/ /**/
var Box.glyphVectors by Inheritance<LoadedTexture>() /*from:ascii_shape_gpu_match*/ /**/
val Once.glyphVectors by Onceing<LoadedTexture>() /*from:ascii_shape_gpu_match*/ /**/
val Why.glyphVectors by Whying() /*from:ascii_shape_gpu_match*/ /**/
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
