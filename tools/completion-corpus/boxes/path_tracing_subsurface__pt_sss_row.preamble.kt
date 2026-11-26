import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.math.linalg.Vector3f
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.tracer by Inheritance<PathTracingRenderer>() /**/
val Once.tracer by Onceing<PathTracingRenderer>() /**/
val Why.tracer by Whying() /**/
var Box.skin by Inheritance<Vector3f>() /**/
val Once.skin by Onceing<Vector3f>() /**/
val Why.skin by Whying() /**/
var Box.radius by Inheritance<Vector3f>() /**/
val Once.radius by Onceing<Vector3f>() /**/
val Why.radius by Whying() /**/
var Box.remapped by Inheritance<List<Int>>() /**/
val Once.remapped by Onceing<List<Int>>() /**/
val Why.remapped by Whying() /**/
var Box.window by Inheritance<Window>() /*from:pt_sss_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pt_sss_renderer*/ /**/
val Why.window by Whying() /*from:pt_sss_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pt_sss_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pt_sss_renderer*/ /**/
val Why.renderer by Whying() /*from:pt_sss_renderer*/ /**/
