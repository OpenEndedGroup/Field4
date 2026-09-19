// This is the whole instrument. One line.
//
// Until tonight it produced
//   PathTracingRenderer afield.four.fabricated.PathTracingRenderer@19266ca3
// and nothing else -- 376 bytes, ~330 of them the wrapper div. `PathTracingRenderer` is not a
// data class and had no value printer registered, so `ValueVisualizer` fell all the way through
// to `formatFallbackObject`, which is a class name and a `toString()`. Every one of the 26
// `afield.four` types the tracer is built out of -- `Renderer`, `Camera`, `ComputeModulePointer`,
// `RenderToTexture`, `VulkanBuffer` -- has had a printer since it was written.
//
// Re-run this box at any time; it reads the live tracer.
//
// The row to read is `up`. This scene's camera declares an up of (0, 1, 0), and the tracer
// builds its own as `right x forward`, which on this camera is 11.94 degrees away -- so the
// declared vector is not the one the image is made with. Two of last night's four scratch boxes
// were printing `camera.up` and its three components as `toRawBits()`, hunting exactly this, and
// no property tree could have shown it to them: the value is a local inside a private method.
// The row only appears when the two disagree.
import afield.four.fabricated.PathTracingRenderer

prop<PathTracingRenderer>(tracer)
display("output", tracer)
