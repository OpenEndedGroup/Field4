// Run this in a box beneath "11. forest fire", whose `fire` property it uses.
//
// Draws into the fire's ignition texture: two points that burn for ever, one red
// and one green. Each relights its neighbourhood as soon as the trees recover, so
// each sends out a train of rings, and where the trains meet one kind takes over.
// start from a clean ignition texture and an unburnt forest, so a re-run does not keep the old drawing
fire.clearFireInput()
fire.clearState()

// a module that renders into the ignition texture; the drawing itself is this box's `fragment` shader
var module = moduleHere(fire.fireInputParameters("starter"))
fire.addFireInput("starter", module)

val quad = fullscreenTriangle(module)
module += "quad" to quad

// positions are in trees, so place them as fractions of the forest's size
val w = fire.settings.width.toFloat()
val h = fire.settings.height.toFloat()
quad.red = { vec(w * 0.33f, h * 0.5f) }
quad.green = { vec(w * 0.68f, h * 0.5f) }
quad.radius = { 4f }
