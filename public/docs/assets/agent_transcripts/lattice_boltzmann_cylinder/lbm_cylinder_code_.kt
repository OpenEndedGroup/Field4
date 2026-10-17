// Draws into the fluid's control texture: a solid disc, walls top and bottom, and
// stripes of dye released at the left edge. The controls persist from frame to
// frame, so clear them first or a re-run leaves the old drawing behind.
lbm.clearControls()

var module = moduleHere(lbm.controlsParameters("cylinder"))
lbm.addControls("cylinder", module)

val quad = fullscreenTriangle(module)
module += "quad" to quad

quad.center = { vec(160f, 236f) }
quad.radius = { 24f }
quad.height = { 480f }
