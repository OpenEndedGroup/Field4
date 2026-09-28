import afield.four.Renderer
import afield.four.util.Window
var Box.particles by Inheritance<afield.stdlib.ShaderNeuralParticles>() /**/
val Once.particles by Onceing<afield.stdlib.ShaderNeuralParticles>() /**/
var Box.fieldA by Inheritance<afield.stdlib.FlowMatchExport>() /*from:li_fields*/ /**/
val Once.fieldA by Onceing<afield.stdlib.FlowMatchExport>() /*from:li_fields*/ /**/
var Box.fieldB by Inheritance<afield.stdlib.FlowMatchExport>() /*from:li_fields*/ /**/
val Once.fieldB by Onceing<afield.stdlib.FlowMatchExport>() /*from:li_fields*/ /**/
var Box.window by Inheritance<Window>() /*from:li_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:li_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:li_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:li_renderer*/ /**/
