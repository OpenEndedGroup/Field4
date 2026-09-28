import afield.four.Renderer
import afield.four.util.Window
import afield.four.fabricated.PathTraceConvergence
import afield.four.fabricated.PathTracingRenderer
var Box.tracer by Inheritance<PathTracingRenderer>() /**/
val Once.tracer by Onceing<PathTracingRenderer>() /**/
var Box.log by Inheritance<PathTraceConvergence>() /**/
val Once.log by Onceing<PathTraceConvergence>() /**/
var Box.tracer by Inheritance<PathTracingRenderer>() /*from:pt_tracer*/ /**/
val Once.tracer by Onceing<PathTracingRenderer>() /*from:pt_tracer*/ /**/
var Box.log by Inheritance<PathTraceConvergence>() /*from:pt_tracer*/ /**/
val Once.log by Onceing<PathTraceConvergence>() /*from:pt_tracer*/ /**/
var Box.window by Inheritance<Window>() /*from:pt_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pt_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pt_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pt_renderer*/ /**/
