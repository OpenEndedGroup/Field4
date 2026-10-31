/*
Run this in a box beneath the renderer (`window`) box.

A lattice Boltzmann fluid in a wind tunnel: a wind blows left to right and the
lattice wraps, so what leaves on the right comes back on the left.
On its own it is uniform wind and shows nothing; "10b. lbm cylinder", beneath
this box, puts something in its way.
published as the `lbm` property so boxes beneath this one can reach the system
*/
prop<ShaderLatticeBoltzmannSystem>(lbm) = ShaderLatticeBoltzmannSystem() {
    width = 800
    height = 480
    baseVelocity = Vector2f(0.08f, 0f)
    // a thin fluid (viscosity about 0.014) so the wake sheds vortices
    relaxation = 1.85f
    // nothing pulls the fluid back toward the wind, so the wake travels;
    // the inlet that 10b draws at the left edge keeps the wind blowing
    driveBlend = 0f
    stepsPerFrame = 8
    dyeDecayPerFrame = 0.001f
    // vorticity: orange turns one way, blue the other. Try COMPOSITE, DYE or SPEED
    previewMode = FluidVisualizationMode.CURL
}

// this installs the editable collision-and-streaming rule in this box's fragment tab
lbm.installInto()
