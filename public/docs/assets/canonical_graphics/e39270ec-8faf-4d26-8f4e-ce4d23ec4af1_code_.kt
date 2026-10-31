// Diffusion-limited aggregation. Run this in a box beneath the renderer (`window`) box.
//
// `ShaderDlaSystem` is a packaged simulation: walkers take random steps over a 1024 x 1024 image and stick
// when they touch something solid, so a branching cluster grows. The density of walkers matters; about
// 0.3 per pixel, as here, grows visible branches within a couple of thousand frames.
var dla = ShaderDlaSystem() {
    width = 1024
    height = 1024
    particleCount = 300_000
}

// This writes the system's shaders into this box's `vertex` and `fragment` properties, so they can be
// opened from the editor's property switcher and edited. The vertex shader is the simulation: one
// invocation per walker, reading the image as a texture and writing walker state back to a buffer.
dla.installInto()
