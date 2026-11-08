import afield.simulation.agents.ShaderPhysarumSystem
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.physarum by Inheritance<ShaderPhysarumSystem>() /**/
val Once.physarum by Onceing<ShaderPhysarumSystem>() /**/
val Why.physarum by Whying() /**/
var Box.window by Inheritance<Window>() /*from:net_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:net_renderer*/ /**/
val Why.window by Whying() /*from:net_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:net_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:net_renderer*/ /**/
val Why.renderer by Whying() /*from:net_renderer*/ /**/
