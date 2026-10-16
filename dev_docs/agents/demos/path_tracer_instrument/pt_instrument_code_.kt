/*
Re-run to inspect the live tracer, including its accumulation generation.
The up row appears when the declared camera up differs from the effective
right × forward basis used to make the image.
*/
import afield.graphics.scene.fabricated.PathTracingRenderer

prop<PathTracingRenderer>(tracer)
display("output", tracer)
