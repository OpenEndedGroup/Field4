import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.tracer by Inheritance<PathTracingRenderer>() /**/
val Once.tracer by Onceing<PathTracingRenderer>() /**/
val Why.tracer by Whying() /**/
var Box.window by Inheritance<Window>() /*from:kirigami_popup_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:kirigami_popup_renderer*/ /**/
val Why.window by Whying() /*from:kirigami_popup_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:kirigami_popup_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:kirigami_popup_renderer*/ /**/
val Why.renderer by Whying() /*from:kirigami_popup_renderer*/ /**/
