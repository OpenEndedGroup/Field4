import afield.four.Renderer
import afield.four.util.Window
import afield.four.fabricated.PathTraceConvergence
import afield.four.fabricated.PathTracingRenderer
var Box.tracer by Inheritance<PathTracingRenderer>() /**/
val Once.tracer by Onceing<PathTracingRenderer>() /**/
val Why.tracer by Whying() /**/
var Box.tracer by Inheritance<PathTracingRenderer>() /*from:pt_tracer*/ /**/
val Once.tracer by Onceing<PathTracingRenderer>() /*from:pt_tracer*/ /**/
val Why.tracer by Whying() /*from:pt_tracer*/ /**/
var Box.log by Inheritance<PathTraceConvergence>() /*from:pt_tracer*/ /**/
val Once.log by Onceing<PathTraceConvergence>() /*from:pt_tracer*/ /**/
val Why.log by Whying() /*from:pt_tracer*/ /**/
var Box.window by Inheritance<Window>() /*from:pt_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pt_renderer*/ /**/
val Why.window by Whying() /*from:pt_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pt_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pt_renderer*/ /**/
val Why.renderer by Whying() /*from:pt_renderer*/ /**/
