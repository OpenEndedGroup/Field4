// A fullscreen StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot; this box's `stackSL` property fills it. Edit the stackSL live and the
// shader re-binds in place.
//
// [[property stackSL]]

val module = moduleHere { }
val m = Mesh()
m.resize(3)
module += m named "fullscreen"
renderer += module

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
module.program = stackSL
