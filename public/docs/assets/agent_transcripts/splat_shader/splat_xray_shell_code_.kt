import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Xray shell. Colour by distance from the centre: the core goes dark, the outer shell
pale blue, and a shimmer runs outward through it, nudging the splats as it passes.
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.nautilusShell())
showControls(splats)
