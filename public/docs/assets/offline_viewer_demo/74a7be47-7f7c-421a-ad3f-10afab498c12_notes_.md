# Sketch

This box writes the document's **drawing layer** from code with `notation { ... }`: a frame, an
arrow from `flock` to `chart`, a heading and a freehand underline. In Field you can also draw on
the layer by hand (Alt+D).

The offline viewer draws the same scene, in the same world coordinates, under the boxes.

- the frame is a `rect`
- the arrow follows the boxes when they move
- the underline is a `stroke`, the same kind of mark as one drawn with the pen
