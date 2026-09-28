import afield.four.Renderer
import afield.four.util.Window
import afield.four.fabricated.PathTracingRenderer
var Box.tracer by Inheritance<PathTracingRenderer>() /**/
val Once.tracer by Onceing<PathTracingRenderer>() /**/
var Box.window by Inheritance<Window>() /*from:chain_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:chain_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:chain_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:chain_renderer*/ /**/
