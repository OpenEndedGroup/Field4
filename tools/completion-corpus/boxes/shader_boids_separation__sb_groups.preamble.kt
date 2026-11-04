import afield.simulation.physics.ShaderBoids
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.groups by Inheritance<afield.simulation.physics.ShaderBoids>() /**/
val Once.groups by Onceing<afield.simulation.physics.ShaderBoids>() /**/
val Why.groups by Whying() /**/
var Box.window by Inheritance<Window>() /*from:sb_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:sb_renderer*/ /**/
val Why.window by Whying() /*from:sb_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:sb_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:sb_renderer*/ /**/
val Why.renderer by Whying() /*from:sb_renderer*/ /**/
