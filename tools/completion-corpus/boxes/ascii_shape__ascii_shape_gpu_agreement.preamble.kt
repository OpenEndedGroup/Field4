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
var Box.cellsTex by Inheritance<RenderToTexture>() /*from:ascii_shape_gpu_draw*/ /**/
val Once.cellsTex by Onceing<RenderToTexture>() /*from:ascii_shape_gpu_draw*/ /**/
val Why.cellsTex by Whying() /*from:ascii_shape_gpu_draw*/ /**/
var Box.atlasTex by Inheritance<LoadedTexture>() /*from:ascii_shape_gpu_draw*/ /**/
val Once.atlasTex by Onceing<LoadedTexture>() /*from:ascii_shape_gpu_draw*/ /**/
val Why.atlasTex by Whying() /*from:ascii_shape_gpu_draw*/ /**/
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
