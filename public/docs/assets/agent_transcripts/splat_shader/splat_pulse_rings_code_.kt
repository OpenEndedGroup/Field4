import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Pulse rings. A wave travels out from the cloud's axis: each splat rides up and down on
it, swings round a little, and lights up from cyan to ember as a crest passes.
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.phyllotaxisBloom())
showControls(splats)
