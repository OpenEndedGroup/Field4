import afield.four.Renderer
import afield.four.util.Window
import afield.four.synth.PatSynthLaunch
var Box.bandEnergy by Inheritance<DoubleArray>() /**/
val Once.bandEnergy by Onceing<DoubleArray>() /**/
var Box.patternSynth by Inheritance<PatSynthLaunch>() /**/
val Once.patternSynth by Onceing<PatSynthLaunch>() /**/
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
