// Debugging / mapping: a texture probe that drives a quantile warp. Run this in a box beneath the
// renderer (`window`) box.
//
// The fragment shader (open `fragment` from the editor's property switcher) probes a value it computes
// and then remaps that value through a warp curve before drawing it. Each frame the box reads the
// probe's samples, refits the curve so the probed values spread evenly over 0..1, and pushes the new
// curve back to the shader. Use this when the interesting variation sits in a narrow part of the range
// and a linear readout flattens it.

// The texture is bound by name: `bbc` matches the `sampler2D bbc` in the fragment shader.
// The bundled image makes the lesson runnable; change the path to try your own.
prop<LoadedTexture>(bbc) = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc.transferNow()

var module = moduleHere {}
var m = Mesh()

// create the probe and show its readback in the "probe" output channel
val probe = fieldProbeHere().display("probe")

// The warp starts as the identity and adapts toward a uniform distribution on 0..1, smoothed over
// recent frames.
val identityWarp = QuantileWarpState.identity01()
val warp = AdaptiveQuantileWarp(
    config = QuantileWarpConfig(
        historyFrames = 8,
        smoothingAlpha = 0.01,
    ),
    target = TargetDistributions.Uniform01,
).reset(seed = identityWarp)

// Connects the probe to the warp. `install = false` means nothing runs automatically: the `loop` at the
// end calls `tick()` once per frame. Each refit is passed to the block below, which writes the curve into
// the mesh's `warpXLo` ... `warpYHi` push constants.
val warper = fieldProbeQuantileWarp(
    probe = probe,
    warp = warp,
    config = ShaderProbeQuantileWarpConfig(
        minSamples = 256,
        clipLower = 0.0,
        clipUpper = 1.0,
        displayChannel = null,
    ),
    install = false,
) { nextWarp, _ ->
    nextWarp.applyToPushConstants(
        setVec4 = { name, value ->
            m.push.set(name, value)
        },
    )
}

module += "mainMesh" to m
renderer += "boo" to module

// the camera's projection and view matrices feed the shader's `p` and `m` push constants
val camera = Camera()
m.p = camera.p
m.m = camera.m
m.warpStrength = { 1.0f }
var kc by keyCamera(camera = camera)
val n = 100
// `n` textured quads (4 vertices, 2 triangles each), stacked one unit apart along z
m.set(4 * n, 2 * n) {
    n {
        val z = it + 0.0

        pos.add(
            0,
            0,
            0 + z,
            1.0,
            0.1,
            z,
            1,
            1,
            z,
            0,
            1,
            z,
        )

        tc.add(
            0,
            0,
            1,
            0,
            1,
            1,
            0,
            1,
        )

        val b = it * 4
        tri.add(b + 0, b + 1, b + 2)
        tri.add(b + 0, b + 2, b + 3)
    }
}

// read the probe, refit the warp and push it to the shader, every frame
loop {
    warper.tick()
}
