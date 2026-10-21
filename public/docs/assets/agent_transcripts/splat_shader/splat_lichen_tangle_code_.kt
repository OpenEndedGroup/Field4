import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Lichen tangle. Five octaves of folded noise grow a canopy over the coral: splats under
it swell and turn pale green and are pushed a little outward, the rest sink into the
dark.
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.coralBranches())
showControls(splats)
