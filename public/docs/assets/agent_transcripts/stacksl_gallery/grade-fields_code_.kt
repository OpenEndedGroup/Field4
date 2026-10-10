// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
renderer += "stacksl-gallery" to module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""
