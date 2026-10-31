import afield.simulation.agents.ShaderForestFireSystem
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.fire by Inheritance<ShaderForestFireSystem>() /**/
val Once.fire by Onceing<ShaderForestFireSystem>() /**/
val Why.fire by Whying() /**/
var Box.window by Inheritance<Window>() /*from:fire_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:fire_renderer*/ /**/
val Why.window by Whying() /*from:fire_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:fire_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:fire_renderer*/ /**/
val Why.renderer by Whying() /*from:fire_renderer*/ /**/
