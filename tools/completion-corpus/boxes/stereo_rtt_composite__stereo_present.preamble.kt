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
var Box.window by Inheritance<Window>() /*from:stereo_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:stereo_renderer*/ /**/
val Why.window by Whying() /*from:stereo_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:stereo_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:stereo_renderer*/ /**/
val Why.renderer by Whying() /*from:stereo_renderer*/ /**/
