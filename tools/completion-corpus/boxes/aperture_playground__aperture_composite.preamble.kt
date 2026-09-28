import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.targets.RenderToTexture
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:displayControl*/ var Structure.displayControl by Structure.FromStructure<Any>()
/*graphics-pc-binding:displayControl*/ var Mesh.displayControl by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-binding-group:displayControl*/ var MeshGroup.displayControl by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
var Box.paintMedium by Inheritance<RenderToTexture>() /*from:aperture_medium*/ /**/
val Once.paintMedium by Onceing<RenderToTexture>() /*from:aperture_medium*/ /**/
var Box.paintBroad by Inheritance<RenderToTexture>() /*from:aperture_broad*/ /**/
val Once.paintBroad by Onceing<RenderToTexture>() /*from:aperture_broad*/ /**/
var Box.paintHalf by Inheritance<RenderToTexture>() /*from:aperture_half*/ /**/
val Once.paintHalf by Onceing<RenderToTexture>() /*from:aperture_half*/ /**/
var Box.paintQuarter by Inheritance<RenderToTexture>() /*from:aperture_quarter*/ /**/
val Once.paintQuarter by Onceing<RenderToTexture>() /*from:aperture_quarter*/ /**/
var Box.paintSource by Inheritance<RenderToTexture>() /*from:aperture_source*/ /**/
val Once.paintSource by Onceing<RenderToTexture>() /*from:aperture_source*/ /**/
var Box.apertureControl by Inheritance<afield.stdlib.Vec4>() /*from:aperture_source*/ /**/
val Once.apertureControl by Onceing<afield.stdlib.Vec4>() /*from:aperture_source*/ /**/
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
