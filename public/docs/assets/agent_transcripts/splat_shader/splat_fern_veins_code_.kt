import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Fern veins. The splats along the frond's midrib glow green and sway; away from it they
darken and thin, so the frond reads as veins in the dark.
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.fernPlume())
showControls(splats)
