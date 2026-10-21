import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Dissolve. A threshold sweeps through a noise field. Where the noise falls below it,
splats let go: they drift outward, shrink, burn orange and fade. Every few seconds the
sweep starts again and the cloud comes back. The splats really do move, so the renderer
sorts them where they drift to.
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.icosphereShell())
showControls(splats)
