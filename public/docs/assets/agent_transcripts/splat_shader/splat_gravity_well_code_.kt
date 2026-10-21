import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
Gravity well. Splats near the vertical axis are pulled harder: they spin faster round
it, bob, swell and glow violet, while those far out stay faint.
*/
var module = computeModuleHere {}

// trail: each frame lands on a fading copy of the last, so the spin leaves streaks
val splats = SplatShader(module, ProceduralSplats.torusBraid()) {
    trail = true
    fade = 0.12
}
showControls(splats)
