import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.tracer by Inheritance<PathTracingRenderer>() /**/
val Once.tracer by Onceing<PathTracingRenderer>() /**/
val Why.tracer by Whying() /**/
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
