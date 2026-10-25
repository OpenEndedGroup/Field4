/*
Re-run to inspect the live tracer, its camera and render state.
The optional `up` row compares the declared camera up with the orthogonal
right × forward vector used for rendering; it appears when they disagree.
*/
import afield.graphics.scene.fabricated.PathTracingRenderer

prop<PathTracingRenderer>(tracer)
display("output", tracer)
