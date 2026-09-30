import afield.four.Renderer
import afield.four.util.Window
var Box.particles by Inheritance<afield.stdlib.ShaderNeuralParticles>() /**/
val Once.particles by Onceing<afield.stdlib.ShaderNeuralParticles>() /**/
val Why.particles by Whying() /**/
var Box.neuralExport by Inheritance<afield.stdlib.FlowMatchExport>() /*from:np_field*/ /**/
val Once.neuralExport by Onceing<afield.stdlib.FlowMatchExport>() /*from:np_field*/ /**/
val Why.neuralExport by Whying() /*from:np_field*/ /**/
var Box.window by Inheritance<Window>() /*from:np_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:np_renderer*/ /**/
val Why.window by Whying() /*from:np_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:np_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:np_renderer*/ /**/
val Why.renderer by Whying() /*from:np_renderer*/ /**/
