import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.particles by Inheritance<afield.simulation.particles.ShaderNeuralParticles>() /**/
val Once.particles by Onceing<afield.simulation.particles.ShaderNeuralParticles>() /**/
val Why.particles by Whying() /**/
var Box.fieldA by Inheritance<afield.simulation.particles.FlowMatchExport>() /*from:li_fields*/ /**/
val Once.fieldA by Onceing<afield.simulation.particles.FlowMatchExport>() /*from:li_fields*/ /**/
val Why.fieldA by Whying() /*from:li_fields*/ /**/
var Box.fieldB by Inheritance<afield.simulation.particles.FlowMatchExport>() /*from:li_fields*/ /**/
val Once.fieldB by Onceing<afield.simulation.particles.FlowMatchExport>() /*from:li_fields*/ /**/
val Why.fieldB by Whying() /*from:li_fields*/ /**/
var Box.window by Inheritance<Window>() /*from:li_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:li_renderer*/ /**/
val Why.window by Whying() /*from:li_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:li_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:li_renderer*/ /**/
val Why.renderer by Whying() /*from:li_renderer*/ /**/
