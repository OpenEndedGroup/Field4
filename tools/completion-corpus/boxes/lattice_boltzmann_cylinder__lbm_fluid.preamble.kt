import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import afield.simulation.agents.ShaderLatticeBoltzmannSystem
var Box.lbm by Inheritance<ShaderLatticeBoltzmannSystem>() /**/
val Once.lbm by Onceing<ShaderLatticeBoltzmannSystem>() /**/
val Why.lbm by Whying() /**/
var Box.window by Inheritance<Window>() /*from:lbm_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:lbm_renderer*/ /**/
val Why.window by Whying() /*from:lbm_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:lbm_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:lbm_renderer*/ /**/
val Why.renderer by Whying() /*from:lbm_renderer*/ /**/
