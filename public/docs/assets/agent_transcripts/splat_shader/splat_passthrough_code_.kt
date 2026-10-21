import afield.graphics.scene.fabricated.ProceduralSplats
import afield.graphics.scene.fabricated.SplatSet
import afield.graphics.scene.fabricated.SplatShader
import afield.graphics.scene.fabricated.showControls

/*
A splat shader. Every frame, the lines in this box's compute tab run once for each of
the cloud's splats, on the GPU, and may change it: move it, recolour it, stretch it,
fade it. The renderer then sorts the splats by depth and draws them where your lines
left them. This one changes nothing, so you see the cloud as it was built.

The other boxes are the demo's examples, each on a different cloud. Launch one and it
replaces this. Try another cloud here too: ProceduralSplats.fernPlume(), nautilusShell(),
coralBranches(), phyllotaxisBloom(), torusBraid(); or your own capture,
SplatSet.fromPly("/path/to/splats.ply"), or a mesh as one disc per vertex,
SplatSet.fromGltf("/path/to/model.glb").
*/
var module = computeModuleHere {}

val splats = SplatShader(module, ProceduralSplats.icosphereShell())
showControls(splats)
