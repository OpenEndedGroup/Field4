import afield.four.Renderer
import afield.four.util.Window
import afield.four.synth.PatSynthLaunch
var Box.bandEnergy by Inheritance<DoubleArray>() /**/
val Once.bandEnergy by Onceing<DoubleArray>() /**/
val Why.bandEnergy by Whying() /**/
var Box.patternSynth by Inheritance<PatSynthLaunch>() /**/
val Once.patternSynth by Onceing<PatSynthLaunch>() /**/
val Why.patternSynth by Whying() /**/
var Box.window by Inheritance<Window>() /*from:pp_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pp_renderer*/ /**/
val Why.window by Whying() /*from:pp_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pp_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pp_renderer*/ /**/
val Why.renderer by Whying() /*from:pp_renderer*/ /**/
