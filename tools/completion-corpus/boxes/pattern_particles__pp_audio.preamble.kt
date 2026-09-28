import afield.four.Renderer
import afield.four.util.Window
import afield.four.synth.PatSynthLaunch
var Box.bandEnergy by Inheritance<DoubleArray>() /**/
val Once.bandEnergy by Onceing<DoubleArray>() /**/
var Box.patternSynth by Inheritance<PatSynthLaunch>() /**/
val Once.patternSynth by Onceing<PatSynthLaunch>() /**/
var Box.window by Inheritance<Window>() /*from:pp_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pp_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pp_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pp_renderer*/ /**/
