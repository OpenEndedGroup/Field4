import afield.simulation.agents.ShaderPhysarumSystem
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.physarum by Inheritance<ShaderPhysarumSystem>() /*from:net_slime*/ /**/
val Once.physarum by Onceing<ShaderPhysarumSystem>() /*from:net_slime*/ /**/
val Why.physarum by Whying() /*from:net_slime*/ /**/
var Box.window by Inheritance<Window>() /*from:net_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:net_renderer*/ /**/
val Why.window by Whying() /*from:net_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:net_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:net_renderer*/ /**/
val Why.renderer by Whying() /*from:net_renderer*/ /**/
